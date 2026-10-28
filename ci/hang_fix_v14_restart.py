#!/usr/bin/env python3
"""v14: screen-reader restart hang — dict load must not block engineExecutor."""
from pathlib import Path
import re

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
    "\t/** Dict indexing must never share engineExecutor — that blocked openEngine after TalkBack restart. */\n"
    "\tprivate val dictExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->\n"
    "\t\tThread(task, \"evvdroid-dict-load\").apply { isDaemon = true }\n"
    "\t}\n"
    "\tprivate val engineLoadLock = Any()\n"
)
if old_exec not in text:
    if "evvdroid-dict-load" in text:
        print("dictExecutor already present")
    else:
        raise SystemExit("engineExecutor block not found")
else:
    text = text.replace(old_exec, new_exec, 1)
    print("added dictExecutor")

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
    if "dictExecutor.execute" in text and "dictionaries ready" in text:
        print("schedule already on dictExecutor")
    else:
        raise SystemExit("scheduleDictLoad block not found")
else:
    text = text.replace(old_sched, new_sched, 1)
    print("dict load moved off engineExecutor")

text = text.replace(
    "\t\t\tengineExecutor.execute {\n"
    "\t\t\t\ttry { applySettings(target) } catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n",
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry { applySettings(target) } catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"deferred settings failed\", failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n",
)

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
    "\t\tval engines = synchronized(engineStateLock) {\n"
    "\t\t\tLinkedHashSet<EvvEngine>().also { set ->\n"
    "\t\t\t\tset.addAll(enginesByLanguage.values)\n"
    "\t\t\t\tengine?.let(set::add)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tfor (e in engines) {\n"
    "\t\t\ttry { e.abort() } catch (_: Exception) {}\n"
    "\t\t}\n"
    "\t\tsynchronized(engineLoadLock) {\n"
    "\t\t\tpendingEngine?.cancel(true)\n"
    "\t\t\tpendingEngine = null\n"
    "\t\t\tpendingEngineLanguage = null\n"
    "\t\t}\n"
    "\t}"
)
if old_stop not in text:
    if "pendingEngine?.cancel" in text:
        print("onStop already strengthened")
    else:
        raise SystemExit("onStop block not found")
else:
    text = text.replace(old_stop, new_stop, 1)
    print("onStop abort all + cancel pending")

old_destroy_tail = (
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tengine?.abort()\n"
    "\t\tengineExecutor.shutdownNow()\n"
    "\t\ttry {\n"
    "\t\t\tengineExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
    "\t\tsynchronized(engineStateLock) {\n"
)
new_destroy_tail = (
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tengine?.abort()\n"
    "\t\tsynchronized(engineLoadLock) {\n"
    "\t\t\tpendingEngine?.cancel(true)\n"
    "\t\t\tpendingEngine = null\n"
    "\t\t\tpendingEngineLanguage = null\n"
    "\t\t}\n"
    "\t\tdictExecutor.shutdownNow()\n"
    "\t\tengineExecutor.shutdownNow()\n"
    "\t\ttry {\n"
    "\t\t\tdictExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t\tengineExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
    "\t\tsynchronized(engineStateLock) {\n"
)
if old_destroy_tail not in text:
    if "dictExecutor.shutdownNow()" in text:
        print("onDestroy dict shutdown already present")
    else:
        raise SystemExit("onDestroy tail not found")
else:
    text = text.replace(old_destroy_tail, new_destroy_tail, 1)
    print("onDestroy clears pending + dictExecutor")

path.write_text(text)
print("v14 done, chars", len(text))
for m in ["evvdroid-dict-load", "dictExecutor.execute", "pendingEngine?.cancel"]:
    if m not in text:
        raise SystemExit(f"missing {m}")
if re.search(r"scheduleDictLoad && s != null\) \{\s*engineExecutor\.execute", text):
    raise SystemExit("dict load still on engineExecutor")
print("v14 ok")
