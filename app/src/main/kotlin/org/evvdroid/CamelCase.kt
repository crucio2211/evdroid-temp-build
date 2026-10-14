package org.evvdroid

/**
 * Camel-case words split where the case turns, so the engine reads them as
 * words instead of spelling or swallowing them: ChatGpt comes out as
 * Chat Gpt, TalkBack as Talk Back, PDFFile as PDF File, iPhone as i Phone.
 * All-caps abbreviations are left alone, so NVDA stays one word.
 *
 * This runs after numbers and before the crash guards, so a split the
 * guards dislike is still caught: McDonald splits and is joined again,
 * netting nothing.
 */
object CamelCase {

	private val LOWER_TO_UPPER = Regex("(?<=[a-z0-9])(?=[A-Z])")
	private val ACRONYM_TO_WORD = Regex("(?<=[A-Z])(?=[A-Z][a-z])")

	/**
	 * [text] with a space at every case turn.
	 */
	fun apply(text: String): String {
		if (text.isEmpty()) return text
		return LOWER_TO_UPPER.replace(ACRONYM_TO_WORD.replace(text, " "), " ")
	}
}
