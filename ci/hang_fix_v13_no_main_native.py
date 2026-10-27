#!/usr/bin/env python3
"""v13: never call native ECI on main/binder language queries or Service.onCreate."""
from pathlib import Path

path = Path("app/src/main/kotlin/org/evvdroid/EvvTtsService.kt")
text = path.read_text()

if "builtinLanguages" not in text:
    old_match = "\tprivate fun matchLanguage(lang: String?, country: String?): Pair<Int, Int>? {\n"
    if old_match not in text:
        raise SystemExit("matchLanguage not found")
    helper = (
        "\t/** Languages we claim without touching JNI. Native confirm happens at open. */\n"
        "\tprivate fun builtinLanguages(): IntArray = intArrayOf(\n"
        "\t\t0x00010000, 0x00010001, // eng USA GBR\n"
        "\t\t0x00020000, 0x00020001, // spa\n"
        "\t\t0x00030000, 0x00030001, // fra\n"
        "\t\t0x00040000, // deu\n"
        "\t\t0x00050000, // ita\n"
        "\t\t0x00070000, // por BRA\n"
        "\t\t0x00080000, // jpn\n"
        "\t\t0x00090000, // fin\n"
        "\t\t0x00110000, // pol\n"
        "\t)\n"
        "\n"
        "\t/** Always static — never touch JNI on binder/main language queries. */\n"
        "\tprivate fun languagesForQuery(): IntArray {\n"
        "\t\treturn builtinLanguages()\n"
        "\t}\n"
        "\n"
    )
    text = text.replace(old_match, helper + old_match, 1)
    print("added languagesForQuery")

text2 = text
text2 = text2.replace(
    "\t\tfor (candidate in EvvEngine.available) {\n"
    "\t\t\tval loc = Eci.localeOf(candidate) ?: continue\n"
    "\t\t\tif (!loc.first.equals(wantLang, ignoreCase = true)) continue\n",
    "\t\tfor (candidate in languagesForQuery()) {\n"
    "\t\t\tval loc = Eci.localeOf(candidate) ?: continue\n"
    "\t\t\tif (!loc.first.equals(wantLang, ignoreCase = true)) continue\n",
    1,
)
text2 = text2.replace(
    "\t\tval language = engine?.language ?: EvvEngine.available.firstOrNull() ?: return arrayOf(\"eng\", \"USA\", \"\")\n",
    "\t\tval language = engine?.language ?: languagesForQuery().firstOrNull() ?: return arrayOf(\"eng\", \"USA\", \"\")\n",
    1,
)
text2 = text2.replace(
    "\t\tfor (language in EvvEngine.available) {\n"
    "\t\t\tval loc = Eci.localeOf(language) ?: continue\n"
    "\t\t\tval locale = Locale(loc.first, loc.second)\n",
    "\t\tfor (language in languagesForQuery()) {\n"
    "\t\t\tval loc = Eci.localeOf(language) ?: continue\n"
    "\t\t\tval locale = Locale(loc.first, loc.second)\n",
    1,
)
text2 = text2.replace(
    "\t\tfor (language in EvvEngine.available) {\n"
    "\t\t\tfor (preset in Eci.PRESET_NAMES.indices) {\n"
    "\t\t\t\tif (voiceName(language, preset) == name) return language to preset\n",
    "\t\tfor (language in languagesForQuery()) {\n"
    "\t\t\tfor (preset in Eci.PRESET_NAMES.indices) {\n"
    "\t\t\t\tif (voiceName(language, preset) == name) return language to preset\n",
    1,
)
text2 = text2.replace(
    "\t\t\tif (EvvEngine.available.contains(forced)) return forced\n",
    "\t\t\tif (languagesForQuery().contains(forced)) return forced\n",
    1,
)

if text2 == text:
    print("WARN: no available->languagesForQuery replacements")
else:
    text = text2
    print("rewired language queries off forced native list")

if "EvvEngine.available.contains(it)" in text and "dictionary warmup failed" in text:
    text = text.replace(
        "\t\t\tval primary = warm.forcedLanguage.takeIf { EvvEngine.available.contains(it) }\n"
        "\t\t\t\t?: EvvEngine.available.firstOrNull()\n",
        "\t\t\t// Do NOT call EvvEngine.available here — that loads JNI on the main thread.\n"
        "\t\t\tval primary = if (warm.forceLanguage) warm.forcedLanguage else 0x00010000\n",
        1,
    )
    print("onCreate warmup avoids EvvEngine.available")
else:
    print("WARN: onCreate available pattern not found")

path.write_text(text)

Path("app/src/main/kotlin/org/evvdroid/CheckVoiceDataActivity.kt").write_text(
'''package org.evvdroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * What Android asks before it will use the engine.
 * Must NOT touch JNI/ECI on the main thread — that froze TalkBack on first start.
 */
class CheckVoiceDataActivity : Activity() {

\toverride fun onCreate(savedInstanceState: Bundle?) {
\t\tsuper.onCreate(savedInstanceState)
\t\tval available = arrayListOf("eng-USA", "eng-GBR")
\t\tval result = Intent().apply {
\t\t\tputStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
\t\t\tputStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
\t\t}
\t\tsetResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
\t\tfinish()
\t}
}
'''
)
print("CheckVoiceDataActivity static voices")

npath = Path("app/src/main/kotlin/org/evvdroid/EvvNative.kt")
nt = npath.read_text()
if "by lazy" not in nt and "System.loadLibrary" in nt:
    old_init = (
        "\t@Volatile\n"
        "\tvar loadError: Throwable? = null\n"
        "\t\tprivate set\n"
        "\n"
        "\tval loaded: Boolean\n"
        "\n"
        "\tinit {\n"
        "\t\tvar ok = false\n"
        "\t\ttry {\n"
        "\t\t\tSystem.loadLibrary(\"evvjni\")\n"
        "\t\t\tok = true\n"
        "\t\t} catch (e: UnsatisfiedLinkError) {\n"
        "\t\t\tloadError = e\n"
        "\t\t}\n"
        "\t\tloaded = ok\n"
        "\t}\n"
    )
    new_init = (
        "\t@Volatile\n"
        "\tvar loadError: Throwable? = null\n"
        "\t\tprivate set\n"
        "\n"
        "\t/** Loads on first use (engineExecutor), not at class-init on main/binder. */\n"
        "\tval loaded: Boolean by lazy {\n"
        "\t\ttry {\n"
        "\t\t\tSystem.loadLibrary(\"evvjni\")\n"
        "\t\t\ttrue\n"
        "\t\t} catch (e: UnsatisfiedLinkError) {\n"
        "\t\t\tloadError = e\n"
        "\t\t\tfalse\n"
        "\t\t}\n"
        "\t}\n"
    )
    if old_init not in nt:
        raise SystemExit("EvvNative init block not found")
    npath.write_text(nt.replace(old_init, new_init, 1))
    print("EvvNative loadLibrary is lazy")
else:
    print("EvvNative already lazy or unexpected")

print("v13 ok")
