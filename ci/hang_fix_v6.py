#!/usr/bin/env python3
"""v6: separate dict teach thread (stop hang regression) + real eciStop on touch."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_exec = (
    "\tprivate val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->\n"
    "\t\tThread(task, \"evvdroid-engine-load\").apply { isDaemon = true }\n"
    "\t}\n"
    "\tprivate val engineLoadLock = Any()\n"
)
new_exec = (
    "\tprivate val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->\n"
    "\t\tThread(task, \"evvdroid-engine-load\").apply { isDaemon = true }\n"
    "\t}\n"
    "\t/** Dictionary teachWord must NOT run on engineExecutor: a multi-minute teach\n"
    "\t *  queue blocked openEngine/ensureEngine and brought the hang back. */\n"
    "\tprivate val dictExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->\n"
    "\t\tThread(task, \"evvdroid-dict-teach\").apply { isDaemon = true }\n"
    "\t}\n"
    "\t@Volatile private var speaking = false\n"
    "\tprivate val engineLoadLock = Any()\n"
)
if old_exec not in text:
    if "evvdroid-dict-teach" in text:
        print("dictExecutor already present")
    else:
        raise SystemExit("engineExecutor block not found")
else:
    text = text.replace(old_exec, new_exec, 1)
    print("added dictExecutor")

old_destroy_shutdown = (
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tengine?.abort()\n"
    "\t\tengineExecutor.shutdownNow()\n"
    "\t\ttry {\n"
    "\t\t\tengineExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
)
new_destroy_shutdown = (
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tspeaking = false\n"
    "\t\tengine?.abort()\n"
    "\t\tdictExecutor.shutdownNow()\n"
    "\t\tengineExecutor.shutdownNow()\n"
    "\t\ttry {\n"
    "\t\t\tdictExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t\tengineExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
)
if old_destroy_shutdown not in text:
    if "dictExecutor.shutdownNow()" in text:
        print("onDestroy dict shutdown already present")
    else:
        raise SystemExit("onDestroy shutdown block not found")
else:
    text = text.replace(old_destroy_shutdown, new_destroy_shutdown, 1)
    print("updated onDestroy")

old_teach = (
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
)
new_teach = (
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
)
if old_teach not in text:
    if "teaching on dict thread" in text:
        print("teach path already updated")
    else:
        raise SystemExit("teach block not found")
else:
    text = text.replace(old_teach, new_teach, 1)
    print("moved teach to dictExecutor")

old_stop = (
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
new_stop = (
    "\toverride fun onStop() {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tspeaking = false\n"
    "\t\t// Abort wakes blocked read() immediately; eciStop clears the native queue.\n"
    "\t\t// abort alone left audio playing most of the time on touch-to-stop.\n"
    "\t\tval engines = synchronized(engineStateLock) {\n"
    "\t\t\tLinkedHashSet<EvvEngine>().also { set ->\n"
    "\t\t\t\tset.addAll(enginesByLanguage.values)\n"
    "\t\t\t\tengine?.let(set::add)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tfor (e in engines) {\n"
    "\t\t\ttry { e.abort() } catch (_: Exception) {}\n"
    "\t\t}\n"
    "\t\t// eciStop can wait on a rule pass — never block TalkBack's onStop thread.\n"
    "\t\tdictExecutor.execute {\n"
    "\t\t\tfor (e in engines) {\n"
    "\t\t\t\ttry { e.stop() } catch (_: Exception) {}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}"
)
if old_stop not in text:
    if "eciStop clears the native queue" in text:
        print("onStop already strengthened")
    else:
        raise SystemExit("onStop block not found")
else:
    text = text.replace(old_stop, new_stop, 1)
    print("strengthened onStop")

old_synth_head = (
    "\t\tif (stopGeneration.get() != requestGeneration) return\n"
    "\t\t// Do NOT call applySettings here"
)
new_synth_head = (
    "\t\tif (stopGeneration.get() != requestGeneration) return\n"
    "\t\tspeaking = true\n"
    "\t\ttry {\n"
    "\t\t// Do NOT call applySettings here"
)
if old_synth_head not in text:
    if "speaking = true" in text:
        print("speaking flag head already present")
    else:
        raise SystemExit("synth head not found")
else:
    text = text.replace(old_synth_head, new_synth_head, 1)
    print("added speaking=true")

old_end = (
    "\t\t} else if (opening.began) callback.done()\n"
    "\t\telse {\n"
    "\t\t\tcallback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)\n"
    "\t\t\tcallback.done()\n"
    "\t\t}\n"
    "\t}\n\n"
    "\t/** Hands one piece's samples on as they arrive, and answers whether the\n"
    "\t *  caller may go on to the next. The output stage is one chain for the\n"
    "\t *  piece, so its filters run continuously across its chunks. */\n"
    "\tprivate fun pump"
)
new_end = (
    "\t\t} else if (opening.began) callback.done()\n"
    "\t\telse {\n"
    "\t\t\tcallback.start(target.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)\n"
    "\t\t\tcallback.done()\n"
    "\t\t}\n"
    "\t\t} finally {\n"
    "\t\t\tspeaking = false\n"
    "\t\t}\n"
    "\t}\n\n"
    "\t/** Hands one piece's samples on as they arrive, and answers whether the\n"
    "\t *  caller may go on to the next. The output stage is one chain for the\n"
    "\t *  piece, so its filters run continuously across its chunks. */\n"
    "\tprivate fun pump"
)
if old_end not in text:
    if "speaking = false" in text:
        print("speaking finally already present")
    else:
        raise SystemExit("synth end not found for finally")
else:
    text = text.replace(old_end, new_end, 1)
    print("added speaking finally")

path.write_text(text)
print("v6 done, chars", len(text))
for m in ["evvdroid-dict-teach", "teaching on dict thread", "eciStop clears the native queue", "speaking = true", "speaking = false"]:
    if m not in text:
        raise SystemExit(f"missing: {m}")
print("v6 ok")
