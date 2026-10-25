/* The JNI library the app loads, when each language is a library of its own.
 *
 * Every language is built into libevv_<tag>.so: the engine, that one
 * language's rules and tables, and the bridge in evv_jni.c. Nothing is mapped
 * for a language nobody speaks, and the first utterance in a language pays for
 * that language alone instead of for all of them.
 *
 * This file is what Java binds to. It holds no engine. A call to create picks
 * the library for the language asked for, opens it the first time, and from
 * then on every call on that instance is handed straight to the same
 * function in that library. The libraries are opened privately, so each keeps
 * its own copy of the engine's state and the same names in different
 * libraries never meet.
 *
 * The handle Java sees is a small record of the library and the library's own
 * handle, so a call finds its way back without a lookup table.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1 /* dladdr, on libraries that keep it behind this */
#endif

#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "evvdroid", __VA_ARGS__)
#else
#define LOGE(...) (fprintf(stderr, "evvdroid: " __VA_ARGS__), fputc('\n', stderr))
#endif

/* EVV_LANG_COUNT, evv_lang_ids[] and evv_lang_libs[], written by the build
   from the languages it was given. */
#include "evv_langs.h"

#define PREFIX "Java_org_evvdroid_EvvNative_"

typedef jlong (*create_fn)(JNIEnv*, jclass, jint);
typedef void (*destroy_fn)(JNIEnv*, jclass, jlong);
typedef jboolean (*speak_fn)(JNIEnv*, jclass, jlong, jbyteArray);
typedef jint (*read_fn)(JNIEnv*, jclass, jlong, jbyteArray);
typedef void (*stop_fn)(JNIEnv*, jclass, jlong);
typedef jint (*load_dictionary_fn)(JNIEnv*, jclass, jlong, jint, jstring);
typedef jint (*teach_word_fn)(JNIEnv*, jclass, jlong, jint, jbyteArray, jbyteArray);
typedef jstring (*look_up_word_fn)(JNIEnv*, jclass, jlong, jint, jbyteArray);
typedef void (*forget_fn)(JNIEnv*, jclass, jlong);
typedef jint (*set_param_fn)(JNIEnv*, jclass, jlong, jint, jint);
typedef jint (*get_param_fn)(JNIEnv*, jclass, jlong, jint);
typedef jint (*set_voice_param_fn)(JNIEnv*, jclass, jlong, jint, jint, jint);
typedef jint (*get_voice_param_fn)(JNIEnv*, jclass, jlong, jint, jint);
typedef jint (*copy_voice_fn)(JNIEnv*, jclass, jlong, jint, jint);
typedef jstring (*version_fn)(JNIEnv*, jclass);

typedef struct {
	void*              handle;
	create_fn          create;
	destroy_fn         destroy;
	speak_fn           speak;
	read_fn            read;
	stop_fn            stop;
	load_dictionary_fn load_dictionary;
	teach_word_fn      teach_word;
	look_up_word_fn    look_up_word;
	forget_fn          forget;
	set_param_fn       set_param;
	get_param_fn       get_param;
	set_voice_param_fn set_voice_param;
	get_voice_param_fn get_voice_param;
	copy_voice_fn      copy_voice;
	version_fn         version;
} language_lib;

typedef struct {
	language_lib* lib;
	jlong         inner;
} instance;

static language_lib    libs[EVV_LANG_COUNT];
static pthread_mutex_t libs_lock = PTHREAD_MUTEX_INITIALIZER;

static instance* unwrap(jlong handle) {
	return (instance*)(intptr_t)handle;
}

/* Where this library sits, so a language library beside it can be named by
   path when its bare name is not found. */
static int sibling_path(const char* leaf, char* out, size_t room) {
	Dl_info info;
	const char* slash;
	size_t dir;
	if (!dladdr((void*)&sibling_path, &info) || info.dli_fname == NULL)
		return 0;
	slash = strrchr(info.dli_fname, '/');
	if (slash == NULL)
		return 0;
	dir = (size_t)(slash - info.dli_fname) + 1;
	if (dir + strlen(leaf) + 1 > room)
		return 0;
	memcpy(out, info.dli_fname, dir);
	strcpy(out + dir, leaf);
	return 1;
}

#define BIND(field, name)                                   \
	do {                                                    \
		lib->field = dlsym(lib->handle, PREFIX name);       \
		if (lib->field == NULL) {                           \
			LOGE("%s has no " PREFIX name, evv_lang_libs[i]); \
			goto fail;                                      \
		}                                                   \
	} while (0)

/* The library for language number i in the table, opened the first time. A
   library that would not open is tried again the next time it is asked for. */
static language_lib* open_lib(int i) {
	language_lib* lib = &libs[i];
	char          path[512];
	pthread_mutex_lock(&libs_lock);
	if (lib->handle != NULL) {
		pthread_mutex_unlock(&libs_lock);
		return lib;
	}
	lib->handle = dlopen(evv_lang_libs[i], RTLD_NOW | RTLD_LOCAL);
	if (lib->handle == NULL && sibling_path(evv_lang_libs[i], path, sizeof path))
		lib->handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	if (lib->handle == NULL) {
		LOGE("cannot open %s: %s", evv_lang_libs[i], dlerror());
		pthread_mutex_unlock(&libs_lock);
		return NULL;
	}
	BIND(create, "create");
	BIND(destroy, "destroy");
	BIND(speak, "speak");
	BIND(read, "read");
	BIND(stop, "stop");
	BIND(load_dictionary, "loadDictionary");
	BIND(teach_word, "teachWord");
	BIND(look_up_word, "lookUpWord");
	BIND(forget, "forgetDictionaries");
	BIND(set_param, "setParam");
	BIND(get_param, "getParam");
	BIND(set_voice_param, "setVoiceParam");
	BIND(get_voice_param, "getVoiceParam");
	BIND(copy_voice, "copyVoice");
	BIND(version, "version");
	pthread_mutex_unlock(&libs_lock);
	return lib;
fail:
	dlclose(lib->handle);
	memset(lib, 0, sizeof *lib);
	pthread_mutex_unlock(&libs_lock);
	return NULL;
}

