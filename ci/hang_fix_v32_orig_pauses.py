#!/usr/bin/env python3
"""v32: restore pause handling EXACTLY from known-good 0c3b4718."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = """\t\tval raw = request.charSequenceText?.toString().orEmpty()
\t\tval numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null
\t\tval punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null
\t\tval pauseMode = ((s ?: settings)?.pauses ?: Pauses.ALL).coerceIn(Pauses.KEEP, Pauses.ALL)
\t\tval rawPieces = if (pauseMode == Pauses.KEEP) {
\t\t\tif (raw.length <= TextPieces.PIECE_CAP) listOf(raw) else TextPieces.split(raw)
\t\t} else {
\t\t\tTextPieces.split(raw)
\t\t}
"""

new = """\t\tval raw = request.charSequenceText?.toString().orEmpty()
\t\tval numberMode: Int? = if (s != null && s.numberProcessing) s.numberMode else null
\t\tval punctLevel: Int? = if (s != null && s.punctProcessing) s.punctLevel else null
\t\t// Split before any rewriting, NVDA-style: each raw piece is
\t\t// processed and spoken on its own, so the first audio lands after
\t\t// the first piece's frontend rather than the whole block's. A
\t\t// block-sized regex and dictionary scan plus one engine frontend
\t\t// over all of it is what kept big text waiting.
\t\tval rawPieces = TextPieces.split(raw)
"""

if old in text:
    text = text.replace(old, new, 1)
    print("restored outer split")
elif "pauseMode" not in text and "val rawPieces = TextPieces.split(raw)" in text:
    print("outer already original")
else:
    raise SystemExit("outer block not found")

text = text.replace("\t\tval pauses = pauseMode\n", "\t\tval pauses = settings?.pauses ?: Pauses.ALL\n", 1)
text = text.replace("\t\tval pauses = (s ?: settings)?.pauses ?: Pauses.ALL\n", "\t\tval pauses = settings?.pauses ?: Pauses.ALL\n", 1)

old_inner = "\t\t\tval pieces = if (pauses == Pauses.KEEP) listOf(text) else TextPieces.split(text)\n"
if old_inner in text:
    text = text.replace(old_inner, "\t\t\tval pieces = TextPieces.split(text)\n", 1)
    print("restored inner split")

if "pauseMode" in text:
    raise SystemExit("pauseMode still present")
if "val pauses = settings?.pauses ?: Pauses.ALL" not in text:
    raise SystemExit("pauses not original")
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")

path.write_text(text)
print("v32 ok")
