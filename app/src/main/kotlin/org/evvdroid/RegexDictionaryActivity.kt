package org.evvdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** The regex dictionary's entries, reached from the settings screen.
 *
 *  Listing, adding and deleting live here rather than on the settings
 *  screen itself: entries can number in the hundreds, and a settings screen
 *  is one stop per setting while this is a list to work through.
 */
class RegexDictionaryActivity : ComponentActivity() {

	private var model: SettingsModel? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val state = SettingsModel(this).also { model = it }
		setContent {
			EvvdroidTheme {
				RegexEditorScreen(state)
			}
		}
	}

	override fun onStop() {
		super.onStop()
		model?.stopSpeaking()
	}

	override fun onDestroy() {
		super.onDestroy()
		model?.close()
		model = null
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexEditorScreen(state: SettingsModel) {
	val context = LocalContext.current
	val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { state.chooseRegexDictionary(it) }
	}
	val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		val from = state.regexFile()
		if (uri != null && from != null) {
			runCatching {
				context.contentResolver.openOutputStream(uri)?.use { sink ->
					from.inputStream().use { source -> source.copyTo(sink) }
				}
			}
		}
	}
	var adding by remember { mutableStateOf(false) }
	var actingAt by remember { mutableStateOf<Int?>(null) }
	var editingAt by remember { mutableStateOf<Int?>(null) }
	var deleting by remember { mutableStateOf<RegexDictionaries.RawEntry?>(null) }
	var clearing by remember { mutableStateOf(false) }
	Scaffold(
		topBar = { TopAppBar(title = { Text(stringResource(R.string.regex_editor_title)) }) }
	) { inset ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inset)
				.verticalScroll(rememberScrollState())
				.padding(bottom = 24.dp)
		) {
			EditorActionRow(stringResource(R.string.regex_add_label)) { adding = true }
			state.regexEntries.forEachIndexed { at, entry ->
				EntryRow(
					text = "${entry.pattern} → ${entry.replacement}",
					openDescription = stringResource(
						R.string.regex_entry_open,
						entry.pattern,
						entry.replacement
					),
					onOpen = { actingAt = at }
				)
			}
			EditorActionRow(stringResource(R.string.regex_import_label)) {
				import.launch(arrayOf("*/*"))
			}
			if (state.regexEntries.isNotEmpty()) {
				EditorActionRow(stringResource(R.string.regex_export_label)) {
					export.launch(exportName(state))
				}
				EditorActionRow(stringResource(R.string.regex_remove_all)) {
					clearing = true
				}
			}
		}
	}
	if (adding) {
		AddPatternDialog(
			title = stringResource(R.string.regex_add_label),
			onDismiss = { adding = false },
			onSave = { pattern, replacement, matchCase ->
				if (state.addRegexEntry(pattern, replacement, matchCase)) {
					adding = false
					true
				} else {
					false
				}
			}
		)
	}
	actingAt?.let { at ->
		val entry = state.regexEntries.getOrNull(at)
		if (entry != null) {
			Dialog(
				onDismissRequest = { actingAt = null }
			) {
				Surface(shape = MaterialTheme.shapes.medium) {
					Column(modifier = Modifier.padding(vertical = 8.dp)) {
						EditorActionRow(stringResource(R.string.regex_edit)) {
							editingAt = at
							actingAt = null
						}
						EditorActionRow(stringResource(R.string.regex_delete)) {
							deleting = entry
							actingAt = null
						}
						EditorActionRow(stringResource(R.string.cancel)) {
							actingAt = null
						}
					}
				}
			}
		}
	}
	editingAt?.let { at ->
		val entry = state.regexEntries.getOrNull(at)
		if (entry != null) {
			val folded = entry.pattern.startsWith("(?i)")
			AddPatternDialog(
				title = stringResource(R.string.regex_edit_label),
				initialPattern = if (folded) entry.pattern.removePrefix("(?i)") else entry.pattern,
				initialReplacement = entry.replacement,
				initialMatchCase = !folded,
				onDismiss = { editingAt = null },
				onSave = { pattern, replacement, matchCase ->
					if (state.editRegexEntry(at, pattern, replacement, matchCase)) {
						editingAt = null
						true
					} else {
						false
					}
				}
			)
		}
	}
	if (clearing) {
		AlertDialog(
			onDismissRequest = { clearing = false },
			title = { Text(stringResource(R.string.regex_remove_all_title)) },
			text = {
				Text(
					stringResource(
						R.string.regex_dictionary_entries,
						state.regexDictionary.substringBefore(", ").trim(),
						state.regexEntries.size
					)
				)
			},
			confirmButton = {
				TextButton(onClick = {
					state.clearRegexEntries()
					clearing = false
				}) {
					Text(stringResource(R.string.regex_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { clearing = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
	deleting?.let { gone ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			title = { Text(stringResource(R.string.regex_delete_title)) },
			text = { Text("${gone.pattern} → ${gone.replacement}") },
			confirmButton = {
				TextButton(onClick = {
					val at = state.regexEntries.indexOf(gone)
					if (at >= 0) state.removeRegexEntryAt(at)
					deleting = null
				}) {
					Text(stringResource(R.string.regex_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { deleting = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

/** A suggested file name for the export: whatever the dictionary is called
 *  on the settings screen, without its count. */
private fun exportName(state: SettingsModel): String {
	val shown = state.regexDictionary.substringBefore(", ").trim()
	return shown.ifEmpty { "regex.dic" }
}

/** A label and something to press, as one stop for a screen reader. */
@Composable
private fun EditorActionRow(label: String, onClick: () -> Unit) {
	Text(
		text = label,
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				role = Role.Button
			}
	)
}

/** One entry as one stop: what it matches, what it says, and that pressing
 *  offers editing and deleting. */
@Composable
private fun EntryRow(text: String, openDescription: String, onOpen: () -> Unit) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onOpen)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = openDescription
				role = Role.Button
			}
	)
}

@Composable
private fun AddPatternDialog(
	title: String,
	initialPattern: String = "",
	initialReplacement: String = "",
	initialMatchCase: Boolean = false,
	onDismiss: () -> Unit,
	onSave: (String, String, Boolean) -> Boolean
) {
	var pattern by remember(initialPattern) { mutableStateOf(initialPattern) }
	var replacement by remember(initialReplacement) { mutableStateOf(initialReplacement) }
	var matchCase by remember(initialMatchCase) { mutableStateOf(initialMatchCase) }
	var failed by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column {
				OutlinedTextField(
					value = pattern,
					onValueChange = { pattern = it; failed = false },
					label = { Text(stringResource(R.string.regex_pattern_hint)) },
					singleLine = true
				)
				OutlinedTextField(
					value = replacement,
					onValueChange = { replacement = it; failed = false },
					label = { Text(stringResource(R.string.regex_replacement_hint)) },
					singleLine = true
				)
				DialogSwitchRow(
					label = stringResource(R.string.regex_match_case),
					checked = matchCase,
					onChange = { matchCase = it }
				)
				if (failed) {
					Text(
						stringResource(R.string.regex_invalid),
						color = MaterialTheme.colorScheme.error
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { if (!onSave(pattern, replacement, matchCase)) failed = true }) {
				Text(stringResource(R.string.regex_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}

/** A switch as one stop: label, on or off, toggled in place. */
@Composable
private fun DialogSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	val on = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onChange(!checked) }
			.padding(vertical = 8.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = on
				role = Role.Switch
			},
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.weight(1f)
		)
		Switch(checked = checked, onCheckedChange = null)
	}
}
