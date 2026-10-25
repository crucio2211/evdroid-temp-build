package org.evvdroid

import kotlin.math.tanh

/**
 * The output stage: everything between the engine's samples and the speaker.
 *
 * Code Factory's Eloquence carries no equaliser and no compressor in its app.
 * What it does carry is gain staging in the engine itself -- a volume
 * multiplier with headroom behind it (klattSetVolumeMultiplier and friends)
 * -- so turning it up stays loud instead of turning harsh. This is the same
 * shape: a master volume, a soft limiter that rounds peaks off instead of
 * lopping them, and a DC blocker ahead of both so an offset cannot waste the
 * headroom the limiter is keeping. The old static rate-tied lift is gone;
 * loudness is a setting now rather than a guess.
 *
 * One [Chain] serves one utterance. Its DC blocker runs from chunk to chunk
 * inside that utterance and must not be shared across them.
 */
object AudioOutput {

	const val VOLUME_MIN = 0
	const val VOLUME_MAX = 200
	const val DEFAULT_VOLUME = 130

	/** What the settings screen chose, read back for one utterance. [boost]
	 *  multiplies on top of [volume] past the setting's own maximum: an
	 *  exclamation shouts with the same rows, and the limiter below keeps
	 *  the extra headroom rounded instead of clipped. One per utterance,
	 *  never stored. */
	data class Snapshot(
		val volume: Int = DEFAULT_VOLUME,
		val limiter: Boolean = true,
		val boost: Float = 1f
	)

	class Chain(s: Snapshot) {

		private val volume = s.volume.coerceIn(VOLUME_MIN, VOLUME_MAX) / 100f * s.boost.coerceAtLeast(0f)
		private val limiter = s.limiter

		private var dcX = 0f
		private var dcY = 0f

		/** Runs the 16-bit little-endian samples in [buffer] up to [n] bytes
		 *  through the stage, in place. A stray odd tail byte is left alone. */
		fun process(buffer: ByteArray, n: Int) {
			var i = 0
			val end = n - (n % 2)
			while (i < end) {
				val lo = buffer[i].toInt() and 0xFF
				val hi = buffer[i + 1].toInt()
				var y = ((hi shl 8) or lo) / 32768f
				// DC blocker first: an offset pushed loud wastes the headroom
				// the limiter is keeping.
				val clean = y - dcX + 0.995f * dcY
				dcX = y
				dcY = clean
				y = clean * volume
				val v = if (limiter) softClip(y) else hardClip(y)
				buffer[i] = (v and 0xFF).toByte()
				buffer[i + 1] = ((v shr 8) and 0xFF).toByte()
				i += 2
			}
		}

		/** Linear to the knee at 0.85 of full scale, then a tanh shoulder:
		 *  peaks round off instead of flattening, so boosted speech stays
		 *  loud rather than turning harsh. */
		private fun softClip(y: Float): Int {
			val negative = y < 0f
			val a = if (negative) -y else y
			val shaped = if (a <= KNEE) a
				else KNEE + (1f - KNEE) * tanh(((a - KNEE) / (1f - KNEE)).toDouble()).toFloat()
			val v = (shaped * 32767f).toInt().coerceIn(-32768, 32767)
			return if (negative) -v else v
		}

		private fun hardClip(y: Float): Int =
			(y * 32767f).toInt().coerceIn(-32768, 32767)

		companion object {
			const val KNEE = 0.85f
		}
	}
}
