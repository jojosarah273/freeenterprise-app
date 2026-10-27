// ---------------------------------------------------------------------------
// ff4emu — our frontend around the bundled libretro Snes9x core.
//
// Exposes a small, purpose-built JNI surface for the app:
//   init(corePath, systemDir, saveDir)
//   loadRom(path) / unload()
//   setPixelFormat()               (tell the core we want RGB565)
//   run()                          (one frame; returns audio frames produced)
//   audioBuffer() / clearAudio()
//   setButton(port, id, pressed)
//   saveRam() / saveRamSize() / flushSaveRam()
//   setVideoBufferPointer(...)     (direct ByteBuffer, no copy)
//
// The core is the real Snes9x libretro core (retro_* API); we implement the
// frontend side of that API here. No RetroArch is involved.
// ---------------------------------------------------------------------------
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <vector>
#include <dlfcn.h>

#define LOG_TAG "FF4Emu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "libretro/libretro.h"

// ---------------------------------------------------------------- libretro API
typedef void (*fn_retro_init)(void);
typedef void (*fn_retro_deinit)(void);
typedef unsigned (*fn_retro_api_version)(void);
typedef void (*fn_retro_get_system_info)(struct retro_system_info*);
typedef void (*fn_retro_get_system_av_info)(struct retro_system_av_info*);
typedef void (*fn_retro_set_environment)(retro_environment_t);
typedef void (*fn_retro_set_video_refresh)(retro_video_refresh_t);
typedef void (*fn_retro_set_audio_sample)(retro_audio_sample_t);
typedef void (*fn_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
typedef void (*fn_retro_set_input_poll)(retro_input_poll_t);
typedef void (*fn_retro_set_input_state)(retro_input_state_t);
typedef void (*fn_retro_set_controller_port_device)(unsigned, unsigned);
typedef bool (*fn_retro_load_game)(const struct retro_game_info*);
typedef void (*fn_retro_unload_game)(void);
typedef void (*fn_retro_run)(void);
typedef void* (*fn_retro_get_memory_data)(unsigned);
typedef size_t (*fn_retro_get_memory_size)(unsigned);
typedef size_t (*fn_retro_serialize_size)(void);
typedef bool (*fn_retro_serialize)(void*, size_t);
typedef bool (*fn_retro_unserialize)(const void*, size_t);

static fn_retro_init                     p_retro_init;
static fn_retro_deinit                   p_retro_deinit;
static fn_retro_api_version              p_retro_api_version;
static fn_retro_get_system_info          p_retro_get_system_info;
static fn_retro_get_system_av_info       p_retro_get_system_av_info;
static fn_retro_set_environment          p_retro_set_environment;
static fn_retro_set_video_refresh        p_retro_set_video_refresh;
static fn_retro_set_audio_sample         p_retro_set_audio_sample;
static fn_retro_set_audio_sample_batch   p_retro_set_audio_sample_batch;
static fn_retro_set_input_poll           p_retro_set_input_poll;
static fn_retro_set_input_state          p_retro_set_input_state;
static fn_retro_set_controller_port_device p_retro_set_controller_port_device;
static fn_retro_load_game                p_retro_load_game;
static fn_retro_unload_game              p_retro_unload_game;
static fn_retro_run                      p_retro_run;
static fn_retro_get_memory_data          p_retro_get_memory_data;
static fn_retro_get_memory_size          p_retro_get_memory_size;
static fn_retro_serialize_size           p_retro_serialize_size;
static fn_retro_serialize                p_retro_serialize;
static fn_retro_unserialize              p_retro_unserialize;

// ------------------------------------------------------------------- our state
static std::string g_system_dir;
static std::string g_save_dir;
static std::string g_last_error;

static uint8_t* g_video_buffer = nullptr;   // Java-owned direct ByteBuffer
static int      g_video_width  = 0;
static int      g_video_height = 0;
static bool     g_frame_ready  = false;
static bool     g_use_rgb565   = false;

static std::vector<int16_t> g_audio;        // interleaved stereo
static uint16_t g_buttons = 0;              // RETRO_DEVICE_ID_JOYPAD_* bitfield

// ------------------------------------------------------------------ callbacks
static void cb_video_refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;                       // duped frame
    if (!g_video_buffer) return;

    g_video_width = (int) width;
    g_video_height = (int) height;

    if (g_use_rgb565) {
        const int bpp = 2;
        const uint8_t* src = (const uint8_t*) data;
        uint8_t* dst = g_video_buffer;
        for (unsigned y = 0; y < height; y++) {
            memcpy(dst, src, (size_t) width * bpp);
            src += pitch;
            dst += (size_t) width * bpp;
        }
    } else {
        // XRGB8888 -> RGBA8888
        const uint8_t* src = (const uint8_t*) data;
        uint32_t* dst = (uint32_t*) g_video_buffer;
        for (unsigned y = 0; y < height; y++) {
            const uint32_t* row = (const uint32_t*) (src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint32_t px = row[x];
                uint32_t r = (px >> 16) & 0xFF;
                uint32_t g = (px >> 8) & 0xFF;
                uint32_t b = px & 0xFF;
                dst[y * width + x] = (0xFFu << 24) | (b << 16) | (g << 8) | r;
            }
        }
    }
    g_frame_ready = true;
}

