# evdroid changes

## The engine is no longer a submodule

`native/openevv` now holds the engine tree as ordinary files (the openevv-16k
merge: upstream merged in, fifi and ptbr, the baked enus.dict, your dictionary
edits, produce in the hetero table, fatima removed). `.gitmodules` is gone and
both workflows just check out the repo: the `OPENVV16K_DEPLOY_KEY` secret and
the private-repo fetch are not needed any more, so that secret can be deleted.
The old patch series is retired (`native/patches-retired/`): everything in it is
in the tree already and it does not apply to it.

To switch an existing clone (the submodule has to leave git before files go in
its place):

    git submodule deinit -f native/openevv
    git rm -f native/openevv
    rm -rf .git/modules/native/openevv
    # now copy the contents of evdroid/ from this zip over the repo
    git add -A
    git commit -m "Vendor the engine; one library per language; dictionary fixes"

`native/openevv/build/` stays ignored. If you change the engine by hand, build
once with `--rerun-tasks` (the buildNative task does not watch the engine's
files, because its own bake step rewrites them).

## What changed

1. **Two more languages: Finnish and Brazilian Portuguese.**
   `.github/workflows/build.yml` adds `lang/ptbr lang/fifi` to
   `EVVDROID_LANGS`. `Eci.kt` already had both locales; `Dictionaries.kt` now
   knows the file tags `ptb` and `fin`. README says twelve languages.

2. **One native library per language, one APK.**
   - `app/src/main/cpp/evv_dispatch.c` (new) is the `libevvjni.so` the app
     loads. It holds no engine. `create(language)` opens `libevv_<tag>.so` the
     first time that language is used (`dlopen`, private), and every call on
     that instance is forwarded to the same function in that library. The
     language word is passed on exactly as the app wrote it.
   - `native/build-native.sh` builds each language into `libevv_<tag>.so`
     (engine + that language + `evv_jni.c`, flags unchanged) and generates the
     language table the dispatcher is compiled with. `SPLIT=0` (Gradle:
     `-Pevvdroid.split=0`) builds the old single library instead.
   - `evv_jni.c` is unchanged except for `#include <stdint.h>`. Kotlin is
     unchanged for this: `EvvNative` still loads `evvjni`.
   - The APK grows, since each library carries its own copy of the engine core.

3. **Dictionary loading.**
   - The engine matches a key against the word as spoken, byte for byte and
     case included (a key `fatima` does not touch `Fatima`; measured on the
     engine). The app lowercased every key on the way in, so entries that
     differ only in case collapsed into one and the last won. Keys are now kept
     and looked up exactly as written.
   - `isUsable` refused every character from 0x80 to 0x9F. Read as Latin-1 those
     are the engine's Windows Western letters and marks (curly apostrophe, oe,
     s caron), so such entries were silently dropped. Only C0 controls and DEL
     are refused now.
   - A file in the engine's own binary form (what enumain.dic, enuroot.dic and
     enuabbr.dic are on Windows) was refused at import. It is now kept and read
     by the engine's own loader, as the NVDA add-on does; the row says
     "engine format".
   - `DictionaryTest` is updated, with new tests for case variants, the 0x80 to
     0x9F range and the engine's case matching.

## What was and was not checked

Checked here: `evv_dispatch.c` and `evv_jni.c` compile and run under a real JVM
on Linux against two real language libraries (English and Finnish): both speak,
two instances at once, an unknown language and a null handle answer cleanly,
and the sibling-path fallback works. The generated language table and the
script's syntax were checked. Engine case matching was measured on the host.

Not checked: nothing was compiled for Android. The Kotlin, Gradle, NDK link
and the CI build have not run, and nothing ran on a device. Treat the first CI
run as the first compile.
