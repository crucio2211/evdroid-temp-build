package org.evvdroid

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the settings screen is showing, and the engine it can hear itself on.
 *
 * The screen edits engine numbers and shows percentages, and this is where the
 * two meet. Everything written here goes straight into [Settings], which is
 * what the speech service reads, so a change is in force before the sample
 * that demonstrates it has finished.
 */
class SettingsModel(context: Context) {

	private val app = context.applicationContext
	private val settings = Settings(app)

	private var preview: EvvPreview? = null
	private var previewLang: Int? = null

	var voice by mutableStateOf(settings.voice)
		private set

	var forceLanguage by mutableStateOf(settings.forceLanguage)
		private set

	var forcedLanguage by mutableStateOf(settings.forcedLanguage)
		private set

	/** The languages the linked engine speaks, in its own order. Empty when
	 *  the native library is missing, in which case the screen hides the
	 *  language choice rather than offering nothing. */
	val languageOptions: List<Int> get() = EvvEngine.available.toList()

	var abbreviations by mutableStateOf(settings.abbreviations)
		private set

	var sampleRateHz by mutableStateOf(settings.sampleRateHz)
		private set

	var audioVolume by mutableStateOf(settings.audioVolume)
		private set

	var audioLimiter by mutableStateOf(settings.audioLimiter)
		private set

	var forceRate by mutableStateOf(settings.forceRate)
		private set

	var forcePitch by mutableStateOf(settings.forcePitch)
		private set

	var pauses by mutableStateOf(settings.pauses)
		private set

	var phrasePrediction by mutableStateOf(settings.phrasePrediction)
		private set

	var speakEmoji by mutableStateOf(settings.speakEmoji)
		private set

	var emojiCompress by mutableStateOf(settings.emojiCompress)
		private set

	var numberProcessing by mutableStateOf(settings.numberProcessing)
		private set

	var numberMode by mutableStateOf(settings.numberMode)
		private set

	var splitCamelCase by mutableStateOf(settings.splitCamelCase)
		private set

	var punctProcessing by mutableStateOf(settings.punctProcessing)
		private set

	var punctLevel by mutableStateOf(settings.punctLevel)
		private set

	var backquoteTags by mutableStateOf(settings.backquoteTags)
		private set

	var regexDictionaryEnabled by mutableStateOf(settings.regexDictionaryEnabled)
		private set

	private val shape = mutableStateMapOf<Int, Int>().apply { putAll(settings.shape(settings.voice)) }

	private var dictionaries: Map<Int, String> by mutableStateOf(
		Eci.DICT_VOLUMES.mapNotNull { volume ->
			settings.dictionaryName(volume)?.let { volume to it }
		}.toMap()
	)

	private var useDictionaries: Map<Int, Boolean> by mutableStateOf(
		Eci.DICT_VOLUMES.associateWith { settings.useDictionary(it) }
	)

	/** Whether the file picked for [volume] is in use, which needs a file to
	 *  mean anything: a volume with nothing picked reads as off. */
	fun useDictionary(volume: Int): Boolean =
		useDictionaries[volume] == true && dictionaries.contains(volume)

	fun chooseUseDictionary(volume: Int, on: Boolean) {
		settings.setUseDictionary(volume, on)
		useDictionaries = useDictionaries + (volume to on)
	}

	init {
		ensurePreview(EvvEngine.available.firstOrNull())
		preview?.open {
			// A voice's own settings are what the rows start at, and only
			// the engine knows them.
			if (!settings.shapeIsCustom(settings.voice)) adoptVoice()
			shape.putAll(settings.shape(settings.voice))
		}
	}

	/** The preview engine speaking [language], opening a new one when the
	 *  language changed under it. A null language leaves whatever is there. */
	private fun ensurePreview(language: Int?) {
		if (language == null || (language == previewLang && preview != null)) return
		preview?.close()
		previewLang = language
		preview = EvvPreview(language).apply { open() }
	}

	fun stopSpeaking() {
		preview?.stopSpeaking()
	}

	fun close() {
		preview?.close()
	}

	fun percentOf(param: Int): Int = Eci.toPercent(param, shape[param] ?: 0)

	// ---- what the screen changes -----------------------------------------

	fun chooseVoice(which: Int) {
		voice = which
		settings.voice = which
		// A voice that has been changed keeps its changes. One that has not
		// takes what the engine says it is.
		if (!settings.shapeIsCustom(which)) adoptVoice()
		shape.clear()
		shape.putAll(settings.shape(which))
	}

	fun chooseForceLanguage(on: Boolean) {
		forceLanguage = on
		settings.forceLanguage = on
	}

	fun chooseForcedLanguage(id: Int) {
		forcedLanguage = id
		settings.forcedLanguage = id
	}

