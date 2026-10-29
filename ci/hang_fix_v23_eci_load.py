#!/usr/bin/env python3
"""v23: stop Java word replace (broke enumain + slowed speech).

Pass full text dictionaries to ECI via prepareForEngine + loadDictionary
on a background thread, only while not speaking.
"""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_speak = "\t\t\tval text = applyWordDict(afterRegex)\n"
new_speak = "\t\t\tval text = afterRegex\n"
if old_speak not in text:
    if "applyWordDict(afterRegex)" not in text:
        print("speak path already without applyWordDict")
    else:
        raise SystemExit("speak applyWordDict site not found")
else:
    text = text.replace(old_speak, new_speak, 1)
    print("removed applyWordDict from speak path")

pat = re.compile(
    r"\t/\*\* Text dicts: parse into \[wordDict\].*?\n"
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

new_load = r'''	/** Hand each dictionary file to ECI (prepareForEngine + loadDictionary).
	 *  Runs only off the speak path; waits while speaking so restart stays responsive.
	 *  No Java case-fold rewrite — that broke enumain and slowed every utterance. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		var ready = true
		for ((volume, path) in want) {
			val dictLang = langs[volume]
			if (dictLang != null && dictLang != target.language) continue
			val started = System.nanoTime()
			try {
				while (speaking) {
					try { Thread.sleep(40) } catch (_: InterruptedException) {
						Thread.currentThread().interrupt()
						return false
					}
				}
				val file = java.io.File(path)
				if (!file.exists()) {
					ready = false
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					val ok = Dictionaries.loadBinary(target, volume, file)
					ready = ready && ok
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val prepared = Dictionaries.prepareForEngine(file, target.language)
				while (speaking) {
					try { Thread.sleep(40) } catch (_: InterruptedException) {
						Thread.currentThread().interrupt()
						return false
					}
				}
				val result = target.loadDictionary(volume, prepared.absolutePath)
				val ok = result == 0
				ready = ready && ok
				Log.i(TAG, "volume $volume: ${file.name} eciLoadDict ok=$ok code=$result in ${(System.nanoTime() - started) / 1_000_000L} ms")
			} catch (failed: Exception) {
				ready = false
				Log.e(TAG, "volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
		return ready
	}
'''

text = text[: m.start()] + new_load + text[m.end() :]
print("loadEngineDictionaries -> ECI loadDictionary")

if "@Volatile private var speaking" not in text:
    if "wordDict" in text:
        text = text.replace(
            "\t@Volatile private var wordDict: Map<String, String> = emptyMap()\n",
            "\t@Volatile private var wordDict: Map<String, String> = emptyMap()\n"
            "\t@Volatile private var speaking = false\n",
            1,
        )
    else:
        text = text.replace(
            "\tprivate var regexEnabled = false\n",
            "\tprivate var regexEnabled = false\n"
            "\t@Volatile private var speaking = false\n",
            1,
        )
    print("added speaking")

if "speaking = true" not in text:
    old_loop = "\t\twhile (true) {\n\t\t\tval target = ensureEngine(language)\n"
    if old_loop in text:
        text = text.replace(
            old_loop,
            "\t\tspeaking = true\n\t\ttry {\n" + old_loop,
            1,
        )
        marker = (
            "\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
            "\t\t\treturn\n"
            "\t\t}\n"
        )
        if marker not in text:
            raise SystemExit("loop end not found")
        text = text.replace(
            marker,
            "\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
            "\t\t\treturn\n"
            "\t\t}\n"
            "\t\t} finally {\n"
            "\t\t\tspeaking = false\n"
            "\t\t}\n",
            1,
        )
        print("speaking wrap added")
else:
    print("speaking wrap present")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "applyWordDict(afterRegex)" in text:
    raise SystemExit("applyWordDict still on speak path")
if "loadDictionary(volume" not in text:
    raise SystemExit("loadDictionary missing")
print("v23 ok")
