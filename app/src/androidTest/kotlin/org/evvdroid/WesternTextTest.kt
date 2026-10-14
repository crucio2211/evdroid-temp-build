package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android text turned into what the engine reads.
 *
 * Bytes for the Western languages, Shift-JIS bytes for Japanese: what comes
 * out of each encoder must be exactly what the engine's own code set holds,
 * with nothing in it that would end the text early.
 */
@RunWith(AndroidJUnit4::class)
class WesternTextTest {

	private val SHIFT_JIS = java.nio.charset.Charset.forName("Shift_JIS")

	@Test
	fun japaneseRoundTrips() {
		assertEquals(
			"こんにちは",
			String(WesternText.encodeJapanese("こんにちは"), SHIFT_JIS)
		)
	}

	@Test
	fun japaneseKeepsAsciiAndTypographicEquivalents() {
		assertEquals("Hello", String(WesternText.encodeJapanese("Hello"), SHIFT_JIS))
		assertEquals("...", String(WesternText.encodeJapanese("…"), SHIFT_JIS))
	}

	@Test
	fun japaneseNeutralisesNoughts() {
		assertEquals("a b", String(WesternText.encodeJapanese("a\u0000b"), SHIFT_JIS))
	}

	@Test
	fun japaneseReplacesTheUnencodable() {
		assertEquals("?", String(WesternText.encodeJapanese("\uD83D\uDE00"), SHIFT_JIS))
	}

	@Test
	fun bytesKeepWesternCharacters() {
		assertEquals(1, WesternText.encode("é").size)
		assertArrayEquals(
			" euro ".toByteArray(Charsets.ISO_8859_1),
			WesternText.encode("€")
		)
	}
}
