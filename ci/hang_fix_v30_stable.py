#!/usr/bin/env python3
"""v30: stable baseline after the v26–v29 mess.

Assumes hang_fix + v3 + v19…v25 already applied.
ONLY fixes pause KEEP. Does not touch dictionary loading.
"""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_p = "\t\tval pauses = settings?.pauses ?: Pauses.ALL\n"
if old_p in text:
    text = text.replace(old_p, "\t\tval pauses = (s ?: settings)?.pauses ?: Pauses.ALL\n", 1)
    print("pauses from s")
elif "(s ?: settings)?.pauses" in text:
    print("pauses already from s")
else:
    print("WARN pauses line not found")

old_split = "\t\t\tif (text.isEmpty()) continue\n\t\t\tval pieces = TextPieces.split(text)\n"
new_split = (
    "\t\t\tif (text.isEmpty()) continue\n"
    "\t\t\tval pieces = if (pauses == Pauses.KEEP) {\n"
    "\t\t\t\tif (text.length <= TextPieces.PIECE_CAP) listOf(text) else TextPieces.split(text)\n"
    "\t\t\t} else {\n"
    "\t\t\t\tTextPieces.split(text)\n"
    "\t\t\t}\n"
)
if old_split in text:
    text = text.replace(old_split, new_split, 1)
    print("KEEP single-piece")
elif "pauses == Pauses.KEEP" in text:
    print("KEEP split already present")
else:
    raise SystemExit("split site not found")

if "val voiceOk = if (speaking)" not in text:
    pat = re.compile(
        r"\t\tloadRegexDictionary\(s\)\n"
        r".*?"
        r"\tif \(target\.applyVoice\(s\.voice, s\.shape\(s\.voice\)\) && dictionariesReady\) \{\n"
        r"\t\t\tappliedRevisions\[target\] = s\.revision\n"
        r"\t\t\}\n",
        re.S,
    )
    m = pat.search(text)
    if m:
        new_as = (
            "\t\tloadRegexDictionary(s)\n"
            "\t\tval voiceOk = if (speaking) true else target.applyVoice(s.voice, s.shape(s.voice))\n"
            "\t\tif (voiceOk && dictionariesReady) {\n"
            "\t\t\tappliedRevisions[target] = s.revision\n"
            "\t\t}\n"
        )
        text = text[: m.start()] + new_as + text[m.end() :]
        print("applyVoice guard while speaking")
    else:
        print("WARN applyVoice block not patched")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
print("v30 ok")
