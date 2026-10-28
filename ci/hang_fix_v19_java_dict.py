#!/usr/bin/env python3
"""v19: apply text dictionaries in Java — never eciLoadDict/teachWord.

Native dictionary load blocks the ECI engine across TalkBack restarts.
Parse into memory and rewrite utterance text before speak (like regex dicts).
Binary .dic still uses loadBinary only when the engine is not speaking.
"""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "wordDict:" not in text and "wordDict =" not in text:
    old = "\tprivate var regexEnabled = false\n"
    if old not in text:
        raise SystemExit("regexEnabled not found")
    text = text.replace(
        old,
        old
        + "\t/** Text dictionary key->say. Applied in Java so the native engine is never blocked. */\n"
        + "\t@Volatile private var wordDict: Map<String, String> = emptyMap()\n",
        1,
    )
    print("added wordDict")

pat = re.compile(
    r"\t/\*\* Binary dictionaries use the engine's saved form\..*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    raise SystemExit("loadEngineDictionaries not found")

new_load = r'''	/** Text dicts: parse into [wordDict] only (NO eciLoadDict / teachWord).
	 *  Binary: loadBinary. Restart of TalkBack no longer waits on native index. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		var ready = true
		val merged = java.util.LinkedHashMap<String, String>(wordDict)
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
					val ok = Dictionaries.loadBinary(target, volume, file)
					ready = ready && ok
					Log.i(TAG, "volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val entries = Dictionaries.parse(file)
				for ((key, pair) in entries) {
					merged[key] = pair.second
				}
				Log.i(TAG, "volume $volume: ${file.name} ${entries.size} entries into memory in ${(System.nanoTime() - started) / 1_000_000L} ms (no native load)")
			} catch (failed: Exception) {
				ready = false
				Log.e(TAG, "volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
		wordDict = merged
		return ready
	}

	/** Case-sensitive whole-word replacement from [wordDict], same matching spirit as ECI keys. */
	private fun applyWordDict(text: String): String {
		val map = wordDict
		if (map.isEmpty() || text.isEmpty()) return text
		val out = StringBuilder(text.length + 16)
		var i = 0
		while (i < text.length) {
			val ch = text[i]
			if (ch.isLetter()) {
				val start = i
				i++
				while (i < text.length && (text[i].isLetter() || text[i] == '\'' || text[i] == '\u2019')) i++
				val word = text.substring(start, i)
				out.append(map[word] ?: word)
			} else {
				out.append(ch)
				i++
			}
		}
		return out.toString()
	}
'''

text = text[: m.start()] + new_load + text[m.end() :]
print("loadEngineDictionaries -> memory map")

old_rx = "\t\t\tval afterRegex = if (regexEnabled) RegexDictionaries.apply(loadedRegex, fixed) else fixed\n\t\t\tval text = afterRegex\n"
new_rx = "\t\t\tval afterRegex = if (regexEnabled) RegexDictionaries.apply(loadedRegex, fixed) else fixed\n\t\t\tval text = applyWordDict(afterRegex)\n"
if old_rx not in text:
    raise SystemExit("afterRegex apply site not found")
text = text.replace(old_rx, new_rx, 1)
print("speak path applies wordDict")

old_sched = (
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tengineExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
new_sched = (
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tThread({\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}, \"evv-dict-mem\").apply { isDaemon = true }.start()\n"
    "\t\t}\n"
)
if old_sched not in text:
    raise SystemExit("scheduleDictLoad block not found")
text = text.replace(old_sched, new_sched, 1)
print("dict schedule off engineExecutor")

old_stop = (
    "\toverride fun onStop() {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\t// ECI stop waits for its current rule pass and the next speak would wait\n"
    "\t\t// behind that cleanup. Abort the callback now; the native worker answers\n"
    "\t\t// eciDataAbort, and its next speak waits for that worker to become idle.\n"
    "\t\tengine?.abort()\n"
    "\t}"
)
new_stop = (
    "\toverride fun onStop() {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tval engines = synchronized(engineStateLock) {\n"
    "\t\t\tLinkedHashSet<EvvEngine>().also { set ->\n"
    "\t\t\t\tset.addAll(enginesByLanguage.values)\n"
    "\t\t\t\tengine?.let(set::add)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tfor (e in engines) {\n"
    "\t\t\ttry { e.abort() } catch (_: Exception) {}\n"
    "\t\t}\n"
    "\t\tThread({\n"
    "\t\t\tfor (e in engines) {\n"
    "\t\t\t\ttry { e.stop() } catch (_: Exception) {}\n"
    "\t\t\t}\n"
    "\t\t}, \"evv-stop\").apply { isDaemon = true }.start()\n"
    "\t}"
)
if old_stop in text:
    text = text.replace(old_stop, new_stop, 1)
    print("onStop abort+stop all")
else:
    print("WARN onStop not patched")

path.write_text(text)
print("braces", text.count("{"), text.count("}"))
if text.count("{") != text.count("}"):
    raise SystemExit("brace mismatch")
for marker in ["wordDict", "applyWordDict", "no native load", "evv-dict-mem"]:
    if marker not in text:
        raise SystemExit(f"missing {marker}")
print("v19 ok")
