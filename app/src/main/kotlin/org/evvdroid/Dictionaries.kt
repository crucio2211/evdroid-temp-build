package org.evvdroid

import android.util.Log
import java.io.File

/**
 * The pronunciation dictionaries people write for this engine.
 *
 * Two forms come in. A file in the engine's own saved binary form -- what
 * enumain.dic, enuroot.dic and enuabbr.dic are on Windows -- is handed to the
 * engine's loader, which reads it exactly as it does there ([loadBinary]).
 * What people share as text is read here and the engine is taught a word at a
 * time, which is the same interface the loader would have used and does not
 * depend on agreeing about a file format.
 *
 * The engine matches a key against the word as spoken, byte for byte and
 * case included: a key "fatima" does not touch "Fatima". So every key is kept
 * exactly as written, two entries that differ only in case are two entries,
 * and nothing here folds case on the way in.
 *
 * A line is a key, a tab, and what to say instead. What to say is either a
 * respelling ("dee oe jeigh") or a pronunciation in the engine's own alphabet
 * ("`[Ekspoz1e]"), and the engine takes both. The bytes are the engine's own
 * code set already -- an e-acute is one byte -- so the file is read as Latin-1
 * rather than as UTF-8, which is what the files in the wild actually are.
 *
 * That is the Western byte set, so a file for Polish or Japanese is not read
 * right here. Neither has a dictionary anyone shares, and guessing at a form
 * for one would be inventing a format rather than reading one.
 */
object Dictionaries {

	/** Whether [file] looks like the engine's saved binary form rather than
	 *  text. Its bytes are never taught as words, which can fault the engine
	 *  into silence: a file with nought bytes in its head goes to the
	 *  engine's own loader instead, through [loadBinary]. */
	fun isBinary(file: File): Boolean = try {
		file.inputStream().use { source ->
			val head = ByteArray(8192)
			val n = source.read(head)
			(0 until n).any { head[it] == 0.toByte() }
		}
	} catch (unreadable: Exception) {
		false
	}

	/** Whether [key] to [say] is safe to teach: both present, modestly
	 *  sized, and free of control characters that come out of binary reads
	 *  and malformed lines. Anything else is skipped and counted refused
	 *  rather than handed to the engine.
	 *
	 *  Only the C0 controls and DEL count. The file is read as Latin-1 and the
	 *  engine's code set is the Windows Western one, where 0x80 to 0x9F are
	 *  letters and marks (a curly apostrophe, a quote, oe, s caron) rather than
	 *  controls, so an entry written with one is a good entry. */
	fun isUsable(key: String, say: String): Boolean {
		if (key.isEmpty() || say.isEmpty()) return false
		if (key.length > MAX_KEY || say.length > MAX_SAY) return false
		return !(key + say).any { it.code < 0x20 || it.code == 0x7F }
	}

	/** Hands a file in the engine's own saved form to the engine's loader, for
	 *  [volume]. True when it took the whole file. The engine refuses while it
	 *  is speaking, so this is for a moment when it is not. */
	fun loadBinary(engine: EvvEngine, volume: Int, file: File): Boolean {
		val rc = engine.loadDictionary(volume, file.absolutePath)
		if (rc != 0) Log.e(TAG, "${file.name}: the engine's loader answered $rc for volume $volume")
		return rc == 0
	}

