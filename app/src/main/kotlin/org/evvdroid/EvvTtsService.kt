package org.evvdroid

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * The engine Android talks to.
 *
 * onSynthesizeText runs on the framework's own synthesis thread and is the
 * only place an utterance is driven; onStop arrives from another thread and
 * does nothing but set the engine's abort flag, which is what unblocks the
 * read below.
 */
class EvvTtsService : TextToSpeechService() {

	@Volatile
	private var engine: EvvEngine? = null
	private var settings: Settings? = null
	private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
		Thread(task, "evvdroid-engine-load").apply { isDaemon = true }
	}
	private val engineLoadLock = Any()
	private var pendingEngineLanguage: Int? = null
	private var pendingEngine: java.util.concurrent.Future<EvvEngine?>? = null

	/** Written on one binder thread and read on another: a stop the pump
	 *  never sees is audio committed past it, and that is the stale sliver
	 *  at the head of the next utterance. */
	@Volatile
	private var stopped = false

	private var appliedRevision = -1

	private var loadedDictionaries: Map<Int, String> = emptyMap()
	private var loadedDictionaryLangs: Map<Int, Int> = emptyMap()
	private var loadedDictionaryStamps: Map<Int, Pair<Long, Long>> = emptyMap()
	private var loadedNativeDictionaries: Map<Int, String> = emptyMap()
	private var loadedNativeDictionaryLangs: Map<Int, Int> = emptyMap()
	private var loadedNativeDictionaryStamps: Map<Int, Pair<Long, Long>> = emptyMap()
	private var loadedNativeEngine: EvvEngine? = null
	private var loadedTextRootStamp: Triple<String, Long, Long>? = null
	private val taughtTextRootWords = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, String>>()
	/** Picked files as read: engine language to exact dictionary key, to every
	 *  entry written under it, with the volume each was picked for and
	 *  the key as written and what to say. Files for one language read
	 *  as one map, so a word is taught to the engine that will speak
	 *  it. Taught words live on the engine instance, so [taughtWords]
	 *  starts over with every new one. */
	private data class DictEntry(val volume: Int, val key: String, val say: String)
	private data class TextDictionary(val volume: Int, val entries: Map<String, Pair<String, String>>)
	private var wordMaps: Map<Int, List<TextDictionary>> = emptyMap()
	private val taughtWords = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, String>>()

	/** Dictionary tokens can include Unicode letters and marks, apostrophes,
	 *  hyphens, and the punctuation found inside legitimate keys such as P!nk.
	 *  This keeps Windows-1252 names and other non-ASCII entries matchable. */
	private val WORD = Regex("""\+?[\p{L}\p{M}\p{N}\u0080-\u009F]+(?:['’-][\p{L}\p{M}\p{N}\u0080-\u009F]+|![\p{L}\p{M}\p{N}\u0080-\u009F]+)*""")

	private var loadedRegex: RegexDictionaries.Loaded? = null
	private var regexEnabled = false

	override fun onCreate() {
		settings = Settings(this)
		super.onCreate()
		warmDictionaryCaches()
		// The emoji table builds on first touch, which
		// would otherwise land on the first utterance. Warm it here, off
		// every thread that speaks.
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()
	}

	/** Parse text dictionaries after service startup, without making the first
	 *  TalkBack request scan every saved file. */
	private fun warmDictionaryCaches() {
		val saved = settings ?: return
		val paths = saved.dictionaryPaths()
		Thread({
			for ((volume, path) in paths) {
				try {
					val file = java.io.File(path)
					if (file.exists() && !Dictionaries.isBinary(file)) {
						Dictionaries.parse(file)
					}
				} catch (failed: Exception) {
					Log.e(TAG, "could not warm dictionary volume $volume", failed)
				}
			}
		}, "evvdroid-dictionary-cache").apply { isDaemon = true }.start()
	}

	override fun onDestroy() {
		super.onDestroy()
		engineExecutor.shutdown()
		try {
			engineExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
		} catch (interrupted: InterruptedException) {
			Thread.currentThread().interrupt()
		}
		engine?.close()
		engine = null
	}

	// ---- languages -------------------------------------------------------

	private fun matchLanguage(lang: String?, country: String?): Pair<Int, Int>? {
		if (lang.isNullOrEmpty()) return null
		val wantLang = normalise(lang)
		val wantCountry = country?.takeIf { it.isNotEmpty() }?.let { normaliseCountry(it) }
		var byLanguage: Int? = null
		for (candidate in EvvEngine.available) {
			val loc = Eci.localeOf(candidate) ?: continue
			if (!loc.first.equals(wantLang, ignoreCase = true)) continue
			if (wantCountry != null && loc.second.equals(wantCountry, ignoreCase = true)) {
				return candidate to TextToSpeech.LANG_COUNTRY_AVAILABLE
			}
			if (byLanguage == null) byLanguage = candidate
		}
		return byLanguage?.let { it to TextToSpeech.LANG_AVAILABLE }
	}

	private fun normalise(lang: String): String =
		runCatching { Locale(lang).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: lang

	private fun normaliseCountry(country: String): String =
		runCatching { Locale("", country).isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() } ?: country

	override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		return found.second
	}

	override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		requestEngine(found.first)
		return found.second
	}

	override fun onGetLanguage(): Array<String> {
		val language = engine?.language ?: EvvEngine.available.firstOrNull() ?: return arrayOf("eng", "USA", "")
		val loc = Eci.localeOf(language) ?: return arrayOf("eng", "USA", "")
		return arrayOf(loc.first, loc.second, loc.third)
	}

	// ---- voices ----------------------------------------------------------

	private fun voiceName(language: Int, preset: Int): String {
		val loc = Eci.localeOf(language) ?: return "evv-$preset"
		return "${loc.first}-${loc.second}-${Eci.PRESET_NAMES[preset]}"
	}

	override fun onGetVoices(): MutableList<Voice> {
		val out = ArrayList<Voice>()
		for (language in EvvEngine.available) {
			val loc = Eci.localeOf(language) ?: continue
			val locale = Locale(loc.first, loc.second)
			for (preset in Eci.PRESET_NAMES.indices) {
				out.add(
					Voice(
						voiceName(language, preset),
						locale,
						Voice.QUALITY_NORMAL,
						Voice.LATENCY_VERY_LOW,
						false,
						emptySet()
					)
				)
			}
		}
		return out
	}

	private fun findVoice(name: String?): Pair<Int, Int>? {
		if (name.isNullOrEmpty()) return null
		for (language in EvvEngine.available) {
			for (preset in Eci.PRESET_NAMES.indices) {
				if (voiceName(language, preset) == name) return language to preset
			}
		}
		return null
	}

	override fun onIsValidVoiceName(name: String?): Int =
		if (findVoice(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

	override fun onLoadVoice(name: String?): Int {
		val found = findVoice(name) ?: return TextToSpeech.ERROR
		requestEngine(found.first)
		return TextToSpeech.SUCCESS
	}

	override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
		val found = matchLanguage(lang, country) ?: return null
		return voiceName(found.first, settings?.voice ?: 0)
	}

	// ---- speaking --------------------------------------------------------

	/** The language to speak in: the forced one when set and linked into the
	 *  engine, else the caller's. A forced language from a build with fewer
	 *  languages falls back instead of failing the utterance. */
	private fun resolveLanguage(s: Settings?, fallback: Int): Int {
		if (s?.forceLanguage == true) {
			val forced = s.forcedLanguage
			if (EvvEngine.available.contains(forced)) return forced
			Log.w(TAG, "forced language ${"0x%08x".format(forced)} is not linked; using the requested one")
		}
		return fallback
	}

	/** Language selection starts engine and dictionary setup on a worker. The
	 *  synthesis callback joins that same task only if speech arrives first. */
	private fun requestEngine(language: Int): java.util.concurrent.Future<EvvEngine?> = synchronized(engineLoadLock) {
		engine?.takeIf { it.language == language }?.let { ready ->
			val completed = java.util.concurrent.FutureTask<EvvEngine?>(java.util.concurrent.Callable { ready })
			completed.run()
			return@synchronized completed
		}
		pendingEngine?.takeIf { pendingEngineLanguage == language && !it.isDone }?.let {
			return@synchronized it
		}
		pendingEngineLanguage = language
		engineExecutor.submit<EvvEngine?> { openEngine(language) }.also { pendingEngine = it }
	}

	private fun ensureEngine(language: Int): EvvEngine? {
		return try {
			requestEngine(language).get()
		} catch (interrupted: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		} catch (failed: java.util.concurrent.ExecutionException) {
			Log.e(TAG, "engine setup failed for 0x%08x".format(language), failed.cause ?: failed)
			null
		}
	}

	/** Called only by [engineExecutor], so changing language cannot race another
	 *  engine construction. Dictionary files are attached before the task ends. */
	private fun openEngine(language: Int): EvvEngine? {
		val held = engine
		if (held != null && held.language == language) return held
		held?.close()
		engine = null
		val made = EvvEngine.open(language)
		engine = made
		// A new engine owns a fresh native dictionary set.
		taughtWords.clear()
		if (made != null) applySettings(made)
		return made
	}

	private fun applySettings(target: EvvEngine) {
		val s = settings ?: return
		target.setSampleRate(s.sampleRateHz)
		target.setAbbreviations(s.abbreviations)
		loadDictionaries(target, s)
		loadRegexDictionary(s)
		// A voice is written, so the engine can refuse it while it is speaking.
		// One that did not land is left unrecorded and asked for again at the
		// next utterance rather than believed.
		if (target.applyVoice(s.voice, s.shape(s.voice))) appliedRevision = s.revision
	}

	/** The regex dictionary is recompiled when the settings change, which is
	 *  the only time the file it reads can have changed: it is a copy of
	 *  our own, written by the settings screen. */
	private fun loadRegexDictionary(s: Settings) {
		regexEnabled = s.regexDictionaryEnabled
		val path = if (regexEnabled) s.regexDictionaryPath() else null
		val file = path?.let { java.io.File(it) }
		loadedRegex = if (file != null && file.exists()) {
			RegexDictionaries.load(file).also {
				Log.i(TAG, "regex dictionary: ${it?.entries?.size ?: 0} patterns from ${file.name}")
			}
		} else {
			null
		}
	}

	/** Text dictionaries are cached as exact-key maps, so toggling them does not
	 *  clear and reload every ECI volume. Binary dictionaries remain native. */
	private fun loadDictionaries(target: EvvEngine, s: Settings) {
		// The engine flag below controls its built-in abbreviation list. Keep
		// the user's abbreviation volume behind the same switch: otherwise a
		// custom enuabbr dictionary still expands entries such as `jan` when
		// the setting is off.
		val want = s.dictionaryPaths().filterKeys {
			s.useDictionary(it) && (it != Eci.DICT_ABBREVIATION || s.abbreviations)
		}
		val langs = want.keys.associateWith { s.dictionaryLanguageResolved(it, EvvEngine.available) }
		val stamps = want.mapValues { (_, path) ->
			val file = java.io.File(path)
			file.length() to file.lastModified()
		}
		val binary = want.mapValues { (_, path) ->
			val file = java.io.File(path)
			file.exists() && Dictionaries.isBinary(file)
		}
		val nativeWant = want.filterKeys { binary[it] == true }
		val nativeLangs = langs.filterKeys { it in nativeWant }
		val nativeStamps = stamps.filterKeys { it in nativeWant }
		val rootTextStamp = want[Eci.DICT_ROOT]?.let { path ->
			if (binary[Eci.DICT_ROOT] != true) java.io.File(path).let { Triple(path, it.length(), it.lastModified()) } else null
		}
		val dictionariesChanged = want != loadedDictionaries || langs != loadedDictionaryLangs || stamps != loadedDictionaryStamps
		val textRootChanged = rootTextStamp != loadedTextRootStamp
		val nativeChanged = nativeWant != loadedNativeDictionaries || nativeLangs != loadedNativeDictionaryLangs ||
			nativeStamps != loadedNativeDictionaryStamps || target !== loadedNativeEngine
		if (!dictionariesChanged && !nativeChanged && !textRootChanged) return
		if (dictionariesChanged) {
			val read = LinkedHashMap<Int, MutableList<TextDictionary>>()
			for ((volume, path) in want) {
				try {
					val file = java.io.File(path)
					if (!file.exists()) continue
					if (binary[volume] == true) continue
					val entries = Dictionaries.parse(file)
					Log.i(TAG, "volume $volume: ${entries.size} cached entries from ${file.name}")
					// Keep each dictionary with the language chosen at import:
					// enuroot belongs to US English; engroot belongs to UK English.
					val into = read.getOrPut(langs[volume] ?: target.language) { mutableListOf() }
					into.add(TextDictionary(volume, entries))
				} catch (failed: Exception) {
					// One bad volume must not take the utterance, or every
					// utterance after it, down with it.
					Log.e(TAG, "volume $volume not read", failed)
				}
			}
			wordMaps = read.mapValues { (_, dictionaries) ->
				dictionaries.sortedBy { dictionaryPriority(it.volume) }
			}
		}
		loadedDictionaries = want
		loadedDictionaryLangs = langs
		loadedDictionaryStamps = stamps
		if (nativeChanged) {
			target.forgetDictionaries()
			taughtWords.clear()
			taughtTextRootWords.clear()
			loadedNativeDictionaries = nativeWant
			loadedNativeDictionaryLangs = nativeLangs
			loadedNativeDictionaryStamps = nativeStamps
			loadedNativeEngine = target
			loadedTextRootStamp = rootTextStamp
			loadEngineDictionaries(target, nativeWant, nativeLangs)
		} else if (textRootChanged) {
			// Root text stems are taught temporarily so ECI can add possessive
			// suffixes. Remove only those few overrides when that file changes;
			// do not forget and reload every native dictionary volume.
			for ((volume, key) in taughtTextRootWords) {
				val result = target.removeWord(volume, key)
				if (result != 0) Log.e(TAG, "volume $volume could not remove stale root stem $key: $result")
				taughtWords.remove(volume to key)
			}
			taughtTextRootWords.clear()
			loadedTextRootStamp = rootTextStamp
		}
	}

	/** Saved binary dictionaries use the engine's native loader. Text entries
	 *  are already handled by the cached in-memory index. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>) {
		for ((volume, path) in want) {
			if ((langs[volume] ?: target.language) != target.language) continue
			try {
				val file = java.io.File(path)
				if (!file.exists() || !Dictionaries.isBinary(file)) continue
				val ok = Dictionaries.loadBinary(target, volume, file)
				Log.i(TAG, "volume $volume: ${file.name} ${if (ok) "loaded by the engine" else "refused by the engine"}")
			} catch (failed: Exception) {
				Log.e(TAG, "volume $volume not loaded by the engine", failed)
			}
		}
	}

	/** Volumes one entry goes to: its own, and main too for a root
	 *  word, so a built-in main never shouts over a taught root. An
	 *  abbreviation stays in abbreviations: its key carries periods
	 *  the other volumes must never learn. */
	private fun teachVolumes(volume: Int): List<Int> =
		if (volume == Eci.DICT_ROOT) listOf(Eci.DICT_MAIN, Eci.DICT_ROOT) else listOf(volume)

	/** True when [say] contains an Eloquence inline phoneme annotation. */
	private fun isPhonemeSay(say: String): Boolean =
		say.indexOf('`') >= 0 || (say.startsWith("[") && say.endsWith("]"))

	private fun binaryDictionarySay(target: EvvEngine, volume: Int, word: String): String? {
		val forms = LinkedHashSet<String>()
		for (form in apostropheVariants(word)) {
			forms.add(form)
			if (isSentenceCapitalized(form)) forms.add(form.lowercase())
		}
		for (form in forms) {
			target.lookUpWord(volume, form)?.let { return it }
		}
		return null
	}
	/** A root entry applies to a possessive/contraction form as well. Keep the
	 *  suffix in the text sent to Eloquence: only the root key is taught, so the
	 *  engine can pronounce the suffix using its own morphology. */
	private fun rootApostropheStems(word: String): List<String> {
		val at = word.indexOfAny(charArrayOf('\'', '\u2019'))
		if (at <= 0 || at == word.lastIndex) return emptyList()
		val suffix = word.substring(at + 1)
		if (!suffix.equals("s", ignoreCase = true)) return emptyList()
		return listOf(word.substring(0, at))
	}
	private fun lookupRootEntries(target: EvvEngine, language: Int, word: String): List<DictEntry> {
		if (target.language != language || Eci.DICT_ROOT !in loadedDictionaries) return emptyList()
		if ((loadedDictionaryLangs[Eci.DICT_ROOT] ?: language) != language) return emptyList()
		return rootApostropheStems(word).flatMap { stem ->
			val textEntry = lookupEntryInVolume(language, Eci.DICT_ROOT, stem)
			if (textEntry != null) listOf(textEntry)
			else target.lookUpWord(Eci.DICT_ROOT, stem)?.let { say -> listOf(DictEntry(Eci.DICT_ROOT, stem, say)) }.orEmpty()
		}.distinctBy { it.key }
	}
	private fun apostropheVariants(word: String): Set<String> = linkedSetOf(
		word,
		word.replace('\u2019', '\''),
		word.replace('\'', '\u2019')
	)
	/** Root entries precede main entries just as they did when the root file was
	 *  loaded into both native volumes: a custom root pronunciation must be able
	 *  to override the engine's built-in main pronunciation. */
	private fun dictionaryPriority(volume: Int): Int = when (volume) {
		Eci.DICT_ROOT -> 0
		Eci.DICT_MAIN -> 1
		else -> 2
	}

	/** Match dictionary keys with Eloquence's case-sensitive rule. A sentence
	 *  initial capital may use a lowercase key, as the on-demand teacher installs
	 *  that form; an uppercase key such as `NA` must not rewrite lowercase `na`.
	 *  Root entries take precedence over main entries. */
	private fun lookupEntries(language: Int, word: String): List<DictEntry> {
		val dictionaries = wordMaps[language] ?: return emptyList()
		for (dictionary in dictionaries) {
			lookupEntryInVolume(language, dictionary.volume, word)?.let { return listOf(it) }
		}
		return emptyList()
	}
	private fun lookupEntryInVolume(language: Int, volume: Int, word: String): DictEntry? {
		val dictionary = wordMaps[language]?.firstOrNull { it.volume == volume } ?: return null
		for (form in apostropheVariants(word)) {
			(dictionary.entries[form] ?: dictionary.entries[form + "."])?.let {
				return DictEntry(volume, it.first, it.second)
			}
		}
		if (!isSentenceCapitalized(word)) return null
		for (form in apostropheVariants(word.lowercase())) {
			(dictionary.entries[form] ?: dictionary.entries[form + "."])?.let {
				return DictEntry(volume, it.first, it.second)
			}
		}
		return null
	}
	private fun isSentenceCapitalized(word: String): Boolean =
		word.isNotEmpty() && word[0].isUpperCase() && word.drop(1).none { it.isUpperCase() }

	/** Cached text entries are applied directly, so ordinary text phonemes do
	 *  not need one native JNI update per dictionary entry or a reload on toggle. */
	private fun applyRespellings(target: EvvEngine, text: String): String {
		if (text.isEmpty()) return text
		return WORD.replace(text) { match ->
			val word = match.value
			val entry = lookupEntries(target.language, word).firstOrNull()
			val say = entry?.say ?: if (word.any { it == '\'' || it == '\u2019' }) {
				loadedDictionaries.entries
					.filter { (volume, _) -> (loadedDictionaryLangs[volume] ?: target.language) == target.language }
					.sortedBy { dictionaryPriority(it.key) }
					.firstNotNullOfOrNull { (volume, path) ->
						val file = java.io.File(path)
						if (file.exists() && Dictionaries.isBinary(file)) binaryDictionarySay(target, volume, word) else null
					}
			} else null
			if (say != null && isPhonemeSay(say) &&
				(entry != null || word.any { it == '\'' || it == '\u2019' })
			) {
				if (say.startsWith("[") && say.endsWith("]") && !say.startsWith("`[")) "`$say" else say
			} else if (say != null && entry != null && !isPhonemeSay(say)) {
				if (word.isNotEmpty() && word[0].isUpperCase() && say.isNotEmpty() && say[0].isLowerCase()) {
					say.replaceFirstChar { it.uppercase() }
				} else {
					say
				}
			} else {
				word
			}
		}
	}

	/** Teaches only root stems needed for ECI to handle a possessive suffix.
	 *  Ordinary text entries are rewritten from the cached map. */
	private fun teachUnknownWords(target: EvvEngine, text: String) {
		if (wordMaps.isEmpty() || text.isEmpty()) return
		for (raw in WORD.findAll(text)) {
			val word = raw.value
			if (word.none { it == '\'' || it == '\u2019' }) continue
			// The native loader already indexed all ordinary entries. Keep this
			// single-word fallback only for a root stem before an apostrophe.
			val hits = lookupRootEntries(target, target.language, word).distinctBy { it.volume to it.key }
			if (hits.isEmpty()) continue
			for (entry in hits) {
				val say = if (entry.say.startsWith("[") && entry.say.endsWith("]") && !entry.say.startsWith("`[")) {
					"`${entry.say}"
				} else {
					entry.say
				}
				for (volume in teachVolumes(entry.volume)) {
					val forms = if (entry.key.firstOrNull()?.isLowerCase() == true) {
						linkedSetOf(entry.key, entry.key.replaceFirstChar { it.uppercase() })
					} else {
						linkedSetOf(entry.key)
					}
					for (form in forms) {
						val k = volume to form
						if (k in taughtWords) continue
						try {
							if (target.teachWord(volume, form, say) == 0) {
								taughtWords.add(k)
								if (entry.volume == Eci.DICT_ROOT && loadedTextRootStamp != null) {
									taughtTextRootWords.add(k)
								}
							} else {
								Log.e(TAG, "volume $volume refused dictionary word $form")
							}
						} catch (failed: Exception) {
							Log.e(TAG, "volume $volume could not teach dictionary word $form", failed)
						}
					}
				}
			}
		}
	}

	override fun onStop() {
		stopped = true
		engine?.stop()
	}

	override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
		if (request == null || callback == null) return
		stopped = false
		val s = settings
		val found = matchLanguage(request.language, request.country)
		if (found == null) {
			callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
			return
		}
		// The voice is whichever the settings screen says, not whichever the
		// caller named. A caller holds on to the Voice it was given, so a
		// screen reader that connected while Reed was chosen goes on asking
		// for Reed however many times the setting is changed underneath it.
		// The name still decides the language, since that is what it is for --
		// unless a language is forced, which overrules every caller.
		val wanted = findVoice(request.voiceName)
		val language = resolveLanguage(s, wanted?.first ?: found.first)
		val target = ensureEngine(language)
		if (target == null) {
			callback.error(TextToSpeech.ERROR_SERVICE)
			return
		}
		// Stopped first, NVDA-style: its driver cancels before every speak,
		// flushing whatever a previous utterance left behind when the reader
		// moved on without saying stop. eciStop on an idle engine is safe
		// here (upstream's fix for that wedge is in this tree).
		target.stop()
		if (s != null && s.revision != appliedRevision) applySettings(target)
		// Re-sent every time rather than once, because it is the one setting
		// with no way of telling whether something else has moved it.
		s?.let { target.setAbbreviations(it.abbreviations) }
		// A forced rate or pitch is the voice's own Speed or Pitch row: the
		// voice is applied absolutely below, so 100 here leaves it alone and
		// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers
		// multiply the voice as before.
		target.setRatePercent(if (s != null && s.forceRate) 100 else request.speechRate)
		target.setPitchPercent(if (s != null && s.forcePitch) 100 else request.pitch)
		// One pipeline for TalkBack and the sample button alike; the shape of
		// it is documented on TextPipeline.
		val raw = request.charSequenceText?.toString().orEmpty()
		val numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null
		val punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null
		// Split before any rewriting, NVDA-style: each raw piece is
		// processed and spoken on its own, so the first audio lands after
		// the first piece's frontend rather than the whole block's. A
		// block-sized regex and dictionary scan plus one engine frontend
		// over all of it is what kept big text waiting.
		val rawPieces = TextPieces.split(raw)
		// The framework is not told an utterance has begun until there are
		// samples for it. Saying so up front and then being silenced before the
		// engine has made anything hands the reader a whole start and finish
		// with no speech in it, and TalkBack reads that as its queue running
		// dry: it answers "Completed speech while already completed", stops the
		// queue, and the item the finger is actually on dies with it. That is
		// what made dragging across a launcher go quiet. RHVoice opens the same
		// way, from inside its player rather than ahead of it.
		val opening = Opening(callback, target.sampleRateHz)
		opening.open()
		val pace = Pace(target.sampleRateHz * BYTES_PER_SAMPLE)
		// Piece by piece, so that asking for silence waits out a piece rather
		// than the whole message: the engine cannot abandon what it is saying.
		// Pauses are shortened after the split rather than before it: the
		// annotation sits in front of the full stop, and a piece boundary is
		// found by looking at the end of a word.
		val pauses = settings?.pauses ?: Pauses.ALL
		val prosody = Prosody.prefix(settings?.phrasePrediction ?: false)
		// No loudness tricks: an exclamation must read as one from its
		// tune alone. Snapshot.boost stays off (1) until intonation lands.
		val audio = s?.audioSnapshot() ?: AudioOutput.Snapshot()
		for ((ri, rawPiece) in rawPieces.withIndex()) {
			if (stopped) break
			val fixed = TextPipeline.process(
				rawPiece,
				emoji = s?.speakEmoji != false,
				emojiCompress = s?.emojiCompress ?: EmojiProcessing.DEFAULT_COMPRESS,
				numberMode = numberMode,
				camelCase = s?.splitCamelCase != false,
				stripBackquotes = s?.backquoteTags != true,
				punctLevel = punctLevel
			)
			// The user's own rewrites go last, closest to the engine, so what is
			// written in the regex dictionary is what gets said.
			val afterRegex = if (regexEnabled) RegexDictionaries.apply(loadedRegex, fixed) else fixed
			// Words the dictionaries know are taught now, ahead of the engine
			// meeting them below.
			teachUnknownWords(target, afterRegex)
			val text = applyRespellings(target, afterRegex)
			if (text.isEmpty()) continue
			val pieces = TextPieces.split(text)
			for ((at, part) in pieces.withIndex()) {
				if (stopped) break
				val piece = prosody + Pauses.apply(
					part, pauses,
					at == pieces.lastIndex && ri == rawPieces.lastIndex
				)
		if (!target.speak(piece)) {
			Log.e(TAG, "the engine refused ${piece.length} characters")
			callback.error(TextToSpeech.ERROR_SYNTHESIS)
			return
		}
		if (!pump(target, callback, pace, opening, audio)) {
					remember(pace)
					return
				}
			}
		}
		remember(pace)
		if (stopped) {
			// Over, not done: finishing the callback flushes the tail it
			// is still holding, and that tail sounds at the head of
			// whatever comes next. A stopped utterance is left unfinished;
			// TalkBack asked for silence, not for completion.
		} else if (opening.began) callback.done()
		else {
			callback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)
			callback.done()
		}
	}

	/** Hands one piece's samples on as they arrive, and answers whether the
	 *  caller may go on to the next. The output stage is one chain for the
	 *  piece, so its filters run continuously across its chunks. */
	private fun pump(target: EvvEngine, callback: SynthesisCallback, pace: Pace, opening: Opening, audio: AudioOutput.Snapshot): Boolean {
		val size = callback.maxBufferSize.coerceIn(MIN_CHUNK, MAX_CHUNK)
		val buffer = ByteArray(size)
		val chain = AudioOutput.Chain(audio)
		while (!stopped) {
			val n = target.read(buffer)
			if (n <= 0) break
			// A chunk taken before the stop landed dies here, unplayed:
			// handing it over gives the framework audio for an utterance
			// already over, and it sounds at the head of the next one.
			if (stopped) {
				target.stop()
				return false
			}
			chain.process(buffer, n)
			if (!opening.open()) {
				target.stop()
				return false
			}
			if (callback.audioAvailable(buffer, 0, n) != TextToSpeech.SUCCESS) {
				// The framework has torn the callback down already, so done()
				// is neither wanted nor listened to.
				target.stop()
				return false
			}
			pace.handed(n)
		}
		return true
	}

	private fun remember(pace: Pace) {
		if (pace.handedMs() > mostAudioHandedMs) mostAudioHandedMs = pace.handedMs()
	}

	/** Tells the framework an utterance has begun, once, and not before there
	 *  is something to hear. [began] answers whether it was ever told, which is
	 *  what decides whether it is owed a finish. */
	private class Opening(private val callback: SynthesisCallback, private val rateHz: Int) {
		var began = false
			private set

		fun open(): Boolean {
			if (began) return true
			began = callback.start(rateHz, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS
			return began
		}
	}

	/** How far the audio handed over has run ahead of the time it takes to
	 *  play it. */
	private class Pace(private val bytesPerSecond: Int) {
		private var bytes = 0L

		fun handed(more: Int) {
			bytes += more
		}

		fun handedMs(): Long = if (bytesPerSecond <= 0) 0 else bytes * 1000L / bytesPerSecond

	}

	internal companion object {
		/** The most audio, in milliseconds, that any one utterance has handed
		 *  over since this was last cleared. Nothing in the app reads it. It is
		 *  what lets a test say the engine is not running ahead of the speech,
		 *  which is invisible from outside: an engine that queues six items in
		 *  a tenth of a second and one that paces itself look the same to the
		 *  framework and sound completely different to a person. */
		@Volatile
		var mostAudioHandedMs: Long = 0

		const val TAG = "evvdroid"
		const val MIN_CHUNK = 1024
		/** Small commits for a screen reader: what is handed over plays,
		 *  so a stop waits out at most this much, and speech starts in
		 *  slivers rather than after whole mouthfuls. */
		const val MAX_CHUNK = 4096
		const val BYTES_PER_SAMPLE = 2
	}
}
