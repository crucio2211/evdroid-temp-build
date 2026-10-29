#!/usr/bin/env python3
"""v31: pause KEEP must skip the OUTER TextPieces.split too."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = (
    "\t\tval raw = request.charSequenceText?.toString().orEmpty()\n"
    "\t\tval numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null\n"
    "\t\tval punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null\n"
    "\t\t// Split before any rewriting, NVDA-style: each raw piece is\n"
    "\t\t// processed and spoken on its own, so the first audio lands after\n"
    "\t\t// the first piece's frontend rather than the whole block's. A\n"
    "\t\t// block-sized regex and dictionary scan plus one engine frontend\n"
    "\t\t// over all of it is what kept big text waiting.\n"
    "\t\tval rawPieces = TextPieces.split(raw)\n"
)

if old not in text:
    old = (
        "\t\tval raw = request.charSequenceText?.toString().orEmpty()\n"
        "\t\tval numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null\n"
        "\t\tval punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null\n"
    )
    if old not in text:
        raise SystemExit("raw/numberMode block not found")
    idx = text.find(old)
    rest = text[idx + len(old):]
    m = re.search(r"\t\tval rawPieces = TextPieces\.split\(raw\)\n", rest)
    if not m:
        raise SystemExit("rawPieces line not found after raw")
    start = idx
    end = idx + len(old) + m.end()
else:
    start = text.find(old)
    end = start + len(old)

new = (
    "\t\tval raw = request.charSequenceText?.toString().orEmpty()\n"
    "\t\tval numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null\n"
    "\t\tval punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null\n"
    "\t\tval pauseMode = ((s ?: settings)?.pauses ?: Pauses.ALL).coerceIn(Pauses.KEEP, Pauses.ALL)\n"
    "\t\tval rawPieces = if (pauseMode == Pauses.KEEP) {\n"
    "\t\t\tif (raw.length <= TextPieces.PIECE_CAP) listOf(raw) else TextPieces.split(raw)\n"
    "\t\t} else {\n"
    "\t\t\tTextPieces.split(raw)\n"
    "\t\t}\n"
)

text = text[:start] + new + text[end:]
print("outer split respects KEEP")

if "\t\tval pauses = (s ?: settings)?.pauses ?: Pauses.ALL\n" in text:
    text = text.replace("\t\tval pauses = (s ?: settings)?.pauses ?: Pauses.ALL\n", "\t\tval pauses = pauseMode\n", 1)
elif "\t\tval pauses = settings?.pauses ?: Pauses.ALL\n" in text:
    text = text.replace("\t\tval pauses = settings?.pauses ?: Pauses.ALL\n", "\t\tval pauses = pauseMode\n", 1)
print("pauses = pauseMode")

old_inner = (
    "\t\t\tval pieces = if (pauses == Pauses.KEEP) {\n"
    "\t\t\t\tif (text.length <= TextPieces.PIECE_CAP) listOf(text) else TextPieces.split(text)\n"
    "\t\t\t} else {\n"
    "\t\t\t\tTextPieces.split(text)\n"
    "\t\t\t}\n"
)
new_inner = "\t\t\tval pieces = if (pauses == Pauses.KEEP) listOf(text) else TextPieces.split(text)\n"
if old_inner in text:
    text = text.replace(old_inner, new_inner, 1)
elif "if (text.isEmpty()) continue\n\t\t\tval pieces = TextPieces.split(text)\n" in text:
    text = text.replace(
        "\t\t\tif (text.isEmpty()) continue\n\t\t\tval pieces = TextPieces.split(text)\n",
        "\t\t\tif (text.isEmpty()) continue\n\t\t\tval pieces = if (pauses == Pauses.KEEP) listOf(text) else TextPieces.split(text)\n",
        1,
    )
print("inner KEEP")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "pauseMode" not in text:
    raise SystemExit("pauseMode missing")
print("v31 ok")
