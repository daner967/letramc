#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <string>

#include "whisper.h"

#define TAG "LetraMC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct ProgressCtx {
    JavaVM *vm;
    jobject listener;
    jmethodID mid;
    int last;
};

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user_data) {
    auto *p = static_cast<ProgressCtx *>(user_data);
    if (p == nullptr || p->listener == nullptr || progress == p->last) return;
    p->last = progress;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (p->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (p->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(p->listener, p->mid, static_cast<jint>(progress));
    if (attached) p->vm->DetachCurrentThread();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_letramc_app_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(cpath, cparams);
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_letramc_app_WhisperLib_nativeFree(JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_letramc_app_WhisperLib_nativeTranscribe(JNIEnv *env, jobject, jlong ctxPtr,
                                                 jfloatArray samples, jstring lang,
                                                 jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return -1;

    const char *clang = env->GetStringUTFChars(lang, nullptr);
    std::string language(clang);
    env->ReleaseStringUTFChars(lang, clang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language.c_str();
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = false;
    // Líneas cortas con su tiempo (como las de una letra)
    params.token_timestamps = true;
    params.max_len = 42;
    params.split_on_word = true;

    ProgressCtx pc{nullptr, nullptr, nullptr, -1};
    if (listener != nullptr) {
        env->GetJavaVM(&pc.vm);
        pc.listener = env->NewGlobalRef(listener);
        jclass cls = env->FindClass("com/letramc/app/ProgressListener");
        pc.mid = env->GetMethodID(cls, "onProgress", "(I)V");
        params.progress_callback = on_progress;
        params.progress_callback_user_data = &pc;
    }

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    LOGI("whisper_full: %d muestras, idioma %s, hilos %d", static_cast<int>(n), language.c_str(), static_cast<int>(threads));
    int ret = whisper_full(ctx, params, data, static_cast<int>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (pc.listener != nullptr) env->DeleteGlobalRef(pc.listener);
    return ret;
}

// Devuelve líneas "centésimas|texto\n" en UTF-8
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_letramc_app_WhisperLib_nativeResult(JNIEnv *env, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    std::string out;
    if (ctx != nullptr) {
        int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; i++) {
            long long t0 = static_cast<long long>(whisper_full_get_segment_t0(ctx, i));
            const char *text = whisper_full_get_segment_text(ctx, i);
            std::string line = text != nullptr ? text : "";
            for (auto &c : line) {
                if (c == '\n' || c == '\r') c = ' ';
            }
            out += std::to_string(t0) + "|" + line + "\n";
        }
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}
