#!/usr/bin/env python3
"""v7: restore native eciLoadDict for text dictionaries (old mode).

User reports teachWord path does not load external dicts correctly.
Hang mitigations kept:
- applySettings / loadDictionary only on dictExecutor (never engineExecutor)
- never hold engineStateLock during synthesis
- pause dict load while speaking
- openEngine returns without waiting for dict index
"""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

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
    "\t\t\t// Native eciLoadDict on dictExecutor only — never block engine open.\n"
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
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
)
if old_sched not in text:
    if "Native eciLoadDict on dictExecutor only" in text:
        print("openEngine schedule already on dictExecutor")
    else:
        raise SystemExit("openEngine schedule block not found")
else:
    text = text.replace(old_sched, new_sched, 1)
    print("openEngine dict load on dictExecutor")

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
    "\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
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
if old_def not in text:
    if "Native eciLoadDict on dictExecutor only" in text:
        print("deferred may already be updated")
    else:
        raise SystemExit("deferred applySettings block not found")
else:
    text = text.replace(old_def, new_def, 1)
    print("deferred apply on dictExecutor")

old_load = (
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
    "\t\t\t\tLog.i(TAG, \"volume $volume: ${file.name} parsed ${entries.size} entries in ${(System.nanoTime() - started) / 1_000_000L} ms; teaching on dict thread\")\n"
    "\t\t\t\tval eng = target\n"
    "\t\t\t\tval vol = volume\n"
    "\t\t\t\tdictExecutor.execute {\n"
    "\t\t\t\t\tvar taught = 0\n"
    "\t\t\t\t\tvar refused = 0\n"
    "\t\t\t\t\tval batchStarted = System.nanoTime()\n"
    "\t\t\t\t\tfor ((key, pair) in entries) {\n"
    "\t\t\t\t\t\t// Never contend with live speech / onStop.\n"
    "\t\t\t\t\t\twhile (speaking) {\n"
    "\t\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\t\tif (eng.teachWord(vol, pair.first, pair.second) == 0) taught++ else refused++\n"
    "\t\t\t\t\t\t} catch (_: Exception) {\n"
    "\t\t\t\t\t\t\trefused++\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t\tif ((taught + refused) % 32 == 0) {\n"
    "\t\t\t\t\t\t\ttry { Thread.sleep(25) } catch (_: InterruptedException) {\n"
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

new_load = (
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

if old_load not in text:
    if "indexed natively" in text:
        print("native load already restored")
    else:
        raise SystemExit("teachWord loadEngineDictionaries block not found")
else:
    text = text.replace(old_load, new_load, 1)
    print("restored native eciLoadDict")

path.write_text(text)
print("v7 done, chars", len(text))
for m in ["indexed natively", "prepareForEngine", "Native eciLoadDict on dictExecutor only"]:
    if m not in text:
        raise SystemExit(f"missing: {m}")
if "teaching on dict thread" in text:
    raise SystemExit("teach path still present")
print("v7 ok")
