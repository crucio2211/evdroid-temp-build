package org.evvdroid

/**
 * Punctuation spoken from the SAPI5 symbol table: levels, names and
 * preserve flags, mark for mark.
 *
 * A mark the level reaches is spoken by name. Marks flagged to stay with
 * the synthesiser keep it company as a brief pause instead of as
 * themselves, because this engine spells an isolated mark aloud and every
 * kept one came out twice. A mark below its level stays for the engine's
 * default, and sentence-ending !? stays prosody below all. Periods are spoken
 * as "dot" at Some or above; decimal points between digits pass untouched.
 *
 * Glued marks get no special treatment: don't becomes don apostrophe t at
 * all, exactly as on SAPI5, whose engine takes it in stride.
 *
 * This runs after AntiCrash, so the guards see words as typed, and after
 * TextFixes, so its letter-digit rule never walks into a pause left here
 * and turns `p1 into a spoken `p 1.
 */
object PunctuationProcessing {

	const val NONE = 0
	const val SOME = 1
	const val MOST = 2
	const val ALL = 3

	fun apply(text: String, level: Int): String {
		if (text.isEmpty()) return text
		val out = StringBuilder(text.length * 2)
		var i = 0
		while (i < text.length) {
			val cp = Character.codePointAt(text, i)
			val adv = Character.charCount(cp)
			var rep: String? = null
			var pres = PunctSymbols.PASS
			if (cp > 0x20) {
				val entry = PunctSymbols.lookup(cp)
				if (entry != null) {
					pres = entry.pres
					if (entry.level <= level) rep = entry.word
				}
			}
			// Numeric context: . or , between ASCII digits passes as-is.
			if ((cp == '.'.code || cp == ','.code) && i > 0 && i + adv < text.length &&
				text[i - 1] in '0'..'9' && text[i + adv] in '0'..'9'
			) {
				rep = null
				pres = PunctSymbols.PASS
			}
			// Speak periods as the lowercase word "dot" when punctuation is
			// enabled at Some or above. Decimal points remain numeric punctuation.
			if (cp == '.'.code && level >= SOME && rep != null &&
				!(i > 0 && i + adv < text.length && text[i - 1] in '0'..'9' && text[i + adv] in '0'..'9')
			) {
				rep = "dot"
				pres = PunctSymbols.PASS
			}
			// Sentence boundary .!? : below all it is prosody, not speech.
			if (isSentenceMark(cp) && isSentenceEnding(text, i, text.length)) {
				if (level < ALL && !(cp == '.'.code && level >= SOME && rep != null)) rep = null
				if (!(cp == '.'.code && level >= SOME && rep != null)) pres = PunctSymbols.ALWAYS
			}
			if (rep != null) {
				pad(out)
				out.append(rep)
				if (pres == PunctSymbols.ALWAYS || pres == PunctSymbols.NOREP) {
					out.append(' ')
					out.append(BRIEF_PAUSE)
				}
				if (i + adv >= text.length || !text[i + adv].isWhitespace()) out.append(' ')
			} else if (pres != PunctSymbols.NEVER) {
				for (k in 0 until adv) out.append(text[i + k])
			}
			i += adv
		}
		return out.toString().trim()
	}

	private fun pad(out: StringBuilder) {
		if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
	}

	private fun isSentenceMark(cp: Int): Boolean =
		cp == '.'.code || cp == '!'.code || cp == '?'.code ||
			cp == 0xFF01 || cp == 0xFF1F || cp == 0x061F

	private fun isSentenceEnding(text: String, at: Int, length: Int): Boolean {
		if (at == 0) return false
		val prev = text[at - 1]
		if (prev == ' ' || prev == '\t') return false
		var j = at + 1
		while (j < length) {
			val c = text[j]
			if (c != ' ' && c != '\t' && c != '\'' && c != '"' &&
				c != '”' && c != '’' && c != ')' && c != ']'
			) break
			j++
		}
		if (j >= length) return true
		val nc = text[j]
		if (nc in 'A'..'Z') return true
		if (nc in 'a'..'z') return false
		if (nc in '0'..'9') return false
		return true
	}

	/** The pause left where a kept mark would stand: brief, obeyed, never
	 *  spelled. Added after the backquote strip, so it always survives. */
	private const val BRIEF_PAUSE = "`p1"
}
