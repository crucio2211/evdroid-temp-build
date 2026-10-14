# The openevv synthesiser driver.
#
# Turns a speech sequence into text with Eloquence annotations in it, and hands
# that to the engine layer beside this file.
#
# Prosody inside a sentence is said as an annotation rather than by setting a
# parameter, and that is the one design decision here worth explaining. A
# parameter is set on the instance and takes effect for everything queued
# behind it, so a pitch change meant for one word arrives too early. An
# annotation travels inside the text and takes effect where it sits. The
# annotations used are in the engine's own test cases and are known to match
# IBM's byte for byte, so this is the path with evidence behind it.

import os
import re
from collections import OrderedDict

from autoSettingsUtils.driverSetting import (
	BooleanDriverSetting,
	DriverSetting,
	NumericDriverSetting,
)
from autoSettingsUtils.utils import StringParameterInfo
from logHandler import log
from speech.commands import (
	BreakCommand,
	CharacterModeCommand,
	IndexCommand,
	LangChangeCommand,
	PitchCommand,
	RateCommand,
	VolumeCommand,
)
from speech.types import SpeechSequence
from synthDriverHandler import (
	SynthDriver,
	VoiceInfo,
	synthDoneSpeaking,
	synthIndexReached,
)

from . import _openevv

#: What the engine's speed setting is worth at either end of the reader's
#: nought to a hundred. The engine will take nought to two hundred and fifty,
#: but the top of that range is far past intelligible and the bottom is a
#: crawl, so the useful stretch is mapped instead. These two numbers are the
#: ones the IBMTTS driver arrived at by ear on this same engine.
MIN_RATE = 40
MAX_RATE = 156

#: How much further the boost goes, for someone who reads faster than the
#: plain range allows.
RATE_BOOST = 1.6

#: A break is asked for in milliseconds and the engine's pause annotation is
#: not in milliseconds, so the number has to be scaled -- and by how much
#: depends on the speaking rate, since a pause is counted in something closer
#: to syllables. Measured at these five rates and interpolated between them.
BREAK_FACTORS = {10: 1, 43: 2, 60: 3, 75: 4, 85: 5}

#: How far an unpunctuated stretch may run before it is ended at whitespace.
#:
#: No interrupt makes the engine abandon an utterance -- the engine layer
#: beside this file says why -- so asking for silence means waiting for the
#: utterance in flight to finish synthesising into nothing. That is cheap for a line of a list and is not cheap for a
#: chat message of several thousand characters: measured on this engine, one
#: such message costs 0.83 s, and 560 characters of Arabic, which is spelled
#: out character by character, cost 1.44 s. A reader arrowing down a list
#: every 200 ms has every item that lands inside that wait dropped, which is
#: speech going silent for several objects and then catching up.
#:
#: Sentence ends are the usual boundary because the engine already pauses
#: there: one measured message was 252,010 samples whole and the same 252,010
#: samples in six pieces. This larger fallback is for a stretch with no
#: sentence end, such as the 560-character Arabic case above.
PIECE_CAP = 20

#: Where a piece may end: after a run of whitespace, so nothing is split
#: inside a word and no annotation is separated from what it applies to.
_BOUNDARY = re.compile(r"(\s+)")

#: Closing marks which may follow sentence-final punctuation.
_CLOSERS = "\"')]}\N{RIGHT-POINTING DOUBLE ANGLE QUOTATION MARK}\N{RIGHT DOUBLE QUOTATION MARK}\N{RIGHT SINGLE QUOTATION MARK}"

#: Repairs for the engine's own text-processing faults. The
#: engine's rules fault on certain strings -- a crash word, a date
#: it reads wrongly, a time it stops short of -- and these are the
#: repairs the IBMTTS driver arrived at for this same engine. They
#: are matched against the bytes a language reads, so a code set's
#: own bytes are what is seen, and are applied after the reader's
#: own backquote processing, so that a repair may say an
#: annotation of its own.
time_re = re.compile(br"(\d):(\d+):(\d+)")

