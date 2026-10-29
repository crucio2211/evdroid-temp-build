#!/usr/bin/env python3
"""v28: typing latency + pause KEEP really no-shorten."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

text = text.replace(
    "speechQuietUntil = System.nanoTime() + 600_000_000L",
    "speechQuietUntil = System.nanoTime() + 3_000_000_000L",
)
if "3_000_000_000L" not in text:
    raise SystemExit("quiet window not updated")
print("quiet window 3s")

text = text.replace(
    "if (!waitUntilSpeechIdle(800L, 20000L)) return\n",
    "if (!waitUntilSpeechIdle(2000L, 30000L)) return\n",
    1,
)
text = text.replace(
    "if (!waitUntilSpeechIdle(300L, 60000L)) return\n",
    "if (!waitUntilSpeechIdle(1000L, 90000L)) return\n",
)
text = text.replace(
    "if ((taught + refused) % 64 == 0) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(5) } catch (_: InterruptedException) {\n",
    "if ((taught + refused) % 32 == 0) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n",
    1,
)
print("stricter teach idle")

old_split = "\t\t\tif (text.isEmpty()) continue\n\t\t\tval pieces = TextPieces.split(text)\n"
new_split = (
    "\t\t\tif (text.isEmpty()) continue\n"
    "\t\t\tval pieces = if (pauses == Pauses.KEEP) {\n"
    "\t\t\t\tif (text.length <= TextPieces.PIECE_CAP) listOf(text)\n"
    "\t\t\t\telse TextPieces.split(text)\n"
    "\t\t\t} else {\n"
    "\t\t\t\tTextPieces.split(text)\n"
    "\t\t\t}\n"
)
if old_split not in text:
    raise SystemExit("TextPieces.split site not found")
text = text.replace(old_split, new_split, 1)
print("KEEP avoids sentence splits")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
print("v28 ok")
