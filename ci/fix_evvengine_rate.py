#!/usr/bin/env python3
"""If baseSpeed is not set yet, still compute wantSpeed from DEFAULT_SPEED."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvEngine.kt")
text = path.read_text()

old = (
    "\tfun setRatePercent(percent: Int) = synchronized(guard) {\n"
    "\t\tif (closed || baseSpeed < 0) return\n"
    "\t\t// Not baseSpeed * percent. The engine's speed scale is nothing like\n"
    "\t\t// linear in how fast it speaks, so SpeechRate does the conversion.\n"
    "\t\twantSpeed = SpeechRate.speedForPercent(baseSpeed, percent)\n"
    "\t}"
)
new = (
    "\tfun setRatePercent(percent: Int) = synchronized(guard) {\n"
    "\t\tif (closed) return\n"
    "\t\t// Until applyVoice lands, use DEFAULT_SPEED so the first utterance is\n"
    "\t\t// not left at the engine's raw default (sounds wrongly slow).\n"
    "\t\tval base = if (baseSpeed >= 0) baseSpeed else Eci.DEFAULT_SPEED\n"
    "\t\twantSpeed = SpeechRate.speedForPercent(base, percent)\n"
    "\t}"
)
if old not in text:
    if "Until applyVoice lands" in text:
        print("EvvEngine rate already fixed")
    else:
        raise SystemExit("setRatePercent not found")
else:
    text = text.replace(old, new, 1)
    path.write_text(text)
    print("applied EvvEngine rate fallback")

old_p = (
    "\tfun setPitchPercent(percent: Int) = synchronized(guard) {\n"
    "\t\tif (closed || basePitch < 0) return\n"
    "\t\twantPitch = (basePitch.toLong() * percent / 100).toInt().coerceIn(0, Eci.PERCENT_MAX)\n"
    "\t}"
)
new_p = (
    "\tfun setPitchPercent(percent: Int) = synchronized(guard) {\n"
    "\t\tif (closed) return\n"
    "\t\tval base = if (basePitch >= 0) basePitch else 50\n"
    "\t\twantPitch = (base.toLong() * percent / 100).toInt().coerceIn(0, Eci.PERCENT_MAX)\n"
    "\t}"
)
if old_p not in text:
    if "basePitch >= 0) basePitch else 50" in path.read_text():
        print("pitch already fixed")
    else:
        raise SystemExit("setPitchPercent not found")
else:
    text = text.replace(old_p, new_p, 1)
    path.write_text(text)
    print("applied EvvEngine pitch fallback")
print("engine rate ok")