english_fixes = {
	# A dictionary entry carrying the Mc prefix, which NVDA splits
	# up, crashes the engine.
	re.compile(br"\b(Mc)\s+([A-Z][a-z]|[A-Z][A-Z]+)"): br"\1\2",
	# The date parser. Without this, "03 Marble" is read as
	# "march threerd ble".
	re.compile(br"\b(\d+) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)([a-z]+)"): br"\1  \2\3",
	# UK formatted dates.
	re.compile(br"\b(\d+)  (January|February|March|April|May|June|July|August|September|October|November|December)\b"): br"\1 \2",
	# Crash words.
	re.compile(br'c(ae|\xe6)sur(e)?', re.I): br'seizur',
	re.compile(br"\b(|\d+|\W+)h'(r|v)[e]", re.I): br"\1h \2e",
	re.compile(br"\b(\w+[bdfhjlmnqrvyz])(h[he]s)([abcdefghjklmnopqrstvwy]\w+)\b", re.I): br"\1 \2\3",
	re.compile(br"\b(\w+[bdfhjlmnqrvyz])(h[he]s)(iron+[degins]?)", re.I): br"\1 \2\3",
	re.compile(br"\b(\w+'{1,}[bcdfghjklmnpqrstvwxyz])'*(h+[he]s)([abcdefghijklmnopqrstvwy]\w+)\b", re.I): br"\1 \2\3",
	re.compile(br"\b(\w+[bcdfghjklmnpqrstvwxyz])('{1,}h+[he]s)([abcdefghijklmnopqrstvwy]\w+)\b", re.I): br"\1 \2\3",
	re.compile(br"(\d):(\d\d[snrt][tdh])", re.I): br"\1 \2",
	re.compile(br"\b([bcdfghjklmnpqrstvwxz]+)'([bcdefghjklmnpqrstvwxz']+)'([drtv][aeiou]?)", re.I): br"\1 \2 \3",
	re.compile(br"\b(you+)'(re)+'([drv]e?)", re.I): br"\1 \2 \3",
	re.compile(br"(re|un|non|anti)cosp", re.I): br"\1kosp",
	re.compile(br"(EUR[A-Z]+)(\d+)", re.I): br"\1 \2",
	re.compile(br"\b(\d+|\W+)?(\w+\_+)?(\_+)?([bcdfghjklmnpqrstvwxz]+)?(\d+)?t+z[s]che", re.I): br"\1 \2 \3 \4 \5 tz sche",
	re.compile(br"(juar)([a-z']{9,})", re.I): br"\1 \2",
}
spanish_fixes = {
	# Euros.
	re.compile(b'([\x80$]\\d{1,3})((\\s\\d{3})+\\.\\d{2})'): br'\1 \2',
	# Numbers separated by a space are spoken together unless an
	# extra space says otherwise, as with a three-digit second
	# number: "3 456".
	re.compile(b'(\\d+) (\\d{3})'): br'\1  \2',
	# The ordinal feminine of thirteen or more digits ending in
	# 1, 2, 3, 6, 7 or 9.
	re.compile(br'(\d{12,}[123679])(\xaa)'): br'\1 \2',
}
french_fixes = {
	# "n°", which is otherwise read out as a number.
	re.compile(br'\bn\xb0', re.I): b'num\xe9ro',
	# "quil" faults the engine, though what it should sound like
	# depends on the context.
	re.compile(br'(?<=anq)uil(?=l)', re.I): br'i',
	re.compile(br'quil(?=\W)', re.I | re.L): br'kil',
	# Function keys, "f1" to "f12", read as "1 franc" and so on.
	re.compile(br'f(?=\s?\d)', re.I | re.L): br'f ',
	# A capitalised Roman numeral followed by "e" or "eme", which
	# NVDA splits: "IIIe" becomes "II Ie".
	re.compile(br'\b([CDILMVX]+)(\s?)([CDILMVX]e)(me)?\b'): b'\\1\\3',
	# A right parenthesis inside a word, always spoken whatever
	# the punctuation level.
	re.compile(br'(\w\))(?=\w)', re.I | re.L): br'\1 ',
	re.compile(br'(n\s?vda)', re.I): b' \xe8nv\xe9d\xe9a ',
	# A "y" that is sometimes lost entirely.
	re.compile(br'([ou])y([bnp])', re.I): br"\1i\2",
}
german_fixes = {
	# Crash words, each given the pause the engine wanted in the
	# first place.
	re.compile(br'dane-ben', re.I): br'dane `0 ben',
	re.compile(br'dage-gen', re.I): br'dage `0 gen',
	re.compile(br'(audio|macro|video)(-)(en[a-z]+)', re.I): br'\1 `0 \3',
}

