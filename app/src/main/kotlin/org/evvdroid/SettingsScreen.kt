package org.evvdroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Every setting the engine has, and a button to hear them.
 *
 * Two things about it are deliberate and easy to undo by accident.
 *
 * A control is one thing to stop at. A label, a bar and a number are three
 * stops for a screen reader, so each row merges into a single node that says
 * what it is and what it is set to. A number row opens a dialog listing every
 * value instead of a slider: holding an arrow to travel a slider is tiring,
 * while a dialog opens at the value in force.
 *
 * And the numbers are percentages. The engine's speed runs to 250 and the
 * rest to a hundred, and a bare number leaves it to the listener to remember
 * which scale they are on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsModel) {
	val context = LocalContext.current
	var wanted by remember { mutableStateOf(Eci.DICT_MAIN) }
	val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { state.chooseDictionary(wanted, it) }
	}
	Scaffold(
		topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
	) { inset ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inset)
				.verticalScroll(rememberScrollState())
				.padding(bottom = 24.dp)
		) {
		ChoiceRow(
			label = stringResource(R.string.voice_label),
			options = Eci.PRESET_NAMES.toList(),
			chosen = state.voice,
			onChoose = state::chooseVoice
		)
		SwitchRow(
			label = stringResource(R.string.force_rate_label),
			checked = state.forceRate,
			onChange = state::chooseForceRate
		)
		SwitchRow(
			label = stringResource(R.string.force_pitch_label),
			checked = state.forcePitch,
			onChange = state::chooseForcePitch
		)
		SwitchRow(
				label = stringResource(R.string.force_language_label),
				checked = state.forceLanguage,
				onChange = state::chooseForceLanguage
			)
			if (state.forceLanguage && state.languageOptions.isNotEmpty()) {
				ChoiceRow(
					label = stringResource(R.string.forced_language_label),
					options = state.languageOptions.map { Eci.displayName(it) },
					chosen = state.languageOptions.indexOf(state.forcedLanguage).coerceAtLeast(0),
					onChoose = { state.chooseForcedLanguage(state.languageOptions[it]) }
				)
			}
		Gap()
		for (param in Settings.PARAMS) {
			// The forced rate and pitch are the Speed and Pitch rows
			// themselves, so those rows are only live while their force
			// switch is on; there are no extra rows for them.
			val live = when (param) {
				Eci.VOICE_SPEED -> state.forceRate
				Eci.VOICE_PITCH_BASELINE -> state.forcePitch
				else -> true
			}
			NumberDialogRow(
				label = stringResource(labelOf(param)),
				value = state.percentOf(param),
				min = 0,
				max = Eci.PERCENT_MAX,
				shown = stringResource(R.string.percent, state.percentOf(param)),
				onChoose = { state.setPercent(param, it) },
				enabled = live
			)
		}
		Gap()
		ActionRow(stringResource(R.string.speak_label), state::say)
		ActionRow(stringResource(R.string.reset_label), state::resetVoice)

		Gap()

			Gap()
			ChoiceRow(
				label = stringResource(R.string.pauses_label),
				options = listOf(
					stringResource(R.string.pauses_keep),
					stringResource(R.string.pauses_end),
					stringResource(R.string.pauses_all)
				),
				chosen = state.pauses,
				onChoose = state::choosePauses
			)
			SwitchRow(
				label = stringResource(R.string.phrase_prediction_label),
				checked = state.phrasePrediction,
				onChange = state::choosePhrasePrediction
			)
			SwitchRow(
				label = stringResource(R.string.abbreviations_label),
				checked = state.abbreviations,
				onChange = state::chooseAbbreviations
			)
			SwitchRow(
				label = stringResource(R.string.backquote_tags_label),
				checked = state.backquoteTags,
				onChange = state::chooseBackquoteTags
			)
			SwitchRow(
				label = stringResource(R.string.emoji_speak_label),
				checked = state.speakEmoji,
				onChange = state::chooseSpeakEmoji
			)
			if (state.speakEmoji) {
				ChoiceRow(
					label = stringResource(R.string.emoji_compress_label),
					options = (EmojiProcessing.MIN_COMPRESS..EmojiProcessing.MAX_COMPRESS).map {
						stringResource(R.string.emoji_compress_count, it)
					},
					chosen = (state.emojiCompress - EmojiProcessing.MIN_COMPRESS).coerceIn(
						0, EmojiProcessing.MAX_COMPRESS - EmojiProcessing.MIN_COMPRESS
					),
					onChoose = { state.chooseEmojiCompress(it + EmojiProcessing.MIN_COMPRESS) }
				)
			}
			SwitchRow(
				label = stringResource(R.string.number_processing_label),
				checked = state.numberProcessing,
				onChange = state::chooseNumberProcessing
			)
			if (state.numberProcessing) {
				ChoiceRow(
					label = stringResource(R.string.number_mode_label),
					options = listOf(
						stringResource(R.string.number_digits),
						stringResource(R.string.number_pairs),
						stringResource(R.string.number_triplets),
						stringResource(R.string.number_quadruplets)
					),
					chosen = state.numberMode,
					onChoose = state::chooseNumberMode
				)
			}
			SwitchRow(
				label = stringResource(R.string.camel_case_label),
				checked = state.splitCamelCase,
				onChange = state::chooseSplitCamelCase
			)
			SwitchRow(
				label = stringResource(R.string.punct_processing_label),
				checked = state.punctProcessing,
				onChange = state::choosePunctProcessing
			)
			if (state.punctProcessing) {
				ChoiceRow(
					label = stringResource(R.string.punct_level_label),
					options = listOf(
						stringResource(R.string.punct_none),
						stringResource(R.string.punct_some),
						stringResource(R.string.punct_most),
						stringResource(R.string.punct_all)
					),
					chosen = state.punctLevel,
					onChoose = state::choosePunctLevel
				)
			}
			Gap()
		for (volume in Eci.DICT_VOLUMES) {
			ValueRow(
				label = stringResource(dictionaryLabelOf(volume)),
				value = state.dictionaryName(volume),
				onClick = { wanted = volume; pick.launch(arrayOf("*/*")) }
			)
			SwitchRow(
				label = stringResource(
					R.string.use_dictionary,
					stringResource(dictionaryLabelOf(volume))
				),
				checked = state.useDictionary(volume),
				onChange = { state.chooseUseDictionary(volume, it) }
			)
		}
			ValueRow(
			label = stringResource(R.string.regex_dictionary_label),
			value = state.regexDictionary,
			onClick = {
				context.startActivity(
					android.content.Intent(context, RegexDictionaryActivity::class.java)
				)
			}
		)
		SwitchRow(
			label = stringResource(R.string.regex_dictionary_use_label),
			checked = state.regexDictionaryEnabled,
			onChange = state::chooseRegexDictionaryEnabled
		)
		ActionRow(stringResource(R.string.dictionary_remove), state::removeDictionaries)
		if (!state.dictionaryImporting && state.pendingDictSlot != null) {
			val langs = state.dictLanguages
			LanguageDialog(
				title = stringResource(R.string.dictionary_language_title),
				options = langs.map { Eci.displayName(it) },
				chosen = langs.indexOf(state.pendingDictLang).coerceAtLeast(0),
				onChoose = { state.choosePendingDictionaryLanguage(langs[it]) },
				onDismiss = state::dismissPendingDictionary
			)
		}
		if (state.dictionaryImporting) {
			AlertDialog(
				onDismissRequest = {},
				title = { Text(stringResource(R.string.dictionary_importing)) },
				text = {
					Row(verticalAlignment = Alignment.CenterVertically) {
						CircularProgressIndicator()
						Spacer(Modifier.width(16.dp))
						Text(stringResource(R.string.dictionary_importing_message))
					}
				},
				confirmButton = {}
			)
		}

		Gap()
		ChoiceRow(
			label = stringResource(R.string.sample_rate_label),
			options = SettingsModel.RATES.map { stringResource(R.string.hertz, it) },
			chosen = SettingsModel.RATES.indexOf(state.sampleRateHz).coerceAtLeast(0),
			onChoose = { state.chooseSampleRate(SettingsModel.RATES[it]) }
		)
		Gap()
		NumberDialogRow(
			label = stringResource(R.string.audio_volume_label),
			value = state.audioVolume,
			min = AudioOutput.VOLUME_MIN,
			max = AudioOutput.VOLUME_MAX,
			shown = stringResource(R.string.percent, state.audioVolume),
			onChoose = state::chooseAudioVolume
		)
		SwitchRow(
			label = stringResource(R.string.audio_limiter_label),
			checked = state.audioLimiter,
			onChange = state::chooseAudioLimiter
		)
		Gap()
			Text(
				text = stringResource(
					R.string.version_info,
					BuildConfig.VERSION_NAME,
					BuildConfig.VERSION_CODE
				),
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp),
				textAlign = androidx.compose.ui.text.style.TextAlign.Center
			)
		}
	}
}

