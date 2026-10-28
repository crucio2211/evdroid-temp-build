#!/usr/bin/env python3
"""v20: stronger Java dictionary matching so external phoneme entries hit."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat = re.compile(
    r"\t/\*\* Case-sensitive whole-word replacement from \[wordDict\].*?\n"
    r"\tprivate fun applyWordDict\(text: String\): String \{.*?\n"
    r"\t\treturn out\.toString\(\)\n"
    r"\t\}\n",
    re.S,
)
m = pat.search(text)
if not m:
    pat = re.compile(
        r"\tprivate fun applyWordDict\(text: String\): String \{.*?\n\t\}\n",
        re.S,
    )
    m = pat.search(text)
    if not m:
        raise SystemExit("applyWordDict block not found")

new_fn = r'''	/** Apply [wordDict] with longest-match and light case fallback.
	 *  External phoneme lists often use lowercase keys while TalkBack sends
	 *  mixed case; pure exact match skipped most of those entries. */
	private fun applyWordDict(text: String): String {
		val map = wordDict
		if (map.isEmpty() || text.isEmpty()) return text
		var result = text
		val phrases = map.keys.filter { it.contains(' ') }.sortedByDescending { it.length }
		for (phrase in phrases) {
			val say = map[phrase] ?: continue
			if (result.contains(phrase)) result = result.replace(phrase, say)
		}
		val out = StringBuilder(result.length + 16)
		var i = 0
		val s = result
		while (i < s.length) {
			val ch = s[i]
			if (ch.isLetterOrDigit()) {
				val start = i
				i++
				while (i < s.length) {
					val c = s[i]
					if (c.isLetterOrDigit() || c == '\'' || c == '\u2019' || c == '-' || c == '.') i++
					else break
				}
				var end = i
				while (end > start && (s[end - 1] == '.' || s[end - 1] == '-')) end--
				val word = s.substring(start, end)
				val trail = s.substring(end, i)
				val say = lookupWordDict(map, word)
				if (say != null) {
					out.append(say)
					out.append(trail)
				} else {
					out.append(s, start, i)
				}
			} else {
				out.append(ch)
				i++
			}
		}
		return out.toString()
	}

	private fun lookupWordDict(map: Map<String, String>, word: String): String? {
		map[word]?.let { return it }
		if (word.isEmpty()) return null
		val lower = word.lowercase()
		if (lower != word) map[lower]?.let { return it }
		val title = word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
		if (title != word && title != lower) map[title]?.let { return it }
		return null
	}

'''

text = text[: m.start()] + new_fn + text[m.end() :]
print("stronger applyWordDict")

old_filter = "\t\t\tif ((langs[volume] ?: target.language) != target.language) continue\n"
new_filter = (
    "\t\t\tval dictLang = langs[volume]\n"
    "\t\t\tif (dictLang != null && dictLang != target.language) continue\n"
)
if old_filter in text:
    text = text.replace(old_filter, new_filter, 1)
    print("softened language filter")
else:
    print("WARN language filter not found")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "lookupWordDict" not in text:
    raise SystemExit("lookup missing")
print("v20 ok")