/* Which place in the table a language number is: the exact one, else the one
   that shares its family and dialect with the code set left out. */
static int find_language(jint language) {
	int i;
	for (i = 0; i < EVV_LANG_COUNT; i++)
		if ((jint)evv_lang_ids[i] == language)
			return i;
	for (i = 0; i < EVV_LANG_COUNT; i++)
		if (((jint)evv_lang_ids[i] & 0xFFFF00FF) == (language & 0xFFFF00FF))
			return i;
	return -1;
}

JNIEXPORT jlong JNICALL Java_org_evvdroid_EvvNative_create(JNIEnv* env, jclass cls, jint language) {
	int           i = find_language(language);
	language_lib* lib;
	instance*     in;
	jlong         inner;
	if (i < 0) {
		LOGE("no library for language %#x", (unsigned)language);
		return 0;
	}
	lib = open_lib(i);
	if (lib == NULL)
		return 0;
	/* The language word as the app wrote it, code set and all: the table only
	   says which library it belongs to. */
	inner = lib->create(env, cls, language);
	if (inner == 0)
		return 0;
	in = (instance*)calloc(1, sizeof *in);
	if (in == NULL) {
		lib->destroy(env, cls, inner);
		return 0;
	}
	in->lib = lib;
	in->inner = inner;
	return (jlong)(intptr_t)in;
}

JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_destroy(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	if (in == NULL)
		return;
	in->lib->destroy(env, cls, in->inner);
	free(in);
}

JNIEXPORT jboolean JNICALL Java_org_evvdroid_EvvNative_speak(JNIEnv* env, jclass cls, jlong handle, jbyteArray text) {
	instance* in = unwrap(handle);
	return in == NULL ? JNI_FALSE : in->lib->speak(env, cls, in->inner, text);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_read(JNIEnv* env, jclass cls, jlong handle, jbyteArray dst) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->read(env, cls, in->inner, dst);
}

JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_stop(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	if (in != NULL)
		in->lib->stop(env, cls, in->inner);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_loadDictionary(JNIEnv* env, jclass cls, jlong handle, jint volume, jstring path) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->load_dictionary(env, cls, in->inner, volume, path);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_teachWord(JNIEnv* env, jclass cls, jlong handle, jint volume, jbyteArray key, jbyteArray say) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->teach_word(env, cls, in->inner, volume, key, say);
}

JNIEXPORT jstring JNICALL Java_org_evvdroid_EvvNative_lookUpWord(JNIEnv* env, jclass cls, jlong handle, jint volume, jbyteArray key) {
	instance* in = unwrap(handle);
	return in == NULL ? NULL : in->lib->look_up_word(env, cls, in->inner, volume, key);
}

JNIEXPORT void JNICALL Java_org_evvdroid_EvvNative_forgetDictionaries(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	if (in != NULL)
		in->lib->forget(env, cls, in->inner);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_setParam(JNIEnv* env, jclass cls, jlong handle, jint param, jint value) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->set_param(env, cls, in->inner, param, value);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_getParam(JNIEnv* env, jclass cls, jlong handle, jint param) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->get_param(env, cls, in->inner, param);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_setVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param, jint value) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->set_voice_param(env, cls, in->inner, voice, param, value);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_getVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->get_voice_param(env, cls, in->inner, voice, param);
}

JNIEXPORT jint JNICALL Java_org_evvdroid_EvvNative_copyVoice(JNIEnv* env, jclass cls, jlong handle, jint from, jint to) {
	instance* in = unwrap(handle);
	return in == NULL ? -1 : in->lib->copy_voice(env, cls, in->inner, from, to);
}

/* The languages the build was given, from the table. Nothing is opened to
   answer, so asking which languages there are costs no more than the table. */
JNIEXPORT jintArray JNICALL Java_org_evvdroid_EvvNative_languages(JNIEnv* env, jclass cls) {
	jintArray out = (*env)->NewIntArray(env, EVV_LANG_COUNT);
	jint      ids[EVV_LANG_COUNT];
	int       i;
	(void)cls;
	if (out == NULL)
		return NULL;
	for (i = 0; i < EVV_LANG_COUNT; i++)
		ids[i] = (jint)evv_lang_ids[i];
	(*env)->SetIntArrayRegion(env, out, 0, EVV_LANG_COUNT, ids);
	return out;
}

/* The engine's version, from whichever language is already open, or else from
   the first in the table. */
JNIEXPORT jstring JNICALL Java_org_evvdroid_EvvNative_version(JNIEnv* env, jclass cls) {
	language_lib* lib = NULL;
	int           i;
	pthread_mutex_lock(&libs_lock);
	for (i = 0; i < EVV_LANG_COUNT && lib == NULL; i++)
		if (libs[i].handle != NULL)
			lib = &libs[i];
	pthread_mutex_unlock(&libs_lock);
	if (lib == NULL)
		lib = open_lib(0);
	if (lib == NULL)
		return (*env)->NewStringUTF(env, "evvdroid");
	return lib->version(env, cls);
}
