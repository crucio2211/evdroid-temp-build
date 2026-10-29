#!/usr/bin/env python3
"""v27: fix pause KEEP + speech rate drops."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat_as = re.compile(
    r"\t\tloadRegexDictionary\(s\)\n"
    r".*?"
    r"\tif \(target\.applyVoice\(s\.voice, s\.shape\(s\.voice\)\) && dictionariesReady\) \{\n"
    r"\t\t\tappliedRevisions\[target\] = s\.revision\n"
    r"\t\t\}\n",
    re.S,
)
m = pat_as.search(text)
if not m:
    raise SystemExit("applySettings voice block not found")

new_as = (
    "\t\tloadRegexDictionary(s)\n"
    "\t\tval voiceOk = if (speaking) {\n"
    "\t\t\ttrue\n"
    "\t\t} else {\n"
    "\t\t\ttarget.applyVoice(s.voice, s.shape(s.voice))\n"
    "\t\t}\n"
    "\t\tif (voiceOk && dictionariesReady) {\n"
    "\t\t\tappliedRevisions[target] = s.revision\n"
    "\t\t}\n"
)
text = text[: m.start()] + new_as + text[m.end() :]
print("applyVoice skipped while speaking")

old_p = "\t\tval pauses = settings?.pauses ?: Pauses.ALL\n"
new_p = "\t\tval pauses = (s ?: settings)?.pauses ?: Pauses.ALL\n"
if old_p not in text:
    raise SystemExit("pauses line not found")
text = text.replace(old_p, new_p, 1)
print("pauses from snapshot s")

old_speak = (
    "\t\t\t\tval accepted = stopGeneration.get() == requestGeneration && target.speak(piece)\n"
)
new_speak = (
    "\t\t\t\tif (s != null) {\n"
    "\t\t\t\t\ttarget.setRatePercent(if (s.forceRate) 100 else request.speechRate)\n"
    "\t\t\t\t\ttarget.setPitchPercent(if (s.forcePitch) 100 else request.pitch)\n"
    "\t\t\t\t} else {\n"
    "\t\t\t\t\ttarget.setRatePercent(request.speechRate)\n"
    "\t\t\t\t\ttarget.setPitchPercent(request.pitch)\n"
    "\t\t\t\t}\n"
    "\t\t\t\tval accepted = stopGeneration.get() == requestGeneration && target.speak(piece)\n"
)
if old_speak not in text:
    raise SystemExit("speak call not found")
text = text.replace(old_speak, new_speak, 1)
print("rate/pitch before each speak")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
print("v27 ok")
