package org.evvdroid

/**
 * The `@` the engine pauses at instead of reading.
 *
 * It always becomes the word `at`, padded with spaces so an address reads
 * as words: user at example com. Saying "at sign" instead was never asked
 * for and reads badly in every address it lands in. Everything else passes
 * to the engine untouched, punctuation on or off: with it off the engine's
 * own defaults speak dots and pluses, which is what that setting means.
 *
 * Runs after TextFixes, which must not see the spaces added here as gaps
 * before marks, and before PunctuationProcessing, which never sees an `@`
 * anymore.
 */
object SymbolWords {

	fun apply(text: String): String {
		if (text.isEmpty() || '@' !in text) return text
		val out = StringBuilder(text.length + 16)
		for (c in text) {
			if (c == '@') {
				pad(out)
				out.append("at ")
			} else {
				out.append(c)
			}
		}
		return out.toString().replace(Regex(" {2,}"), " ").trim()
	}

	private fun pad(out: StringBuilder) {
		if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
	}
}
