package org.evvdroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The whole app is dark, always: darker surfaces with lighter text, in one
 * place so no screen can drift light. Backgrounds go near-black with white
 * on them; everything else is Material's own dark scheme, whose pairs were
 * measured together, so contrast is kept rather than mixed up.
 */
private val DarkColors = darkColorScheme(
	background = Color(0xFF101010),
	onBackground = Color(0xFFFFFFFF),
	surface = Color(0xFF1A1A1A),
	onSurface = Color(0xFFFFFFFF)
)

@Composable
fun EvvdroidTheme(content: @Composable () -> Unit) {
	MaterialTheme(colorScheme = DarkColors) {
		Surface(content = content)
	}
}
