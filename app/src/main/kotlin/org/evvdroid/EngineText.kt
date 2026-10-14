package org.evvdroid

/**
 * Android text turned into the bytes the language in force reads.
 *
 * Eight of the ten modules read the Windows Western byte set and [WesternText]
 * is the whole of what they want. Two do not.
 *
 * Polish has eight letters that set has no room for, so the module declares
 * them and the engine converts its text from UTF-8 itself. Nothing else about
 * the text changes: a character the module does not declare still goes through
 * the Western list, so everything but those eight is flattened exactly as it is
 * for every other language.
 *
 * Japanese has a romanizer in front of the engine and that reads Shift-JIS.
 * [WesternText.encodeJapanese] is what it gets, question marks and all: a
 * character Shift-JIS has no room for reads as punctuation rather than
 * silence, which the engine tests pin down.
 */
object EngineText {

	/**
	 * The bytes to hand the engine speaking [language], without a terminator:
	 * the native side adds one, and a nought anywhere inside would end the text
	 * early, so there is none in what comes back.
	 */
	fun encode(language: Int, text: String): ByteArray = when (Eci.baseLanguage(language)) {
		Eci.JAPANESE -> WesternText.encodeJapanese(text)
		Eci.POLISH -> WesternText.flatten(text) { it in POLISH_LETTERS }
			.toByteArray(Charsets.UTF_8)
		else -> WesternText.encode(text)
	}

	/** The eight letters Polish's own alphabet has and the Western set does
	 *  not, in both cases. Its o with an acute is in the Western set already
	 *  and so is not here. */
	private val POLISH_LETTERS = "ąćęłńśźżĄĆĘŁŃŚŹŻ"
}
