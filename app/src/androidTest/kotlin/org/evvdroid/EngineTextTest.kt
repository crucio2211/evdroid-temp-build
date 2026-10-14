package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android text turned into the bytes the language in force reads.
 *
 * Western languages go through the Western byte set exactly as before.
 * Polish keeps its own eight letters as UTF-8 and flattens everything else
 * the Western way. Japanese keeps its Shift-JIS path, question marks and
 * all.
 */
@RunWith(AndroidJUnit4::class)
class EngineTextTest {

	@Test
	fun westernLanguagesAreUnchanged() {
		assertArrayEquals(
			WesternText.encode("caf\u00e9"),
			EngineText.encode(0x00010000, "caf\u00e9")
		)
	}

	@Test
	fun polishKeepsItsOwnLettersAsUtf8() {
		assertEquals(
			"Zaba",
			String(EngineText.encode(0x00110000, "Zaba"), Charsets.UTF_8)
		)
		val kept = String(EngineText.encode(0x00110000, "ab\u00e9"), Charsets.UTF_8)
		assertEquals("ab\u00e9", kept)
	}

	@Test
	fun japaneseKeepsItsShiftJisPath() {
		assertArrayEquals(
			WesternText.encodeJapanese("text"),
			EngineText.encode(0x00080000, "text")
		)
	}
}
