#!/usr/bin/env python3
"""v24: gradual teachWord into ECI — no eciLoadDict (restart hang)."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat = re.compile(
    r"\t/\*\* Hand each dictionary file to ECI \(prepareForEngine \+ loadDictionary\)\..*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    pat = re.compile(
        r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
        r"\t\treturn ready\n"
        r"\t\}",
        re.S,
    )
    m = pat.search(text)
if not m:
    raise SystemExit("loadEngineDictionaries not found")

new_load = r'''	/** Gradual teachWord into ECI. Never eciLoadDict — that freezes TalkBack on restart. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		var ready = true
		if (!waitUntilSpeechIdle(1000L, 15000L)) return false
		for ((volume, path) in want) {
			val dictLang = langs[volume]
			if (dictLang != null && dictLang != target.language) continue
			val started = System.nanoTime()
			try {
				if (!waitUntilSpeechIdle(400L, 60000L)) return false
				val file = java.io.File(path)
				if (!file.exists()) {
					ready = false
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					if (!waitUntilSpeechIdle(400L, 60000L)) return false
					val ok = Dictionaries.loadBinary(target, volume, file)
					ready = ready && ok
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val entries = Dictionaries.parse(file)
				Log.i(TAG, "volume $volume: ${file.name} gradual teachWord ${entries.size} entries")
				var taught = 0
				var refused = 0
				for (pair in entries.values) {
					if (!waitUntilSpeechIdle(0L, 120000L)) return false
					try {
						if (target.teachWord(volume, pair.first, pair.second) == 0) taught++ else refused++
					} catch (_: Exception) {
						refused++
					}
					if ((taught + refused) % 32 == 0) {
						try { Thread.sleep(15) } catch (_: InterruptedException) {
							Thread.currentThread().interrupt()
							return false
						}
					}
				}
				Log.i(TAG, "volume $volume: taught=$taught refused=$refused in ${(System.nanoTime() - started) / 1_000_000L} ms")
			} catch (failed: Exception) {
				ready = false
				Log.e(TAG, "volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
		return ready
	}

	/** Wait until [speaking] has been false for [idleMs], or [maxMs] total. */
	private fun waitUntilSpeechIdle(idleMs: Long, maxMs: Long): Boolean {
		val deadline = System.nanoTime() + maxMs * 1_000_000L
		var idleStart = -1L
		while (System.nanoTime() < deadline) {
			if (speaking) {
				idleStart = -1L
			} else {
				val now = System.nanoTime()
				if (idleStart < 0L) idleStart = now
				if (idleMs <= 0L || (now - idleStart) >= idleMs * 1_000_000L) return true
			}
			try {
				Thread.sleep(40)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
		return !speaking
	}
'''

text = text[: m.start()] + new_load + text[m.end() :]
print("gradual teachWord load")

if "applyWordDict(afterRegex)" in text:
    text = text.replace("\t\t\tval text = applyWordDict(afterRegex)\n", "\t\t\tval text = afterRegex\n", 1)
    print("cleared applyWordDict from speak")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "teachWord(volume" not in text:
    raise SystemExit("teachWord missing")
if "waitUntilSpeechIdle" not in text:
    raise SystemExit("idle wait missing")
print("v24 ok")
