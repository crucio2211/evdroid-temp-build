package org.evvdroid

import java.text.Normalizer

/**
 * Android text turned into the Windows Western bytes the engine reads.
 *
 * The engine takes single bytes in the language's own code set, which for
 * every language IBM shipped is the Windows Western set. UTF-16 is not an
 * option here: the engine allows the wide code set only for Chinese, Japanese
 * and Korean, and asking for it in English gets the text read as bytes and
 * stopped at the first high byte, which is one character of speech.
 *
 * So anything outside that set has to become something inside it before the
 * engine sees it. A screen reader is handed curly quotes, dashes, emoji and
 * names in scripts Eloquence never knew, and dropping them silently is worse
 * than saying the nearest thing.
 *
 * The two languages that read something else are [EngineText]'s business, and
 * [flatten] is the half of this they still want.
 */
object WesternText {

	/** The typographic characters that turn up in ordinary text and have a
	 *  plain equivalent worth saying. */
	private val PLAIN = mapOf(
		'‘' to "'", '’' to "'", '‚' to "'", '‛' to "'",
		'“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"",
		'‐' to "-", '‑' to "-", '‒' to "-", '–' to "-",
		'—' to "-", '―' to "-", '−' to "-",
		'…' to "...", '•' to ".", '·' to ".",
		' ' to " ", ' ' to " ", ' ' to " ", '​' to "",
		'‌' to "", '‍' to "", '﻿' to "",
		'‹' to "<", '›' to ">", '«' to "\"", '»' to "\"",
		'′' to "'", '″' to "\"", '⁄' to "/", '∕' to "/",
		'×' to "x", '™' to " trademark ", '№' to " number ",
		'€' to " euro ", '£' to " pounds ", '¥' to " yen ",
		'≤' to " less than or equal to ", '≥' to " greater than or equal to ",
		'≠' to " not equal to ", '≈' to " about ", '∞' to " infinity ",
		'→' to " to ", '←' to " from ", '°' to " degrees "
	)

	private const val HIGHEST = 0xFF

	/**
	 * The bytes to hand the engine, without a terminator: the native side adds
	 * one, and a nought anywhere inside would end the text early, so there is
	 * none in what comes back.
	 */
	fun encode(text: String): ByteArray {
		val flat = flatten(text) { false }
		val out = ByteArray(flat.length)
		for (n in flat.indices) {
			val c = flat[n].code
			out[n] = WINDOWS_1252_BYTES[flat[n]]?.toByte()
				?: if (c in 1..HIGHEST) c.toByte() else ' '.code.toByte()
		}
		return out
	}

	/**
	 * [text] with everything outside the byte set turned into something inside
	 * it, and nothing written down as bytes yet.
	 *
	 * [own] says which characters above the set the language reads itself, so
	 * that they are left standing rather than flattened. For the languages IBM
	 * shipped that is none, and then what comes back is a character a byte.
	 */
	fun flatten(text: String, own: (Char) -> Boolean): String {
		val flat = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			val plain = PLAIN[c]
			when {
				own(c) -> flat.append(c)
				plain != null -> flat.append(plain)
				WINDOWS_1252_BYTES.containsKey(c) -> flat.append(c)
				c.code == 0 -> flat.append(' ')
				c.code <= HIGHEST -> flat.append(c)
				else -> flat.append(fallback(text, i))
			}
			i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
		}
		return flat.toString()
	}

	/**
	 * The bytes to hand the engine for Japanese: Shift-JIS, which is what
	 * its romanizer reads, with the same plain equivalents for typographic
	 * characters and a space where a nought would end the text early.
	 * Anything Shift-JIS has no room for comes out as a question mark,
	 * which is the encoder's own replacement and reads as punctuation
	 * rather than silence.
	 */
	fun encodeJapanese(text: String): ByteArray {
		val flat = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			val plain = PLAIN[c]
			when {
				plain != null -> flat.append(plain)
				c.code == 0 -> flat.append(' ')
				Character.isHighSurrogate(c) && i + 1 < text.length ->
					flat.append(c).append(text[i + 1])
				else -> flat.append(c)
			}
			i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
		}
		return flat.toString().toByteArray(SHIFT_JIS)
	}

	private val SHIFT_JIS: java.nio.charset.Charset =
		java.nio.charset.Charset.forName("Shift_JIS")

	/** Unicode characters represented in Windows' Western byte set's C1 area. */
	private val WINDOWS_1252_BYTES = mapOf(
		'€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85,
		'†' to 0x86, '‡' to 0x87, 'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A,
		'‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91, '’' to 0x92,
		'“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
		'˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C,
		'ž' to 0x9E, 'Ÿ' to 0x9F
	)

	/**
	 * What to say for a character the code set has no room for. An accent that
	 * decomposes gives up its base letter, which turns a name like Dvořák into
	 * one the engine can read; everything else becomes a space rather than a
	 * noise.
	 */
	private fun fallback(text: String, at: Int): String {
		val c = text[at]
		val one = if (Character.isHighSurrogate(c) && at + 1 < text.length) {
			text.substring(at, at + 2)
		} else {
			c.toString()
		}
		val stripped = Normalizer.normalize(one, Normalizer.Form.NFD)
			.filter { it.code <= HIGHEST && !isCombining(it) }
		return if (stripped.isEmpty()) " " else stripped
	}

	private fun isCombining(c: Char): Boolean = when (Character.getType(c).toByte()) {
		Character.NON_SPACING_MARK,
		Character.COMBINING_SPACING_MARK,
		Character.ENCLOSING_MARK -> true
		else -> false
	}
}