#: The repairs for each of the engine's languages, by its number.
#: Only the languages written in the Western code set are here,
#: since a repair matched against another code set's bytes would be
#: meaningless at best.
_CRASH_FIXES = {
	0x10000: english_fixes,
	0x10001: english_fixes,
	0x20000: spanish_fixes,
	0x20001: spanish_fixes,
	0x30000: french_fixes,
	0x30001: french_fixes,
	0x40000: german_fixes,
}


def resub(dct, s):
	"""Every repair in a set, applied in turn."""
	for pattern in dct:
		s = pattern.sub(dct[pattern], s)
	return s


def _endsClause(text):
	t = text.rstrip()
	if not t: return False
	while t and t[-1] in _CLOSERS: t = t[:-1]
	return bool(t) and t[-1] in ",;:—"

def _endsSentence(text):
	"""Whether a word ends a sentence rather than a dotted number.

	A dot is the doubtful one and the doubt is not symmetrical. A boundary the
	engine would not have made costs an audible pause -- it ends a clause there,
	and that is 0.40 s of gap -- while a boundary declined costs only a longer
	wait on the next cancel. So a dot has to argue for itself.

	An abbreviation and an initial are what it fails on. "Mr. Jones" split after
	the dot measured 0.70 s longer than the same sentence whole, and "J. R. R.
	Tolkien" split at every initial measured 1.48 s longer than 3.72, which is
	nearly half again. Two tests are enough for both, in any of the nine
	languages: a word carrying a dot inside it is an abbreviation rather than a
	sentence -- "e.g.", "i.e.", "U.S." -- and so is a short word that starts with
	a capital, which is every initial and every "Mr.", "Mrs.", "Dr.", "St." and
	"Nr." there is. What that turns down as well is a short capitalised sentence
	end such as "Yes.", and turning one of those down costs nothing but a piece
	that runs to the next sentence.
	"""
	tail = text.rstrip(_CLOSERS)
	if not tail:
		return False
	if tail.endswith(("?", "!", "\N{HORIZONTAL ELLIPSIS}", "\N{IDEOGRAPHIC FULL STOP}", "\N{FULLWIDTH EXCLAMATION MARK}", "\N{FULLWIDTH QUESTION MARK}")):
		return True
	if not tail.endswith(".") or len(tail) < 2 or tail[-2].isdigit():
		return False
	word = tail[:-1]
	return "." not in word and not (len(word) <= 3 and word[:1].isupper())


