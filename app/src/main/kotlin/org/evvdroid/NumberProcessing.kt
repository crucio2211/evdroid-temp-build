package org.evvdroid

/**
 * Numbers read the way the SAPI5 driver reads them.
 *
 * Pure digit runs, wherever they stand: digits always come apart, pairs
 * and triplets split remainder-first (12345 in pairs is 1 23 45), and
 * quadruplets pass up to four digits unsplit so the synthesiser reads
 * naturally, falling back past that (22222 is 2 2 2 2 2).
 *
 * Thousands commas never reach grouping: [stripThousandsCommas] drops
 * them first, always, even with number processing off, so 1,000 reads one
 * thousand instead of one comma hundred. Decimal dots pass through; the
 * digit runs around them regroup on their own.
 */
object NumberProcessing {

	const val DIGITS = 0
	const val PAIRS = 1
	const val TRIPLETS = 2
	const val QUADRUPLETS = 3

	/**
	 * Always-on pre-pass: a comma with a digit before it, three digits
	 * after it and no fourth digit behind those goes, so 1,000 reads whole
	 * and 1,000,000 too. Everything else, decimal dots included, passes
	 * through intact.
	 */
	fun stripThousandsCommas(text: String): String {
		if (text.isEmpty()) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			if (c == ',' && i > 0 && i + 3 < text.length &&
				text[i - 1] in '0'..'9' &&
				text[i + 1] in '0'..'9' && text[i + 2] in '0'..'9' && text[i + 3] in '0'..'9' &&
				(i + 4 >= text.length || text[i + 4] !in '0'..'9')
			) {
				i++
				continue
			}
			out.append(c)
			i++
		}
		return out.toString()
	}

	/**
	 * [text] regrouped for [mode].
	 */
	fun apply(text: String, mode: Int): String {
		if (text.isEmpty()) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val c = text[i]
			if (c !in '0'..'9') {
				out.append(c)
				i++
				continue
			}
			var j = i
			while (j < text.length && text[j] in '0'..'9') j++
			val digits = text.substring(i, j)
			val length = digits.length
			when (mode) {
				QUADRUPLETS -> if (length <= 4) {
					out.append(digits)
				} else {
					spell(out, digits)
				}
				DIGITS -> spell(out, digits)
				else -> {
					val size = mode + 1
					if (length <= size) {
						out.append(digits)
					} else {
						val rem = length % size
						var k = 0
						if (rem > 0) {
							out.append(digits.substring(0, rem))
							k = rem
						}
						while (k < length) {
							if (k > 0) out.append(' ')
							out.append(digits.substring(k, minOf(k + size, length)))
							k += size
						}
					}
				}
			}
			i = j
		}
		return out.toString()
	}

	private fun spell(out: StringBuilder, digits: String) {
		for ((at, digit) in digits.withIndex()) {
			if (at > 0) out.append(' ')
			out.append(digit)
		}
	}
}
