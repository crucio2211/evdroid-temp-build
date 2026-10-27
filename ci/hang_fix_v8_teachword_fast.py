#!/usr/bin/env python3
"""v8: back to teachWord (v4), faster batches, dictExecutor only — no eciLoadDict.

User: v7 still hangs and loads too slowly; restore teachWord and focus on hang.
"""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_load = (
    "\t/** Binary + text dictionaries via native loader (original mode).\n"
    "\t *  Called only from dictExecutor while not speaking so synthesis/onStop stay responsive. */\n"
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
    "\t\t\t\t// Wait out live speech so eciLoadDict is not refused / does not race audio.\n"
    "\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\treturn false\n"
    "\t\t\t\t\t}\n"
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

new_load = (
    "\t/** Binary: native loadBinary. Text/phoneme: parse + teachWord on dictExecutor.\n"
    "\t *  No eciLoadDict (hangs). Teaching is fast-batched and pauses while speaking. */\n"
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
    "\t\t\t\t// Parse is Java-only (fast). teachWord runs async — do not block ready.\n"
    "\t\t\t\tval entries = Dictionaries.parse(file)\n"
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} parsed ${entries.size} entries in ${(System.nanoTime() - started) / 1_000_000L} ms; teachWord async\")\n"
    "\t\t\t\tval eng = target\n"
    "\t\t\t\tval vol = volume\n"
    "\t\t\t\tdictExecutor.execute {\n"
    "\t\t\t\t\tvar taught = 0\n"
    "\t\t\t\t\tvar refused = 0\n"
    "\t\t\t\t\tval batchStarted = System.nanoTime()\n"
    "\t\t\t\t\tfor ((_, pair) in entries) {\n"
    "\t\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\t\ttry { Thread.sleep(30) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\t\tif (eng.teachWord(vol, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t// Light yield only — keep teaching fast without starving speech.\n"
    "\t\t\t\t\t\tif ((taught + refused) % 256 == 0) {\n"
    "\t\t\t\t\t\t\tThread.yield()\n"
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

if old_load not in text:
    if "teachWord async" in text:
        print("v8 teach path already present")
    else:
        raise SystemExit("v7 native loadEngineDictionaries not found — run after full chain including v7")
else:
    text = text.replace(old_load, new_load, 1)
    print("restored fast teachWord (no eciLoadDict)")

path.write_text(text)
print("v8 done, chars", len(text))
if "teachWord async" not in text:
    raise SystemExit("teachWord async marker missing")
print("v8 ok")