static size_t cb_audio_sample_batch(const int16_t* data, size_t frames) {
    g_audio.insert(g_audio.end(), data, data + frames * 2);
    return frames;
}

static void cb_audio_sample(int16_t left, int16_t right) {
    g_audio.push_back(left);
    g_audio.push_back(right);
}

static void cb_input_poll(void) {}

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    if (index != 0) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) g_buttons;
    if (id > 15) return 0;
    return (g_buttons >> id) & 1;
}

static void cb_log(enum retro_log_level level, const char* fmt, ...) {
    (void) level;
    (void) fmt;
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const enum retro_pixel_format* fmt = (const enum retro_pixel_format*) data;
            if (*fmt == RETRO_PIXEL_FORMAT_RGB565) {
                g_use_rgb565 = true;
                LOGI("pixel format: RGB565");
                return true;
            }
            // Decline anything else so the core keeps its default (RGB565).
            LOGI("pixel format %d declined", (int) *fmt);
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char**) data = g_system_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char**) data = g_save_dir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *(bool*) data = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback* cb = (struct retro_log_callback*) data;
            cb->log = cb_log;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable* var = (struct retro_variable*) data;
            var->value = nullptr;      // use core defaults
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            *(bool*) data = false;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            *(unsigned*) data = RETRO_LANGUAGE_ENGLISH;
            return true;
        }
        default:
            return false;
    }
}