class SynthDriver(SynthDriver):
	name = "openevv"
	description = "Eloquence (openevv)"

	supportedSettings = (
		SynthDriver.VoiceSetting(),
		SynthDriver.VariantSetting(),
		SynthDriver.RateSetting(),
		SynthDriver.RateBoostSetting(),
		SynthDriver.PitchSetting(),
		SynthDriver.InflectionSetting(),
		SynthDriver.VolumeSetting(),
		# Translators: Label for a setting in voice settings dialog.
		NumericDriverSetting("headSize", _("Hea&d size"), False),
		# Translators: Label for a setting in voice settings dialog.
		NumericDriverSetting("roughness", _("Rou&ghness"), False),
		# Translators: Label for a setting in voice settings dialog.
		NumericDriverSetting("breathiness", _("Breathi&ness"), False),
		# Translators: Label for a setting in voice settings dialog.
		BooleanDriverSetting("abbreviations", _("Expand a&bbreviations"), False),
		# Translators: Label for a setting in voice settings dialog.
		BooleanDriverSetting("voiceTags", _("Allow backquote voice &tags"), False),
		# Translators: Label for a setting in voice settings dialog.
		DriverSetting("samplerate", _("Sa&mple rate"), False),
		# Translators: Label for a setting in voice settings dialog.
		DriverSetting("volumeBoost", _("Volume boos&ter"), False),
	)

	supportedCommands = {
		IndexCommand,
		CharacterModeCommand,
		LangChangeCommand,
		BreakCommand,
		PitchCommand,
		RateCommand,
		VolumeCommand,
	}
	supportedNotifications = {synthIndexReached, synthDoneSpeaking}

	@classmethod
	def check(cls):
		return os.path.isfile(_openevv.libraryPath())

	def __init__(self):
		self._rateBoost = False
		self._abbreviations = False
		self._voiceTags = False
		self._volumeBoost = "0"
		self._engine = _openevv.Engine(self._onIndexReached)
		self._engine.open()
		self._voice = str(self._engine.language)
		self._variant = str(_openevv.VOICE_FIRST)
		log.debug("openevv: engine version %s" % self._engine.version)

	def terminate(self):
		self._engine.close()

	# ---- speaking ----------------------------------------------------

	def speak(self, speechSequence: SpeechSequence):
		engine = self._engine
		#: The pieces to hand over, in order. Nearly every utterance a screen
		#: reader says is one piece; a long one is several, so that asking for
		#: silence waits out a piece and not the whole of it.
		pieces = []
		batch = []
		text = []
		spelling = False
		#: Prosody annotations which have not yet been restored to the reader's
		#: configured value. An opening and its restore have to stay in one queue
		#: item, or a cancel between them leaks the change into later speech.
		prosody = set()
		#: Which language the text being built is in, since a sequence may
		#: change it more than once and each change is against the last. A
		#: sequence starts in the reader's own, because the one before put it
		#: back.
		home = self._home()
		speaking = home
		switched = False
		#: Whether anything in this sequence is meant to make a sound. A
		#: sequence of nothing but commands is silent because it should be, and
		#: the engine layer is told so rather than complaining about it.
		words = False

		#: What this piece has to say, and how much has gathered in it. A piece
		#: is what a cancel waits for, so it is closed at the first boundary
		#: past the limit rather than at the limit exactly.
		saying = False
		gathered = 0
		sentence = False
		clause = False

		def flush():
			if text:
				joined = _openevv.textFor(speaking, "".join(text))
				joined = self._anticrash(joined, speaking)
				batch.append((engine.addText, (joined,)))
				del text[:]

		def endPiece():
			"""Close the piece being gathered, if it has anything in it."""
			nonlocal batch, saying, gathered
			flush()
			if batch:
				pieces.append((batch, saying))
				batch = []
			saying = False
			gathered = 0

		def mayEndPiece():
			# Voice tags are arbitrary annotations supplied inside the text; when
			# enabled their state cannot be inferred here, so retain the old single
			# queue item just as for a command whose state is visibly open.
			return not spelling and not prosody and not self._voiceTags

		for item in speechSequence:
			if isinstance(item, str):
				said = self._processText(item)
				words = words or said.strip() != ""
				# Sentence ends are free boundaries because the engine already pauses
				# there. Whitespace after the much larger cap bounds a sentence which
				# has no end of its own.
				for part in _BOUNDARY.split(said):
					if not part:
						continue
					text.append(part)
					gathered += len(part)
					if part.strip():
						saying = True
						sentence = _endsSentence(part)
						clause = _endsClause(part)
					else:
						if mayEndPiece() and (sentence or clause or gathered >= PIECE_CAP):
							endPiece()
						sentence = False
						clause = False
			elif isinstance(item, IndexCommand):
				# An index has to sit between stretches of text rather than
				# inside one, so what has been gathered goes first.
				flush()
				batch.append((engine.index, (item.index,)))
			elif isinstance(item, CharacterModeCommand):
				# What the last such command asked for, so that spelling left
				# open at the end of the sequence is closed once and spelling
				# already closed is not closed again.
				spelling = item.state
				# On its own, not on the end of a stretch of text. An
				# annotation with nothing after it in the same call does not
				# take effect, which is how spelling used to leak out of one
				# utterance and into every one after it.
				flush()
				batch.append((engine.addText, (b"`ts1 " if item.state else b"`ts0 ",)))
			elif isinstance(item, BreakCommand):
				text.append(" `p%d " % self._breakToPause(item.time))
			elif isinstance(item, PitchCommand):
				if item.isDefault:
					prosody.discard(PitchCommand)
				else:
					prosody.add(PitchCommand)
				text.append("`vb%d " % self._pitchToParam(item.newValue))
			elif isinstance(item, RateCommand):
				if item.isDefault:
					prosody.discard(RateCommand)
				else:
					prosody.add(RateCommand)
				text.append("`vs%d " % self._rateToParam(item.newValue))
			elif isinstance(item, VolumeCommand):
				if item.isDefault:
					prosody.discard(VolumeCommand)
				else:
					prosody.add(VolumeCommand)
				text.append("`vv%d " % self._volumeToParam(item.newValue))
			elif isinstance(item, LangChangeCommand):
				# A document saying part of itself is in another language.
				# Where the library has that language it is switched to, in
				# the order the sequence asks for it, so a German quotation
				# in an English page is read as German rather than as
				# English with German spelling.
				#
				# The switch has to be flushed first: it is a call and not an
				# annotation, so text already handed over would otherwise be
				# spoken in the language that came after it. A command naming
				# no language is NVDA asking for the reader's own back.
				language = (self._languageFor(item.lang) if item.lang
				            else home)
				if language is not None and language != speaking:
					flush()
					batch.append((engine.selectLanguage,
					              (language, self._presetNow())))
					speaking = language
					switched = True
			else:
				log.error("openevv: unknown speech: %s" % item)

		flush()
		if spelling:
			batch.append((engine.addText, (b"`ts0 ",)))
		if batch or not pieces:
			pieces.append((batch, saying or not pieces and words))

		# One queue item per piece, so that a cancel drops the pieces that
		# have not started rather than having to wait for them. Only the last
		# reports the utterance finished.
		for position, (piece, expectAudio) in enumerate(pieces):
			last = position == len(pieces) - 1
			piece.append((engine.synthesize if last else engine.synthesizePart, (expectAudio,)))
			engine.post(piece)

		# A document's language is its own and ends with it, whether or not
		# NVDA says so: otherwise everything after a German quotation is
		# German. Sent as a control step, since a cancel throws speech away
		# and this has to arrive whatever was cancelled.
		if switched:
			engine.control([(engine.selectLanguage, (home, self._presetNow()))])

	def _processText(self, text):
		if not self._voiceTags:
			text = text.replace("`", " ")
		return text


	def _anticrash(self, data, language):
		"""Repair the engine's own text-processing faults.

		The repairs are matched against the bytes a language reads, so
		a code set's own bytes are what is seen, and are applied after
		the reader's own backquote processing, so that a repair may say
		an annotation of its own.
		"""
		fixes = _CRASH_FIXES.get(language)
		if fixes:
			data = resub(fixes, data)
		# A time such as 2:30:15 is otherwise read as just 2:30.
		return time_re.sub(br"\1:\2 \3", data)

	def cancel(self):
		self._engine.cancel()

	def pause(self, switch):
		self._engine.pause(switch)

	def _onIndexReached(self, index):
		if index is None:
			synthDoneSpeaking.notify(synth=self)
		else:
			synthIndexReached.notify(synth=self, index=index)

	# ---- turning the reader's numbers into the engine's --------------

	def _rateToParam(self, percent):
		value = self._percentToParam(percent, MIN_RATE, MAX_RATE)
		if self._rateBoost:
			value = int(round(value * RATE_BOOST))
		return min(value, _openevv.VOICE_RANGE[_openevv.VOICE_SPEED][1])

	def _pitchToParam(self, percent):
		return int(percent)

	def _volumeToParam(self, percent):
		return int(percent)

	def _breakToPause(self, milliseconds):
		rates = sorted(BREAK_FACTORS)
		rate = self.rate
		if rate <= rates[0]:
			factor = BREAK_FACTORS[rates[0]]
		elif rate >= rates[-1]:
			factor = BREAK_FACTORS[rates[-1]]
		elif rate in BREAK_FACTORS:
			factor = BREAK_FACTORS[rate]
		else:
			below = [i for i, r in enumerate(rates) if r < rate][-1]
			lo, hi = rates[below], rates[below + 1]
			factor = BREAK_FACTORS[lo] + (BREAK_FACTORS[hi] - BREAK_FACTORS[lo]) * (
				rate - lo
			) / (hi - lo)
		return max(0, int(factor * milliseconds))

	# ---- the settings ------------------------------------------------

	def _get_rate(self):
		value = self._engine.voiceParams.get(_openevv.VOICE_SPEED, 0)
		if self._rateBoost:
			value = int(round(value / RATE_BOOST))
		return self._paramToPercent(value, MIN_RATE, MAX_RATE)

	def _set_rate(self, percent):
		self._post(_openevv.VOICE_SPEED, self._rateToParam(percent))

	def _get_rateBoost(self):
		return self._rateBoost

	def _set_rateBoost(self, enable):
		if enable != self._rateBoost:
			rate = self.rate
			self._rateBoost = enable
			self.rate = rate

	def _get_pitch(self):
		return self._engine.voiceParams.get(_openevv.VOICE_PITCH, 0)

	def _set_pitch(self, value):
		self._post(_openevv.VOICE_PITCH, value)

	def _get_volume(self):
		return self._engine.voiceParams.get(_openevv.VOICE_VOLUME, 0)

	def _set_volume(self, value):
		self._post(_openevv.VOICE_VOLUME, value)

	def _get_inflection(self):
		return self._engine.voiceParams.get(_openevv.VOICE_FLUCTUATION, 0)

	def _set_inflection(self, value):
		self._post(_openevv.VOICE_FLUCTUATION, value)

	def _get_headSize(self):
		return self._engine.voiceParams.get(_openevv.VOICE_HEAD_SIZE, 0)

	def _set_headSize(self, value):
		self._post(_openevv.VOICE_HEAD_SIZE, value)

	def _get_roughness(self):
		return self._engine.voiceParams.get(_openevv.VOICE_ROUGHNESS, 0)

	def _set_roughness(self, value):
		self._post(_openevv.VOICE_ROUGHNESS, value)

	def _get_breathiness(self):
		return self._engine.voiceParams.get(_openevv.VOICE_BREATHINESS, 0)

	def _set_breathiness(self, value):
		self._post(_openevv.VOICE_BREATHINESS, value)

	def _get_abbreviations(self):
		return self._abbreviations

	def _set_abbreviations(self, enable):
		self._abbreviations = enable
		# Nought turns the abbreviation dictionary on, which is the engine's
		# own sense of the setting and not a mistake here.
		self._engine.control(
			[(self._engine.setParam, (_openevv.PARAM_DICTIONARY, 0 if enable else 1))],
		)

	def _get_voiceTags(self):
		return self._voiceTags

	def _set_voiceTags(self, enable):
		self._voiceTags = enable

	def _get_availableSamplerates(self):
		"""What the synthesiser can be run at.

		Not the speaking rate, which NVDA already calls the rate: this is the
		sample rate. Eleven kilohertz is what Eloquence has always sounded like
		and is the default. Eight kilohertz is the narrow telephone voice IBM
		shipped, and sixteen is made by the synthesiser at that rate, so it
		carries more of the top of the voice.
		"""
		return OrderedDict(
			(
				str(hz),
				StringParameterInfo(
					str(hz),
					# Translators: A sample rate, shown in kilohertz.
					_("%.3g kHz") % (hz / 1000.0),
				),
			)
			for _number, hz in _openevv.SAMPLE_RATES
		)

	def _get_samplerate(self):
		return str(self._engine.sampleRate)

	def _set_samplerate(self, value):
		# Through the queue like every other setting, so it lands between
		# utterances: the player is replaced along with the rate and swapping
		# one out from under audio being fed to it is how a reader ends up
		# with no voice.
		try:
			hz = int(value)
		except (TypeError, ValueError):
			log.error("openevv: %r is not a sample rate" % (value,))
			return
		self._engine.control([(self._engine.setSampleRate, (hz,))])

	def _get_availableVolumeboosts(self):
		"""How much louder than the engine makes it the speech can be.

		Applied to the samples on the way to the player, with the loudest
		peaks rounded off rather than cut, so a boost is louder without being
		harsh.
		"""
		names = {
			# Translators: A volume boost level that changes nothing.
			"0": _("Off"),
			# Translators: A volume boost level, one and a half times louder.
			"1": _("1.5x"),
			# Translators: A volume boost level, twice as loud.
			"2": _("2x"),
			# Translators: A volume boost level, three times as loud.
			"3": _("3x"),
		}
		return OrderedDict(
			(level, StringParameterInfo(level, names[level]))
			for level, _gain in _openevv.BOOST_LEVELS
		)

	def _get_volumeBoost(self):
		return self._volumeBoost

	def _set_volumeBoost(self, value):
		if self._engine.setBoost(value):
			self._volumeBoost = str(value)

	def _get_availableVoices(self):
		"""One voice per language.

		The eight presets are not voices here: they are the variants, a
		setting of their own, so that choosing a language and choosing who
		speaks it are two choices and not one list of pairs. The language is
		what a document's own language is matched against.
		"""
		voices = OrderedDict()
		for language in self._engine.languages:
			voices[str(language)] = VoiceInfo(
				str(language),
				_openevv.nameOf(language),
				_openevv.localeOf(language),
			)
		return voices

	def _get_availableVariants(self):
		"""Who speaks it: the eight presets, by the names Eloquence has always
		given them. The same eight in every language."""
		names = self._engine.voiceNamesFor(self._engine.language)
		return OrderedDict(
			(
				str(number),
				StringParameterInfo(
					str(number), _openevv.VARIANT_NAMES.get(number, names[number])
				),
			)
			for number in sorted(names)
		)

	def _languageFor(self, locale):
		"""Which of the library's languages a document's locale means.

		A document says `de' or `de_DE' or `de-AT'; the library has one
		German. So the whole locale is tried first, and then just the
		language part of it, and anything the library does not have answers
		nothing, which leaves the voice where it was.
		"""
		if not locale:
			return None
		want = str(locale).replace("-", "_")
		short = want.split("_")[0].lower()
		loose = None
		for language in self._engine.languages:
			have = _openevv.localeOf(language)
			if have is None:
				continue
			if have.lower() == want.lower():
				return language
			if loose is None and have.split("_")[0].lower() == short:
				loose = language
		return loose

	def _home(self):
		"""The language the reader chose, which a document's own changes of
		language are made from and go back to."""
		try:
			return int(self._voice)
		except (TypeError, ValueError):
			return self._engine.language

	def _presetNow(self):
		"""Which of the eight the reader has chosen, whatever language it was
		chosen in. A language change keeps the preset and changes what it
		sounds like, which is what a person expects of a voice."""
		try:
			return int(self._variant)
		except (TypeError, ValueError):
			return _openevv.VOICE_FIRST

	def _get_voice(self):
		return self._voice

	def _set_voice(self, value):
		"""Choose the language.

		A configuration written before the presets were a setting of their own
		holds a preset, bare or after its language ("65536:4"), and is read as
		the language and the variant it was.
		"""
		language = preset = None
		try:
			text = str(value)
			if ":" in text:
				first, second = text.split(":", 1)
				language, preset = int(first), int(second)
			else:
				number = int(text)
				if number in self._engine.languages:
					language = number
				elif _openevv.VOICE_FIRST <= number <= _openevv.VOICE_LAST:
					preset = number
				else:
					return
		except ValueError:
			return
		if language is not None and language not in self._engine.languages:
			return
		names = self._engine.voiceNamesFor(
			language if language is not None else self._engine.language
		)
		if preset is not None and preset not in names:
			preset = None
		if language is None and preset is None:
			return

		# A language change keeps everything the reader has set, the rate
		# above all: it is the same voice speaking another language. Only
		# who is speaking, if the old configuration named it, is changed.
		batch = []
		if language is not None and language != self._engine.language:
			batch.append((self._engine.selectLanguage,
			              (language, self._presetNow())))
		if preset is not None:
			batch.append((self._engine.selectPreset, (preset,)))
			self._variant = str(preset)
		if language is not None:
			self._voice = str(language)
		if batch:
			self._engine.control(batch)

	def _get_variant(self):
		return self._variant

	def _set_variant(self, value):
		"""Choose who speaks: copy that preset over the voice in force."""
		try:
			number = int(value)
		except (TypeError, ValueError):
			return
		if number not in self._engine.voiceNamesFor(self._engine.language):
			return
		self._variant = str(number)
		self._engine.control([(self._engine.selectPreset, (number,))])

	def _get_language(self):
		return _openevv.localeOf(self._engine.language)

	def _post(self, which, value):
		# As a control step, not as speech: a setting asked for while speech is
		# being cancelled -- which every keystroke does -- would otherwise be
		# thrown away with the utterances, and the reader's choice would not
		# take.
		self._engine.control([(self._engine.setVoiceParam, (which, value))])