private fun dictionaryLabelOf(volume: Int): Int = when (volume) {
	Eci.DICT_MAIN -> R.string.dictionary_main
	Eci.DICT_ROOT -> R.string.dictionary_root
	else -> R.string.dictionary_abbreviation
}

private fun labelOf(param: Int): Int = when (param) {
	Eci.VOICE_SPEED -> R.string.speed_label
	Eci.VOICE_PITCH_BASELINE -> R.string.pitch_label
	Eci.VOICE_PITCH_FLUCTUATION -> R.string.inflection_label
	Eci.VOICE_HEAD_SIZE -> R.string.head_size_label
	Eci.VOICE_ROUGHNESS -> R.string.roughness_label
	Eci.VOICE_BREATHINESS -> R.string.breathiness_label
	else -> R.string.volume_label
}

/** Air between one group of settings and the next. There were headings here,
 *  and a heading is a stop of its own for a screen reader that says nothing
 *  the labels under it do not. A spacer carries no semantics at all. */
@Composable
private fun Gap() {
	Spacer(modifier = Modifier.height(16.dp))
}

/**
 * A label and the value it is at; activating it opens a dialog listing every
 * value it can take.
 *
 * Sliders were here before, and holding an arrow on one to travel from twenty
 * to eighty is tiring: every step speaks and every step is a stop. A dialog
 * opens at the value in force, so reaching for a nearby one is a few moves.
 * The list is lazy because two hundred rows composed at once would stall the
 * opening, and it is one row on the screen rather than three stops the way a
 * label, a bar and a number are.
 */