	/** The volume a shared file names, if its name says: enumain goes to
	 *  main, enuroot to root, enuabbr to abbreviations, and so for every
	 *  language prefix IBM used. Answers null when the name says nothing,
	 *  and then the row it was picked from decides. */
	fun volumeForFile(name: String): Int? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		return when (match.groupValues[2].lowercase()) {
			"main" -> Eci.DICT_MAIN
			"root" -> Eci.DICT_ROOT
			"abbr", "abbrev", "abbreviation" -> Eci.DICT_ABBREVIATION
			else -> null
		}
	}

	/** The language a shared file names, if its name says: enumain is
	 *  American English, engmain is British, and so on. For showing, not
	 *  for routing: every picked file is taught to whichever language
	 *  engine is speaking. */
	fun languageForFile(name: String): String? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		return LANGUAGE_NAMES[match.groupValues[1].lowercase()]
	}

	/** Teaches the engine every entry in [file]. Answers how many went in.
	 *  Only for small files and tests: a big dictionary taught this way
	 *  blocks the synthesis thread past any reasonable wait, so the service
	 *  parses once and teaches words as they are spoken instead. */
	fun load(engine: EvvEngine, volume: Int, file: File): Int {
		var taught = 0
		var refused = 0
		try {
			forEachLineSafe(file) { line ->
				val entry = splitEntry(line) ?: return@forEachLineSafe
				if (isUsable(entry.first, entry.second)) {
					if (engine.teachWord(volume, entry.first, entry.second) == 0) taught++ else refused++
				} else {
					refused++
				}
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
			return taught
		}
		if (refused > 0) Log.e(TAG, "${file.name}: $refused of ${taught + refused} entries refused")
		return taught
	}

	/** Every usable entry in [file] by its key exactly as written, with what to
	 *  say instead. Case is part of the key, because it is part of the match in
	 *  the engine: "US" and "us" are two entries. Reading is fast -- tens of
	 *  milliseconds even for tens of thousands of lines -- because nothing
	 *  here touches the engine. A later duplicate of the very same key wins,
	 *  the way sequential teaching would leave it. */
	fun parse(file: File): Map<String, Pair<String, String>> {
		val out = LinkedHashMap<String, Pair<String, String>>()
		try {
			forEachLineSafe(file) { line ->
				val entry = splitEntry(line) ?: return@forEachLineSafe
				if (isUsable(entry.first, entry.second)) {
					out[entry.first] = entry
				}
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
		}
		return out
	}

	/** A line as a key and what to say, or null when it is no entry.
	 *  Accepts tab separation or whitespace, ignores comments (#, //, ;),
	 *  and ensures phoneme entries starting with [ carry the required backtick. */
	fun splitEntry(rawLine: String): Pair<String, String>? {
		var line = rawLine.trim()
		if (line.startsWith('\uFEFF')) line = line.substring(1).trim()
		if (line.isEmpty() || line.startsWith('#') || line.startsWith("//") || line.startsWith(";")) {
			return null
		}
		val tab = line.indexOf('\t')
		val (rawKey, rawSay) = if (tab > 0) {
			line.substring(0, tab).trim() to line.substring(tab + 1).trim()
		} else {
			val ws = line.indexOfFirst { it.isWhitespace() }
			if (ws <= 0) return null
			line.substring(0, ws).trim() to line.substring(ws + 1).trim()
		}
		if (rawKey.isEmpty() || rawSay.isEmpty()) return null
		val say = if (rawSay.startsWith("[") && rawSay.endsWith("]") && !rawSay.startsWith("`[")) {
			"`$rawSay"
		} else {
			rawSay
		}
		return rawKey to say
	}

	private fun forEachLineSafe(file: File, action: (String) -> Unit) {
		val bytes = file.readBytes()
		val charset = if (isUtf8(bytes)) Charsets.UTF_8 else Charsets.ISO_8859_1
		var text = String(bytes, charset)
		if (text.startsWith('\uFEFF')) {
			text = text.substring(1)
		}
		text.lineSequence().forEach(action)
	}

	private fun isUtf8(bytes: ByteArray): Boolean {
		if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
			return true
		}
		return try {
			val decoder = Charsets.UTF_8.newDecoder()
				.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
			decoder.decode(java.nio.ByteBuffer.wrap(bytes))
			true
		} catch (_: Exception) {
			false
		}
	}

	/** How many usable entries a file offers, for saying so on the settings
	 *  screen without an engine to hand. */
	fun count(file: File): Int = try {
		var n = 0
		forEachLineSafe(file) { line ->
			val entry = splitEntry(line) ?: return@forEachLineSafe
			if (isUsable(entry.first, entry.second)) n++
		}
		n
	} catch (unreadable: Exception) {
		0
	}

	private const val TAG = "evvdroid"

	private const val MAX_KEY = 128
	private const val MAX_SAY = 512

	/** A shared name like enumain.dic or esmabbr.dic: a three-letter IBM
	 *  language tag, then which volume it is. */
	private val FILE_VOLUME = Regex("""^([a-z]{3})(main|root|abbr|abbrev|abbreviation)\b""", RegexOption.IGNORE_CASE)

	/** The engine language a shared file names, if its tag is one this app
	 *  both speaks and links: enumain is American English and so on. Answers
	 *  null when the name says nothing usable, and then the importer asks. */
	fun engineForFile(name: String, available: IntArray): Int? {
		val match = FILE_VOLUME.find(name.trim()) ?: return null
		val engine = TAG_ENGINE[match.groupValues[1].lowercase()] ?: return null
		return engine.takeIf { it in available }
	}

	private val TAG_ENGINE = mapOf(
		"enu" to 0x00010000, "eng" to 0x00010001,
		"esp" to 0x00020000, "esm" to 0x00020001,
		"fra" to 0x00030000, "frc" to 0x00030001,
		"deu" to 0x00040000, "ita" to 0x00050000,
		"ptb" to 0x00070000, "fin" to 0x00090000,
		"jpn" to 0x00080000, "pol" to 0x00110000, "plp" to 0x00110000
	)

	private val LANGUAGE_NAMES = mapOf(
		"enu" to "American English",
		"eng" to "British English",
		"esp" to "Castilian Spanish",
		"esm" to "Latin American Spanish",
		"fra" to "French",
		"frc" to "Canadian French",
		"deu" to "German",
		"ita" to "Italian",
		"ptb" to "Brazilian Portuguese",
		"chs" to "Chinese",
		"cht" to "Chinese Traditional",
		"ctt" to "Cantonese",
		"jpn" to "Japanese",
		"kor" to "Korean",
		"fin" to "Finnish",
		"dan" to "Danish",
		"nor" to "Norwegian",
		"swe" to "Swedish",
		"nld" to "Dutch",
		"tha" to "Thai",
		"plp" to "Polish",
		"pol" to "Polish"
	)
}
