package org.evvdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** The engine's own settings, reached from the system's text-to-speech screen
 *  and from the launcher. */
class SettingsActivity : ComponentActivity() {

	private var model: SettingsModel? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val state = SettingsModel(this).also { model = it }
		setContent {
			EvvdroidTheme {
				SettingsScreen(state)
			}
		}
	}

	override fun onStop() {
		super.onStop()
		// Leaving the screen should not leave a sample talking over whatever
		// comes next.
		model?.stopSpeaking()
	}

	override fun onResume() {
		super.onResume()
		// The regex editor is its own screen with its own model, so coming
		// back from it must re-read what it may have changed.
		model?.refreshRegexEntries()
	}

	override fun onDestroy() {
		super.onDestroy()
		model?.close()
		model = null
	}
}
