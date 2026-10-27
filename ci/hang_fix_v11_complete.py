#!/usr/bin/env python3
"""v11: teachWord ALL entries, no eciLoadDict, never block TalkBack on load."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "evvdroid-dict-teach" not in text:
    raise SystemExit("dictExecutor missing — run v6 first")

if "@Volatile private var deferDictLoad" not in text:
    if "@Volatile private var speaking = false\n" not in text:
        raise SystemExit("speaking flag missing")
    text = text.replace(
        "\t@Volatile private var speaking = false\n",
        "\t@Volatile private var speaking = false\n"
        "\t@Volatile private var deferDictLoad = true\n",
        1,
    )
    print("added deferDictLoad")

old_sched = (
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tengineExecutor.execute {\n"
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
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(30) } catch (_: InterruptedException) {\n"
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
if old_sched in text:
    text = text.replace(old_sched, new_sched, 1)
    print("openEngine applySettings -> dictExecutor + defer")
elif "while (deferDictLoad || speaking)" in text:
    print("openEngine schedule already deferred")
else:
    raise SystemExit("openEngine schedule block not found")

old_def = (
    "\t\tif (s != null && appliedRevisions[target] != s.revision) {\n"
    "\t\t\tengineExecutor.execute {\n"
    "\t\t\t\ttry { applySettings(target) } catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
new_def = (
    "\t\tif (s != null && appliedRevisions[target] != s.revision) {\n"
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(30) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
if old_def in text:
    text = text.replace(old_def, new_def, 1)
    print("synth deferred apply -> dictExecutor")
else:
    old_def2 = (
        "\t\tif (s != null && appliedRevisions[target] != s.revision) {\n"
        "\t\t\tdictExecutor.execute {\n"
        "\t\t\t\ttry { applySettings(target) } catch (failed: Exception) {\n"
        "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
        "\t\t\t\t}\n"
        "\t\t\t}\n"
        "\t\t}\n"
    )
    if old_def2 in text:
        text = text.replace(old_def2, new_def, 1)
        print("synth deferred apply updated")
    else:
        print("WARN: deferred apply block skipped")

if "First utterance done" not in text and text.count("deferDictLoad = false") < 1:
    old_f = "\t\t} finally {\n\t\t\tspeaking = false\n\t\t}\n"
    new_f = "\t\t} finally {\n\t\t\tspeaking = false\n\t\t\tdeferDictLoad = false\n\t\t}\n"
    if old_f in text:
        text = text.replace(old_f, new_f, 1)
        print("finally clears deferDictLoad")
elif "deferDictLoad = false" not in text:
    old_f = "\t\t} finally {\n\t\t\tspeaking = false\n\t\t}\n"
    new_f = "\t\t} finally {\n\t\t\tspeaking = false\n\t\t\tdeferDictLoad = false\n\t\t}\n"
    if old_f in text:
        text = text.replace(old_f, new_f, 1)
        print("finally clears deferDictLoad")

if "still allow dict load after a short grace" not in text and "dictExecutor.execute {\n\t\t\t\t\ttry { Thread.sleep(5000)" not in text:
    old_w = (
        "\t\t\t\tengineExecutor.execute {\n"
        "\t\t\t\t\ttry {\n"
        "\t\t\t\t\t\topenEngine(primary)\n"
        "\t\t\t\t\t} catch (failed: Exception) {\n"
        "\t\t\t\t\t\tLog.e(TAG, \"dictionary warmup failed for 0x%08x\".format(primary), failed)\n"
        "\t\t\t\t\t}\n"
        "\t\t\t\t}\n"
    )
    new_w = (
        "\t\t\t\tengineExecutor.execute {\n"
        "\t\t\t\t\ttry {\n"
        "\t\t\t\t\t\topenEngine(primary)\n"
        "\t\t\t\t\t} catch (failed: Exception) {\n"
        "\t\t\t\t\t\tLog.e(TAG, \"dictionary warmup failed for 0x%08x\".format(primary), failed)\n"
        "\t\t\t\t\t}\n"
        "\t\t\t\t}\n"
        "\t\t\t\tdictExecutor.execute {\n"
        "\t\t\t\t\ttry { Thread.sleep(5000) } catch (_: InterruptedException) {\n"
        "\t\t\t\t\t\tThread.currentThread().interrupt()\n"
        "\t\t\t\t\t\treturn@execute\n"
        "\t\t\t\t\t}\n"
        "\t\t\t\t\tdeferDictLoad = false\n"
        "\t\t\t\t}\n"
    )
    if old_w in text:
        text = text.replace(old_w, new_w, 1)
        print("added 5s grace")

pat = re.compile(
    r"\t/\*\* Binary dictionaries use the engine's saved form.*?\n"
    r"\tprivate fun loadEngineDictionaries\(target: EvvEngine, want: Map<Int, String>, langs: Map<Int, Int>\): Boolean \{.*?\n"
    r"\t\treturn ready\n"
    r"\t\}",
    re.S,
)
m = pat.search(text)
if not m:
    raise SystemExit("loadEngineDictionaries not found")

new_load = (
    "\t/** Binary: loadBinary. Text: parse + teachWord EVERY entry on dictExecutor.\n"
    "\t *  No eciLoadDict. Teaching pauses only while speaking; all entries are taught. */\n"
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
    "\t\t\t\t\t\ttry { Thread.sleep(30) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn false\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tval ok = Dictionaries.loadBinary(target, volume, file)\n"
    "\t\t\t\t\tready = ready && ok\n"
    "\t\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${if (ok) \"loaded by the engine\" else \"refused by the engine\"} in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
    "\t\t\t\t\tcontinue\n"
    "\t\t\t\t}\n"
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} parsed ${entries.size} entries in ${(System.nanoTime() - started) / 1_000_000L} ms; teaching ALL on dict thread\")\n"
    "\t\t\t\tval eng = target\n"
    "\t\t\t\tval vol = volume\n"
    "\t\t\t\tvar taught = 0\n"
    "\t\t\t\tvar refused = 0\n"
    "\t\t\t\tval batchStarted = System.nanoTime()\n"
    "\t\t\t\tfor ((_, pair) in entries) {\n"
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(30) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn false\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\tif (eng.teachWord(vol, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tif ((taught + refused) % 256 == 0) Thread.yield()\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $vol: COMPLETE taught $taught refused $refused in ${(System.nanoTime() - batchStarted) / 1_000_000L} ms\")\n"
    "\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\tready = false\n"
    "\t\t\t\tLog.e(TAG, \"volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms\", failed)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\treturn ready\n"
    "\t}"
)

text = text[: m.start()] + new_load + text[m.end() :]
print("loadEngineDictionaries: complete teachWord all entries")

text = text.replace(
    "requestEngine(language).get(8, java.util.concurrent.TimeUnit.SECONDS)",
    "requestEngine(language).get(5, java.util.concurrent.TimeUnit.SECONDS)",
    1,
)
text = text.replace("timed out after 8s", "timed out after 5s", 1)

path.write_text(text)
print("v11 done, chars", len(text))
for marker in ["COMPLETE taught", "deferDictLoad", "dictExecutor", "teaching ALL"]:
    if marker not in text:
        raise SystemExit(f"missing {marker}")
print("v11 ok")