// ---------------------------------------------------------------- JNI surface
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeInit(
        JNIEnv* env, jobject, jstring corePath, jstring systemDir, jstring saveDir) {

    const char* path = env->GetStringUTFChars(corePath, nullptr);
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        // With mapped native libs the absolute path may not exist; the core is
        // loaded by name (System.loadLibrary("retro")) so try that too.
        LOGI("dlopen(%s) failed (%s); trying by name", path, dlerror());
        handle = dlopen("libretro.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!handle) {
        handle = dlopen("libretro", RTLD_NOW | RTLD_LOCAL);
    }
    env->ReleaseStringUTFChars(corePath, path);
    if (!handle) {
        g_last_error = std::string("could not load libretro.so: ") + dlerror();
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    #define SYM(v, n) v = (fn_##n) dlsym(handle, #n); if (!v) { LOGE("missing %s", #n); return JNI_FALSE; }
    SYM(p_retro_init, retro_init)
    SYM(p_retro_deinit, retro_deinit)
    SYM(p_retro_api_version, retro_api_version)
    SYM(p_retro_get_system_info, retro_get_system_info)
    SYM(p_retro_get_system_av_info, retro_get_system_av_info)
    SYM(p_retro_set_environment, retro_set_environment)
    SYM(p_retro_set_video_refresh, retro_set_video_refresh)
    SYM(p_retro_set_audio_sample, retro_set_audio_sample)
    SYM(p_retro_set_audio_sample_batch, retro_set_audio_sample_batch)
    SYM(p_retro_set_input_poll, retro_set_input_poll)
    SYM(p_retro_set_input_state, retro_set_input_state)
    SYM(p_retro_set_controller_port_device, retro_set_controller_port_device)
    SYM(p_retro_load_game, retro_load_game)
    SYM(p_retro_unload_game, retro_unload_game)
    SYM(p_retro_run, retro_run)
    SYM(p_retro_get_memory_data, retro_get_memory_data)
    SYM(p_retro_get_memory_size, retro_get_memory_size)
    SYM(p_retro_serialize_size, retro_serialize_size)
    SYM(p_retro_serialize, retro_serialize)
    SYM(p_retro_unserialize, retro_unserialize)
    #undef SYM

    const char* sys = env->GetStringUTFChars(systemDir, nullptr);
    const char* sav = env->GetStringUTFChars(saveDir, nullptr);
    g_system_dir = sys;
    g_save_dir = sav;
    env->ReleaseStringUTFChars(systemDir, sys);
    env->ReleaseStringUTFChars(saveDir, sav);

    LOGI("core loaded; api version %u", p_retro_api_version());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeSetVideoBuffer(
        JNIEnv* env, jobject, jobject buffer) {
    g_video_buffer = (uint8_t*) env->GetDirectBufferAddress(buffer);
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeLoadRom(
        JNIEnv* env, jobject, jstring romPath, jstring savePath) {
    if (!p_retro_load_game) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(romPath, nullptr);
    std::string p(path);
    env->ReleaseStringUTFChars(romPath, path);

    // Read the ROM ourselves.
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) { LOGE("cannot open ROM %s", p.c_str()); return JNI_FALSE; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> data((size_t) size);
    fread(data.data(), 1, (size_t) size, f);
    fclose(f);
    LOGI("ROM loaded: %s (%ld bytes)", p.c_str(), size);

    // Give the core its callbacks.
    p_retro_set_environment(cb_environment);
    p_retro_set_video_refresh(cb_video_refresh);
    p_retro_set_audio_sample(cb_audio_sample);
    p_retro_set_audio_sample_batch(cb_audio_sample_batch);
    p_retro_set_input_poll(cb_input_poll);
    p_retro_set_input_state(cb_input_state);

    p_retro_init();
    p_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);

    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = p.c_str();
    info.data = data.data();
    info.size = (size_t) size;

    if (!p_retro_load_game(&info)) {
        g_last_error = "retro_load_game failed (unsupported ROM?)";
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    // Battery save: load an existing .srm if we have one, else blank SRAM.
    const char* savePathStr = env->GetStringUTFChars(savePath, nullptr);
    std::string srm(savePathStr);
    env->ReleaseStringUTFChars(savePath, savePathStr);

    void* sram = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sramSize = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (sram && sramSize > 0) {
        FILE* sf = fopen(srm.c_str(), "rb");
        if (sf) {
            size_t got = fread(sram, 1, sramSize, sf);
            fclose(sf);
            LOGI("save RAM loaded from %s (%zu/%zu bytes)", srm.c_str(), got, sramSize);
        } else {
            memset(sram, 0, sramSize);
            LOGI("no save file; SRAM zeroed (%zu bytes)", sramSize);
        }
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeRun(JNIEnv*, jobject) {
    if (!p_retro_run) return;
    g_frame_ready = false;
    p_retro_run();
}

JNIEXPORT jstring JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeFrameReady(JNIEnv*, jobject) {
    return g_frame_ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeVideoWidth(JNIEnv*, jobject) {
    return g_video_width;
}

JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeVideoHeight(JNIEnv*, jobject) {
    return g_video_height;
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeUsesRgb565(JNIEnv*, jobject) {
    return g_use_rgb565 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeSetButton(
        JNIEnv*, jobject, jint id, jboolean pressed) {
    if (id < 0 || id > 15) return;
    if (pressed) g_buttons |= (uint16_t) (1u << id);
    else         g_buttons &= (uint16_t) ~(1u << id);
}

JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeAudioFrames(JNIEnv*, jobject) {
    return (jint) (g_audio.size() / 2);
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeCopyAudio(
        JNIEnv* env, jobject, jshortArray target, jint frames) {
    if (frames <= 0) return;
    jsize len = env->GetArrayLength(target);
    jint n = frames * 2;
    if (n > len) n = len;
    env->SetShortArrayRegion(target, 0, n, (const jshort*) g_audio.data());
    // Consume what was copied. The audio callback appends every frame and nothing else
    // removed it, so on a host that never called nativeClearAudio (the desktop client)
    // this vector grew for the whole session: each video frame then copied the *oldest*
    // frames - thousands of them - so the audio write blocked for a quarter of a second
    // per frame and the emulator crawled at a few frames a second until memory ran out.
    g_audio.erase(g_audio.begin(), g_audio.begin() + n);
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeClearAudio(JNIEnv*, jobject) {
    g_audio.clear();
}

JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeSaveRamSize(JNIEnv*, jobject) {
    if (!p_retro_get_memory_size) return 0;
    return (jint) p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeFlushSaveRam(
        JNIEnv* env, jobject, jstring savePath) {
    if (!p_retro_get_memory_data || !p_retro_get_memory_size) return JNI_FALSE;
    void* sram = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sramSize = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || sramSize == 0) return JNI_FALSE;

    const char* p = env->GetStringUTFChars(savePath, nullptr);
    FILE* f = fopen(p, "wb");
    if (!f) { env->ReleaseStringUTFChars(savePath, p); return JNI_FALSE; }
    fwrite(sram, 1, sramSize, f);
    fclose(f);
    env->ReleaseStringUTFChars(savePath, p);
    LOGI("save RAM flushed");
    return JNI_TRUE;
}

// region: 0 = SAVE_RAM (SRAM), 1 = SYSTEM_RAM (WRAM, live game state)
JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeReadMemory(
        JNIEnv* env, jobject, jint region, jint offset, jbyteArray target) {
    if (!p_retro_get_memory_data || !p_retro_get_memory_size) return 0;
    unsigned r = (region == 1) ? RETRO_MEMORY_SYSTEM_RAM : RETRO_MEMORY_SAVE_RAM;
    void* mem = p_retro_get_memory_data(r);
    size_t size = p_retro_get_memory_size(r);
    if (!mem || size == 0 || offset < 0 || (size_t) offset >= size) return 0;
    size_t avail = size - (size_t) offset;
    jsize len = env->GetArrayLength(target);
    jint n = (jint) (avail < (size_t) len ? avail : (size_t) len);
    env->SetByteArrayRegion(target, 0, n, (const jbyte*) ((const uint8_t*) mem + offset));
    return n;
}

JNIEXPORT jint JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeCopySaveRam(
        JNIEnv* env, jobject, jbyteArray target) {
    if (!p_retro_get_memory_data || !p_retro_get_memory_size) return 0;
    void* sram = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || size == 0) return 0;
    jsize len = env->GetArrayLength(target);
    jint n = (jint) (size < (size_t) len ? size : (size_t) len);
    env->SetByteArrayRegion(target, 0, n, (const jbyte*) sram);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeSaveState(
        JNIEnv* env, jobject, jstring path) {
    if (!p_retro_serialize || !p_retro_serialize_size) {
        g_last_error = "core has no save-state support";
        return JNI_FALSE;
    }
    size_t size = p_retro_serialize_size();
    if (size == 0) {
        g_last_error = "core reported a zero-size state";
        return JNI_FALSE;
    }
    std::vector<uint8_t> buffer(size);
    if (!p_retro_serialize(buffer.data(), size)) {
        g_last_error = "retro_serialize failed";
        return JNI_FALSE;
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    FILE* f = fopen(p, "wb");
    if (!f) {
        g_last_error = std::string("cannot write ") + p;
        env->ReleaseStringUTFChars(path, p);
        return JNI_FALSE;
    }
    fwrite(buffer.data(), 1, size, f);
    fclose(f);
    env->ReleaseStringUTFChars(path, p);
    LOGI("save state written (%zu bytes)", size);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeLoadState(
        JNIEnv* env, jobject, jstring path) {
    if (!p_retro_unserialize) {
        g_last_error = "core has no save-state support";
        return JNI_FALSE;
    }
    const char* p = env->GetStringUTFChars(path, nullptr);
    FILE* f = fopen(p, "rb");
    if (!f) {
        g_last_error = std::string("no state file at ") + p;
        env->ReleaseStringUTFChars(path, p);
        return JNI_FALSE;
    }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> buffer((size_t) size);
    size_t got = fread(buffer.data(), 1, (size_t) size, f);
    fclose(f);
    env->ReleaseStringUTFChars(path, p);
    if (got != (size_t) size || size <= 0) {
        g_last_error = "could not read the state file";
        return JNI_FALSE;
    }
    if (!p_retro_unserialize(buffer.data(), (size_t) size)) {
        g_last_error = "retro_unserialize rejected the state";
        return JNI_FALSE;
    }
    LOGI("save state loaded (%zu bytes)", (size_t) size);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_freeenterprise_app_emulator_EmulatorBridge_nativeUnload(JNIEnv*, jobject) {
    if (p_retro_unload_game) p_retro_unload_game();
    if (p_retro_deinit) p_retro_deinit();
    g_buttons = 0;
    g_frame_ready = false;
    g_audio.clear();
}

} // extern "C"
