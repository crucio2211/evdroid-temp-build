#!/usr/bin/env bash
# Builds libevvjni.so out of native/openevv, one file per ABI.
#
# The engine comes out of its own Makefile, which writes the language rules
# with Python and then compiles them; that work is per language rather than per
# ABI, so the first ABI pays for it and the rest do not. What this adds is the
# JNI bridge and the published ECI names, linked into one shared object so
# there is no soname for Android's packaging to trip over.

set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
engine="$here/openevv"

ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}
API=${API:-21}
RULES=${RULES:-c}
LANGS=${LANGS:-lang/enus}
OUT=${OUT:-"$root/app/build/native/jniLibs"}
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}

# Two Gradle tasks (assembleRelease + assembleDebugAndroidTest) can run
# this script at once on the same tree; the patch loop tolerates it but
# the dictionary bake is read-modify-write and two of those at once
# clobber each other's words. Serialize whole runs with an atomic
# mkdir lock (portable: Linux, macOS, Git Bash). Fresh CI VMs make a
# stale lock impossible across runs; the wait timeout keeps a crashed
# holder from hanging the job forever.
lockdir="$here/.build-native.lock"
waited=0
while ! mkdir "$lockdir" 2>/dev/null; do
	sleep 5
	waited=$((waited + 5))
	if [ "$waited" -ge 600 ]; then
		echo "build-native lock held 10 minutes; proceeding unlocked" >&2
		lockdir=""
		break
	fi
done
if [ -n "$lockdir" ]; then
	trap 'rmdir "$lockdir" 2>/dev/null || true' EXIT
fi

# TEMPORARY ground-truth trace (diagnosing dictionary bake races). Gradle
# captures task stdout in bursts and can drop or interleave lines when two
# buildNative tasks run at once, so the log alone cannot say how many runs
# happened or what each saw. This file is append-only and is read back by
# a later, sequential workflow step. Revert before shipping.
trace="$root/NATIVE-BUILD-TRACE.txt"
trace_dict_state() {
	echo "--- $1 pid=$$ here=$here date=$(date -u +%FT%TZ)"
	md5sum "$engine/lang/enus/enus.dict" 2>/dev/null || md5 "$engine/lang/enus/enus.dict" 2>/dev/null || true
	echo "new-count=$(grep -c ' 255 255 new' "$engine/lang/enus/enus.dict" || true)"
	grep -n -e '  mutual 255' -e '  encourage 255' -e '  celtics 255' -e '  annan 255' -e '  biogen 255' "$engine/lang/enus/enus.dict" || echo "WORDS NOT IN TEXT"
	git -C "$engine" status --short | head -20
	ls -lad "$here/.build-native.lock" 2>/dev/null || echo "no lockdir visible"
}
{ echo "=== BEGIN pid=$$ ABIS=$ABIS LANGS=$LANGS"; trace_dict_state "pre-patch"; } >>"$trace" 2>&1

NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$NDK" ]; then
	echo "set ANDROID_NDK_HOME to an NDK r23 or later" >&2
	exit 1
fi
NDK=${NDK//\\//}
OUT=${OUT//\\//}

# Git Bash hands a native program its arguments with the paths converted, but
# it cannot convert what is inside a response file, so those are written the
# way the compiler reads them.
winpath() {
	if command -v cygpath >/dev/null 2>&1; then
		cygpath -m "$1"
	else
		printf '%s' "$1"
	fi
}

case "$(uname -s)" in
	Linux*)  hosttag=linux-x86_64 ;;
	Darwin*) hosttag=darwin-x86_64 ;;
	*)       hosttag=windows-x86_64 ;;
esac
bin="$NDK/toolchains/llvm/prebuilt/$hosttag/bin"
exe=""
[ -x "$bin/clang" ] || exe=".exe"
clang="$bin/clang$exe"
strip="$bin/llvm-strip$exe"
if [ ! -x "$clang" ]; then
	echo "no clang at $clang" >&2
	exit 1
fi
if [ ! -f "$engine/Makefile" ]; then
	echo "native/openevv is empty; run: git submodule update --init --recursive" >&2
	exit 1
fi

# What the engine needs before it is right on this architecture. Each is a bug
# in openevv rather than a change for Android, and each is written up in its
# own file; the submodule stays as upstream left it.
for patch in "$here"/patches/*.patch; do
	[ -e "$patch" ] || continue
	if git -C "$engine" apply --reverse --check "$patch" >/dev/null 2>&1; then
		echo "already applied $(basename "$patch")"
		echo "pid=$$ already applied $(basename "$patch")" >>"$trace"
		continue
	fi
	if ! git -C "$engine" apply --whitespace=nowarn "$patch"; then
		# Two builds racing here both see it unapplied and both try it. The
		# loser has nothing left to do rather than nothing to say.
		if git -C "$engine" apply --reverse --check "$patch" >/dev/null 2>&1; then
			echo "pid=$$ race-skipped $(basename "$patch")" >>"$trace"
			continue
		fi
		echo "could not apply $(basename "$patch") to native/openevv" >&2
		echo "pid=$$ FAILED $(basename "$patch")" >>"$trace"
		exit 1
	fi
	echo "applied $(basename "$patch")"
	echo "pid=$$ applied $(basename "$patch")" >>"$trace"
done
{ echo "=== pid=$$ patch loop done"; trace_dict_state "post-patch"; } >>"$trace" 2>&1

# Bake the dictionaries' text into the rules before compiling: dict.py reads
# the generated rule code and rewrites arms and records out of the .dict
# text, and the compile below picks up what changed. Only English has text
# changes to bake; the other languages' dictionaries are untouched.
make -C "$engine" -f Makefile rulecode LANGS="$LANGS"
if command -v python3 >/dev/null 2>&1; then
	bake_log=$( ( cd "$engine" && EVV_LANG_DIR=lang/enus python3 tools/module/dict.py build ) 2>&1 )
	echo "$bake_log"
	{ echo "=== pid=$$ bake output:"; echo "$bake_log"; trace_dict_state "post-bake"; } >>"$trace" 2>&1
else
	echo "no python3: dictionaries stay as last baked" >&2
	echo "pid=$$ no python3, bake skipped" >>"$trace"
fi

# The bake mints arms for new words into the rule text; compile them too,
# or the engine keeps reading the old ones. `make rulecode' writes the
# generated rule code out of lang/<tag>/rules, which is where the bake
# put the new arms, so run it again before compiling.
make -C "$engine" -f Makefile rulecode LANGS="$LANGS"

# Every directory the engine's headers sit in, the same set its Makefile builds
# its own include path from. They stay relative because the link below runs
# from the engine's own directory.
incs="-Iinclude"
for l in $LANGS; do
	incs="$incs -I$l -Irom/${l##*/}"
