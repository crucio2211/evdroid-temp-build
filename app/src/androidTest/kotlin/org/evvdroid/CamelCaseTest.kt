package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Camel-case words split where the case turns, so the engine reads them as
 * words instead of spelling or swallowing them.
 */
@RunWith(AndroidJUnit4::class)
class CamelCaseTest {

	@Test
	fun lowerToUpperSplits() {
		assertEquals("Chat Gpt", CamelCase.apply("ChatGpt"))
		assertEquals("Talk Back", CamelCase.apply("TalkBack"))
		assertEquals("i Phone", CamelCase.apply("iPhone"))
		assertEquals("e Bay", CamelCase.apply("eBay"))
		assertEquals("mp3 Player", CamelCase.apply("mp3Player"))
	}

	@Test
	fun acronymsSplitBeforeTheWord() {
		assertEquals("PDF File", CamelCase.apply("PDFFile"))
		assertEquals("ABC Def", CamelCase.apply("ABCDef"))
	}

	@Test
	fun allCapsStayWhole() {
		for (text in listOf("NVDA", "PDF", "ABC", "USB")) {
			assertEquals(text, CamelCase.apply(text))
		}
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "already spaced", "lowercase", "3.14", "")) {
			assertEquals(text, CamelCase.apply(text))
		}
	}
}
