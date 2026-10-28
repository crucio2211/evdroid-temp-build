#!/usr/bin/env python3
"""v21: teachWord all text dict entries into ECI (background)."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "@Volatile private var speaking" not in text:
    old = "\t@Volatile private var wordDict: Map<String, String> = emptyMap()\n"
    if old not in text:
        raise SystemExit("wordDict field not found")
    text = text.replace(
        old,
        old + "\t@Volatile private var speaking = false\n",
        1,
    )
    print("added speaking flag")

if "speaking = true" not in text:
    old_loop = "\t\twhile (true) {\n\t\t\tval target = ensureEngine(language)\n"
    if old_loop not in text:
        raise SystemExit("synth loop not found for speaking wrap")
    text = text.replace(
        old_loop,
        "\t\tspeaking = true\n"
        "\t\ttry {\n"
        + old_loop,
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
        "\t\t}\n",
        1,
    )
    print("speaking wrap on synthesize")
else:
    print("speaking already wrapped")

old_branch = (
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\tmerged[key] = pair.second\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${entries.size} entries into memory in ${(System.nanoTime() - started) / 1_000_000L} ms (no native load)\")\n"
)
new_branch = (
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\tmerged[key] = pair.second\n"
    "\t\t\t\t}\n"
    "\t\t\t\twordDict = java.util.LinkedHashMap(merged)\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${entries.size} entries in memory; teaching ECI…\")\n"
    "\t\t\t\tvar taught = 0\n"
    "\t\t\t\tvar refused = 0\n"
    "\t\t\t\tfor (pair in entries.values) {\n"
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn false\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\tif (target.teachWord(volume, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tif ((taught + refused) % 64 == 0) Thread.yield()\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ECI teachWord taught=$taught refused=$refused in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
)
if old_branch not in text:
    raise SystemExit("memory-only branch not found")
text = text.replace(old_branch, new_branch, 1)
print("teachWord into ECI after memory publish")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "teachWord(volume" not in text:
    raise SystemExit("teachWord missing")
print("v21 ok")
