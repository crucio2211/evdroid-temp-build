#!/usr/bin/env python3
"""v25: dict teach fully async so restart speech is not delayed."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "teachingEngines" not in text:
    old = "\t@Volatile private var speaking = false\n"
    if old not in text:
        old = "\t@Volatile private var wordDict: Map<String, String> = emptyMap()\n"
        if old not in text:
            raise SystemExit("no place for teachingEngines")
        text = text.replace(
            old,
            old
            + "\t@Volatile private var speaking = false\n"
            + "\tprivate val teachingEngines = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EvvEngine, Boolean>())\n",
            1,
        )
    else:
        text = text.replace(
            old,
            old
            + "\tprivate val teachingEngines = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EvvEngine, Boolean>())\n",
            1,
        )
    print("added teachingEngines")

pat = re.compile(
    r"\t/\*\* Gradual teachWord into ECI\..*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}\n"
    r"\n"
    r"\t/\*\* Wait until \[speaking\].*?\n"
    r"\tprivate fun waitUntilSpeechIdle\(idleMs: Long, maxMs: Long\): Boolean \{.*?\n"
    r"\t\treturn !speaking\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    raise SystemExit("gradual load block not found")

new = r'''	/** Schedule gradual teachWord; return immediately so openEngine/restart is not blocked. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		if (!teachingEngines.add(target)) {
			Log.i(TAG, "dictionary teach already running for this engine")
			return true
		}
		val wantCopy = want.toMap()
		val langsCopy = langs.toMap()
		Thread({
			try {
				teachDictionariesGradual(target, wantCopy, langsCopy)
			} finally {
				teachingEngines.remove(target)
			}
		}, "evv-gradual-teach").apply { isDaemon = true }.start()
		return true
	}

	private fun teachDictionariesGradual(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>) {
		if (!waitUntilSpeechIdle(200L, 10000L)) return
		for ((volume, path) in want) {
			val dictLang = langs[volume]
			if (dictLang != null && dictLang != target.language) continue
			val started = System.nanoTime()
			try {
				if (!waitUntilSpeechIdle(100L, 30000L)) return
				val file = java.io.File(path)
				if (!file.exists()) {
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					if (!waitUntilSpeechIdle(200L, 30000L)) return
					val ok = Dictionaries.loadBinary(target, volume, file)
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val entries = Dictionaries.parse(file)
				Log.i(TAG, "volume $volume: ${file.name} async teachWord ${entries.size} entries")
				var taught = 0
				var refused = 0
				for (pair in entries.values) {
					if (!waitUntilSpeechIdle(0L, 60000L)) return
					try {
						if (target.teachWord(volume, pair.first, pair.second) == 0) taught++ else refused++
					} catch (_: Exception) {
						refused++
					}
					if ((taught + refused) % 64 == 0) {
						try { Thread.sleep(5) } catch (_: InterruptedException) {
							Thread.currentThread().interrupt()
							return
						}
					}
				}
				Log.i(TAG, "volume $volume: taught=$taught refused=$refused in ${(System.nanoTime() - started) / 1_000_000L} ms")
			} catch (failed: Exception) {
				Log.e(TAG, "volume $volume teach failed after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
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
				Thread.sleep(if (idleMs <= 0L) 20 else 25)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
		return !speaking
	}
'''

text = text[: m.start()] + new + text[m.end() :]
print("async gradual teach")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "evv-gradual-teach" not in text:
    raise SystemExit("async thread missing")
print("v25 ok")
