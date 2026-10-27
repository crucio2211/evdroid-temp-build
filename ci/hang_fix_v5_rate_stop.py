#!/usr/bin/env python3
"""Fix first-utterance slow rate + unreliable stop-on-touch (TalkBack)."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_stop = (
    "\toverride fun onStop() {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\t// ECI stop waits for its current rule pass and the next speak would wait\n"
    "\t\t// behind that cleanup. Abort the callback now; the native worker answers\n"
    "\t\t// eciDataAbort, and its next speak waits for that worker to become idle.\n"
    "\t\tengine?.abort()\n"
    "\t}"
)
new_stop = (
    "\toverride fun onStop() {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\t// Abort every engine, not only `engine`. Language switch can leave speech\n"
    "\t\t// on a cached instance while `engine` already points elsewhere — TalkBack\n"
    "\t\t// touch-to-stop then appeared to do nothing most of the time.\n"
    "\t\tval engines = synchronized(engineStateLock) {\n"
    "\t\t\tLinkedHashSet<EvvEngine>().also { set ->\n"
    "\t\t\t\tset.addAll(enginesByLanguage.values)\n"
    "\t\t\t\tengine?.let(set::add)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tfor (e in engines) {\n"
    "\t\t\ttry { e.abort() } catch (_: Exception) {}\n"
    "\t\t}\n"
    "\t}"
)
if old_stop not in text:
    if "Abort every engine" in text:
        print("onStop already fixed")
    else:
        raise SystemExit("onStop block not found")
else:
    text = text.replace(old_stop, new_stop, 1)
    print("applied onStop fix")

old_rate = (
    "\t\t// Re-sent every time rather than once, because it is the one setting\n"
    "\t\t// with no way of telling whether something else has moved it.\n"
    "\t\ts?.let { target.setAbbreviations(it.abbreviations) }\n"
    "\t\t// A forced rate or pitch is the voice's own Speed or Pitch row: the\n"
    "\t\t// voice is applied absolutely below, so 100 here leaves it alone and\n"
    "\t\t// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers\n"
    "\t\t// multiply the voice as before.\n"
    "\t\ttarget.setRatePercent(if (s != null && s.forceRate) 100 else request.speechRate)\n"
    "\t\ttarget.setPitchPercent(if (s != null && s.forcePitch) 100 else request.pitch)\n"
)
new_rate = (
    "\t\t// Re-apply voice before rate/pitch so baseSpeed is set. First utterance\n"
    "\t\t// after restart often raced openEngine and spoke at the engine default\n"
    "\t\t// (slow), then the next utterance jumped to the real rate.\n"
    "\t\ts?.let {\n"
    "\t\t\ttarget.applyVoice(it.voice, it.shape(it.voice))\n"
    "\t\t\ttarget.setAbbreviations(it.abbreviations)\n"
    "\t\t}\n"
    "\t\t// A forced rate or pitch is the voice's own Speed or Pitch row: the\n"
    "\t\t// voice is applied absolutely above, so 100 here leaves it alone and\n"
    "\t\t// whatever TalkBack asked for is ignored. Unforced, TalkBack's numbers\n"
    "\t\t// multiply the voice as before.\n"
    "\t\ttarget.setRatePercent(if (s != null && s.forceRate) 100 else request.speechRate)\n"
    "\t\ttarget.setPitchPercent(if (s != null && s.forcePitch) 100 else request.pitch)\n"
)
if old_rate not in text:
    if "Re-apply voice before rate" in text:
        print("rate already fixed")
    else:
        raise SystemExit("rate block not found")
else:
    text = text.replace(old_rate, new_rate, 1)
    print("applied rate fix")

path.write_text(text)
print("v5 done, chars", len(text))
for m in ["Abort every engine", "Re-apply voice before rate"]:
    if m not in text:
        raise SystemExit(f"missing: {m}")
print("v5 ok")
