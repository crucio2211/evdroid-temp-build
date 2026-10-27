#!/usr/bin/env python3
"""v9: defer dictionary load until after first utterance — fixes TalkBack first-start hang.

Root cause: onCreate/openEngine scheduled applySettings (parse + teachWord) on the
dict thread at the same moment TalkBack's first speak hit native ECI. teachWord
and speak contend in native code → freeze until force-stop.

Fix: do not start applySettings/teachWord until the first synthesizeWithEngine
finishes (or a short grace after open if nothing speaks).
"""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = "\t@Volatile private var speaking = false\n"
new = (
    "\t@Volatile private var speaking = false\n"
    "\t/** Until the first utterance ends, skip dict teach/load so TalkBack's\n"
    "\t *  first start never races teachWord inside native ECI (that was the hang). */\n"
    "\t@Volatile private var deferDictLoad = true\n"
)
if old not in text:
    if "deferDictLoad" in text:
        print("deferDictLoad already declared")
    else:
        raise SystemExit("speaking flag not found")
else:
    text = text.replace(old, new, 1)
    print("added deferDictLoad")

if "\t\tspeaking = false\n\t\tengine?.abort()\n\t\tdictExecutor.shutdownNow()\n" in text:
    text = text.replace(
        "\t\tspeaking = false\n\t\tengine?.abort()\n\t\tdictExecutor.shutdownNow()\n",
        "\t\tdeferDictLoad = false\n\t\tspeaking = false\n\t\tengine?.abort()\n\t\tdictExecutor.shutdownNow()\n",
        1,
    )
    print("onDestroy clears defer")

old_wait = (
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
)
new_wait = (
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\t// Wait out first TalkBack utterance (and any live speech).\n"
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
)
if old_wait not in text:
    if "while (deferDictLoad || speaking)" in text:
        print("openEngine wait already deferred")
    else:
        raise SystemExit("openEngine dict wait block not found")
else:
    text = text.replace(old_wait, new_wait, 1)
    print("openEngine waits for deferDictLoad")

old_def = (
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
)
new_def = (
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
)
if old_def not in text:
    if text.count("while (deferDictLoad || speaking)") >= 2:
        print("deferred apply already waits deferDictLoad")
    else:
        raise SystemExit("deferred apply wait not found")
else:
    text = text.replace(old_def, new_def, 1)
    print("deferred apply waits for deferDictLoad")

old_finally = (
    "\t\t} finally {\n"
    "\t\t\tspeaking = false\n"
    "\t\t}\n"
)
new_finally = (
    "\t\t} finally {\n"
    "\t\t\tspeaking = false\n"
    "\t\t\t// First utterance done — safe to teachWord / load dicts on dict thread.\n"
    "\t\t\tdeferDictLoad = false\n"
    "\t\t}\n"
)
if old_finally not in text:
    if "First utterance done" in text:
        print("finally already clears defer")
    else:
        raise SystemExit("speaking finally not found")
else:
    text = text.replace(old_finally, new_finally, 1)
    print("finally clears deferDictLoad")

old_warm = (
    "\t\t\t\tengineExecutor.execute {\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\topenEngine(primary)\n"
    "\t\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\t\tLog.e(TAG, \"dictionary warmup failed for 0x%08x\".format(primary), failed)\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
)
new_warm = (
    "\t\t\t\tengineExecutor.execute {\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\topenEngine(primary)\n"
    "\t\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\t\tLog.e(TAG, \"dictionary warmup failed for 0x%08x\".format(primary), failed)\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
    "\t\t\t\t// If no utterance arrives (rare), still allow dict load after a short grace.\n"
    "\t\t\t\tdictExecutor.execute {\n"
    "\t\t\t\t\ttry { Thread.sleep(5000) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tdeferDictLoad = false\n"
    "\t\t\t\t}\n"
)
if old_warm not in text:
    if "still allow dict load after a short grace" in text:
        print("grace timer already present")
    else:
        raise SystemExit("onCreate warmup block not found")
else:
    text = text.replace(old_warm, new_warm, 1)
    print("added 5s grace for deferDictLoad")

text2 = text.replace(
    "requestEngine(language).get(8, java.util.concurrent.TimeUnit.SECONDS)",
    "requestEngine(language).get(3, java.util.concurrent.TimeUnit.SECONDS)",
    1,
)
text2 = text2.replace(
    "engine setup timed out after 8s",
    "engine setup timed out after 3s",
    1,
)
if text2 == text and "get(3," not in text:
    raise SystemExit("ensureEngine timeout not updated")
text = text2
print("ensureEngine timeout 3s")

path.write_text(text)
print("v9 done, chars", len(text))
for m in ["deferDictLoad", "while (deferDictLoad || speaking)", "First utterance done", "get(3,"]:
    if m not in text:
        raise SystemExit(f"missing: {m}")
print("v9 ok")
