package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emoji spoken by name.
 *
 * Each check puts an emoji through and says the words that must come out.
 * Longest-first matching is the point of half of them: heart on fire must
 * win over a bare heart, a family over its first person, a keycap over its
 * digit. Modifiers standing alone say nothing and are dropped. Runs of one
 * name compress past the threshold instead of repeating.
 */
@RunWith(AndroidJUnit4::class)
class EmojiTest {

	@Test
	fun singleEmojiBecomeTheirNames() {
		assertEquals("Hi grinning face", EmojiProcessing.apply("Hi \uD83D\uDE00"))
		assertEquals("grinning face hi", EmojiProcessing.apply("\uD83D\uDE00hi"))
		assertEquals("hi grinning face", EmojiProcessing.apply("hi\uD83D\uDE00"))
	}

	@Test
	fun longestMatchWins() {
		// Heart on fire over a bare heart.
		assertEquals(
			"heart on fire",
			EmojiProcessing.apply("❤️‍🔥")
		)
		// A family over its first person.
		assertEquals(
			"family: man, woman, girl",
			EmojiProcessing.apply("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")
		)
		// A keycap over its digit.
		assertEquals("keycap: 1", EmojiProcessing.apply("1️⃣"))
	}

	@Test
	fun flagsAndTonedHandsHaveNames() {
		assertEquals("flag: Philippines", EmojiProcessing.apply("🇵🇭"))
		assertEquals("thumbs up: medium skin tone", EmojiProcessing.apply("👍🏽"))
	}

	@Test
	fun newestEmojiHaveNames() {
		assertEquals("treasure chest", EmojiProcessing.apply("🪎"))
		assertEquals("trombone", EmojiProcessing.apply("🪊"))
		assertEquals("distorted face", EmojiProcessing.apply("🫪"))
	}

	@Test
	fun emoji18HasNames() {
		assertEquals("cracking face", EmojiProcessing.apply(emoji(0x1FAEB)))
		assertEquals("leftwards thumb sign", EmojiProcessing.apply(emoji(0x1FAF9)))
		assertEquals(
			"rightwards thumb sign: dark skin tone",
			EmojiProcessing.apply(emoji(0x1FAFA, 0x1F3FF))
		)
		assertEquals("monarch butterfly", EmojiProcessing.apply(emoji(0x1FACC)))
		assertEquals("pickle", EmojiProcessing.apply(emoji(0x1FADD)))
		assertEquals("lighthouse", EmojiProcessing.apply(emoji(0x1F6D9)))
		assertEquals("meteor", EmojiProcessing.apply(emoji(0x1FA8B)))
		assertEquals("eraser", EmojiProcessing.apply(emoji(0x1FA8C)))
		assertEquals("net with handle", EmojiProcessing.apply(emoji(0x1FA8D)))
	}

	@Test
	fun emoji18Compresses() {
		assertEquals(
			"3 cracking face",
			EmojiProcessing.apply(emoji(0x1FAEB, 0x1FAEB, 0x1FAEB), 3)
		)
	}

	private fun emoji(vararg codePoints: Int): String =
		codePoints.map { String(Character.toChars(it)) }.joinToString("")

	@Test
	fun textPresentationStaysNamed() {
		assertEquals("copyright 2024", EmojiProcessing.apply("© 2024"))
	}

	@Test
	fun loneModifiersAreDropped() {
		assertEquals("ab", EmojiProcessing.apply("a\uFE0Fb"))
		assertEquals("ab", EmojiProcessing.apply("a\u200Db"))
		assertEquals("hi", EmojiProcessing.apply("\uD83C\uDFFDhi"))
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "3.14", "Navigate up", "")) {
			assertEquals(text, EmojiProcessing.apply(text))
		}
	}

	@Test
	fun runsCompressPastTheThreshold() {
		val three = "😀😀😀"
		assertEquals("3 grinning face", EmojiProcessing.apply(three, 3))
		assertEquals("2 grinning face", EmojiProcessing.apply("😀😀", 2))
		assertEquals(
			"Hi 3 grinning face bye",
			EmojiProcessing.apply("Hi 😀😀😀 bye", 3)
		)
	}

	@Test
	fun shortRunsSpeakEachOne() {
		assertEquals(
			"grinning face grinning face",
			EmojiProcessing.apply("😀😀", 3)
		)
	}

	@Test
	fun mixedRunsDoNotCompress() {
		assertEquals(
			"grinning face grinning face with big eyes grinning face",
			EmojiProcessing.apply("😀😃😀", 2)
		)
	}

	@Test
	fun sameNameDifferentBytesCompress() {
		assertEquals("2 red heart", EmojiProcessing.apply("❤️❤", 2))
	}
}
