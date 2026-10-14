package org.evvdroid

/**
 * Everything text goes through before the engine, in order, shared by the
 * speech service and the settings preview so the sample button hears what
 * TalkBack will hear.
 *
 * Thousands commas go first, always, even with everything off. Then emoji
 * into words, numbers regrouped when asked, camel case split when asked,
 * the crash guards on words as typed, readability fixes, `@` as the word
 * `at`, punctuation when asked, and the user's own rewrites closest to the
 * engine. User dictionaries stay out: they live on the service engine,
 * which the preview does not share.
 */
object TextPipeline {

	/**
	 * [raw] rewritten. A null [numberMode] or [punctLevel] switches that
	 * pass off; [stripBackquotes] guards hostile markup out.
	 */
	fun process(
		raw: String,
		emoji: Boolean,
		emojiCompress: Int,
		numberMode: Int?,
		camelCase: Boolean,
		stripBackquotes: Boolean,
		punctLevel: Int?
	): String {
		if (raw.isEmpty()) return raw
		val stripped = NumberProcessing.stripThousandsCommas(raw)
		val worded = if (emoji) EmojiProcessing.apply(stripped, emojiCompress) else stripped
		val numbered = if (numberMode != null) NumberProcessing.apply(worded, numberMode) else worded
		val camelled = if (camelCase) CamelCase.apply(numbered) else numbered
		val guarded = AntiCrash.apply(camelled, stripBackquotes)
		val fixed = TextFixes.apply(guarded)
		val voiced = SymbolWords.apply(fixed)
		return if (punctLevel != null) PunctuationProcessing.apply(voiced, punctLevel) else voiced
	}
}
