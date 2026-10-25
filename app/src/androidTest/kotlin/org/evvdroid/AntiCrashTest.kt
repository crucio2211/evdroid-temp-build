package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rewrites that keep crash words away from the engine.
 *
 * Each check feeds a known crash shape through and says what must come out.
 * They are pure text checks on purpose: a crash guard is only worth having if
 * it fires where it should and nowhere else, and that is decided off the
 * page rather than in the engine.
 */
@RunWith(AndroidJUnit4::class)
class AntiCrashTest {

	@Test
	fun crashCodesAreDefused() {
		assertEquals("h re", AntiCrash.apply("h're"))
		assertEquals("1h ve", AntiCrash.apply("1h've"))
	}

	/** The driver's replacement drops the trailing e along with the crash,
	 *  so caesure reads seizur and caesura reads seizura. */
	@Test
	fun caesuraReadsAsSeizure() {
		assertEquals("seizura", AntiCrash.apply("caesura"))
		assertEquals("seizur", AntiCrash.apply("caesure"))
	}

	@Test
	fun spacedMcNamesAreJoined() {
		assertEquals("McDonald", AntiCrash.apply("Mc Donald"))
		assertEquals("McDONALD", AntiCrash.apply("Mc DONALD"))
	}

	@Test
	fun dateParserIsKeptApart() {
		assertEquals("03  Marble", AntiCrash.apply("03 Marble"))
		// Full month names pass through as they were: the guard below puts
		// back the space this rule added, same as the driver's order does.
		assertEquals("3 September", AntiCrash.apply("3 September"))
	}

	@Test
	fun fullMonthNamesSurvive() {
		assertEquals("3 March", AntiCrash.apply("3  March"))
	}

	@Test
	fun clockTimeWithOrdinalIsSplit() {
		assertEquals("3 25th", AntiCrash.apply("3:25th"))
	}

	@Test
	fun fullClockTimeKeepsBothHalves() {
		assertEquals("2:30 15", AntiCrash.apply("2:30:15"))
	}

	@Test
	fun gluedPrefixesAreSplit() {
		assertEquals("rekosp", AntiCrash.apply("recosp"))
		assertEquals("antikosp", AntiCrash.apply("anticosp"))
	}

	@Test
	fun gluedEurCodesAreSplit() {
		assertEquals("EURUSD 5", AntiCrash.apply("EURUSD5"))
	}

	@Test
	fun longJuarWordsAreSplit() {
		assertEquals("juar eeeeeeeee", AntiCrash.apply("juareeeeeeeee"))
	}

	@Test
	fun youreClustersAreSplit() {
		assertEquals("you re ve", AntiCrash.apply("you're've"))
	}

	@Test
	fun arithmeticOnDecimalsIsSpaced() {
		assertEquals("2+3.5. 00", AntiCrash.apply("2+3.5.00"))
		assertEquals("2+3, 00", AntiCrash.apply("2+3,00"))
	}

	@Test
	fun backquotesAreStrippedByDefault() {
		assertEquals(" vs50 hello", AntiCrash.apply("`vs50 hello"))
		assertFalse(AntiCrash.apply("say `p1 hi").contains('`'))
	}

	@Test
	fun backquotesSurviveWhenTagsAreOn() {
		assertEquals("`vs50 hello", AntiCrash.apply("`vs50 hello", stripBackquotes = false))
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("3.14", "1,024", "Hello world", "Navigate up", "")) {
			assertEquals(text, AntiCrash.apply(text))
		}
	}

	/**
	 * Why the service guards the text before it fixes it. The other way
	 * round, the readability rules get to the crash words first.
	 */
	@Test
	fun guardsRunBeforeFixes() {
		val guarded = TextFixes.apply(AntiCrash.apply("Mc Donald5"))
		assertEquals("McDonald 5", guarded)
	}
}
