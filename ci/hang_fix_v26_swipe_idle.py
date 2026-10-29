#!/usr/bin/env python3
"""v26: no teachWord during swipe/type — require real quiet gap after speech."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "speechQuietUntil" not in text:
    old = "\t@Volatile private var speaking = false\n"
    if old not in text:
        raise SystemExit("speaking flag not found")
    text = text.replace(
        old,
        old
        + "\t/** NanoTime until which teachWord must not run (after speak / between swipes). */\n"
        + "\t@Volatile private var speechQuietUntil = 0L\n",
        1,
    )
    print("added speechQuietUntil")

if "speechQuietUntil = System.nanoTime()" not in text:
    text = text.replace(
        "\t\tspeaking = true\n"
        "\t\ttry {\n",
        "\t\tspeaking = true\n"
        "\t\tspeechQuietUntil = System.nanoTime() + 600_000_000L\n"
        "\t\ttry {\n",
        1,
    )
    text = text.replace(
        "\t\t} finally {\n"
        "\t\t\tspeaking = false\n"
        "\t\t}\n",
        "\t\t} finally {\n"
        "\t\t\tspeaking = false\n"
        "\t\t\tspeechQuietUntil = System.nanoTime() + 600_000_000L\n"
        "\t\t}\n",
        1,
    )
    print("quiet window around speak")

old_wait = '''	/** Wait until [speaking] has been false for [idleMs], or [maxMs] total. */
	private fun waitUntilSpeechIdle(idleMs: Long, maxMs: Long): Boolean {
		val deadline = System.nanoTime() + maxMs * 1_000_000L
		var idleStart = -1L
		while (System.nanoTime() < deadline) {
			if (speaking) {
				idleStart = -1L
			} else {
				val now = System.nanoTime()
				if (idleStart < 0L) idleStart = now
				if (idleMs <= 0L || (now - idleStart) >= idleMs * 1_000_000L) return true
			}
			try {
				Thread.sleep(if (idleMs <= 0L) 20 else 25)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
		return !speaking
	}
'''

new_wait = '''	/** Wait until speech is idle and the post-speak quiet window has passed. */
	private fun waitUntilSpeechIdle(idleMs: Long, maxMs: Long): Boolean {
		val deadline = System.nanoTime() + maxMs * 1_000_000L
		var idleStart = -1L
		while (System.nanoTime() < deadline) {
			val now = System.nanoTime()
			if (speaking || now < speechQuietUntil) {
				idleStart = -1L
			} else {
				if (idleStart < 0L) idleStart = now
				if (idleMs <= 0L || (now - idleStart) >= idleMs * 1_000_000L) return true
			}
			try {
				Thread.sleep(25)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
		}
		return !speaking && System.nanoTime() >= speechQuietUntil
	}
'''

if old_wait not in text:
    raise SystemExit("waitUntilSpeechIdle block not found")
text = text.replace(old_wait, new_wait, 1)
print("wait honors quiet window")

text = text.replace(
    "if (!waitUntilSpeechIdle(200L, 10000L)) return\n",
    "if (!waitUntilSpeechIdle(800L, 20000L)) return\n",
    1,
)
text = text.replace(
    "if (!waitUntilSpeechIdle(100L, 30000L)) return\n",
    "if (!waitUntilSpeechIdle(300L, 60000L)) return\n",
)
print("longer idle before teach batches")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "speechQuietUntil" not in text:
    raise SystemExit("quiet missing")
print("v26 ok")
