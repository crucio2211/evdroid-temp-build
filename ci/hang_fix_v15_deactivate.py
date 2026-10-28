#!/usr/bin/env python3
"""v15: fully deactivate engines when TalkBack disconnects."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "private fun deactivateEngines" not in text:
    marker = "\toverride fun onStop() {\n"
    if marker not in text:
        raise SystemExit("onStop not found")
    helper = (
        "\t/** Drop every live native engine. TalkBack off/on was reusing a hung\n"
        "\t *  instance after abort-only onStop — silence until force-stop. */\n"
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
        "\t\tif (closeInstances) {\n"
        "\t\t\tsynchronized(engineStateLock) {\n"
        "\t\t\t\tenginesByLanguage.clear()\n"
        "\t\t\t\tappliedRevisions.clear()\n"
        "\t\t\t\tloadedDictionaries.clear()\n"
        "\t\t\t\tengine = null\n"
        "\t\t\t}\n"
        "\t\t\t// close() may wait on dictionaryGuard / eciStop — never on binder thread.\n"
        "\t\t\tThread({\n"
        "\t\t\t\tfor (e in engines) {\n"
        "\t\t\t\t\ttry { e.close() } catch (_: Exception) {}\n"
        "\t\t\t\t}\n"
        "\t\t\t}, \"evv-deactivate\").apply { isDaemon = true }.start()\n"
        "\t\t} else {\n"
        "\t\t\tThread({\n"
        "\t\t\t\tfor (e in engines) {\n"
        "\t\t\t\t\ttry { e.stop() } catch (_: Exception) {}\n"
        "\t\t\t\t}\n"
        "\t\t\t}, \"evv-stop\").apply { isDaemon = true }.start()\n"
        "\t\t}\n"
        "\t}\n"
        "\n"
    )
    text = text.replace(marker, helper + marker, 1)
    print("added deactivateEngines")

old_stop = (
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
new_stop = (
    "\toverride fun onStop() {\n"
    "\t\t// Utterance stop: abort + eciStop, keep instances for the next speak.\n"
    "\t\tdeactivateEngines(closeInstances = false)\n"
    "\t}"
)
if old_stop in text:
    text = text.replace(old_stop, new_stop, 1)
    print("onStop uses deactivateEngines")
elif "deactivateEngines(closeInstances = false)" in text:
    print("onStop already uses deactivateEngines")
else:
    raise SystemExit("onStop block not found")

if "override fun onUnbind" not in text:
    old_destroy = "\toverride fun onDestroy() {\n"
    if old_destroy not in text:
        raise SystemExit("onDestroy not found for onUnbind insert")
    unbind = (
        "\toverride fun onUnbind(intent: android.content.Intent?): Boolean {\n"
        "\t\tLog.i(TAG, \"onUnbind: deactivating all engines\")\n"
        "\t\tdeactivateEngines(closeInstances = true)\n"
        "\t\treturn super.onUnbind(intent)\n"
        "\t}\n"
        "\n"
    )
    text = text.replace(old_destroy, unbind + old_destroy, 1)
    print("added onUnbind full deactivate")
else:
    print("onUnbind already present")

path.write_text(text)
print("v15 done, chars", len(text))
for m in ["deactivateEngines", "onUnbind", "closeInstances = true", "evv-deactivate"]:
    if m not in text:
        raise SystemExit(f"missing {m}")
print("v15 ok")
