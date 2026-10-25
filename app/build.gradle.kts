import org.gradle.internal.os.OperatingSystem

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

val abis = (findProperty("evvdroid.abis") as String? ?: "arm64-v8a,armeabi-v7a,x86_64")
	.split(",").map { it.trim() }.filter { it.isNotEmpty() }
val rulesForm = findProperty("evvdroid.rules") as String? ?: "c"
val languages = findProperty("evvdroid.langs") as String? ?: "lang/enus"
// One library per language, opened when that language is first spoken: 1, the
// default. 0 builds every language into the one library, as before.
val splitLanguages = findProperty("evvdroid.split") as String? ?: "1"
// CI passes its run number so every build installs as an update over the
// last: same versionCode twice and Android may keep the old APK in place.
val versionCodeNumber = (findProperty("evvdroid.versionCode") as String?)?.toIntOrNull() ?: 1
val versionNameText = findProperty("evvdroid.versionName") as String? ?: "0.1.0"
val nativeOut = layout.buildDirectory.dir("native/jniLibs")

// The engine is built by its own Makefile rather than by CMake, because that
// Makefile writes the language rules with Python before it compiles them and
// nothing here has a reason to reimplement that.
val buildNative by tasks.registering(Exec::class) {
	group = "build"
	description = "Cross-compiles the openevv engine and the JNI bridge for each ABI."
	val engine = rootProject.layout.projectDirectory.dir("native/openevv")
	val script = rootProject.layout.projectDirectory.file("native/build-native.sh")
	// Deliberately not the engine's own sources. The script patches them, so a
	// task declaring them would be rewriting its own inputs every time it ran
	// and would never settle -- which showed up as the first build after any
	// change failing and the next one succeeding. What actually decides the
	// output is the engine's commit, the patches laid on it, and the two
	// files here. Editing native/openevv by hand wants --rerun-tasks.
	// A file tree rather than a directory, because there is nothing to patch at
	// the moment and git does not carry an empty one.
	inputs.files(rootProject.fileTree("native/patches"))
	inputs.file(rootProject.layout.projectDirectory.file("native/android.mk"))
	inputs.file(script)
	inputs.file(layout.projectDirectory.file("src/main/cpp/evv_jni.c"))
	inputs.file(layout.projectDirectory.file("src/main/cpp/evv_dispatch.c"))
	inputs.property("abis", abis)
	inputs.property("rules", rulesForm)
	inputs.property("langs", languages)
	inputs.property("split", splitLanguages)
	outputs.dir(nativeOut)
	val shell = if (OperatingSystem.current().isWindows) {
		val git = System.getenv("PROGRAMFILES")?.let { file("$it/Git/bin/bash.exe") }
		if (git != null && git.exists()) git.absolutePath else "bash"
	} else {
		"bash"
	}
	commandLine(shell, script.asFile.absolutePath)
	environment("ABIS", abis.joinToString(" "))
	environment("RULES", rulesForm)
	environment("LANGS", languages)
	environment("SPLIT", splitLanguages)
	environment("OUT", nativeOut.get().asFile.absolutePath)
	doFirst {
		if (!engine.file("Makefile").asFile.exists()) {
			throw GradleException("native/openevv is empty: the engine tree belongs there")
		}
	}
}

android {
	namespace = "org.evvdroid"
	compileSdk = 36
	ndkVersion = "26.1.10909125"

	defaultConfig {
		applicationId = "org.evvdroid"
		minSdk = 23
		targetSdk = 36
		versionCode = versionCodeNumber
		versionName = versionNameText
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		// The UI is English-only; AndroidX libraries ship strings for a
		// hundred locales the app never shows.
		resConfigs("en")
		ndk {
			abiFilters += abis
		}
	}

	sourceSets.getByName("androidTest") {
		kotlin.srcDirs("src/androidTest/kotlin")
	}

	sourceSets.getByName("main") {
		kotlin.srcDirs("src/main/kotlin")
		jniLibs.srcDirs(nativeOut)
	}

	// One key for every CI build, so each installs as an update over the
	// last. The keystore itself travels as a secret and never lands in the
	// tree; without its properties local builds keep the default debug key.
	signingConfigs {
		create("fixedDebug") {
			val storePath = findProperty("evvdroid.keystore") as String?
			if (!storePath.isNullOrBlank()) {
				storeFile = file(storePath)
				storePassword = findProperty("evvdroid.storePass") as String?
				keyAlias = "evvdroid"
				keyPassword = findProperty("evvdroid.keyPass") as String?
				storeType = "PKCS12"
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			if (!(findProperty("evvdroid.keystore") as String?).isNullOrBlank()) {
				signingConfig = signingConfigs.getByName("fixedDebug")
			}
		}
		debug {
			isJniDebuggable = true
			// The fixed CI key when its properties are here, else the
			// default debug key for local builds.
			if (!(findProperty("evvdroid.keystore") as String?).isNullOrBlank()) {
				signingConfig = signingConfigs.getByName("fixedDebug")
			}
			// Shrinking a debug build is off for the fast loop and on in CI,
			// where install size matters more than readable stack traces.
			// The JNI bridge's names are kept by proguard-rules.pro.
			val minify = (findProperty("evvdroid.minify") as String?)?.toBoolean() ?: false
			isMinifyEnabled = minify
			isShrinkResources = minify
			if (minify) {
				proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			}
		}
	}

	packaging {
		jniLibs {
			// Uncompressed libraries map straight out of the APK, so a
			// screen reader never waits for unpacking. Compressed ones
			// download far smaller and install fast on modern phones, at
			// the cost of disk (APK plus extracted libraries) and a first
			// load from extracted files. CI ships compressed.
			useLegacyPackaging = (findProperty("evvdroid.legacyPackaging") as String?)?.toBoolean() ?: false
		}
	}

	splits {
		abi {
			isEnable = (findProperty("evvdroid.abiSplits") as String?)?.toBoolean() ?: false
			reset()
			include(*abis.toTypedArray())
			isUniversalApk = true
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		buildConfig = true
		compose = true
	}
}

tasks.named("preBuild") {
	dependsOn(buildNative)
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.material)
	implementation(libs.androidx.activity.compose)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.material3)

	androidTestImplementation(libs.junit)
	androidTestImplementation(libs.androidx.test.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.core)
}
