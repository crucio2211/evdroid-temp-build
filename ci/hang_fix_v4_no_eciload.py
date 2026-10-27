#!/usr/bin/env python3
"""Hang fix v4: never call native eciLoadDict for text dictionaries.

Native loadDictionary indexes multi-megabyte phoneme files and freezes the
whole process (JNI/native global work) — async Java locks do not help.
Instead: parse text dicts in Java, teachWord in small batches with yields.
Binary IBM .dic files still use loadBinary (usually small/fast).
"""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = (
    "\t/** Binary dictionaries use the engine's saved form. Text dictionaries are\n"
    "\t *  normalized and encoded during import/cache warmup, then indexed natively\n"
    "\t *  once in their selected volume, matching Code Factory's per-volume loading. */\n"
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
    "\t\t\t\t\tval ok = Dictionaries.loadBinary(target, volume, file)\n"
    "\t\t\t\t\tready = ready && ok\n"
    "\t\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${if (ok) \"loaded by the engine\" else \"refused by the engine\"} in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
    "\t\t\t\t\tcontinue\n"
    "\t\t\t\t}\n"
    "\t\t\t\tval prepared = Dictionaries.prepareForEngine(file, target.language)\n"
    "\t\t\t\tval result = target.loadDictionary(volume, prepared.absolutePath)\n"
    "\t\t\t\tif (result != 0) {\n"
    "\t\t\t\t\tready = false\n"
    "\t\t\t\t\tLog.e(TAG, \"volume $volume refused ${file.name}: $result\")\n"
    "\t\t\t\t}\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} indexed natively in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
    "\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\tready = false\n"
    "\t\t\t\tLog.e(TAG, \"volume $volume not loaded by the engine after ${(System.nanoTime() - started) / 1_000_000L} ms\", failed)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\treturn ready\n"
    "\t}"
)

new = (
    "\t/** Binary dictionaries use the engine's saved form (small IBM .dic).\n"
    "\t *  Text / external phoneme dictionaries are NEVER passed to eciLoadDict:\n"
    "\t *  that native index freezes the whole process with multi-megabyte files.\n"
    "\t *  Code Factory style: parse in Java, teachWord in small batches with yields. */\n"
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
    "\t\t\t\t\tval ok = Dictionaries.loadBinary(target, volume, file)\n"
    "\t\t\t\t\tready = ready && ok\n"
    "\t\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} ${if (ok) \"loaded by the engine\" else \"refused by the engine\"} in ${(System.nanoTime() - started) / 1_000_000L} ms\")\n"
    "\t\t\t\t\tcontinue\n"
    "\t\t\t\t}\n"
    "\t\t\t\t// Text/phoneme: parse only (fast). Never eciLoadDict.\n"
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} parsed ${entries.size} entries in ${(System.nanoTime() - started) / 1_000_000L} ms; teaching in background\")\n"
    "\t\t\t\tval eng = target\n"
    "\t\t\t\tval vol = volume\n"
    "\t\t\t\tengineExecutor.execute {\n"
    "\t\t\t\t\tvar taught = 0\n"
    "\t\t\t\t\tvar refused = 0\n"
    "\t\t\t\t\tval batchStarted = System.nanoTime()\n"
    "\t\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\t\tif (eng.teachWord(vol, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t// Yield so TalkBack / synthesis stay responsive.\n"
    "\t\t\t\t\t\tif ((taught + refused) % 64 == 0) {\n"
    "\t\t\t\t\t\t\ttry { Thread.sleep(15) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tLog.i(TAG, \"volume $vol: taught $taught refused $refused in ${(System.nanoTime() - batchStarted) / 1_000_000L} ms\")\n"
    "\t\t\t\t}\n"
    "\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\tready = false\n"
    "\t\t\t\tLog.e(TAG, \"volume $volume not loaded after ${(System.nanoTime() - started) / 1_000_000L} ms\", failed)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\treturn ready\n"
    "\t}"
)

if old not in text:
    if "teaching in background" in text:
        print("v4 already applied")
    else:
        raise SystemExit("loadEngineDictionaries block not found")
else:
    text = text.replace(old, new, 1)
    path.write_text(text)
    print("applied v4 no-eciLoadDict, chars", len(text))

if "teaching in background" not in path.read_text():
    raise SystemExit("v4 marker missing")
print("v4 ok")
