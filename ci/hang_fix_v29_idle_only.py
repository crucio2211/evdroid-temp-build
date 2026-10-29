#!/usr/bin/env python3
"""v29: zero native dict work while swiping — bulk eciLoadDict only after 5s idle."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat = re.compile(
    r"\tprivate fun teachDictionariesGradual\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\) \{.*?\n"
    r"\t\}\n",
    re.S,
)
m = pat.search(text)
if not m:
    raise SystemExit("teachDictionariesGradual not found")

new_teach = r'''	private fun teachDictionariesGradual(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>) {
		if (!waitUntilSpeechIdle(5000L, 120000L)) return
		for ((volume, path) in want) {
			val dictLang = langs[volume]
			if (dictLang != null && dictLang != target.language) continue
			if (!waitUntilSpeechIdle(5000L, 120000L)) return
			val started = System.nanoTime()
			try {
				val file = java.io.File(path)
				if (!file.exists()) {
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					val ok = Dictionaries.loadBinary(target, volume, file)
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val prepared = Dictionaries.prepareForEngine(file, target.language)
				if (!waitUntilSpeechIdle(2000L, 60000L)) return
				val result = target.loadDictionary(volume, prepared.absolutePath)
				Log.i(TAG, "volume $volume: ${file.name} eciLoadDict code=$result in ${(System.nanoTime() - started) / 1_000_000L} ms")
			} catch (failed: Exception) {
				Log.e(TAG, "volume $volume load failed after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
	}
'''

text = text[: m.start()] + new_teach + text[m.end() :]
print("idle-only eciLoadDict")

old_re = (
    "\t\t\t\tif (s != null) {\n"
    "\t\t\t\t\ttarget.setRatePercent(if (s.forceRate) 100 else request.speechRate)\n"
    "\t\t\t\t\ttarget.setPitchPercent(if (s.forcePitch) 100 else request.pitch)\n"
    "\t\t\t\t} else {\n"
    "\t\t\t\t\ttarget.setRatePercent(request.speechRate)\n"
    "\t\t\t\t\ttarget.setPitchPercent(request.pitch)\n"
    "\t\t\t\t}\n"
    "\t\t\t\tval accepted = stopGeneration.get() == requestGeneration && target.speak(piece)\n"
)
new_re = "\t\t\t\tval accepted = stopGeneration.get() == requestGeneration && target.speak(piece)\n"
if old_re in text:
    text = text.replace(old_re, new_re, 1)
    print("removed per-piece rate lock spam")

text = text.replace(
    "speechQuietUntil = System.nanoTime() + 3_000_000_000L",
    "speechQuietUntil = System.nanoTime() + 1_500_000_000L",
)
print("quiet 1.5s")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "teachWord(volume" in text:
    raise SystemExit("teachWord still present")
if "loadDictionary(volume" not in text:
    raise SystemExit("loadDictionary missing")
print("v29 ok")