done
for d in "$engine"/src "$engine"/src/*/ "$engine"/src/*/*/; do
	[ -d "$d" ] || continue
	rel=${d#"$engine"/}
	incs="$incs -I${rel%/}"
done

triple_for() {
	case "$1" in
		arm64-v8a)   echo "aarch64-linux-android$API" ;;
		armeabi-v7a) echo "armv7a-linux-androideabi$API" ;;
		x86_64)      echo "x86_64-linux-android$API" ;;
		x86)         echo "i686-linux-android$API" ;;
		*)           echo "unknown ABI $1" >&2; exit 1 ;;
	esac
}

for abi in $ABIS; do
	triple=$(triple_for "$abi")
	build="build/android-$abi"
	echo "==> $abi ($triple)"
	# Two flags the engine's own Makefile never needs and this build cannot do
	# without.
	#
	# -fsigned-char, because a plain char is signed on x86 and unsigned on ARM,
	# and this engine is x86 code transcribed: it reads bytes out of its tables
	# and compares them as numbers, so where the original saw -114 an ARM build
	# would see 142 and the comparison would go the other way. This is
	# precautionary rather than a fix for anything seen. Turning it on changed
	# no sample of the text tried here, and openevv's own 881-case gate has
	# never been run on ARM, so it stays as the safer of the two.
	#
	# -fPIC, because the objects end up inside a shared object, which none of
	# the engine's own builds are.
	#
	# -fno-strict-aliasing, because the 6.1 rules type-pun: the fork builds
	# everything with it, and without it the optimizer may miscompile the
	# rule tables it punned through.
	#
	# What is deliberately absent is --gc-sections, with the -ffunction-sections
	# and -fdata-sections that feed it. The engine reaches a language's rules,
	# constants and tables through tables of addresses rather than by name, so
	# the linker cannot see what is live: dropping the unreferenced left the
	# engine speaking most text and faulting in the pitch rules on some of it,
	# with a null node reference in visleft. The engine's own shared library
	# target does not use them either.
	common=(
		-C "$engine"
		-f Makefile
		-f "$here/android.mk"
		RULES="$RULES"
		LANGS="$LANGS"
		BUILD="$build"
		CC="$clang --target=$triple"
		CFLAGS="-fPIC -fvisibility=hidden -fsigned-char -fno-strict-aliasing"
	)
	make "${common[@]}" -j"$JOBS" android-objects
	objdir=$(make "${common[@]}" -s android-objdir)
	# A response file, because the object list is longer than a Windows command
	# line takes.
	rsp="$engine/$build/objects.rsp"
	: > "$rsp"
	for o in "$engine/$objdir"/*.o; do
		winpath "$o" >> "$rsp"
		echo >> "$rsp"
	done
	# The Delta machine keeps addresses in 32-bit values, so a 64-bit build
	# wants the low arena the engine's Makefile turns on for itself.
	arena=""
	case "$abi" in
		arm64-v8a|x86_64) arena="-DEVV_ARENA=1" ;;
	esac
	mkdir -p "$OUT/$abi"
	( cd "$engine" && "$clang" --target="$triple" \
		-O2 -std=gnu99 -w -fPIC -fvisibility=hidden -fsigned-char -fno-strict-aliasing \
		-Werror=int-conversion -Werror=incompatible-pointer-types \
		$arena -DECI_STATIC $incs \
		-shared \
		"$root/app/src/main/cpp/evv_jni.c" \
		lib/eci_api.c \
		"@$build/objects.rsp" \
		-Wl,-z,max-page-size=16384 \
		-lm -llog \
		-o "$OUT/$abi/libevvjni.so" )
	# The unstripped copy stays behind so a crash address out of logcat can be
	# turned back into a function with llvm-symbolizer.
	cp "$OUT/$abi/libevvjni.so" "$engine/$build/libevvjni.unstripped.so"
	"$strip" "$OUT/$abi/libevvjni.so"
	ls -l "$OUT/$abi/libevvjni.so"
done

{ echo "=== pid=$$ all ABIs done"; trace_dict_state "final"; } >>"$trace" 2>&1
echo "native libraries are under $OUT"