	fun setPercent(param: Int, percent: Int) = setShape(param, Eci.fromPercent(param, percent))

	fun setShape(param: Int, value: Int) {
		val settled = Eci.clampVoice(param, value)
		if (shape[param] == settled) return
		shape[param] = settled
		settings.setShapeValue(voice, param, settled)
	}

	/** Forgets this voice's changes. Every other voice is left alone, and so is
	 *  the speed, which belongs to the listener rather than to any of them. */
	fun resetVoice() {
		settings.clearShape(voice)
		adoptVoice()
		shape.clear()
		shape.putAll(settings.shape(voice))
	}

	fun chooseAbbreviations(on: Boolean) {
		abbreviations = on
		settings.abbreviations = on
	}

	/** What the row for [volume] says: the file picked for it, or nothing. */
	fun dictionaryName(volume: Int): String {
		val said = dictionaries[volume] ?: return app.getString(R.string.dictionary_none)
		val lang = settings.dictionaryLanguageResolved(volume, EvvEngine.available)
		return "$said (${Eci.displayName(lang)})"
	}

	/** What the regex dictionary row says: the file picked for it, or nothing. */
	var regexDictionary: String by mutableStateOf(
		settings.regexDictionaryName() ?: app.getString(R.string.dictionary_none)
	)
		private set

	/** The regex dictionary's entries as written, for the editor screen. */
	var regexEntries: List<RegexDictionaries.RawEntry> by mutableStateOf(
		settings.regexDictionaryFile()?.let { RegexDictionaries.readEntries(it) } ?: emptyList()
	)
		private set

	/** The regex dictionary file itself, for exporting a copy. */
	fun regexFile(): java.io.File? = settings.regexDictionaryFile()

	fun refreshRegexEntries() {
		regexEntries = settings.regexDictionaryFile()?.let { RegexDictionaries.readEntries(it) } ?: emptyList()
		regexDictionary = settings.regexDictionaryName() ?: app.getString(R.string.dictionary_none)
	}

	/**
	 * Takes a copy of the file the picker handed back.
	 *
	 * A picked document is a content URI belonging to whichever app supplied
	 * it, readable now and quite possibly not tomorrow, and the speech service
	 * is a different process that reads these when it starts. So what is stored
	 * is a copy of our own rather than a reference to somebody else's.
	 */
	fun chooseDictionary(volume: Int, uri: android.net.Uri) {
		val into = java.io.File(app.filesDir, "dictionaries").apply { mkdirs() }
		val pending = java.io.File(into, "pending.dic")
		val name = nameOf(uri) ?: pending.name
		try {
			app.contentResolver.openInputStream(uri).use { source ->
				if (source == null) return
				pending.outputStream().use { sink -> source.copyTo(sink) }
			}
		} catch (unreadable: Exception) {
			android.util.Log.e("evvdroid", "cannot read the dictionary picked", unreadable)
			pending.delete()
			return
		}
		// A shared name says which volume it is: an enuabbr picked on the
		// main row still goes to abbreviations. A binary file is refused
		// outright: teaching its bytes as words can silence the engine.
		if (Dictionaries.isBinary(pending)) {
			android.util.Log.e("evvdroid", "refusing binary dictionary $name")
			pending.delete()
			return
		}
		val routed = Dictionaries.volumeForFile(name) ?: volume
		val entries = Dictionaries.count(pending)
		if (entries == 0) {
			pending.delete()
			dictionaries = dictionaries - routed
			settings.setDictionary(routed, null, null)
			return
		}
		// The language is asked, not assumed: the guess from the filename
		// starts selected, and the choice is stored with the file.
		pendingDictSlot = routed
		pendingDictName = name
		pendingDictLang = Dictionaries.engineForFile(name, EvvEngine.available)
			?: EvvEngine.available.firstOrNull() ?: 0x00010000
	}

	/** A file waiting for its language: the slot it will fill, the name
	 *  picked, and the choice in force. Null slot while nothing waits. */
	var pendingDictSlot: Int? by mutableStateOf(null)
		private set
	var pendingDictName: String by mutableStateOf("")
		private set
	var pendingDictLang: Int by mutableStateOf(0x00010000)

	/** The engines a picked file can go to, in their own order. */
	val dictLanguages: List<Int> get() = EvvEngine.available.toList()

	fun choosePendingDictionaryLanguage(language: Int) {
		val slot = pendingDictSlot ?: return
		pendingDictLang = language
		val into = java.io.File(app.filesDir, "dictionaries")
		val pending = java.io.File(into, "pending.dic")
		if (!pending.exists()) {
			pendingDictSlot = null
			return
		}
		val entries = Dictionaries.count(pending)
		val file = java.io.File(into, "volume-$slot.dic")
		pending.copyTo(file, overwrite = true)
		pending.delete()
		val said = app.getString(R.string.dictionary_entries, pendingDictName, entries)
		settings.setDictionary(slot, file.absolutePath, said)
		settings.setDictionaryLanguage(slot, language)
		dictionaries = dictionaries + (slot to said)
		chooseUseDictionary(slot, true)
		pendingDictSlot = null
	}

