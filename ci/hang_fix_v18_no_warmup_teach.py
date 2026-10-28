#!/usr/bin/env python3
"""v18: remove onCreate engine warmup; text dicts = teachWord never eciLoadDict."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

pat_warm = re.compile(
    r"\t\t// Preload the engine \+ external dictionaries.*?"
    r"\t\tval warm = settings\n"
    r"\t\tif \(warm != null\) \{.*?"
    r"\t\t\}\n",
    re.S,
)
m = pat_warm.search(text)
if m:
    text = text[: m.start()] + text[m.end() :]
    print("removed onCreate engine/dict warmup")
elif "dictionary warmup failed" not in text:
    print("warmup already absent")

bad = (
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}\n"
)
good = (
    "\t\tThread({ EmojiNames.STARTERS.size }, \"emoji-warmup\").start()\n"
    "\t}\n"
)
if bad in text:
    text = text.replace(bad, good, 1)
    print("fixed leftover onCreate braces")

pat = re.compile(
    r"\t/\*\* Binary dictionaries use the engine's saved form\..*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    if "teachWord (no eciLoadDict)" in text:
        print("teachWord path already present")
    else:
        raise SystemExit("loadEngineDictionaries not found")
else:
    new_load = (
        "\t/** Binary: loadBinary when idle. Text: teachWord every entry (NO eciLoadDict).\n"
        "\t *  teachWord yields while speaking so TalkBack restart is never blocked. */\n"
        "\tprivate fun loadEngineDictionaries(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>): Boolean {\n"
        "\t\tvar ready = true\n"
        "\t\tfor ((volume, path) in want) {\n"
        "\t\t\tif ((langs[volume] ?: target.language) != target.language) continue\n"
        "\t\t\tval started = System.nanoTime()\n"
        "\t\t\ttry {\n"
        "\t\t\t\tval file = java.io.File(path)\n"
        "\t\t\t\tif (!file.exists()) {\n"
        "\t\t\t\t\tready = false\n"
        "\t\t\t\t\tLog.e(TAG, \"volume $volume dictionary is missing: ${file.name}\")\n"
        "\t\t\t\t\tcontinue\n"
        "\t\t\t\t}\n"
        "\t\t\t\tif (Dictionaries.isBinary(file)) {\n"
        "\t\t\t\t\twhile (speaking) {\n"
        "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
        "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
        "\t\t\t\t\t\t\treturn false\n"
        "\t\t\t\t\t\t}\n"
        "\t\t\t\t\t}\n"
        "\t\t\t\t\tval ok = Dictionaries.loadBinary(target, volume, file)\n"
        "\t\t\t\t\tready = ready && ok\n"
        "\t\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} binary ok=$ok in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
        "\t\t\t\t\tcontinue\n"
        "\t\t\t\t}\n"
        "\t\t\t\tval entries = Dictionaries.parse(file)\n"
        "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} parsed ${entries.size} entries; teachWord (no eciLoadDict)\")\n"
        "\t\t\t\tvar taught = 0\n"
        "\t\t\t\tvar refused = 0\n"
        "\t\t\t\tval batchStarted = System.nanoTime()\n"
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
        "\t\t\t\t\tif ((taught + refused) % 128 == 0) Thread.yield()\n"
        "\t\t\t\t}\n"
        "\t\t\t\tLog.i(TAG, \"volume $volume: teachWord done taught=$taught refused=$refused in ${(System.nanoTime() - batchStarted) / 1_000_000L} ms\")\n"
        "\t\t\t} catch (failed: Exception) {\n"
        "\t\t\t\tready = false\n"
        "\t\t\t\tLog.e(TAG, \"volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms\", failed)\n"
        "\t\t\t}\n"
        "\t\t}\n"
        "\t\treturn ready\n"
        "\t}"
    )
    text = text[: m.start()] + new_load + text[m.end() :]
    print("loadEngineDictionaries -> teachWord only")

path.write_text(text)
if text.count("{") != text.count("}"):
    raise SystemExit(f"braces {text.count('{')} {text.count('}')}")
if "dictionary warmup failed" in text:
    raise SystemExit("warmup still present")
print("v18 ok, chars", len(text))
