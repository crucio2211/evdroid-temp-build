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
	/** Language replacement, settings and synthesis share one active engine.
	 *  onStop deliberately does not take this lock. */
	private val engineStateLock = Any()
	/** Keep several language engines ready. Recreating a native engine discards
	 *  its indexed dictionaries; reopening large external phoneme files on every
	 *  language round-trip (US↔UK) or after a screen-reader restart was the hang. */
	private val enginesByLanguage = java.util.LinkedHashMap<Int, EvvEngine>(4, 0.75f, true)
	private val stopGeneration = java.util.concurrent.atomic.AtomicLong()
	private var pendingEngineLanguage: Int? = null
	private var pendingEngine: java.util.concurrent.Future<EvvEngine?>? = null

	private data class DictionarySnapshot(
		val paths: Map<Int, String>,
		val languages: Map<Int, Int>,
		val stamps: Map<Int, Pair<Long, Long>>
	)
	private val appliedRevisions = java.util.IdentityHashMap<EvvEngine, Int>()
	private val loadedDictionaries = java.util.IdentityHashMap<EvvEngine, DictionarySnapshot>()
	private var loadedRegex: RegexDictionaries.Loaded? = null
	private var regexEnabled = false

	override fun onCreate() {
		settings = Settings(this)
		super.onCreate()
		// The emoji table builds on first touch, which
		// would otherwise land on the first utterance. Warm it here, off
		// every thread that speaks.
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()
		// Preload the primary language engine + its external dictionaries off
		// the synthesis path. With three large phoneme files, native indexing
		// on the first speak (or right after a screen-reader restart) was what
		// hung TalkBack until force-stop.
		val warm = settings
		if (warm != null) {
			val primary = warm.forcedLanguage.takeIf { EvvEngine.available.contains(it) }
				?: EvvEngine.available.firstOrNull()
			if (primary != null) {
				engineExecutor.execute {
					try {
						openEngine(primary)
					} catch (failed: Exception) {
						Log.e(TAG, "dictionary warmup failed for 0x%08x".format(primary), failed)
					}
				}
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		engineExecutor.shutdown()
		try {
			if (!engineExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
				engineExecutor.shutdownNow()
			}
		} catch (interrupted: InterruptedException) {
			engineExecutor.shutdownNow()
			Thread.currentThread().interrupt()
		}
		synchronized(engineStateLock) {
			enginesByLanguage.values.toSet().forEach { it.close() }
			enginesByLanguage.clear()
			appliedRevisions.clear()
			loadedDictionaries.clear()
			engine = null
		}
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
		return matchLanguage(lang, country)?.second ?: TextToSpeech.LANG_NOT_SUPPORTED
	}

	override fun onGetLanguage(): Array<String> {
		val current = engine?.language ?: EvvEngine.available.firstOrNull() ?: return arrayOf("", "", "")
		val loc = Eci.localeOf(current) ?: return arrayOf("", "", "")
		return arrayOf(loc.first, loc.second, "")
	}

	override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
		val found = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
		requestEngine(found.first)
		return found.second
	}

	override fun onGetVoices(): MutableList<Voice> {
		val out = mutableListOf<Voice>()
		for (language in EvvEngine.available) {
			val loc = Eci.localeOf(language) ?: continue
			val locale = Locale(loc.first, loc.second)
			out.add(Voice("evv-${"%08x".format(language)}", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
		}
		return out
	}

	override fun onIsValidVoiceName(voiceName: String?): Int {
		if (voiceName.isNullOrEmpty()) return TextToSpeech.ERROR
		return if (findVoice(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
	}

	override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
		val found = matchLanguage(lang, country) ?: return null
		return "evv-${"%08x".format(found.first)}"
	}

	override fun onLoadVoice(voiceName: String?): Int {
		val found = findVoice(voiceName) ?: return TextToSpeech.ERROR
		requestEngine(found.first)
		return TextToSpeech.SUCCESS
	}

	private fun findVoice(name: String?): Pair<Int, Voice>? {
		if (name.isNullOrEmpty()) return null
		val hex = name.removePrefix("evv-")
		val language = hex.toIntOrNull(16) ?: return null
		if (language !in EvvEngine.available) return null
		val loc = Eci.localeOf(language) ?: return null
		val voice = Voice(name, Locale(loc.first, loc.second), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
		return language to voice
	}

	private fun resolveLanguage(s: Settings?, fallback: Int): Int {
		val forced = s?.forcedLanguage ?: return fallback
		if (EvvEngine.available.contains(forced)) return forced
		Log.w(TAG, "forced language ${"0x%08x".format(forced)} is not linked; using the requested one")
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
			// Cap wait so a slow multi-dict index cannot freeze TalkBack forever.
			// Warmup and language-switch already run the same work on the executor;
			// if we still time out, the next utterance will try again.
			requestEngine(language).get(90, java.util.concurrent.TimeUnit.SECONDS)
		} catch (timeout: java.util.concurrent.TimeoutException) {
			Log.e(TAG, "engine setup timed out after 90s for 0x%08x".format(language))
			null
		} catch (interrupted: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		} catch (failed: java.util.concurrent.ExecutionException) {
			Log.e(TAG, "engine setup failed for 0x%08x".format(language), failed.cause ?: failed)
			null
		}
	}

	/** Called only by [engineExecutor], so changing language cannot race another
	 *  engine construction. Native dictionary indexing is done *outside*
	 *  [engineStateLock] so synthesis / onStop never block behind a large load. */
	private fun openEngine(language: Int): EvvEngine? {
		val started = System.nanoTime()
		var target: EvvEngine? = null
		var needsSettings = false

		synchronized(engineStateLock) {
			val current = engine
			if (current != null && current.language == language) {
				if (settings != null && appliedRevisions[current] != settings!!.revision) {
					target = current
					needsSettings = true
				} else {
					return current
				}
			} else {
				val cached = enginesByLanguage[language]
				if (cached != null) {
					engine = cached
					if (settings != null && appliedRevisions[cached] != settings!!.revision) {
						target = cached
						needsSettings = true
					} else {
						Log.i(TAG, "engine 0x%08x reused with its native dictionaries".format(language))
						return cached
					}
				} else {
					val made = EvvEngine.open(language) ?: return null
					engine = made
					enginesByLanguage[language] = made
					while (enginesByLanguage.size > MAX_CACHED_ENGINES) {
						val eldest = enginesByLanguage.entries.iterator().next()
						if (eldest.key == language) break
						enginesByLanguage.remove(eldest.key)
						appliedRevisions.remove(eldest.value)
						loadedDictionaries.remove(eldest.value)
						eldest.value.close()
					}
					target = made
					needsSettings = true
				}
			}
		}

		// Heavy work (prepareForEngine + native loadDictionary) runs unlocked.
		// Concurrent synthesis may already see this engine; applySettings is
		// idempotent via the DictionarySnapshot / revision checks.
		val engineToConfigure = target ?: return null
		if (needsSettings) {
			try {
				applySettings(engineToConfigure)
				Log.i(TAG, "engine 0x%08x ready in %d ms".format(language, (System.nanoTime() - started) / 1_000_000L))
			} catch (failed: Exception) {
				synchronized(engineStateLock) {
					if (enginesByLanguage[language] === engineToConfigure) {
						enginesByLanguage.remove(language)
						appliedRevisions.remove(engineToConfigure)
						loadedDictionaries.remove(engineToConfigure)
						if (engine === engineToConfigure) {
							engine = enginesByLanguage.values.lastOrNull()
						}
					}
				}
				engineToConfigure.close()
				throw failed
			}
		}
		return engineToConfigure
	}

	private fun applySettings(target: EvvEngine) {
		val s = settings ?: return
		target.setSampleRate(s.sampleRateHz)
		target.setAbbreviations(s.abbreviations)
		val dictionariesReady = loadDictionaries(target, s)
		loadRegexDictionary(s)
		// A voice is written, so the engine can refuse it while it is speaking.
		// One that did not land is left unrecorded and asked for again at the
		// next utterance rather than believed.
		if (target.applyVoice(s.voice, s.shape(s.voice)) && dictionariesReady) {
			appliedRevisions[target] = s.revision
		}
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

	/** Loads each normalized text dictionary through the engine's native loader. */
	private fun loadDictionaries(target: EvvEngine, s: Settings): Boolean {
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
		val snapshot = DictionarySnapshot(want, langs, stamps)
		if (loadedDictionaries[target] == snapshot) return true
		target.forgetDictionaries()
		loadedDictionaries.remove(target)
		val ready = loadEngineDictionaries(target, want, langs)
		if (ready) loadedDictionaries[target] = snapshot
		return ready
	}

	/** Binary dictionaries use the engine's saved form. Text dictionaries are
	 *  normalized and encoded during import/cache warmup, then indexed natively
	 *  once in their selected volume, matching Code Factory's per-volume loading. */
	private fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {
		var ready = true
		for ((volume, path) in want) {
			if ((langs[volume] ?: target.language) != target.language) continue
			val started = System.nanoTime()
			try {
				val file = java.io.File(path)
				if (!file.exists()) {
					ready = false
					Log.e(TAG, "volume $volume dictionary is missing: ${file.name}")
					continue
				}
				if (Dictionaries.isBinary(file)) {
					val ok = Dictionaries.loadBinary(target, volume, file)
					ready = ready && ok
					Log.i(TAG, "volume $volume: ${file.name} ${if (ok) "loaded by the engine" else "refused by the engine"} in ${(System.nanoTime() - started) / 1_000_000L} ms")
					continue
				}
				val prepared = Dictionaries.prepareForEngine(file, target.language)
				val result = target.loadDictionary(volume, prepared.absolutePath)
				if (result != 0) {
					ready = false
					Log.e(TAG, "volume $volume refused ${file.name}: $result")
				}
				Log.i(TAG, "volume $volume: ${file.name} indexed natively in ${(System.nanoTime() - started) / 1_000_000L} ms")
			} catch (failed: Exception) {
				ready = false
				Log.e(TAG, "volume $volume not loaded by the engine after ${(System.nanoTime() - started) / 1_000_000L} ms", failed)
			}
		}
		return ready
	}

	override fun onStop() {
		stopGeneration.incrementAndGet()
		// ECI stop waits for its current rule pass and the next speak would wait
		// behind that cleanup. Abort the callback now; the native worker answers
		// eciDataAbort, and its next speak waits for that worker to become idle.
		engine?.abort()
	}

	override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
		if (request == null || callback == null) return
		val requestGeneration = stopGeneration.get()
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
		while (true) {
			val target = ensureEngine(language)
			if (target == null) {
				callback.error(TextToSpeech.ERROR_SERVICE)
				return
			}
			val spokeOnCurrentEngine = synchronized(engineStateLock) {
				if (engine !== target) false else {
					synthesizeWithEngine(target, request, callback, requestGeneration, s)
					true
				}
			}
			if (spokeOnCurrentEngine) return
		}
	}

	/** Serializes settings and synthesis against language replacement, as the
	 *  Code Factory service does for its active language engine. onStop remains
	 *  outside this lock so it can interrupt native speech immediately. */
	private fun synthesizeWithEngine(
		target: EvvEngine,
		request: SynthesisRequest,
		callback: SynthesisCallback,
		requestGeneration: Long,
		s: Settings?
	) {
		// A completed utterance has already drained its output. Do not call
		// eciStop before every speak: Code Factory only stops on Android's
		// onStop path, and native eciStop can wait for an entire rule pass.
		if (stopGeneration.get() != requestGeneration) return
				if (s != null && appliedRevisions[target] != s.revision) applySettings(target)
		// Re-sent every time rather than once, because it is the one setting
		// with no way of telling whether something else has moved it.
		s?.let { target.setAbbreviations(it.abbreviations) }
		// A forced rate or pitch is the voice's own Speed or Pitch row: the
		// voice is applied absolutely below, so 100 here leaves it alone and
		// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers
		// multiply the voice as before.
		target.setRatePercent(if (s != null && s.forceRate) 100 else request.speechRate)
		target.setPitchPercent(if (s != null && s.forcePitch) 100 else request.pitch)
		val text = request.charSequenceText?.toString() ?: return
		val processed = TextPipeline.process(text, s, loadedRegex, regexEnabled)
		if (processed.isEmpty()) {
			callback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)
			callback.done()
			return
		}
		if (!target.speak(processed)) {
			callback.error(TextToSpeech.ERROR_SERVICE)
			return
		}
		callback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)
		val buffer = ByteArray(MAX_CHUNK)
		var handed = 0L
		while (true) {
			if (stopGeneration.get() != requestGeneration) {
				target.abort()
				return
			}
			val n = target.read(buffer)
			if (n <= 0) break
			callback.audioAvailable(buffer, 0, n)
			handed += n
		}
		mostAudioHandedMs = maxOf(mostAudioHandedMs, handed * 1000L / (target.sampleRateHz * BYTES_PER_SAMPLE))
		callback.done()
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
		const val MAX_CACHED_ENGINES = 4
		/** Small commits for a screen reader: what is handed over plays,
		 *  so stop has at most one short chunk already in the framework queue. */
		const val MAX_CHUNK = 1024
		const val BYTES_PER_SAMPLE = 2
	}
}
