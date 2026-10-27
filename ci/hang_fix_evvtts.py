#!/usr/bin/env python3
"""Restore EvvTtsService.kt from known-good commit and apply hang fix."""
import re
import pathlib
import urllib.request

path = pathlib.Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
url = (
    "https://raw.githubusercontent.com/crucio2211/evdroid-temp-build/"
    "0c3b4718/app/src/main/kotlin/org/evvdroid/EvvTtsService.kt"
)
text = urllib.request.urlopen(url, timeout=60).read().decode()

# 1) enginesByLanguage comment + capacity
old = (
    "\t/** Keep the active language and the last inactive one ready. Recreating a\n"
    "\t *  native engine discards its indexed dictionaries; reopening a large text\n"
    "\t *  dictionary on every language round trip is the load delay. */\n"
    "\tprivate val enginesByLanguage = java.util.LinkedHashMap<Int, EvvEngine>(4, 0.75f, true)"
)
new = (
    "\t/** Keep several language engines ready (US/UK/etc). Recreating a native\n"
    "\t *  engine discards its indexed dictionaries; reopening large phoneme\n"
    "\t *  files on every language round trip is the hang/load delay. */\n"
    "\tprivate val enginesByLanguage = java.util.LinkedHashMap<Int, EvvEngine>(8, 0.75f, true)"
)
if old not in text:
    raise SystemExit("enginesByLanguage block not found")
text = text.replace(old, new, 1)

# 2) onCreate warmup
old = (
    "\toverride fun onCreate() {\n"
    "\t\tsettings = Settings(this)\n"
    "\t\tsuper.onCreate()\n"
    "\t\t// The emoji table builds on first touch, which\n"
    "\t\t// would otherwise land on the first utterance. Warm it here, off\n"
    "\t\t// every thread that speaks.\n"
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t}"
)
new = (
    "\toverride fun onCreate() {\n"
    "\t\tsettings = Settings(this)\n"
    "\t\tsuper.onCreate()\n"
    "\t\t// The emoji table builds on first touch, which\n"
    "\t\t// would otherwise land on the first utterance. Warm it here, off\n"
    "\t\t// every thread that speaks.\n"
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t\t// Preload the engine + external dictionaries off the synthesis path.\n"
    "\t\t// With three large phoneme files, indexing on the first speak was what\n"
    "\t\t// hung TalkBack until force-stop after a screen-reader restart.\n"
    "\t\tval warm = settings\n"
    "\t\tif (warm != null) {\n"
    "\t\t\tval primary = warm.forcedLanguage.takeIf { EvvEngine.available.contains(it) }\n"
    "\t\t\t\t?: EvvEngine.available.firstOrNull()\n"
    "\t\t\tif (primary != null) {\n"
    "\t\t\t\tengineExecutor.execute {\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\topenEngine(primary)\n"
    "\t\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\t\tLog.e(TAG, \"dictionary warmup failed for 0x%08x\".format(primary), failed)\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}"
)
if old not in text:
    raise SystemExit("onCreate block not found")
text = text.replace(old, new, 1)

# 3) ensureEngine with 90s timeout
old = (
    "\tprivate fun ensureEngine(language: Int): EvvEngine? {\n"
    "\t\treturn try {\n"
    "\t\t\trequestEngine(language).get()\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\tnull\n"
    "\t\t} catch (failed: java.util.concurrent.ExecutionException) {\n"
    "\t\t\tLog.e(TAG, \"engine setup failed for 0x%08x\".format(language), failed.cause ?: failed)\n"
    "\t\t\tnull\n"
    "\t\t}\n"
    "\t}"
)
new = (
    "\tprivate fun ensureEngine(language: Int): EvvEngine? {\n"
    "\t\treturn try {\n"
    "\t\t\t// Cap wait so a slow multi-dict index cannot freeze TalkBack forever.\n"
    "\t\t\trequestEngine(language).get(90, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (timeout: java.util.concurrent.TimeoutException) {\n"
    "\t\t\tLog.e(TAG, \"engine setup timed out after 90s for 0x%08x\".format(language))\n"
    "\t\t\tnull\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\tnull\n"
    "\t\t} catch (failed: java.util.concurrent.ExecutionException) {\n"
    "\t\t\tLog.e(TAG, \"engine setup failed for 0x%08x\".format(language), failed.cause ?: failed)\n"
    "\t\t\tnull\n"
    "\t\t}\n"
    "\t}"
)
if old not in text:
    raise SystemExit("ensureEngine block not found")
text = text.replace(old, new, 1)

