#!/usr/bin/env python3
"""v22: Java passes ALL phonemes into the ECI speak string — no teachWord."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_teach = (
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\tmerged[key] = pair.second\n"
    "\t\t\t\t}\n"
    "\t\t\t\twordDict = java.util.LinkedHashMap(merged)\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${entries.size} entries in memory; teaching ECI…\")\n"
    "\t\t\t\tvar taught = 0\n"
    "\t\t\t\tvar refused = 0\n"
    "\t\t\t\tfor (pair in entries.values) {\n"
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn false\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\tif (target.teachWord(volume, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tif ((taught + refused) % 64 == 0) Thread.yield()\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ECI teachWord taught=$taught refused=$refused in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
)
new_mem = (
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\tvar say = pair.second\n"
    "\t\t\t\t\tif (say.startsWith(\"[\") && say.endsWith(\"]\") && !say.startsWith(\"`[\")) {\n"
    "\t\t\t\t\t\tsay = \"`$say\"\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tmerged[key] = say\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${entries.size} phoneme entries for Java→ECI speak\")\n"
)
if old_teach in text:
    text = text.replace(old_teach, new_mem, 1)
    print("removed teachWord; memory+phoneme normalize only")
elif "teaching ECI" in text:
    raise SystemExit("teach block present but pattern mismatch")
else:
    print("teachWord already absent")

pat = re.compile(
    r"\t/\*\* Apply \[wordDict\].*?\n"
    r"\tprivate fun applyWordDict\(text: String\): String \{.*?\n"
    r"\tprivate fun lookupWordDict\(map: Map<String, String>, word: String\): String\? \{.*?\n"
    r"\t\treturn null\n"
    r"\t\}\n",
    re.S,
)
m = pat.search(text)
if not m:
    pat = re.compile(
        r"\tprivate fun applyWordDict\(text: String\): String \{.*?"
        r"\tprivate fun lookupWordDict\(map: Map<String, String>, word: String\): String\? \{.*?"
        r"\t\treturn null\n"
        r"\t\}\n",
        re.S,
    )
    m = pat.search(text)
if not m:
    raise SystemExit("applyWordDict+lookup not found")

new_apply = r'''	/** Replace every dictionary key with its phoneme/say string so ECI speak()
	 *  receives phonemes directly — no native teachWord / eciLoadDict. */
	private fun applyWordDict(text: String): String {
		val map = wordDict
		if (map.isEmpty() || text.isEmpty()) return text
		val keys = map.keys.sortedByDescending { it.length }
		val out = StringBuilder(text.length + 32)
		var i = 0
		while (i < text.length) {
			var hitKey: String? = null
			var hitSay: String? = null
			for (key in keys) {
				val len = key.length
				if (len == 0 || i + len > text.length) continue
				if (i > 0 && isDictWordChar(text[i - 1]) && isDictWordChar(key[0])) continue
				val after = i + len
				if (after < text.length && isDictWordChar(text[after]) && isDictWordChar(key[len - 1])) continue
				val slice = text.substring(i, after)
				val say = when {
					slice == key -> map[key]
					slice.equals(key, ignoreCase = true) ->
						map[key] ?: map[slice] ?: map[key.lowercase()] ?: map[slice.lowercase()]
					else -> null
				}
				if (say != null) {
					hitKey = key
					hitSay = say
					break
				}
			}
			if (hitKey != null && hitSay != null) {
				out.append(hitSay)
				i += hitKey.length
			} else {
				out.append(text[i])
				i++
			}
		}
		return out.toString()
	}

	private fun isDictWordChar(c: Char): Boolean =
		c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-'

'''

text = text[: m.start()] + new_apply + text[m.end() :]
print("full longest-match Java→ECI phoneme apply")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "teachWord(volume" in text:
    raise SystemExit("teachWord still present")
if "isDictWordChar" not in text:
    raise SystemExit("new apply missing")
print("v22 ok")
