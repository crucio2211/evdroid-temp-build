package org.evvdroid

import android.util.Log
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
 * Teaching the engine how to say a word.
 *
 * The dictionaries people share for this engine are text, and the engine's own
 * loader does not take them, so what is checked here is our reading of the file
 * and the engine's answer to being taught a word at a time.
 *
 * The sample is written here rather than shipped from anywhere. The dictionaries
 * worth loading belong to the people who wrote them.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryTest {

	@Test
	fun aWordCanBeTaughtAndIsThenSaidDifferently() {
		val e = open()
		try {
			val before = samples(e, "DOJ")
			assertEquals("the engine refused the word", 0, e.teachWord(Eci.DICT_MAIN, "DOJ", "dee oe jeigh"))
			assertEquals("dee oe jeigh", e.lookUpWord(Eci.DICT_MAIN, "DOJ"))
			val after = samples(e, "DOJ")
			assertTrue("teaching the word changed nothing", after != before)
		} finally {
			e.close()
		}
	}

	@Test
	fun aPronunciationInTheEnginesOwnAlphabetIsTaken() {
		val e = open()
		try {
			assertEquals(0, e.teachWord(Eci.DICT_MAIN, "DOS", "`[das]"))
			assertEquals("`[das]", e.lookUpWord(Eci.DICT_MAIN, "DOS"))
			assertTrue(samples(e, "DOS") > 0)
		} finally {
			e.close()
		}
	}

	@Test
	fun aFileOfEntriesLoads() {
		val e = open()
		try {
			val file = write(SAMPLE)
			assertEquals("the file was miscounted", 6, Dictionaries.count(file))
			assertEquals("not every entry went in", 6, Dictionaries.load(e, Eci.DICT_MAIN, file))
			assertEquals("dee oe jeigh", e.lookUpWord(Eci.DICT_MAIN, "DOJ"))
			assertEquals("`[das]", e.lookUpWord(Eci.DICT_MAIN, "DOS"))
			assertNotNull("a Latin-1 key was lost", e.lookUpWord(Eci.DICT_MAIN, "café"))
		} finally {
			e.close()
		}
	}

	@Test
	fun linesThatAreNotEntriesAreSkipped() {
		val e = open()
		try {
			val file = write("no tab here\nDOJ\tdee oe jeigh\n   \n")
			assertEquals(1, Dictionaries.count(file))
			assertEquals(1, Dictionaries.load(e, Eci.DICT_MAIN, file))
		} finally {
			e.close()
		}
	}

	/** A real dictionary is a few thousand entries, and this is what says
	 *  loading one is something the speech service can afford when it starts. */
	@Test
	fun thousandsOfEntriesLoadQuickly() {
		val e = open()
		try {
			val many = buildString {
				for (n in 0 until MANY) {
					append("evvword")
					append(n)
					append('\t')
					append("evv word ")
					append(n)
					append("\r\n")
				}
			}
			val file = write(many)
			assertEquals(MANY, Dictionaries.count(file))
			val took = System.currentTimeMillis()
			val taught = Dictionaries.load(e, Eci.DICT_ROOT, file)
			val spent = System.currentTimeMillis() - took
			Log.e(TAG, "$MANY entries in ${spent}ms")
			assertEquals("not every entry went in", MANY, taught)
			assertTrue("loading took ${spent}ms", spent < 8000)
		} finally {
			e.close()
		}
	}

	@Test
	fun sharedNamesRouteToTheirVolumes() {
		assertEquals(Eci.DICT_MAIN, Dictionaries.volumeForFile("enumain.dic"))
		assertEquals(Eci.DICT_ROOT, Dictionaries.volumeForFile("enuroot.dic"))
		assertEquals(Eci.DICT_ABBREVIATION, Dictionaries.volumeForFile("enuabbr.dic"))
		assertEquals(Eci.DICT_MAIN, Dictionaries.volumeForFile("ESPMAIN.DIC"))
		assertEquals(Eci.DICT_ABBREVIATION, Dictionaries.volumeForFile("esmabbrev.dic"))
		assertNull(Dictionaries.volumeForFile("mydict.dic"))
		assertNull(Dictionaries.volumeForFile("main.dic"))
	}

	@Test
	fun sharedNamesSayTheirLanguages() {
		assertEquals("American English", Dictionaries.languageForFile("enumain.dic"))
		assertEquals("British English", Dictionaries.languageForFile("engroot.dic"))
		assertEquals("Castilian Spanish", Dictionaries.languageForFile("espabbr.dic"))
		assertEquals("French", Dictionaries.languageForFile("fraMain.dic"))
		assertNull(Dictionaries.languageForFile("mydict.dic"))
	}

	@Test
	fun binaryFilesAreKnown() {
		assertTrue(Dictionaries.isBinary(writeBytes(byteArrayOf(0x41, 0x09, 0x42, 0x00, 0x43))))
		assertFalse(Dictionaries.isBinary(write("DOJ\tdee oe jeigh\n")))
		assertFalse(Dictionaries.isBinary(File(dir(), "not-there.dic")))
	}

	@Test
	fun unusableEntriesAreKnown() {
		assertTrue(Dictionaries.isUsable("DOJ", "dee oe jeigh"))
		assertFalse(Dictionaries.isUsable("", "dee oe jeigh"))
		assertFalse(Dictionaries.isUsable("DOJ", ""))
		assertFalse(Dictionaries.isUsable("DOJ", "say\tnothing"))
		assertFalse(Dictionaries.isUsable("DOJ", "say\rnothing"))
		assertFalse(Dictionaries.isUsable("D".repeat(129), "dee"))
		assertFalse(Dictionaries.isUsable("DOJ", "d".repeat(513)))
	}

	@Test
	fun parsedEntriesComeBackByLowercaseKey() {
		val file = write("DOJ\tdee oe jeigh\nLOL\tell oe ell\nno tab here\n")
		val entries = Dictionaries.parse(file)
		assertEquals(2, entries.size)
		assertEquals("DOJ", entries["doj"]?.first)
		assertEquals("dee oe jeigh", entries["doj"]?.second)
		assertEquals("ell oe ell", entries["lol"]?.second)
	}

	@Test
	fun laterDuplicatesWin() {
		val file = write("DOJ\tfirst\nDOJ\tsecond\n")
		assertEquals("second", Dictionaries.parse(file)["doj"]?.second)
	}

	@Test
	fun garbageLinesCountForNothing() {
		val file = write("no tab here\nDOJ\t\n\tdee oe jeigh\n")
		assertEquals(0, Dictionaries.count(file))
		assertTrue(Dictionaries.parse(file).isEmpty())
	}

	@Test
	fun forgettingPutsTheEngineBack() {		val e = open()
		try {
			e.teachWord(Eci.DICT_MAIN, "DOJ", "dee oe jeigh")
			assertNotNull(e.lookUpWord(Eci.DICT_MAIN, "DOJ"))
			e.forgetDictionaries()
			assertNull("the set was not put aside", e.lookUpWord(Eci.DICT_MAIN, "DOJ"))
		} finally {
			e.close()
		}
	}

	private fun open(): EvvEngine {
		val e = EvvEngine.open(0x00010000)
		assertNotNull("no engine", e)
		e!!.setSampleRate(11025)
		e.applyVoice(0)
		return e
	}

	private fun write(text: String): File {
		val to = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File(to.cacheDir, "test.dic")
		file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))
		return file
	}

	private fun dir(): File {
		val to = InstrumentationRegistry.getInstrumentation().targetContext
		return File(to.cacheDir, "dicttests").apply { mkdirs() }
	}

	private var fileNumber = 0

	private fun writeBytes(bytes: ByteArray): File {
		val file = File(dir(), "bytes-${fileNumber++}.dic")
		file.writeBytes(bytes)
		return file
	}

	private fun samples(e: EvvEngine, text: String): Int {
		if (!e.speak(text)) return -1
		var total = 0
		val buffer = ByteArray(4096)
		while (true) {
			val n = e.read(buffer)
			if (n <= 0) break
			total += n
		}
		return total
	}

	private companion object {
		const val TAG = "evvdict"
		const val MANY = 3000

		/** The shape the files in the wild have: a key, a tab, and either a
		 *  respelling or a pronunciation in the engine's own alphabet. The bytes
		 *  are Latin-1 and the lines end CRLF, both of which are what real ones
		 *  do. */
		val SAMPLE = listOf(
			"DOJ\tdee oe jeigh",
			"DOS\t`[das]",
			"ACLU\tay cea ell yue",
			"café\t`[kaf1e]",
			"SNES\tess en ee ess",
			"evvdroid\tee vee vee droid"
		).joinToString("\r\n", postfix = "\r\n")
	}
}
