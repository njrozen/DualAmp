// JNI bridge between the Kotlin visualizer and projectM.
// Every function here must be called on the GLSurfaceView render thread, with its context current.

#include <jni.h>
#include <android/log.h>
#include <projectM-4/projectM.h>

#include <algorithm>
#include <string>
#include <vector>

#define TAG "MilkBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

enum EventFlags : int {
    kSwitchRequested = 1, // preset duration elapsed, Kotlin should pick the next preset
    kHardCut = 2,         // ...and it was a beat-triggered hard cut (switch without blending)
    kPresetFailed = 4,    // the last preset could not be compiled
};

struct Bridge {
    projectm_handle pm = nullptr;
    int events = 0;
    std::string lastError;
};

void onSwitchRequested(bool isHardCut, void* user) {
    auto* b = static_cast<Bridge*>(user);
    b->events |= kSwitchRequested | (isHardCut ? kHardCut : 0);
}

void onSwitchFailed(const char* file, const char* message, void* user) {
    auto* b = static_cast<Bridge*>(user);
    b->events |= kPresetFailed;
    b->lastError = std::string(file ? file : "?") + ": " + (message ? message : "unknown error");
    LOGW("Preset failed: %s", b->lastError.c_str());
}

Bridge* get(jlong h) { return reinterpret_cast<Bridge*>(h); }

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dualamp_viz_MilkNative_create(JNIEnv* env, jobject, jint width, jint height,
                                       jobjectArray texturePaths, jint meshX, jint meshY) {
    projectm_handle pm = projectm_create();
    if (!pm) {
        LOGW("projectm_create failed (GLES 3 context required)");
        return 0;
    }
    auto* b = new Bridge();
    b->pm = pm;

    std::vector<std::string> paths;
    jsize n = texturePaths ? env->GetArrayLength(texturePaths) : 0;
    for (jsize i = 0; i < n; i++) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(texturePaths, i));
        paths.push_back(str(env, s));
        env->DeleteLocalRef(s);
    }
    std::vector<const char*> cpaths;
    for (auto& p : paths) cpaths.push_back(p.c_str());
    if (!cpaths.empty()) projectm_set_texture_search_paths(pm, cpaths.data(), cpaths.size());

    projectm_set_window_size(pm, width, height);
    projectm_set_mesh_size(pm, meshX, meshY);
    projectm_set_fps(pm, 60);
    projectm_set_aspect_correction(pm, true);
    projectm_set_preset_switch_requested_event_callback(pm, onSwitchRequested, b);
    projectm_set_preset_switch_failed_event_callback(pm, onSwitchFailed, b);

    int major = 0, minor = 0, patch = 0;
    projectm_get_version_components(&major, &minor, &patch);
    LOGI("projectM %d.%d.%d ready (%dx%d)", major, minor, patch, width, height);
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_destroy(JNIEnv*, jobject, jlong h) {
    auto* b = get(h);
    if (!b) return;
    projectm_destroy(b->pm);
    delete b;
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_resize(JNIEnv*, jobject, jlong h, jint w, jint hgt) {
    if (auto* b = get(h)) projectm_set_window_size(b->pm, w, hgt);
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_render(JNIEnv*, jobject, jlong h) {
    if (auto* b = get(h)) projectm_opengl_render_frame(b->pm);
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_addPcm(JNIEnv* env, jobject, jlong h, jfloatArray data, jint frames) {
    auto* b = get(h);
    if (!b || frames <= 0) return;
    jfloat* samples = env->GetFloatArrayElements(data, nullptr);
    // projectM keeps its own ring buffer; feeding in slices keeps each call within its limit.
    const unsigned int maxFrames = projectm_pcm_get_max_samples();
    for (jint off = 0; off < frames; off += maxFrames) {
        unsigned int count = std::min<unsigned int>(maxFrames, frames - off);
        projectm_pcm_add_float(b->pm, samples + off * 2, count, PROJECTM_STEREO);
    }
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_loadPresetData(JNIEnv* env, jobject, jlong h, jstring data, jboolean smooth) {
    if (auto* b = get(h)) {
        std::string text = str(env, data);
        projectm_load_preset_data(b->pm, text.c_str(), smooth);
    }
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_loadPresetFile(JNIEnv* env, jobject, jlong h, jstring path, jboolean smooth) {
    if (auto* b = get(h)) {
        std::string p = str(env, path);
        projectm_load_preset_file(b->pm, p.c_str(), smooth);
    }
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_configure(JNIEnv*, jobject, jlong h, jdouble presetDuration,
                                          jdouble softCut, jboolean hardCuts, jfloat beatSensitivity) {
    auto* b = get(h);
    if (!b) return;
    // A duration of 0 means "manual only"; projectM needs a positive value, so use a very long one.
    projectm_set_preset_duration(b->pm, presetDuration > 0 ? presetDuration : 1e9);
    projectm_set_soft_cut_duration(b->pm, softCut);
    projectm_set_hard_cut_enabled(b->pm, hardCuts);
    projectm_set_hard_cut_duration(b->pm, 20.0);
    projectm_set_hard_cut_sensitivity(b->pm, 2.0f);
    projectm_set_beat_sensitivity(b->pm, beatSensitivity);
}

JNIEXPORT void JNICALL
Java_com_dualamp_viz_MilkNative_setLocked(JNIEnv*, jobject, jlong h, jboolean locked) {
    if (auto* b = get(h)) projectm_set_preset_locked(b->pm, locked);
}

JNIEXPORT jint JNICALL
Java_com_dualamp_viz_MilkNative_pollEvents(JNIEnv*, jobject, jlong h) {
    auto* b = get(h);
    if (!b) return 0;
    int e = b->events;
    b->events = 0;
    return e;
}

JNIEXPORT jstring JNICALL
Java_com_dualamp_viz_MilkNative_lastError(JNIEnv* env, jobject, jlong h) {
    auto* b = get(h);
    return env->NewStringUTF(b ? b->lastError.c_str() : "");
}

} // extern "C"
