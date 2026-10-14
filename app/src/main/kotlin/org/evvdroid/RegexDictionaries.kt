package org.evvdroid

import android.util.Log
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * A user dictionary written as regular expressions.
 *
 * The volume dictionaries teach the engine single words. This one rewrites
 * the text itself, before the engine ever sees it: a line is a pattern, a
 * tab, and what to put in its place, so `NVDA<TAB>en vee dee ay` says the
 * letters and `\b(\d+)kg<TAB>$1 kilograms` expands a unit. Replacements use
 * the same $1 group references the built-in guards use.
 *
 * A line starting with # is a comment and a blank line is nothing, and a
 * pattern that does not compile is skipped and logged rather than taking the
 * whole file down with it. The file is read as UTF-8: unlike the volume
 * dictionaries it never reaches the engine's byte-oriented loader, it runs
 * on the text while it is still text.
 *
 * This applies last of the rewrites, after AntiCrash and TextFixes -- the
 * same place a user dictionary sits in NVDA, closest to the engine -- so
 * what is written here is what gets said, unmangled by the readability
 * rules. That also means a replacement runs after the backquote strip, so
 * there is no need to enable voice tags for a replacement to carry one.
 */
object RegexDictionaries {

	/** One compiled line. */
	data class Entry(val pattern: Regex, val replacement: String)

	/** A file as compiled, with what identifies it. The service holds one of
	 *  these and only recompiles when the file on disk is a different one. */
	data class Loaded(val entries: List<Entry>, val path: String, val modified: Long)

	/** Reads [file] into entries, skipping comments, blanks and patterns that
	 *  do not compile. Answers null when nothing usable came out. */
	fun load(file: File): Loaded? {
		val entries = ArrayList<Entry>()
		try {
			file.forEachLine(Charsets.UTF_8) { line ->
				entryOf(line)?.let { entries.add(it) }
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
			return null
		}
		if (entries.isEmpty()) return null
		return Loaded(entries, file.absolutePath, file.lastModified())
	}

	/** How many usable patterns a file offers, for saying so on the settings
	 *  screen without compiling anything twice. */
	fun count(file: File): Int = try {
		var n = 0
		file.forEachLine(Charsets.UTF_8) { line -> if (entryOf(line) != null) n++ }
		n
	} catch (unreadable: Exception) {
		0
	}

	/** [text] with every entry applied in file order. A null [loaded] or an
	 *  empty text is handed back untouched. */
	fun apply(loaded: Loaded?, text: String): String {
		if (loaded == null || text.isEmpty()) return text
		var out = text
		for (entry in loaded.entries) out = entry.pattern.replace(out, entry.replacement)
		return out
	}

	/** The pattern as stored for [matchCase]: verbatim when case matters,
	 *  with an inline ignore-case flag otherwise, so the file stays plain
	 *  pattern-and-replacement lines with no sidecar format. */
	fun withCase(pattern: String, matchCase: Boolean): String {
		val trimmed = pattern.trim()
		if (matchCase || trimmed.startsWith("(?")) return pattern
		return "(?i)$pattern"
	}

	/** Whether [loaded] still describes the file on disk. */
	fun isCurrent(loaded: Loaded?): Boolean {
		if (loaded == null) return false
		val file = File(loaded.path)
		return file.exists() && file.lastModified() == loaded.modified
	}

	/** One line as written, for showing and editing. */
	data class RawEntry(val pattern: String, val replacement: String)

	/** The file's entries as written, in file order. Lines that never take
	 *  effect -- comments, blanks and patterns that do not compile -- are not
	 *  listed, and stay in the file untouched by editing. */
	fun readEntries(file: File): List<RawEntry> {
		val out = ArrayList<RawEntry>()
		try {
			file.forEachLine(Charsets.UTF_8) { line ->
				val (pattern, replacement) = splitLine(line) ?: return@forEachLine
				if (runCatching { Regex(pattern); true }.getOrDefault(false)) {
					out.add(RawEntry(pattern, replacement))
				}
			}
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot read ${file.name}", unreadable)
		}
		return out
	}

	/** Appends one entry. Answers false when it is not a valid entry or the
	 *  file cannot be written. */
	fun appendEntry(file: File, pattern: String, replacement: String): Boolean {
		val search = pattern.trim()
		val say = replacement.trim()
		if (search.isEmpty() || say.isEmpty()) return false
		try {
			Regex(search)
		} catch (bad: PatternSyntaxException) {
			Log.e(TAG, "not adding bad pattern: $search", bad)
			return false
		}
		return try {
			if (file.exists() && file.length() > 0L) {
				if (!file.readText(Charsets.UTF_8).endsWith("\n")) {
					file.appendText("\n", Charsets.UTF_8)
				}
			} else {
				file.parentFile?.mkdirs()
			}
			file.appendText("$search\t$say\n", Charsets.UTF_8)
			true
		} catch (unwritable: Exception) {
			Log.e(TAG, "cannot write ${file.name}", unwritable)
			false
		}
	}

	/** Drops the [index]-th entry as [readEntries] lists them, leaving every
	 *  other line byte for byte as it was. Answers false when there is no
	 *  such entry or the file cannot be rewritten. */
	fun removeEntryAt(file: File, index: Int): Boolean =
		rewriteEntryAt(file, index, null)

	/** Rewrites the [index]-th entry as [readEntries] lists it to [pattern]
	 *  said as [replacement], keeping its place in file order. Answers
	 *  false when there is no such entry, the replacement is not a valid
	 *  entry, or the file cannot be rewritten. */
	fun replaceEntryAt(file: File, index: Int, pattern: String, replacement: String): Boolean {
		val search = pattern.trim()
		val say = replacement.trim()
		if (search.isEmpty() || say.isEmpty()) return false
		try {
			Regex(search)
		} catch (bad: PatternSyntaxException) {
			Log.e(TAG, "not writing bad pattern: $search", bad)
			return false
		}
		return rewriteEntryAt(file, index, "$search\t$say")
	}

	/** Drops the [index]-th valid entry, or swaps in [line] for it when
	 *  given. Everything else stays byte for byte as it was. */
	private fun rewriteEntryAt(file: File, index: Int, line: String?): Boolean {
		if (index < 0) return false
		return try {
			val kept = ArrayList<String>()
			var seen = -1
			var changed = false
			for (old in file.readLines(Charsets.UTF_8)) {
				val usable = splitLine(old)?.let { (search, _) ->
					runCatching { Regex(search); true }.getOrDefault(false)
				} == true
				if (usable) seen++
				if (usable && seen == index) {
					changed = true
					if (line != null) kept.add(line)
					continue
				}
				kept.add(old)
			}
			if (!changed) return false
			file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
			true
		} catch (unreadable: Exception) {
			Log.e(TAG, "cannot rewrite ${file.name}", unreadable)
			false
		}
	}

	private fun entryOf(line: String): Entry? {
		val (pattern, replacement) = splitLine(line) ?: return null
		return try {
			Entry(Regex(pattern), replacement)
		} catch (bad: PatternSyntaxException) {
			Log.e(TAG, "skipping bad pattern: $pattern", bad)
			null
		}
	}

	private fun splitLine(line: String): Pair<String, String>? {
		val trimmed = line.trim()
		if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
		val split = line.indexOf('\t')
		if (split <= 0) return null
		val pattern = line.substring(0, split).trim()
		val replacement = line.substring(split + 1).trim()
		if (pattern.isEmpty() || replacement.isEmpty()) return null
		return pattern to replacement
	}

	private const val TAG = "evvdroid"
}
