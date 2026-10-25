package org.evvdroid

/**
 * Emoji spoken by name.
 *
 * The engine has no names for these: left alone most come out as silence
 * and some as spelling. So every emoji becomes its plain-English CLDR name
 * -- grinning face, red heart, flag Philippines -- which is ordinary words
 * for everything below. Modifiers standing alone (a stray variation
 * selector, joiner, skin tone, tag or keycap mark) are dropped rather than
 * spoken, since they say nothing by themselves.
 *
 * Matching is longest-first, so a family wins over its first person, a
 * keycap over its digit and heart on fire over a bare heart.
 *
 * A run of the same name compresses once it reaches [compressAfter]:
 * three grinning faces come out as 3 grinning face instead of three times
 * over. Same name, not same bytes: a heart with and without its selector
 * is still one heart twice.
 *
 * This runs first of all, before numbers: the names are words, which is
 * what every pass below wants to see.
 */
object EmojiProcessing {

	const val MIN_COMPRESS = 2
	const val MAX_COMPRESS = 5
	const val DEFAULT_COMPRESS = 3

	fun apply(text: String, compressAfter: Int = DEFAULT_COMPRESS): String {
		if (text.isEmpty() || !mayContain(text)) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val hit = EmojiNames.matchAt(text, i)
			if (hit == null) {
				val modifier = EmojiNames.modifierLengthAt(text, i)
				if (modifier > 0) {
					i += modifier
					continue
				}
				out.append(text[i])
				i += 1
				continue
			}
			val (name, _) = hit
			var count = 1
			var j = hit.second
			while (true) {
				val next = EmojiNames.matchAt(text, j) ?: break
				if (next.first != name) break
				count++
				j = next.second
			}
			if (count >= compressAfter.coerceIn(MIN_COMPRESS, MAX_COMPRESS)) {
				pad(out)
				out.append(count).append(' ').append(name)
				if (j >= text.length || !text[j].isWhitespace()) out.append(' ')
				i = j
				continue
			}
			var k = i
			repeat(count) {
				val one = EmojiNames.matchAt(text, k)!!
				pad(out)
				out.append(one.first)
				k = one.second
				if (k >= text.length || !text[k].isWhitespace()) out.append(' ')
			}
			i = j
		}
		return out.toString().trim()
	}

	private fun pad(out: StringBuilder) {
		if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
	}

	/** Whether [text] holds anything this pass could change: an entry start
	 *  or a lone modifier, so plain text skips the scan without a lookup
	 *  per character. */
	private fun mayContain(text: String): Boolean {
		for (i in text.indices) {
			if (text[i] in EmojiNames.STARTERS || EmojiNames.modifierLengthAt(text, i) > 0) return true
		}
		return false
	}
}
