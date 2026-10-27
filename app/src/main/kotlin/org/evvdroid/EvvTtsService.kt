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
	/** Keep the active language and the last inactive one ready. Recreating a
	 *  native engine discards its indexed dictionaries; reopening a large text
	 *  dictionary on every language round trip is the load delay. */
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
		// Preload the engine + external dictionaries off the synthesis path.
		// With three large phoneme files, indexing on the first speak was what
		// hung TalkBack until force-stop after a screen-reader restart.
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
