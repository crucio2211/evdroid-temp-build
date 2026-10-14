package org.evvdroid

/**
 * Words and shapes of text that crash the engine, rewritten before it sees
 * them.
 *
 * The engine parses what it is given, and some ordinary strings send that
 * parser off the rails: crash codes such as h're sail straight through a
 * screen reader into the synthesiser, and words like caesura or McDonald
 * split the wrong way take the engine down with them. NVDA restarts when its
 * driver crashes; here the service just goes silent, which for TalkBack is
 * the same thing with less noise.
 *
 * The rules are adapted from David CM's NVDA IBMTTS driver
 * (https://github.com/davidacm/NVDA-IBMTTS-Driver, addon/synthDrivers/ibmeci.py:
 * english_fixes and english_ibm_fixes), rewritten for Kotlin. Only the
 * English crash guards are carried over, since US English is what this build
 * speaks. Nothing here changes what is said, only how it is written down for
 * the engine.
 *
 * This runs before TextFixes and Punctuation: a crash guard that puts a
 * space into h're must see the word as written, and the backquote strip
 * must run before anything that adds annotations of its own.
 */
object AntiCrash {

	/**
	 * [text] with crash words defused. When [stripBackquotes] is on, a
	 * backquote becomes a space, which keeps stray or hostile voice markup in
	 * ordinary text -- `vs, `p and friends -- from reaching the engine.
	 */
	fun apply(text: String, stripBackquotes: Boolean = true): String {
		if (text.isEmpty()) return text
		var out = text
		if (stripBackquotes) out = out.replace('`', ' ')
		for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
		out = TZSCHE.replace(out, ::splitTzsche)
		return out
	}

	private val RULES: List<Pair<Regex, String>> = listOf(
		// Crash codes passed straight through, e.g. from an edit box: h're.
		Regex("""\b(|\d+|\W+)h'(r|v)e""", RegexOption.IGNORE_CASE) to "$1h $2e",
		// Caesura and its kin take the parser down; seizure reads the same.
		Regex("""c(ae|æ)sur(e)?""", RegexOption.IGNORE_CASE) to "seizur",
		// A spaced Mc name crashes; joined it reads fine.
		Regex("""\b(Mc)\s+([A-Z][a-z]|[A-Z][A-Z]+)""") to "$1$2",
		// The date parser mangles "03 Marble" into "march threerd ble".
		Regex("""\b(\d+) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)([a-z]+)""") to "$1  $2$3",
		// ... so the guard above must not break full month names apart.
		Regex("""\b(\d+)  (January|February|March|April|May|June|July|August|September|October|November|December)\b""") to "$1 $2",
		// A clock time carrying an ordinal, e.g. 3:25th.
		Regex("""(\d):(\d\d[snrt][tdh])""", RegexOption.IGNORE_CASE) to "$1 $2",
		// Words glued onto an h..s middle, e.g. fish-ESQUE compounds.
		Regex("""\b(\w+[bdfhjlmnqrvyz])(h[he]s)([abcdefghjklmnopqrstvwy]\w+)\b""", RegexOption.IGNORE_CASE) to "$1 $2$3",
		Regex("""\b(\w+[bdfhjlmnqrvz])(h[he]s)(iron+[degins]?)""", RegexOption.IGNORE_CASE) to "$1 $2$3",
		// Apostrophe clusters the parser chokes on.
		Regex("""\b(\w+'{1,}[bcdfghjklmnpqrstvwxyz])'*(h+[he]s)([abcdefghijklmnopqrstvwy]\w+)\b""", RegexOption.IGNORE_CASE) to "$1 $2$3",
		Regex("""\b(\w+[bcdfghjklmnpqrstvwxyz])('{1,}h+[he]s)([abcdefghijklmnopqrstvwy]\w+)\b""", RegexOption.IGNORE_CASE) to "$1 $2$3",
		Regex("""\b([bcdfghjklmnpqrstvwxz]+)'([bcdefghjklmnpqrstvwxz']+)'([drtv][aeiou]?)""", RegexOption.IGNORE_CASE) to "$1 $2 $3",
		Regex("""\b(you+)'(re)+'([drv]e?)""", RegexOption.IGNORE_CASE) to "$1 $2 $3",
		// recosp-style gluings. Written $1kosp rather than ${1}kosp: the braces
		// would make Kotlin interpolate the number 1 itself instead of leaving
		// a group reference for the regex engine.
		Regex("""(re|un|non|anti)cosp""", RegexOption.IGNORE_CASE) to "$1kosp",
		// A glued EUR code, e.g. EURUSD5.
		Regex("""(EUR[A-Z]+)(\d+)""", RegexOption.IGNORE_CASE) to "$1 $2",
		// Long juar... words.
		Regex("""(juar)([a-z']{9,})""", RegexOption.IGNORE_CASE) to "$1 $2",
		// Addresses with odd separators crash ViaVoice-family parsers.
		Regex("""(http://|ftp://)([a-z]+)(\W){1,3}([a-z]+)(/*\W){1,3}([a-z]){1}""", RegexOption.IGNORE_CASE) to "$1$2$3$4 $5$6",
		// Arithmetic glued onto decimals with trailing noughts.
		Regex("""(\d+)([-+*^/])(\d+)(\.)(\d+)(\.)(0{2,})""", RegexOption.IGNORE_CASE) to "$1$2$3$4$5$6 $7",
		Regex("""(\d+)([-+*^/])(\d+)(\.)(\d+)(\.)(0\W)""", RegexOption.IGNORE_CASE) to "$1$2$3$4 $5$6$7",
		Regex("""(\d+)([-+*^/]+)(\d+)([-+*^/]+)([,.+])(0{2,})""", RegexOption.IGNORE_CASE) to "$1$2$3$4$5 $6",
		Regex("""(\d+)(\.+)(\d+)(\.+)(0{2,})(\.\d*)\s*\.*([-+*^/])""", RegexOption.IGNORE_CASE) to "$1$2$3$4 $5$6$7",
		Regex("""(\d+)\s*([-+*^/])\s*(\d+)(,)(00\b)""", RegexOption.IGNORE_CASE) to "$1$2$3$4 $5",
		Regex("""(\d+)\s*([-+*^/])\s*(\d+)(,)(0{4,})""", RegexOption.IGNORE_CASE) to "$1$2$3$4 $5",
		// A full clock time: without the space only the first half is read.
		Regex("""(\d):(\d+):(\d+)""") to "$1:$2 $3"
	)

	/** A consonant pile ending in t+zsche. Most of its groups sit out any one
	 *  match, and an unmatched group must read as nothing rather than as the
	 *  word "null", so this is built by hand instead of by replacement. */
	private val TZSCHE =
		Regex("""\b(\d+|\W+)?(\w+_+)?(_+)?([bcdfghjklmnpqrstvwxz]+)?(\d+)?t+z[s]che""", RegexOption.IGNORE_CASE)

	private fun splitTzsche(match: MatchResult): String {
		val kept = (1..5).map { match.groupValues[it] }.filter { it.isNotEmpty() }
		return (kept + listOf("tz", "sche")).joinToString(" ")
	}
}
