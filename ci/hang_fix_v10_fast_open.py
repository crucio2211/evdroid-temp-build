#!/usr/bin/env python3
"""v10: minimal openEngine + InitProvider prewarm for cold eciNewEx."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

old = (
    "\t\tval s = settings\n"
    "\t\tif (s != null) {\n"
    "\t\t\ttry {\n"
    "\t\t\t\ttarget.setSampleRate(s.sampleRateHz)\n"
    "\t\t\t\ttarget.setAbbreviations(s.abbreviations)\n"
    "\t\t\t\ttarget.applyVoice(s.voice, s.shape(s.voice))\n"
    "\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\tLog.e(TAG, \"quick settings failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\t// Native eciLoadDict on dictExecutor only — never block engine open.\n"
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\t// Wait out first TalkBack utterance (and any live speech).\n"
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
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
    "\t\tLog.i(TAG, \"engine 0x%08x open in %d ms (dicts async)\".format(\n"
    "\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\treturn target\n"
)
new = (
    "\t\t// CRITICAL PATH: return as soon as eciNewEx finished. Voice + dicts later.\n"
    "\t\tval s = settings\n"
    "\t\tif (scheduleDictLoad && s != null) {\n"
    "\t\t\tdictExecutor.execute {\n"
    "\t\t\t\ttry {\n"
    "\t\t\t\t\twhile (deferDictLoad || speaking) {\n"
    "\t\t\t\t\t\ttry { Thread.sleep(40) } catch (_: InterruptedException) {\n"
    "\t\t\t\t\t\t\tThread.currentThread().interrupt()\n"
    "\t\t\t\t\t\t\treturn@execute\n"
    "\t\t\t\t\t\t}\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\ttry {\n"
    "\t\t\t\t\t\ttarget.setSampleRate(s.sampleRateHz)\n"
    "\t\t\t\t\t\ttarget.setAbbreviations(s.abbreviations)\n"
    "\t\t\t\t\t\ttarget.applyVoice(s.voice, s.shape(s.voice))\n"
    "\t\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\t\tLog.e(TAG, \"deferred voice failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t\t}\n"
    "\t\t\t\t\tapplySettings(target)\n"
    "\t\t\t\t\tLog.i(TAG, \"engine 0x%08x dictionaries ready in %d ms\".format(\n"
    "\t\t\t\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\t\t\t} catch (failed: Exception) {\n"
    "\t\t\t\t\tLog.e(TAG, \"dictionary load failed for 0x%08x\".format(language), failed)\n"
    "\t\t\t\t}\n"
    "\t\t\t}\n"
    "\t\t}\n"
    "\t\tLog.i(TAG, \"engine 0x%08x open in %d ms (voice+dicts deferred)\".format(\n"
    "\t\t\tlanguage, (System.nanoTime() - started) / 1_000_000L))\n"
    "\t\treturn target\n"
)
if old not in text:
    if "voice+dicts deferred" in text:
        print("openEngine already minimal")
    else:
        raise SystemExit("openEngine post-sync block not found")
else:
    text = text.replace(old, new, 1)
    print("openEngine minimal critical path")

old_open = "\t\t\t\t\tval made = EvvEngine.open(language) ?: return null\n"
new_open = "\t\t\t\t\tval made = EvvPrewarm.take(language) ?: EvvEngine.open(language) ?: return null\n"
if old_open in text:
    text = text.replace(old_open, new_open, 1)
    print("openEngine uses EvvPrewarm")
elif "EvvPrewarm.take" in text:
    print("EvvPrewarm already wired")
else:
    raise SystemExit("EvvEngine.open site not found")

path.write_text(text)

Path("app/src/main/kotlin/org/evvdroid/EvvInitProvider.kt").write_text(
'''package org.evvdroid

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/** Prewarms native lib + primary engine as soon as the process starts. */
class EvvInitProvider : ContentProvider() {

\toverride fun onCreate(): Boolean {
\t\tThread({
\t\t\ttry {
\t\t\t\tif (!EvvNative.loaded) {
\t\t\t\t\tLog.e(TAG, "native library missing", EvvNative.loadError)
\t\t\t\t\treturn@Thread
\t\t\t\t}\n\t\t\t\tval n = EvvEngine.available.size
\t\t\t\tLog.i(TAG, "prewarm: $n languages linked")
\t\t\t\tval lang = EvvEngine.available.firstOrNull() ?: return@Thread
\t\t\t\tval t0 = System.nanoTime()
\t\t\t\tval eng = EvvEngine.open(lang)
\t\t\t\tLog.i(TAG, "prewarm: open 0x%08x in %d ms (%s)".format(
\t\t\t\t\tlang,
\t\t\t\t\t(System.nanoTime() - t0) / 1_000_000L,
\t\t\t\t\tif (eng != null) "ok" else "failed",
\t\t\t\t))
\t\t\t\tif (eng != null) EvvPrewarm.offer(lang, eng)
\t\t\t} catch (failed: Exception) {
\t\t\t\tLog.e(TAG, "prewarm failed", failed)
\t\t\t}\n\t\t}, "evv-prewarm").apply { isDaemon = true }.start()
\t\treturn true
\t}

\toverride fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
\toverride fun getType(uri: Uri): String? = null
\toverride fun insert(uri: Uri, values: ContentValues?): Uri? = null
\toverride fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
\toverride fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

\tcompanion object {
\t\tprivate const val TAG = "evvdroid"
\t}
}
'''
)
print("wrote EvvInitProvider")

Path("app/src/main/kotlin/org/evvdroid/EvvPrewarm.kt").write_text(
'''package org.evvdroid

/** Process-wide handoff from [EvvInitProvider] to [EvvTtsService]. */
object EvvPrewarm {
\t@Volatile private var language: Int = 0
\t@Volatile private var engine: EvvEngine? = null

\tfun offer(lang: Int, eng: EvvEngine) {
\t\tsynchronized(this) {
\t\t\tengine?.close()
\t\t\tlanguage = lang
\t\t\tengine = eng
\t\t}
\t}

\tfun take(lang: Int): EvvEngine? = synchronized(this) {
\t\tval eng = engine
\t\tif (eng == null || language != lang) return null
\t\tengine = null
\t\tlanguage = 0
\t\teng
\t}
}
'''
)
print("wrote EvvPrewarm")

manifest = Path("app/src/main/AndroidManifest.xml")
man = manifest.read_text()
if "EvvInitProvider" not in man:
    man = man.replace(
        "\t</application>",
        "\t\t<provider\n"
        "\t\t\tandroid:name=\".EvvInitProvider\"\n"
        "\t\t\tandroid:authorities=\"${applicationId}.evvinit\"\n"
        "\t\t\tandroid:exported=\"false\"\n"
        "\t\t\tandroid:initOrder=\"1000\" />\n"
        "\n"
        "\t</application>",
        1,
    )
    manifest.write_text(man)
    print("manifest provider registered")
else:
    print("manifest already has provider")

print("v10 ok")
