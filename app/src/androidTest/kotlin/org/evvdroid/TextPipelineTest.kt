package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one pipeline TalkBack and the sample button share.
 *
 * Thousands commas go first and always, then emoji, numbers when asked,
 * camel case when asked, crash guards, readability fixes, punctuation when
 * asked, and the user's own rewrites closest to the engine. Stages compose in order and every
 * stage's output reaches the engine: punctuation once computed its text
 * and never handed it on, so all levels were silent, and only an
 * end-to-end check like these can catch that kind of wiring again.
 */
@RunWith(AndroidJUnit4::class)
class TextPipelineTest {

	@Test
	fun thousandsCommasGoFirstAlways() {
		assertEquals(
			"1000",
			TextPipeline.process(
				"1,000",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = null,
				camelCase = false,
				stripBackquotes = true,
				punctLevel = null
			)
		)
	}

	@Test
	fun numbersRunBeforePunctuation() {
		assertEquals(
			"Call 1 23 45.",
			TextPipeline.process(
				"Call 12345.",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = NumberProcessing.PAIRS,
				camelCase = false,
				stripBackquotes = true,
				punctLevel = PunctuationProcessing.SOME
			)
		)
	}

	@Test
	fun punctuationReachesTheEngine() {
		assertEquals(
			"Hello exclamation mark `p1",
			TextPipeline.process(
				"Hello!",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = null,
				camelCase = false,
				stripBackquotes = true,
				punctLevel = PunctuationProcessing.ALL
			)
		)
	}

	@Test
	fun everythingOffLeavesTextAlone() {
		assertEquals(
			"Hello, world!",
			TextPipeline.process(
				"Hello, world!",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = null,
				camelCase = false,
				stripBackquotes = true,
				punctLevel = null
			)
		)
	}

	@Test
	fun camelCaseSplitsBeforeGuards() {
		assertEquals(
			"Chat Gpt",
			TextPipeline.process(
				"ChatGpt",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = null,
				camelCase = true,
				stripBackquotes = true,
				punctLevel = null
			)
		)
	}

	@Test
	fun camelSplitMcRejoinsInGuards() {
		assertEquals(
			"McDonald",
			TextPipeline.process(
				"McDonald",
				emoji = false,
				emojiCompress = EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = null,
				camelCase = true,
				stripBackquotes = true,
				punctLevel = null
			)
		)
	}
}
