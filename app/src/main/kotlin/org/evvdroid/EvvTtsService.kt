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

	private var engine: EvvEngine? = null
	private var settings: Settings? = null

	/** Written on one binder thread and read on another: a stop the pump
	 *  never sees is audio committed past it, and that is the stale sliver
	 *  at the head of the next utterance. */
	@Volatile
	private var stopped = false

	private var appliedRevision = -1

	private var loadedDictionaries: Map<Int, String> = emptyMap()
	private var loadedDictionaryLangs: Map<Int, Int> = emptyMap()
	private var loadedEngine: EvvEngine? = null

	/** Picked files as read: engine language to lowercase word, to every
	 *  entry written under it, with the volume each was picked for and
	 *  the key as written and what to say. Files for one language read
	 *  as one map, so a word is taught to the engine that will speak
	 *  it. Taught words live on the engine instance, so [taughtWords]
	 *  starts over with every new one. Shared with the background
	 *  teacher below. */
	private data class DictEntry(val volume: Int, val key: String, val say: String)
	private var wordMaps: Map<Int, Map<String, List<DictEntry>>> = emptyMap()
	private var wordMapsLower: Map<Int, Map<String, List<DictEntry>>> = emptyMap()
	private val taughtWords = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, String>>()

	/** Words as the engine meets them: letters and digits carrying their
	 *  apostrophes and hyphens, so billets-doux looks up whole. */
	/** Words as the teaching pass sees them. The high half holds the
	 *  accented letters (everything but the multiply and divide signs),
	 *  so a key like ni\u00F1a is taught rather than walked past: the old
	 *  ASCII-only set silently skipped every imported entry above 127. */
	private val WORD = Regex("""[A-Za-z0-9À-ÖØ-öø-ÿ]+(?:['-][A-Za-z0-9À-ÖØ-öø-ÿ]+)*""")

	private var loadedRegex: RegexDictionaries.Loaded? = null
	private var regexEnabled = false

	override fun onCreate() {
		settings = Settings(this)
		super.onCreate()
		// The emoji table builds on first touch, which
		// would otherwise land on the first utterance. Warm it here, off
		// every thread that speaks.
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()
	}

	override fun onDestroy() {
		super.onDestroy()
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
		ensureEngine(found.first) ?: return TextToSpeech.LANG_NOT_SUPPORTED
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
		ensureEngine(found.first) ?: return TextToSpeech.ERROR
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

	private fun ensureEngine(language: Int): EvvEngine? {
		val held = engine
		if (held != null && held.language == language) return held
		held?.close()
		val made = EvvEngine.open(language)
		engine = made
		// Taught words live on the instance, so a new one starts untaught
		// and learns again as words come up.
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

	/** Reads the picked files into memory when they change, and teaches
	 *  the speaking language's words on a background thread: teaching
	 *  tens of thousands of words one by one blocks the synthesis thread
	 *  past any reasonable wait, but lazy teaching alone misses words in
	 *  block reading, so everything registered goes in up front and the
	 *  per-word path stays as backup for stragglers. */
	private fun loadDictionaries(target: EvvEngine, s: Settings) {
		val want = s.dictionaryPaths().filterKeys { s.useDictionary(it) }
		val langs = want.keys.associateWith { s.dictionaryLanguageResolved(it, EvvEngine.available) }
		if (want == loadedDictionaries && langs == loadedDictionaryLangs && target === loadedEngine) return
		target.forgetDictionaries()
		if (want != loadedDictionaries || langs != loadedDictionaryLangs) {
			val read = LinkedHashMap<Int, LinkedHashMap<String, MutableList<DictEntry>>>()
			for ((volume, path) in want) {
				try {
					val file = java.io.File(path)
					if (!file.exists()) continue
					// A file in the engine's own form is not parsed here: the
					// engine reads it, below.
					if (Dictionaries.isBinary(file)) continue
					val entries = Dictionaries.parse(file)
					Log.i(TAG, "volume $volume: ${entries.size} entries from ${file.name}")
					val into = read.getOrPut(langs[volume] ?: target.language) { LinkedHashMap() }
					for ((exact, entry) in entries) {
						into.getOrPut(exact) { mutableListOf() }
							.add(DictEntry(volume, entry.first, entry.second))
					}
				} catch (failed: Exception) {
					// One bad volume must not take the utterance, or every
					// utterance after it, down with it.
					Log.e(TAG, "volume $volume not read", failed)
				}
			}
			wordMaps = read
			val lower = LinkedHashMap<Int, Map<String, List<DictEntry>>>()
			for ((lang, entries) in read) {
				val by = HashMap<String, MutableList<DictEntry>>()
				for ((exact, list) in entries) {
					by.getOrPut(exact.lowercase()) { mutableListOf() }.addAll(list)
				}
				lower[lang] = by
			}
			wordMapsLower = lower
		}
		taughtWords.clear()
		loadedDictionaries = want
		loadedDictionaryLangs = langs
		loadedEngine = target
		loadBinaryDictionaries(target, want, langs)
		teachAllWords(target)
	}

	/** The files in the engine's own saved form, read by the engine's loader
	 *  into the language they were picked for. A text file for the same volume
	 *  is taught word by word after this, and so wins where they overlap. */
	private fun loadBinaryDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>) {
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

	/** How many engine teachings the up-front pass does at most. Big
	 *  files teach the rest as spoken through [teachUnknownWords],
	 *  which also matches a closing period, so capping never strands
	 *  an abbreviation the eager pass did not reach. */
	private val MAX_EAGER_TEACH = 100_000

	/** Every registered word into the engine up front, off the synthesis
	 *  thread, each to the volumes it belongs in. Stops early if the
	 *  engine moved on underneath it or the eager bound is reached; the
	 *  lazy path teaches whatever is still missing when it is spoken. */
	private fun teachAllWords(target: EvvEngine) {
		val entries = wordMaps[target.language] ?: return
		val mine = entries.values.flatten()
		Thread({
			var calls = 0
			for (entry in mine) {
				if (engine !== target) return@Thread
				if (calls >= MAX_EAGER_TEACH) return@Thread
				val say = if (entry.say.startsWith("[") && entry.say.endsWith("]") && !entry.say.startsWith("`[")) {
					"`${entry.say}"
				} else {
					entry.say
				}
				for (volume in teachVolumes(entry.volume)) {
					val key = volume to entry.key
					if (key in taughtWords) continue
					try {
						val rc = target.teachWord(volume, entry.key, say)
						taughtWords.add(key)
						if (rc != 0) Log.e(TAG, "volume $volume refused ${entry.key}")
					} catch (failed: Exception) {
						Log.e(TAG, "volume $volume would not take ${entry.key}", failed)
						taughtWords.add(key)
					}
					calls++
					if (calls % 50 == 0) Thread.yield()
				}
			}
		}, "evvdroid-teach").apply { isDaemon = true }.start()
	}

	/** True when [say] is Eloquence inline phonemes (backtick form), which
	 *  must go through teachWord — never into the text stream (that broke
	 *  hetero roots and words like produce / persona). */
	private fun isPhonemeSay(say: String): Boolean =
		say.indexOf('`') >= 0 || (say.startsWith("[") && say.endsWith("]"))

	/** Prefer exact-case dict hits; fall back to case-insensitive. Prefer
	 *  MAIN over ROOT when several volumes match the same word. */
	private fun lookupEntries(language: Int, word: String): List<DictEntry> {
		val entries = wordMaps[language] ?: return emptyList()
		val exact = entries[word] ?: entries[word + "."]
		if (exact != null) return exact
		val byLower = wordMapsLower[language] ?: return emptyList()
		return byLower[word.lowercase()]
			?: byLower[word.lowercase() + "."]
			?: emptyList()
	}

	/** Plain respelling only: rewrite the spoken text for first-pass accuracy.
	 *  Phoneme entries are never inlined — teachWord handles those. */
	private fun applyRespellings(language: Int, text: String): String {
		if (text.isEmpty() || wordMaps.isEmpty()) return text
		return WORD.replace(text) { match ->
			val word = match.value
			val hits = lookupEntries(language, word)
			if (hits.isEmpty()) return@replace word
			val entry = hits.minByOrNull { it.volume } ?: return@replace word
			if (isPhonemeSay(entry.say)) word else entry.say
		}
	}

	/** Teaches the words in [text] the engine does not know yet. An
	 *  utterance usually teaches nothing, and a new word costs one call:
	 *  a sixty-thousand-word file slows the utterance that first needs a
	 *  word of it, never the one that picks it. */
	private fun teachUnknownWords(target: EvvEngine, text: String) {
		if (wordMaps.isEmpty() || text.isEmpty()) return
		for (raw in WORD.findAll(text)) {
			val word = raw.value
			val hits = lookupEntries(target.language, word)
			if (hits.isEmpty()) continue
			for (entry in hits) {
				val say = if (entry.say.startsWith("[") && entry.say.endsWith("]") && !entry.say.startsWith("`[")) {
					"`${entry.say}"
				} else {
					entry.say
				}
				for (volume in teachVolumes(entry.volume)) {
					val forms = linkedSetOf(entry.key, word)
					for (form in forms) {
						val k = volume to form
						if (k in taughtWords) continue
						try { target.teachWord(volume, form, say) } catch (_: Exception) {}
						taughtWords.add(k)
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
			val text = applyRespellings(target.language, afterRegex)
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
			hold(pace)
		}
		return true
	}

	private fun remember(pace: Pace) {
		if (pace.handedMs() > mostAudioHandedMs) mostAudioHandedMs = pace.handedMs()
	}

	/**
	 * Waits until the audio already handed over has nearly caught up.
	 *
	 * This engine is far faster than the speech it makes -- a second and a
	 * third of audio comes out in twelve milliseconds -- and Android takes
	 * every byte of it without complaint and queues it. So a reader swiping
	 * quickly has five or six items synthesised and queued while the first is
	 * still being spoken, and when it then asks for silence the whole queue
	 * goes, which is items that were never heard at all. Running no further
	 * ahead than [LEAD_MS] keeps at most one utterance in flight, so silence
	 * arrives where the reader actually is.
	 *
	 * The wait is in slices so that a stop is noticed at once rather than at
	 * the end of it. The slices are short on purpose: every slice is the
	 * longest a stop can wait unnoticed while audio is being paced.
	 */
	private fun hold(pace: Pace) {
		while (!stopped) {
			val ahead = pace.ahead() - LEAD_MS
			if (ahead <= 0) return
			try {
				Thread.sleep(minOf(ahead, SLICE_MS))
			} catch (stopping: InterruptedException) {
				Thread.currentThread().interrupt()
				return
			}
		}
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
		private val from = System.nanoTime()
		private var bytes = 0L

		fun handed(more: Int) {
			bytes += more
		}

		fun handedMs(): Long = if (bytesPerSecond <= 0) 0 else bytes * 1000L / bytesPerSecond

		fun ahead(): Long = handedMs() - (System.nanoTime() - from) / 1_000_000L
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
		/** How much audio may sit ahead of the speech, so that the track never
		 *  runs dry while the engine is held back. */
		const val LEAD_MS = 100L
		const val SLICE_MS = 20L
	}
}
