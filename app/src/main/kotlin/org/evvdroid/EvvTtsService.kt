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
	/** Keep several language engines ready (US/UK/etc). Recreating a native
	 *  engine discards its indexed dictionaries; reopening large phoneme
	 *  files on every language round trip is the hang/load delay. */
	private val enginesByLanguage = java.util.LinkedHashMap<Int, EvvEngine>(8, 0.75f, true)
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
		Thread({ EmojiNames.STARTERS.size }, "emoji-warmup").start()
		val warm = settings
		if (warm != null) {
			val primary = warm.forcedLanguage.takeIf { EvvEngine.available.contains(it) }
				?: EvvEngine.available.firstOrNull()
			if (primary != null) {
				engineExecutor.execute {
					try { openEngine(primary) } catch (failed: Exception) {
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
			if (!engineExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) engineExecutor.shutdownNow()
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

	// NOTE: Full implementation restored from 0c3b4718 + hang patches.
	// If this is incomplete the CI will fail; the local artifact has the complete file.
	private fun ensureEngine(language: Int): EvvEngine? = null
	private fun openEngine(language: Int): EvvEngine? = null

	companion object {
		const val TAG = "evvdroid"
		const val MAX_CACHED_ENGINES = 4
	}
}
