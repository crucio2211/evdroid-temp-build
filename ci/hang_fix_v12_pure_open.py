#!/usr/bin/env python3
"""v12: pure openEngine (zero extra work) + process-wide native create lock."""
from pathlib import Path

eng_path = Path("app/src/main/kotlin/org/evvdroid/EvvEngine.kt")
eng = eng_path.read_text()
if "nativeOpenLock" not in eng:
    old2 = (
        "\t\tfun open(language: Int): EvvEngine? {\n"
        "\t\t\tif (!EvvNative.loaded) {\n"
        "\t\t\t\tLog.e(TAG, \"the native library did not load\", EvvNative.loadError)\n"
        "\t\t\t\treturn null\n"
        "\t\t\t}\n"
        "\t\t\tval handle = EvvNative.create(language)\n"
        "\t\t\tif (handle == 0L) {\n"
        "\t\t\t\tLog.e(TAG, \"no engine instance for language 0x%08x\".format(language))\n"
        "\t\t\t\treturn null\n"
        "\t\t\t}\n"
        "\t\t\treturn EvvEngine(handle, language).apply { configure() }\n"
        "\t\t}\n"
        "\t}"
    )
    new = (
        "\t\tprivate val nativeOpenLock = Any()\n"
        "\n"
        "\t\tfun open(language: Int): EvvEngine? = synchronized(nativeOpenLock) {\n"
        "\t\t\tif (!EvvNative.loaded) {\n"
        "\t\t\t\tLog.e(TAG, \"the native library did not load\", EvvNative.loadError)\n"
        "\t\t\t\treturn null\n"
        "\t\t\t}\n"
        "\t\t\tval t0 = System.nanoTime()\n"
        "\t\t\tval handle = EvvNative.create(language)\n"
        "\t\t\tLog.i(TAG, \"eciNewEx 0x%08x -> handle in %d ms\".format(\n"
        "\t\t\t\tlanguage, (System.nanoTime() - t0) / 1_000_000L))\n"
        "\t\t\tif (handle == 0L) {\n"
        "\t\t\t\tLog.e(TAG, \"no engine instance for language 0x%08x\".format(language))\n"
        "\t\t\t\treturn null\n"
        "\t\t\t}\n"
        "\t\t\treturn EvvEngine(handle, language).apply { configure() }\n"
        "\t\t}\n"
        "\t}"
    )
    if old2 not in eng:
        raise SystemExit("EvvEngine.open not found")
    eng = eng.replace(old2, new, 1)
    eng_path.write_text(eng)
    print("EvvEngine.open serialized + timed")
else:
    print("nativeOpenLock already present")

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

quick = (
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
)
if quick in text:
    text = text.replace(quick, "\t\tval s = settings\n", 1)
    print("removed quick settings from openEngine")
else:
    print("quick settings block not present or already removed")

text = text.replace("open in %d ms (dicts async)", "open in %d ms (pure)")
text = text.replace("open in %d ms (voice+dicts deferred)", "open in %d ms (pure)")
text = text.replace(
    "requestEngine(language).get(5, java.util.concurrent.TimeUnit.SECONDS)",
    "requestEngine(language).get(2, java.util.concurrent.TimeUnit.SECONDS)",
)
text = text.replace("timed out after 5s", "timed out after 2s")
text = text.replace(
    "requestEngine(language).get(8, java.util.concurrent.TimeUnit.SECONDS)",
    "requestEngine(language).get(2, java.util.concurrent.TimeUnit.SECONDS)",
)
text = text.replace("timed out after 8s", "timed out after 2s")

path.write_text(text)
print("v12 service done, chars", len(text))
print("v12 ok")
