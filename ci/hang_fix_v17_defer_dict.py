#!/usr/bin/env python3
"""v17: eciLoadDict only after first speech / idle — fix slow TalkBack restart with dicts."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = "\tprivate val stopsInFlight = java.util.concurrent.atomic.AtomicInteger()\n"
if "deferDictUntilIdle" not in text:
    if old not in text:
        raise SystemExit("stopsInFlight not found")
    text = text.replace(
        old,
        old
        + "\t@Volatile private var deferDictUntilIdle = true\n"
        + "\t@Volatile private var speaking = false\n",
        1,
    )
    print("flags")

old_sched = (
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
new_sched = (
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (deferDictUntilIdle || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
if old_sched not in text:
    if "while (deferDictUntilIdle || speaking)" in text:
        print("sched already deferred")
    else:
        raise SystemExit("sched not found")
else:
    text = text.replace(old_sched, new_sched, 1)
    print("sched")

idx = text.find("override fun onCreate()")
sub = text.find("\t\tsuper.onCreate()\n", idx)
if idx >= 0 and sub >= 0 and "deferDictUntilIdle = true" not in text[idx:idx + 700]:
    text = (
        text[:sub]
        + "\t\tsuper.onCreate()\n"
        + "\t\tdeferDictUntilIdle = true\n"
        + "\t\tdictExecutor.execute {\n"
        + "\t\t\ttry { Thread.sleep(3000) } catch (_: InterruptedException) {\n"
        + "\t\t\t\tThread.currentThread().interrupt()\n"
        + "\t\t\t\treturn@execute\n"
        + "\t\t\t}\n"
        + "\t\t\tdeferDictUntilIdle = false\n"
        + "\t\t}\n"
        + text[sub + len("\t\tsuper.onCreate()\n"):]
    )
    print("onCreate grace")

old_loop = "\t\twhile (true) {\n\t\t\tval target = ensureEngine(language)\n"
if old_loop not in text:
    if "speaking = true" in text and "ensureEngine(language)" in text:
        print("loop already wrapped")
    else:
        raise SystemExit("loop not found")
else:
    text = text.replace(
        old_loop,
        "\t\tspeaking = true\n"
        "\t\ttry {\n"
        "\t\twhile (true) {\n"
        "\t\t\tval target = ensureEngine(language)\n",
        1,
    )
    marker = (
        "\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
        "\t\t\treturn\n"
        "\t\t}\n"
    )
    if marker not in text:
        raise SystemExit("loop end not found")
    text = text.replace(
        marker,
        "\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
        "\t\t\treturn\n"
        "\t\t}\n"
        "\t\t} finally {\n"
        "\t\t\tspeaking = false\n"
        "\t\t\tdeferDictUntilIdle = false\n"
        "\t\t}\n",
        1,
    )
    print("onSynthesizeText speaking wrap")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
print("v17 ok, chars", len(text))