	fun dismissPendingDictionary() {
		java.io.File(java.io.File(app.filesDir, "dictionaries"), "pending.dic").delete()
		pendingDictSlot = null
	}

	fun removeDictionaries() {
		for (volume in Eci.DICT_VOLUMES) {
			settings.dictionaryPath(volume)?.let { java.io.File(it).delete() }
			settings.setDictionary(volume, null, null)
		}
		dictionaries = emptyMap()
		settings.regexDictionaryPath()?.let { java.io.File(it).delete() }
		settings.setRegexDictionary(null, null)
		chooseRegexDictionaryEnabled(false)
		regexDictionary = app.getString(R.string.dictionary_none)
		regexEntries = emptyList()
	}

	/**
	 * Takes a copy of the regex dictionary the picker handed back.
	 *
	 * Same copy-of-our-own arrangement as the volume dictionaries. A file
	 * with no usable patterns in it is refused rather than kept: keeping it
	 * would show a name on the screen for a dictionary that says nothing.
	 * Picking a good file turns the dictionary on.
	 */
	fun chooseRegexDictionary(uri: android.net.Uri) {
		val into = java.io.File(app.filesDir, "dictionaries").apply { mkdirs() }
		val file = java.io.File(into, "regex.dic")
		val name = nameOf(uri) ?: file.name
		try {
			app.contentResolver.openInputStream(uri).use { source ->
				if (source == null) return
				file.outputStream().use { sink -> source.copyTo(sink) }
			}
		} catch (unreadable: Exception) {
			android.util.Log.e("evvdroid", "cannot read the regex dictionary picked", unreadable)
			return
		}
		val patterns = RegexDictionaries.count(file)
		if (patterns == 0) {
			file.delete()
			settings.setRegexDictionary(null, null)
			regexDictionary = app.getString(R.string.dictionary_none)
			regexEntries = emptyList()
			return
		}
		val said = app.getString(R.string.regex_dictionary_entries, name, patterns)
		settings.setRegexDictionary(file.absolutePath, said)
		regexDictionary = said
		chooseRegexDictionaryEnabled(true)
		refreshRegexEntries()
	}

	fun chooseRegexDictionaryEnabled(on: Boolean) {
		regexDictionaryEnabled = on
		settings.regexDictionaryEnabled = on
	}

	/**
	 * Adds one entry typed on the editor screen.
	 *
	 * When no file has been picked yet the entry starts one, named for the
	 * copy it is. Either way the dictionary is switched on: an entry just
	 * written is one meant to be heard. [matchCase] off stores the pattern
	 * case-insensitively, so chatgpt fixes ChatGpt too.
	 */
	fun addRegexEntry(pattern: String, replacement: String, matchCase: Boolean = false): Boolean {
		var file = settings.regexDictionaryFile()
		if (file == null) {
			val into = java.io.File(app.filesDir, "dictionaries").apply { mkdirs() }
			file = java.io.File(into, "regex.dic")
		}
		if (!RegexDictionaries.appendEntry(file, RegexDictionaries.withCase(pattern, matchCase), replacement)) return false
		refreshRegexName(file)
		chooseRegexDictionaryEnabled(true)
		refreshRegexEntries()
		return true
	}

	/** Drops one entry from the editor screen. An emptied file is removed
	 *  rather than kept as a name for nothing. */
	fun removeRegexEntryAt(index: Int): Boolean {
		val file = settings.regexDictionaryFile() ?: return false
		if (!RegexDictionaries.removeEntryAt(file, index)) return false
		if (RegexDictionaries.count(file) == 0) {
			file.delete()
			settings.setRegexDictionary(null, null)
			regexDictionary = app.getString(R.string.dictionary_none)
		} else {
			refreshRegexName(file)
		}
		refreshRegexEntries()
		return true
	}

	/** Swaps one editor entry for new text, where it stands. */
	fun editRegexEntry(index: Int, pattern: String, replacement: String, matchCase: Boolean): Boolean {
		val file = settings.regexDictionaryFile() ?: return false
		if (!RegexDictionaries.replaceEntryAt(file, index, RegexDictionaries.withCase(pattern, matchCase), replacement)) {
			return false
		}
		refreshRegexName(file)
		refreshRegexEntries()
		return true
	}

