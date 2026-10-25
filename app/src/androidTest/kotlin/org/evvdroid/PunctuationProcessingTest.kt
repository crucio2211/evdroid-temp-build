package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Punctuation from the SAPI5 symbol table, all 288 entries of it.
 *
 * A reached level speaks the table's own name; marks flagged to stay keep
 * the synthesiser company as a brief pause instead of as themselves,
 * because this engine spells an isolated mark aloud and every kept one
 * came out twice. Below its level a mark stays for the engine's default,
 * sentence-ending .!? stays prosody below all, and dots and commas
 * between digits pass untouched.
 */
@RunWith(AndroidJUnit4::class)
class PunctuationProcessingTest {

	@Test
	fun tableFindsItsMarks() {
		assertEquals("dot", PunctSymbols.lookup('.'.code)?.word)
		assertEquals("comma", PunctSymbols.lookup(','.code)?.word)
		assertEquals("question mark", PunctSymbols.lookup('?'.code)?.word)
		assertEquals("left paren", PunctSymbols.lookup('('.code)?.word)
		assertEquals("euro", PunctSymbols.lookup('€'.code)?.word)
		assertNull(PunctSymbols.lookup('#'.code))
		assertNull(PunctSymbols.lookup(' '.code))
	}

	@Test
	fun spokenMarksComeWithPausesWhereFlagged() {
		assertEquals(
			"Hello comma `p1 world",
			PunctuationProcessing.apply("Hello, world", PunctuationProcessing.ALL)
		)
		assertEquals(
			"left paren `p1 s right paren `p1",
			PunctuationProcessing.apply("(s)", PunctuationProcessing.MOST)
		)
		assertEquals(
			"call colon `p1 now",
			PunctuationProcessing.apply("call: now", PunctuationProcessing.MOST)
		)
	}

	@Test
	fun plainSpokenMarksComeAlone() {
		assertEquals(
			"Hello dot Are you 3:30?",
			PunctuationProcessing.apply("Hello. Are you 3:30?", PunctuationProcessing.SOME)
		)
	}

	@Test
	fun belowLevelStaysForTheEngine() {
		assertEquals(
			"Wait, what? No!",
			PunctuationProcessing.apply("Wait, what? No!", PunctuationProcessing.MOST)
		)
	}

	@Test
	fun sentenceEndingsStayProsodyBelowAll() {
		assertEquals("Hello.", PunctuationProcessing.apply("Hello.", PunctuationProcessing.SOME))
		assertEquals("Hello.", PunctuationProcessing.apply("Hello.", PunctuationProcessing.MOST))
		assertEquals("What?!", PunctuationProcessing.apply("What?!", PunctuationProcessing.SOME))
	}

	@Test
	fun sentenceEndingsSpeakWithPauseAtAll() {
		assertEquals(
			"Hello dot `p1",
			PunctuationProcessing.apply("Hello.", PunctuationProcessing.ALL)
		)
	}

	@Test
	fun numericsPassUntouched() {
		assertEquals("1.5", PunctuationProcessing.apply("1.5", PunctuationProcessing.SOME))
		assertEquals("1,000", PunctuationProcessing.apply("1,000", PunctuationProcessing.SOME))
	}

	@Test
	fun gluedMarksAreSpokenLikeSapi5() {
		assertEquals(
			"don apostrophe t",
			PunctuationProcessing.apply("don't", PunctuationProcessing.ALL)
		)
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "Navigate up", "")) {
			assertEquals(text, PunctuationProcessing.apply(text, PunctuationProcessing.ALL))
		}
	}
}
