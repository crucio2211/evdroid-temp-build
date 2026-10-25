package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `@` is always the word `at`; everything else passes to the engine
 * untouched, punctuation on or off.
 */
@RunWith(AndroidJUnit4::class)
class SymbolWordsTest {

	@Test
	fun atSignBecomesAtWithSpaces() {
		assertEquals("user at example.com", SymbolWords.apply("user@example.com"))
		assertEquals("at home", SymbolWords.apply("@home"))
		assertEquals("mail me at a at b", SymbolWords.apply("mail me@a@b"))
	}

	@Test
	fun everythingElsePassesThrough() {
		assertEquals("example.com", SymbolWords.apply("example.com"))
		assertEquals("a+b", SymbolWords.apply("a+b"))
		assertEquals("Hello. World", SymbolWords.apply("Hello. World"))
		assertEquals("Call 3.14 now", SymbolWords.apply("Call 3.14 now"))
		assertEquals("", SymbolWords.apply(""))
		assertEquals("no marks here", SymbolWords.apply("no marks here"))
	}
}