# 4) openEngine — unlock native dictionary indexing
pat = re.compile(
    r"\t/\*\* Called only by \[engineExecutor\], so changing language cannot race another\n"
    r"\t \*  engine construction\. Dictionary files are attached before the task ends\. \*/\n"
    r"\tprivate fun openEngine\(language: Int\): EvvEngine\? = synchronized\(engineStateLock\) \{.*?\n"
    r"\t\}\n\n"
    r"\tprivate fun applySettings",
    re.S,
)
m = pat.search(text)
if not m:
    raise SystemExit("openEngine block not found")

new_open = (
    "\t/** Called only by [engineExecutor], so changing language cannot race another\n"
    "\t *  engine construction. Native dictionary indexing is done *outside*\n"
    "\t *  [engineStateLock] so synthesis / onStop never block behind a large load. */\n"
    "\tprivate fun openEngine(language: Int): EvvEngine? {\n"
    "\t\tval started = System.nanoTime()\n"
    "\t\tval target: EvvEngine\n"
    "\t\tvar needsSettings = false\n"
    "\t\tsynchronized(engineStateLock) {\n"
    "\t\t\tval current = engine\n"
    "\t\t\tif (current != null && current.language == language) {\n"
    "\t\t\t\tif (settings != null && appliedRevisions[current] != settings!!.revision) {\n"
    "\t\t\t\t\ttarget = current\n"
    "\t\t\t\t\tneedsSettings = true\n"
    "\t\t\t\t} else {\n"
    "\t\t\t\t\treturn current\n"
    "\t\t\t\t}\n"
    "\t\t\t} else {\n"
    "\t\t\t\tval cached = enginesByLanguage[language]\n"
    "\t\t\t\tif (cached != null) {\n"
    "\t\t\t\t\tengine = cached\n"
    "\t\t\t\t\tif (settings != null && appliedRevisions[cached] != settings!!.revision) {\n"
    "\t\t\t\t\t\ttarget = cached\n"
    "\t\t\t\t\t\tneedsSettings = true\n"
    "\t\t\t\t\t} else {\n"
    "\t\t\t\t\t\tLog.i(TAG, \"engine 0x%08x reused with its native dictionaries\".format(language))\n"
    "\t\t\t\t\t\treturn cached\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t} else {\n"
    "\t\t\t\t\tval made = EvvEngine.open(language) ?: return null\n"
    "\t\t\t\t\tengine = made\n"
    "\t\t\t\t\tenginesByLanguage[language] = made\n"
    "\t\t\t\t\twhile (enginesByLanguage.size > MAX_CACHED_ENGINES) {\n"
    "\t\t\t\t\t\tval eldest = enginesByLanguage.entries.iterator().next()\n"
    "\t\t\t\t\t\tif (eldest.key == language) break\n"
    "\t\t\t\t\t\tenginesByLanguage.remove(eldest.key)\n"
    "\t\t\t\t\t\tappliedRevisions.remove(eldest.value)\n"
    "\t\t\t\t\t\tloadedDictionaries.remove(eldest.value)\n"
    "\t\t\t\t\t\teldest.value.close()\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\ttarget = made\n"
    "\t\t\t\t\tneedsSettings = true\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\t// Heavy work (prepareForEngine + native loadDictionary) runs unlocked.\n"
    "\t\tif (needsSettings) {\n"
    "\t\t\ttry {\n"
    "\t\t\t\tapplySettings(target)\n"
    "\t\t\t\tLog.i(TAG, \"engine 0x%08x ready in %d ms\".format(language, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\tsynchronized(engineStateLock) {\n"
    "\t\t\t\t\tif (enginesByLanguage[language] === target) {\n"
    "\t\t\t\t\t\tenginesByLanguage.remove(language)\n"
    "\t\t\t\t\t\tappliedRevisions.remove(target)\n"
    "\t\t\t\t\t\tloadedDictionaries.remove(target)\n"
    "\t\t\t\t\t\tif (engine === target) engine = enginesByLanguage.values.lastOrNull()\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
    "\t\t\t\ttarget.close()\n"
    "\t\t\t\tthrow failed\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\treturn target\n"
    "\t}\n\n"
    "\tprivate fun applySettings"
)

text = text[: m.start()] + new_open + text[m.end() :]

# 5) MAX_CACHED_ENGINES
text = text.replace("const val MAX_CACHED_ENGINES = 2", "const val MAX_CACHED_ENGINES = 4", 1)

path.write_text(text)
print("patched ok, chars", len(text))
for s in ["get(90,", "MAX_CACHED_ENGINES = 4", "dictionary warmup failed", "Native dictionary indexing is done"]:
    if s not in text:
        raise SystemExit(f"missing marker: {s}")
print("all markers present")