	/** Empties the whole regex dictionary: file gone, name cleared,
	 *  switched off. */
	fun clearRegexEntries() {
		settings.regexDictionaryPath()?.let { java.io.File(it).delete() }
		settings.setRegexDictionary(null, null)
		chooseRegexDictionaryEnabled(false)
		regexDictionary = app.getString(R.string.dictionary_none)
		regexEntries = emptyList()
	}

	/** The display name with a fresh count, keeping whichever file it names. */
	private fun refreshRegexName(file: java.io.File) {
		val base = settings.regexDictionaryName()?.substringBefore(", ")?.takeIf { it.isNotEmpty() } ?: file.name
		val said = app.getString(R.string.regex_dictionary_entries, base, RegexDictionaries.count(file))
		settings.setRegexDictionary(file.absolutePath, said)
		regexDictionary = said
	}

	private fun nameOf(uri: android.net.Uri): String? = runCatching {
		app.contentResolver.query(uri, null, null, null, null)?.use { row ->
			val at = row.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (at >= 0 && row.moveToFirst()) row.getString(at) else null
		}
	}.getOrNull()

	fun choosePhrasePrediction(on: Boolean) {
		phrasePrediction = on
		settings.phrasePrediction = on
	}

	fun chooseSpeakEmoji(on: Boolean) {
		speakEmoji = on
		settings.speakEmoji = on
	}

	fun chooseEmojiCompress(threshold: Int) {
		emojiCompress = threshold
		settings.emojiCompress = threshold
	}

	fun chooseNumberProcessing(on: Boolean) {
		numberProcessing = on
		settings.numberProcessing = on
	}

	fun chooseNumberMode(mode: Int) {
		numberMode = mode
		settings.numberMode = mode
	}

	fun chooseSplitCamelCase(on: Boolean) {
		splitCamelCase = on
		settings.splitCamelCase = on
	}

	fun choosePunctProcessing(on: Boolean) {
		punctProcessing = on
		settings.punctProcessing = on
	}

	fun choosePunctLevel(level: Int) {
		punctLevel = level
		settings.punctLevel = level
	}

	fun chooseBackquoteTags(on: Boolean) {
		backquoteTags = on
		settings.backquoteTags = on
	}

	fun choosePauses(mode: Int) {
		pauses = mode
		settings.pauses = mode
	}

	fun chooseSampleRate(hz: Int) {
		sampleRateHz = hz
		settings.sampleRateHz = hz
	}

	fun chooseAudioVolume(volume: Int) {
		audioVolume = volume.coerceIn(AudioOutput.VOLUME_MIN, AudioOutput.VOLUME_MAX)
		settings.audioVolume = audioVolume
	}

	fun chooseAudioLimiter(on: Boolean) {
		audioLimiter = on
		settings.audioLimiter = on
	}

	fun chooseForceRate(on: Boolean) {
		forceRate = on
		settings.forceRate = on
	}

	fun chooseForcePitch(on: Boolean) {
		forcePitch = on
		settings.forcePitch = on
	}

	// ---- hearing it ------------------------------------------------------

	/** Nothing here speaks by itself. Every setting is in force the moment it
	 *  is written down, and this is how it gets heard. The sample follows a
	 *  forced language the same way utterances do, and it runs the same
	 *  rewrites TalkBack hears -- everything but the user dictionaries,
	 *  which live on the service engine. */
	fun say() {
		val forced = settings.forcedLanguage.takeIf {
			settings.forceLanguage && EvvEngine.available.contains(it)
		}
		ensurePreview(forced ?: EvvEngine.available.firstOrNull())
		val heard = TextPipeline.process(
			app.getString(R.string.preview_text),
			emoji = settings.speakEmoji,
			emojiCompress = settings.emojiCompress,
			numberMode = settings.numberMode.takeIf { settings.numberProcessing },
			camelCase = settings.splitCamelCase,
			stripBackquotes = !settings.backquoteTags,
			punctLevel = settings.punctLevel.takeIf { settings.punctProcessing }
		)
		val text = Prosody.prefix(settings.phrasePrediction) +
			Pauses.apply(heard, settings.pauses, true)
		preview?.say(
			text,
			voice,
			settings.shape(voice),
			sampleRateHz,
			settings.audioSnapshot(),
			ratePercent = 100.takeIf { forceRate },
			pitchPercent = 100.takeIf { forcePitch }
		)
	}

	private fun adoptVoice() {
		val own = preview?.presetShape(settings.voice) ?: return
		if (own.isEmpty()) return
		// Everything but the speed, which stays where the listener put it.
		// Glen and Sandy ship at 70 where the rest are 50, so taking the
		// voice's own would move the row and the pace on every change.
		settings.writeShape(settings.voice, own - Eci.VOICE_SPEED)
	}

	companion object {
		val RATES: List<Int> = Eci.SAMPLE_RATES.sorted()
	}
}