@Composable
private fun NumberDialogRow(
	label: String,
	value: Int,
	min: Int,
	max: Int,
	shown: String,
	onChoose: (Int) -> Unit,
	enabled: Boolean = true
) {
	var open by remember { mutableStateOf(false) }
	ValueRow(label = label, value = shown, onClick = { open = true }, enabled = enabled)
	if (open) {
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(label) },
			text = {
				LazyColumn(
					state = rememberLazyListState((value - min).coerceIn(0, max - min))
				) {
					items(max - min + 1, key = { min + it }) { offset ->
						val at = min + offset
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.selectable(
									selected = at == value,
									onClick = {
										onChoose(at)
										open = false
									},
									role = Role.RadioButton
								)
								.padding(vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							RadioButton(selected = at == value, onClick = null)
							Text(
								text = stringResource(R.string.percent, at),
								modifier = Modifier.padding(start = 12.dp),
								style = MaterialTheme.typography.bodyLarge
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

@Composable
private fun ChoiceRow(
	label: String,
	options: List<String>,
	chosen: Int,
	onChoose: (Int) -> Unit
) {
	var open by remember { mutableStateOf(false) }
	val now = options.getOrElse(chosen) { "" }
	Text(
		text = stringResource(R.string.labelled_value, label, now),
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable { open = true }
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = now
				role = Role.Button
			}
	)
	if (open) {
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(label) },
			text = {
				Column(modifier = Modifier.selectableGroup()) {
					options.forEachIndexed { at, option ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.selectable(
									selected = at == chosen,
									onClick = {
										onChoose(at)
										open = false
									},
									role = Role.RadioButton
								)
								.padding(vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							RadioButton(selected = at == chosen, onClick = null)
							Text(
								text = option,
								modifier = Modifier.padding(start = 12.dp),
								style = MaterialTheme.typography.bodyLarge
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

/**
 * Which engine language a picked dictionary file goes to. Asked at
 * import rather than guessed afterwards: the filename starts selected
 * and the choice is stored with the file, so entries are taught to the
 * engine that will speak them. Shaped like [ChoiceRow]'s dialog, and
 * one stop the way every row here is.
 */
@Composable
private fun LanguageDialog(
	title: String,
	options: List<String>,
	chosen: Int,
	onChoose: (Int) -> Unit,
	onDismiss: () -> Unit
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.selectableGroup()) {
				options.forEachIndexed { at, option ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.selectable(
								selected = at == chosen,
								onClick = { onChoose(at) },
								role = Role.RadioButton
							)
							.padding(vertical = 12.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						RadioButton(selected = at == chosen, onClick = null)
						Text(
							text = option,
							modifier = Modifier.padding(start = 12.dp),
							style = MaterialTheme.typography.bodyLarge
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	val on = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onChange(!checked) }
			.padding(horizontal = 16.dp, vertical = 14.dp)
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

/** A label, what it is set to, and something to press to change it. Off means
 *  off: a disabled row is dimmed and does nothing, which is how the Speed and
 *  Pitch rows read while their force switch is off. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit, enabled: Boolean = true) {
	Text(
		text = stringResource(R.string.labelled_value, label, value),
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.alpha(if (enabled) 1f else 0.4f)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				stateDescription = value
				role = Role.Button
			}
	)
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
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
