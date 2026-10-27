#!/usr/bin/env python3
"""Extra hang fix: release engineStateLock during synthesis; fast onDestroy."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old_loop = (
    "\t\twhile (true) {\n"
    "\t\t\tval target = ensureEngine(language)\n"
    "\t\t\tif (target == null) {\n"
    "\t\t\t\tcallback.error(TextToSpeech.ERROR_SERVICE)\n"
    "\t\t\t\treturn\n"
    "\t\t\t}\n"
    "\t\t\tval spokeOnCurrentEngine = synchronized(engineStateLock) {\n"
    "\t\t\t\tif (engine !== target) false else {\n"
    "\t\t\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
    "\t\t\t\t\ttrue\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t\tif (spokeOnCurrentEngine) return\n"
    "\t\t}\n"
)
new_loop = (
    "\t\twhile (true) {\n"
    "\t\t\tval target = ensureEngine(language)\n"
    "\t\t\tif (target == null) {\n"
    "\t\t\t\tcallback.error(TextToSpeech.ERROR_SERVICE)\n"
    "\t\t\t\treturn\n"
    "\t\t\t}\n"
    "\t\t\t// Only check identity under the lock; never synthesize while holding it.\n"
    "\t\t\t// Otherwise openEngine/onDestroy/dict-load block until the utterance ends\n"
    "\t\t\t// (hang after restart / engine deactivate with external dictionaries).\n"
    "\t\t\tval stillCurrent = synchronized(engineStateLock) { engine === target }\n"
    "\t\t\tif (!stillCurrent) continue\n"
    "\t\t\tsynthesizeWithEngine(target, request, callback, requestGeneration, s)\n"
    "\t\t\treturn\n"
    "\t\t}\n"
)
if old_loop not in text:
    if "stillCurrent" in text:
        print("v3 loop already applied")
    else:
        raise SystemExit("onSynthesizeText loop not found")
else:
    text = text.replace(old_loop, new_loop, 1)
    print("applied synthesis lock fix")

old_destroy = (
    "\toverride fun onDestroy() {\n"
    "\t\tsuper.onDestroy()\n"
    "\t\tengineExecutor.shutdown()\n"
    "\t\ttry {\n"
    "\t\t\tif (!engineExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {\n"
    "\t\t\t\tengineExecutor.shutdownNow()\n"
    "\t\t\t}\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tengineExecutor.shutdownNow()\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
    "\t\tsynchronized(engineStateLock) {\n"
    "\t\t\tenginesByLanguage.values.toSet().forEach { it.close() }\n"
    "\t\t\tenginesByLanguage.clear()\n"
    "\t\t\tappliedRevisions.clear()\n"
    "\t\t\tloadedDictionaries.clear()\n"
    "\t\t\tengine = null\n"
    "\t\t}\n"
    "\t}"
)
new_destroy = (
    "\toverride fun onDestroy() {\n"
    "\t\tsuper.onDestroy()\n"
    "\t\t// Abort speech first so close/dict work cannot sit behind a long utterance.\n"
    "\t\tstopGeneration.incrementAndGet()\n"
    "\t\tengine?.abort()\n"
    "\t\tengineExecutor.shutdownNow()\n"
    "\t\ttry {\n"
    "\t\t\tengineExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)\n"
    "\t\t} catch (interrupted: InterruptedException) {\n"
    "\t\t\tThread.currentThread().interrupt()\n"
    "\t\t}\n"
    "\t\tsynchronized(engineStateLock) {\n"
    "\t\t\tenginesByLanguage.values.toSet().forEach {\n"
    "\t\t\t\ttry { it.abort() } catch (_: Exception) {}\n"
    "\t\t\t\ttry { it.close() } catch (_: Exception) {}\n"
    "\t\t\t}\n"
    "\t\t\tenginesByLanguage.clear()\n"
    "\t\t\tappliedRevisions.clear()\n"
    "\t\t\tloadedDictionaries.clear()\n"
    "\t\t\tengine = null\n"
    "\t\t}\n"
    "\t}"
)
if old_destroy not in text:
    if "engineExecutor.shutdownNow()" in text and "stopGeneration.incrementAndGet()" in text:
        print("v3 onDestroy already applied")
    else:
        raise SystemExit("onDestroy block not found")
else:
    text = text.replace(old_destroy, new_destroy, 1)
    print("applied onDestroy fix")

path.write_text(text)
print("v3 extra done, chars", len(text))
