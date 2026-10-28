#!/usr/bin/env python3
"""v16: stronger touch-to-stop + reliable off/on without slow full recreate."""
from pathlib import Path
import re

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "stopsInFlight" not in text:
    old = "\tprivate val stopGeneration = java.util.concurrent.atomic.AtomicLong()\n"
    new = (
        "\tprivate val stopGeneration = java.util.concurrent.atomic.AtomicLong()\n"
        "\t/** Native eciStop still running after onStop/onUnbind; speak waits briefly. */\n"
        "\tprivate val stopsInFlight = java.util.concurrent.atomic.AtomicInteger()\n"
    )
    if old not in text:
        raise SystemExit("stopGeneration not found")
    text = text.replace(old, new, 1)
    print("added stopsInFlight")

pat = re.compile(
    r"\t/\*\* Drop every live native engine\..*?\n"
    r"\tprivate fun deactivateEngines\(closeInstances: Boolean\) \{.*?\n"
    r"\t\}\n",
    re.S,
)
new_fn = (
    "\t/** Abort immediately, then eciStop in parallel. Speak waits on stopsInFlight\n"
    "\t *  so TalkBack off/on never hits a half-stopped native engine. */\n"
    "\tprivate fun deactivateEngines(closeInstances: Boolean) {\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tsynchronized(engineLoadLock) {\n"
    "\t\t\tpendingEngine?.cancel(true)\n"
    "\t\t\tpendingEngine = null\n"
    "\t\t\tpendingEngineLanguage = null\n"
    "\t\t}\n"
    "\t\tval engines = synchronized(engineStateLock) {\n"
    "\t\t\tLinkedHashSet<EvvEngine>().also { set ->\n"
    "\t\t\t\tset.addAll(enginesByLanguage.values)\n"
    "\t\t\t\tengine?.let(set::add)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tfor (e in engines) {\n"
    "\t\t\ttry { e.abort() } catch (_: Exception) {}\n"
    "\t\t}\n"
    "\t\tif (engines.isEmpty() && !closeInstances) return\n"
    "\t\tstopsInFlight.incrementAndGet()\n"
    "\t\tThread({\n"
    "\t\t\ttry {\n"
    "\t\t\t\tval workers = engines.map { eng ->\n"
    "\t\t\t\t\tThread({\n"
    "\t\t\t\t\t\ttry { eng.stop() } catch (_: Exception) {}\n"
    "\t\t\t\t\t\tif (closeInstances) {\n"
    "\t\t\t\t\t\t\ttry { eng.close() } catch (_: Exception) {}\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}, \"evv-stop-one\").apply { isDaemon = true }\n"
    "\t\t\t\t}\n"
    "\t\t\t\tworkers.forEach { it.start() }\n"
    "\t\t\t\tworkers.forEach {\n"
    "\t\t\t\t\ttry { it.join(2500) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
    "\t\t\t\tif (closeInstances) {\n"
    "\t\t\t\t\tsynchronized(engineStateLock) {\n"
    "\t\t\t\t\t\tenginesByLanguage.clear()\n"
    "\t\t\t\t\t\tappliedRevisions.clear()\n"
    "\t\t\t\t\t\tloadedDictionaries.clear()\n"
    "\t\t\t\t\t\tengine = null\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t}\n"
    "\t\t\t} finally {\n"
    "\t\t\t\tstopsInFlight.decrementAndGet()\n"
    "\t\t\t}\n"
    "\t\t}, \"evv-stop\").apply { isDaemon = true }.start()\n"
    "\t}\n"
    "\n"
    "\t/** Brief wait so the next utterance does not race a still-running eciStop. */\n"
    "\tprivate fun awaitNativeStop(maxMs: Long) {\n"
    "\t\tval deadline = System.nanoTime() + maxMs * 1_000_000L\n"
    "\t\twhile (stopsInFlight.get() > 0 && System.nanoTime() < deadline) {\n"
    "\t\t\ttry {\n"
    "\t\t\t\tThread.sleep(10)\n"
    "\t\t\t} catch (_: InterruptedException) {\n"
    "\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\treturn\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t}\n"
    "\n"
)
m = pat.search(text)
if not m:
    raise SystemExit("deactivateEngines block not found")
text = text[: m.start()] + new_fn + text[m.end() :]
print("replaced deactivateEngines")

old_unbind = (
    "\toverride fun onUnbind(intent: android.content.Intent?): Boolean {\n"
    "\t\tLog.i(TAG, \"onUnbind: deactivating all engines\")\n"
    "\t\tdeactivateEngines(closeInstances = true)\n"
    "\t\treturn super.onUnbind(intent)\n"
    "\t}"
)
new_unbind = (
    "\toverride fun onUnbind(intent: android.content.Intent?): Boolean {\n"
    "\t\tLog.i(TAG, \"onUnbind: native stop all engines (keep cache for fast rebind)\")\n"
    "\t\tdeactivateEngines(closeInstances = false)\n"
    "\t\treturn super.onUnbind(intent)\n"
    "\t}"
)
if old_unbind not in text:
    if "keep cache for fast rebind" in text:
        print("onUnbind already fast-rebind")
    else:
        raise SystemExit("onUnbind not found")
else:
    text = text.replace(old_unbind, new_unbind, 1)
    print("onUnbind stop without close")

old_synth = (
    "\t\tif (stopGeneration.get() != requestGeneration) return\n"
    "\t\t// Do NOT call applySettings here"
)
new_synth = (
    "\t\tif (stopGeneration.get() != requestGeneration) return\n"
    "\t\tawaitNativeStop(500)\n"
    "\t\tif (stopGeneration.get() != requestGeneration) return\n"
    "\t\t// Do NOT call applySettings here"
)
if old_synth not in text:
    if "awaitNativeStop" in text:
        print("awaitNativeStop already in synthesize")
    else:
        raise SystemExit("synthesize head not found")
else:
    text = text.replace(old_synth, new_synth, 1)
    print("synthesize waits for native stop")

path.write_text(text)
print("v16 done, chars", len(text))
for marker in ["stopsInFlight", "awaitNativeStop", "evv-stop-one", "keep cache for fast rebind"]:
    if marker not in text:
        raise SystemExit(f"missing {marker}")
print("v16 ok")
