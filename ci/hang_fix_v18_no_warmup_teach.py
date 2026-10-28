#!/usr/bin/env python3
"""v18: remove onCreate engine warmup; text dicts = teachWord never eciLoadDict."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat_warm = re.compile(
    r"\t\t// Preload the engine \+ external dictionaries.*?"
    r"\t\tval warm = settings\n"
    r"\t\tif \(warm != null\) \{.*?"
    r"\t\t\}\n",
    re.S,
)
m = pat_warm.search(text)
if m:
    text = text[: m.start()] + text[m.end() :]
    print("removed onCreate engine/dict warmup")
elif "dictionary warmup failed" not in text:
    print("warmup already absent")

bad = (
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}\n"
)
good = (
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t}\n"
)
if bad in text:
    text = text.replace(bad, good, 1)
    print("fixed leftover onCreate braces")

pat = re.compile(
    r"\t/\*\* Binary dictionaries use the engine's saved form\..*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    if "teachWord (no eciLoadDict)" in text:
        print("teachWord path already present")
    else:
        raise SystemExit("loadEngineDictionaries not found")
else:
    new_load = r'''	/** Binary: loadBinary when idle. Text: teachWord every entry (NO eciLoadDict).
	 *  teachWord yields while speaking so TalkBack restart is never blocked. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		var ready = true
		for ((volume, path) in want) {
			if ((langs[volume] ?: target.language) != target.language) continue
			val started = System.nanoTime()
			try {
				val file = java.io.File(path)
				if (!file.exists()) {
					ready = false
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					while (speaking) {
						try { Thread.sleep(40) } catch (_: InterruptedException) {
							Thread.currentThread().interrupt()
							return false
						}
					}
					val ok = Dictionaries.loadBinary(target, volume, file)
					ready = ready && ok
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val entries = Dictionaries.parse(file)
				Log.i(TAG, "volume $volume: ${file.name} parsed ${entries.size} entries; teachWord (no eciLoadDict)")
				var taught = 0
				var refused = 0
				val batchStarted = System.nanoTime()
				for (pair in entries.values) {
					while (speaking) {
						try { Thread.sleep(40) } catch (_: InterruptedException) {
							Thread.currentThread().interrupt()
							return false
						}
					}
					try {
						if (target.teachWord(volume, pair.first, pair.second) == 0) taught++ else refused++
					} catch (_: Exception) {
						refused++
					}
					if ((taught + refused) % 128 == 0) Thread.yield()
				}
				Log.i(TAG, "volume $volume: teachWord done taught=$taught refused=$refused in ${(System.nanoTime() - batchStarted) / 1_000_000L} ms")
			} catch (failed: Exception) {
				ready = false
				Log.e(TAG, "volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
		return ready
	}'''
    text = text[: m.start()] + new_load + text[m.end() :]
    print("loadEngineDictionaries -> teachWord only")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "dictionary warmup failed" in text:
    raise SystemExit("warmup still present")
print("v18 ok, chars", len(text))
