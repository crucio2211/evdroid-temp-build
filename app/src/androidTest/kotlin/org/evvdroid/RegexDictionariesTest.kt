package org.evvdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The user dictionary written as regular expressions.
 *
 * What is checked here is the reading of the file and the rewriting, both
 * off the page: a pattern that fires where it should and nowhere else is
 * decided without an engine. The sample is written here rather than shipped
 * from anywhere.
 */
@RunWith(AndroidJUnit4::class)
class RegexDictionariesTest {

	@Test
	fun patternsRewriteInFileOrder() {
		val loaded = load("NVDA\ten vee dee ay\n")
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun groupsCarryOver() {
		val loaded = load("\\b(\\d+)kg\t$1 kilograms\n")
		assertEquals("5 kilograms", RegexDictionaries.apply(loaded, "5kg"))
		assertEquals("flour", RegexDictionaries.apply(loaded, "flour"))
	}

	@Test
	fun laterEntriesSeeEarlierResults() {
		val loaded = load("NVDA\ten vee dee ay\nvee\tV\n")
		assertEquals("say en V dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun commentsBlanksAndBadLinesAreSkipped() {
		val file = write(
			"# a comment\n" +
				"\n" +
				"   \n" +
				"no tab here\n" +
				"\tleading tab\n" +
				"trailing tab\t\n" +
				"NVDA\ten vee dee ay\n"
		)
		assertEquals("one usable pattern", 1, RegexDictionaries.count(file))
		val loaded = RegexDictionaries.load(file)
		assertNotNull(loaded)
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun aBadPatternDoesNotSinkTheFile() {
		val loaded = load("([a-z\tbroken\nNVDA\ten vee dee ay\n")
		assertNotNull("the good line went down with the bad one", loaded)
		assertEquals(1, loaded!!.entries.size)
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say NVDA"))
	}

	@Test
	fun anEmptyFileLoadsAsNothing() {
		val file = write("# only a comment\n\n")
		assertEquals(0, RegexDictionaries.count(file))
		assertNull(RegexDictionaries.load(file))
	}

	@Test
	fun aMissingFileLoadsAsNothing() {
		val missing = File(dir(), "not-there.dic")
		if (missing.exists()) missing.delete()
		assertEquals(0, RegexDictionaries.count(missing))
		assertNull(RegexDictionaries.load(missing))
	}

	@Test
	fun nothingLoadedLeavesTextAlone() {
		assertEquals("say NVDA", RegexDictionaries.apply(null, "say NVDA"))
		assertEquals("", RegexDictionaries.apply(null, ""))
	}

	@Test
	fun emptyTextStaysEmpty() {
		val loaded = load("NVDA\ten vee dee ay\n")
		assertEquals("", RegexDictionaries.apply(loaded, ""))
	}

	@Test
	fun aFreshLoadIsCurrentAndADeletedFileIsNot() {
		val file = write("NVDA\ten vee dee ay\n")
		val loaded = RegexDictionaries.load(file)
		assertNotNull(loaded)
		assertTrue(RegexDictionaries.isCurrent(loaded))
		assertFalse(RegexDictionaries.isCurrent(null))
		file.delete()
		assertFalse("a deleted file still counts as current", RegexDictionaries.isCurrent(loaded))
	}

	@Test
	fun inlineFlagsAreHonoured() {
		val loaded = load("(?i)\\bnvda\ten vee dee ay\n")
		assertEquals("say en vee dee ay", RegexDictionaries.apply(loaded, "say nvda"))
	}

	@Test
	fun entriesReadBackAsWritten() {
		val file = write("NVDA\ten vee dee ay\n# comment\n\\b(\\d+)kg\t$1 kilograms\n")
		val entries = RegexDictionaries.readEntries(file)
		assertEquals(2, entries.size)
		assertEquals("NVDA", entries[0].pattern)
		assertEquals("en vee dee ay", entries[0].replacement)
		assertEquals("\\b(\\d+)kg", entries[1].pattern)
	}

	@Test
	fun appendedEntriesTakeEffectInOrder() {
		val file = write("NVDA\ten vee dee ay\n")
		assertTrue(RegexDictionaries.appendEntry(file, "vee", "V"))
		assertEquals("say en V dee ay", RegexDictionaries.apply(RegexDictionaries.load(file), "say NVDA"))
	}

	@Test
	fun invalidEntriesAreNotAppended() {
		val file = write("NVDA\ten vee dee ay\n")
		assertFalse(RegexDictionaries.appendEntry(file, "([a-z", "broken"))
		assertFalse(RegexDictionaries.appendEntry(file, "   ", "blank pattern"))
		assertFalse(RegexDictionaries.appendEntry(file, "fine", "   "))
		assertEquals(1, RegexDictionaries.count(file))
	}

	@Test
	fun appendingTerminatesAnUnterminatedFile() {
		val file = write("NVDA\ten vee dee ay")
		assertTrue(RegexDictionaries.appendEntry(file, "vee", "V"))
		assertEquals(2, RegexDictionaries.count(file))
		assertEquals("say en V dee ay", RegexDictionaries.apply(RegexDictionaries.load(file), "say NVDA"))
	}

	@Test
	fun removingDropsOneEntryAndKeepsTheRest() {
		val file = write("# keep me\nNVDA\ten vee dee ay\n\\b(\\d+)kg\t$1 kilograms\n")
		assertTrue(RegexDictionaries.removeEntryAt(file, 0))
		assertEquals("say NVDA", RegexDictionaries.apply(RegexDictionaries.load(file), "say NVDA"))
		assertEquals("5 kilograms", RegexDictionaries.apply(RegexDictionaries.load(file), "5kg"))
	}

	@Test
	fun replacingKeepsItsPlace() {
		val file = write("NVDA\ten vee dee ay\nLOL\tell oe ell\nSNES\tess en ee ess\n")
		assertTrue(RegexDictionaries.replaceEntryAt(file, 1, "lol", "laughing out loud"))
		val entries = RegexDictionaries.readEntries(file)
		assertEquals(3, entries.size)
		assertEquals("NVDA", entries[0].pattern)
		assertEquals("lol", entries[1].pattern)
		assertEquals("laughing out loud", entries[1].replacement)
		assertEquals("SNES", entries[2].pattern)
	}

	@Test
	fun replacingRefusesBadText() {
		val file = write("NVDA\ten vee dee ay\n")
		assertFalse(RegexDictionaries.replaceEntryAt(file, 0, "([a-z", "broken"))
		assertFalse(RegexDictionaries.replaceEntryAt(file, 0, "   ", "blank"))
		assertFalse(RegexDictionaries.replaceEntryAt(file, 5, "ok", "nowhere"))
		assertEquals("NVDA", RegexDictionaries.readEntries(file)[0].pattern)
	}

	@Test
	fun removingNothingFailsQuietly() {
		val file = write("NVDA\ten vee dee ay\n")
		assertFalse(RegexDictionaries.removeEntryAt(file, 3))
		assertFalse(RegexDictionaries.removeEntryAt(file, -1))
		assertEquals(1, RegexDictionaries.count(file))
	}

	private fun load(text: String): RegexDictionaries.Loaded? {
		val loaded = RegexDictionaries.load(write(text))
		assertNotNull("nothing usable in: $text", loaded)
		return loaded
	}

	@Test
	fun caseFoldingIsAnInlineFlag() {
		assertEquals("(?i)chatgpt", RegexDictionaries.withCase("chatgpt", false))
		assertEquals("chatgpt", RegexDictionaries.withCase("chatgpt", true))
		assertEquals("(?i)chatgpt", RegexDictionaries.withCase("(?i)chatgpt", false))
	}

	@Test
	fun unfoldedPatternsFixCapitalisedWords() {
		val loaded = load(RegexDictionaries.withCase("chatgpt", false) + "\tchat jee pee tee\n")
		assertNotNull(loaded)
		assertEquals(
			"I use chat jee pee tee daily",
			RegexDictionaries.apply(loaded, "I use ChatGpt daily")
		)
	}

	@Test
	fun foldedPatternsKeepTheirCase() {
		val loaded = load(RegexDictionaries.withCase("chatgpt", true) + "\tchat jee pee tee\n")
		assertNotNull(loaded)
		assertEquals(
			"I use ChatGpt daily",
			RegexDictionaries.apply(loaded, "I use ChatGpt daily")
		)
	}

	private fun dir(): File {
		val to = InstrumentationRegistry.getInstrumentation().targetContext
		return File(to.cacheDir, "regex-tests").apply { mkdirs() }
	}

	private var fileNumber = 0

	private fun write(text: String): File {
		val file = File(dir(), "test-${fileNumber++}.dic")
		file.writeBytes(text.toByteArray(Charsets.UTF_8))
		return file
	}
}
