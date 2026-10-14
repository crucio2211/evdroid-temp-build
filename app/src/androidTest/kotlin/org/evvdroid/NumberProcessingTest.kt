package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Numbers read the way the SAPI5 driver reads them.
 *
 * Thousands commas go in an always-on pre-pass, then pure digit runs
 * regroup: apart, remainder-first pairs and triplets, quadruplets whole
 * up to four and apart past that. Glued runs regroup too -- abc123 in
 * pairs is abc1 23 -- and decimals split in digits mode, exactly as on
 * SAPI5, whose engine takes it in stride.
 */
@RunWith(AndroidJUnit4::class)
class NumberProcessingTest {

	@Test
	fun thousandsCommasAlwaysGo() {
		assertEquals("1000", NumberProcessing.stripThousandsCommas("1,000"))
		assertEquals("1000000", NumberProcessing.stripThousandsCommas("1,000,000"))
		assertEquals("1.5", NumberProcessing.stripThousandsCommas("1.5"))
		assertEquals("1.0", NumberProcessing.stripThousandsCommas("1.0"))
		assertEquals("1,00", NumberProcessing.stripThousandsCommas("1,00"))
		assertEquals("", NumberProcessing.stripThousandsCommas(""))
	}

	@Test
	fun digitsComeApart() {
		assertEquals("3 0 0 0", NumberProcessing.apply("3000", NumberProcessing.DIGITS))
		assertEquals("3.1 4", NumberProcessing.apply("3.14", NumberProcessing.DIGITS))
	}

	@Test
	fun pairsSplitRemainderFirst() {
		assertEquals("30 00", NumberProcessing.apply("3000", NumberProcessing.PAIRS))
		assertEquals("1 23 45", NumberProcessing.apply("12345", NumberProcessing.PAIRS))
		assertEquals("12 34 56", NumberProcessing.apply("123456", NumberProcessing.PAIRS))
		assertEquals("12", NumberProcessing.apply("12", NumberProcessing.PAIRS))
	}

	@Test
	fun tripletsSplitRemainderFirst() {
		assertEquals("1 234 567", NumberProcessing.apply("1234567", NumberProcessing.TRIPLETS))
		assertEquals("123 456", NumberProcessing.apply("123456", NumberProcessing.TRIPLETS))
	}

	@Test
	fun quadrupletsFallBackPastFour() {
		assertEquals("3000", NumberProcessing.apply("3000", NumberProcessing.QUADRUPLETS))
		assertEquals("2 2 2 2 2", NumberProcessing.apply("22222", NumberProcessing.QUADRUPLETS))
	}

	@Test
	fun ordinaryTextIsUntouched() {
		for (text in listOf("Hello world", "Navigate up", "")) {
			assertEquals(text, NumberProcessing.apply(text, NumberProcessing.DIGITS))
		}
	}
}
