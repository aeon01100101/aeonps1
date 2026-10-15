/* AeonPS1 native bridge: a minimal libretro frontend for PCSX-ReARMed.
 *
 * Loads the core .so at runtime (dlopen), implements the libretro frontend
 * callbacks, blits video frames to an ANativeWindow (SurfaceView), and feeds
 * audio into a lock-free-ish ring buffer that Kotlin drains into AudioTrack.
 *
 * All retro_* calls happen on the emulator thread. Button/analog state and
 * the window handle are the only things touched from other threads.
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libretro.h"

#define LOG_TAG "AeonPS1Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* WINDOW_FORMAT_* lives in different NDK headers across revisions. */
#ifndef WINDOW_FORMAT_RGBX_8888
#define WINDOW_FORMAT_RGBX_8888 2
#endif

/* ------------------------------------------------------------------ */
/* Core function pointers                                                */
/* ------------------------------------------------------------------ */

static void *core_handle = NULL;

static void     (*core_retro_init)(void);
static void     (*core_retro_deinit)(void);
static unsigned (*core_retro_api_version)(void);
static void     (*core_retro_get_system_info)(struct retro_system_info *);
static void     (*core_retro_get_system_av_info)(struct retro_system_av_info *);
static void     (*core_retro_set_environment)(retro_environment_t);
static void     (*core_retro_set_video_refresh)(retro_video_refresh_t);
static void     (*core_retro_set_audio_sample)(retro_audio_sample_t);
static void     (*core_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void     (*core_retro_set_input_poll)(retro_input_poll_t);
static void     (*core_retro_set_input_state)(retro_input_state_t);
static void     (*core_retro_set_controller_port_device)(unsigned, unsigned);
static void     (*core_retro_reset)(void);
static void     (*core_retro_run)(void);
static size_t   (*core_retro_serialize_size)(void);
static bool     (*core_retro_serialize)(void *, size_t);
static bool     (*core_retro_unserialize)(const void *, size_t);
static bool     (*core_retro_load_game)(const struct retro_game_info *);
static void     (*core_retro_unload_game)(void);
static void     (*core_retro_cheat_reset)(void);
static unsigned (*core_retro_get_region)(void);
static void    *(*core_retro_get_memory_data)(unsigned);
static size_t   (*core_retro_get_memory_size)(unsigned);

#define LOAD_SYM(name) do { \
        core_##name = (void *)dlsym(core_handle, #name); \
        if (!core_##name) { LOGE("dlsym failed: %s", #name); return false; } \
    } while (0)

/* ------------------------------------------------------------------ */
/* Frontend state                                                        */
/* ------------------------------------------------------------------ */

#define MAX_VW 1024
#define MAX_VH 512
#define AUDIO_RING_SHORTS (44100 * 2 * 2)  /* 2 seconds of stereo int16 */

static char     g_system_dir[1024];
static char     g_save_dir[1024];
static char     g_game_basename[256];

static uint32_t g_frame[MAX_VW * MAX_VH];
static int      g_vw = 0, g_vh = 0;
static volatile bool g_frame_ready = false;
static int      g_pixfmt = RETRO_PIXEL_FORMAT_XRGB8888; /* as core reports */
static int      g_screen_mode = 0; /* 0 = 4:3, 1 = stretch */

static int16_t  g_audio_ring[AUDIO_RING_SHORTS];
static volatile unsigned g_audio_w = 0, g_audio_r = 0;
static pthread_mutex_t g_audio_mutex = PTHREAD_MUTEX_INITIALIZER;
static unsigned g_audio_drops = 0;

static volatile uint32_t g_buttons[2] = {0, 0};
static volatile int16_t  g_analog[2][2] = {{0, 0}, {0, 0}};

static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_window_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_win_w = 0, g_win_h = 0;

static struct retro_system_av_info g_av_info;
static bool     g_av_info_valid = false;
static volatile bool g_shutdown_requested = false;

static unsigned long g_frame_count = 0;
static unsigned long g_fps_frames = 0;
static long long g_fps_t0 = 0; /* monotonic ns */
static float g_fps = 0.0f;

static bool g_inited = false;
static bool g_game_loaded = false;

/* ------------------------------------------------------------------ */
/* Save RAM (.srm) persistence                                           */
/* ------------------------------------------------------------------ */

static void srm_path(char *out, size_t n) {
    snprintf(out, n, "%s/%s.srm", g_save_dir, g_game_basename);
}

static void save_ram_load(void) {
    size_t sz = core_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *mem = core_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!sz || !mem) { LOGI("core exposes no SAVE_RAM"); return; }
    char path[1152];
    srm_path(path, sizeof(path));
    FILE *f = fopen(path, "rb");
    if (!f) { LOGI("no existing .srm, fresh memory card"); return; }
    fseek(f, 0, SEEK_END);
    long fsz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if ((size_t)fsz == sz && fread(mem, 1, sz, f) == sz)
        LOGI("loaded .srm (%zu bytes)", sz);
    else
        LOGW(".srm size mismatch (file %ld, core wants %zu), ignoring", fsz, sz);
    fclose(f);
}

static void save_ram_store(void) {
    size_t sz = core_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *mem = core_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!sz || !mem) return;
    char path[1152];
    srm_path(path, sizeof(path));
    FILE *f = fopen(path, "wb");
    if (!f) { LOGW("could not write .srm"); return; }
    if (fwrite(mem, 1, sz, f) != sz) LOGW("short .srm write");
    fclose(f);
}

/* ------------------------------------------------------------------ */
/* libretro frontend callbacks                                           */
/* ------------------------------------------------------------------ */

static void frontend_log(enum retro_log_level level, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    int prio = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == RETRO_LOG_WARN) prio = ANDROID_LOG_WARN;
    __android_log_print(prio, "AeonPS1Core", "%s", buf);
}

static bool env_callback(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_system_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = g_save_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
        *(const char **)data = g_system_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_USERNAME:
        *(const char **)data = "player";
        return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        enum retro_pixel_format fmt = *(const enum retro_pixel_format *)data;
        if (fmt == RETRO_PIXEL_FORMAT_XRGB8888 || fmt == RETRO_PIXEL_FORMAT_RGB565) {
            g_pixfmt = fmt;
            LOGI("core pixel format: %s", fmt == RETRO_PIXEL_FORMAT_XRGB8888 ? "XRGB8888" : "RGB565");
            return true;
        }
        return false;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        g_av_info = *(const struct retro_system_av_info *)data;
        g_av_info_valid = true;
        LOGI("core AV info: %dx%d @ %.2f fps, audio %.0f Hz",
             g_av_info.geometry.base_width, g_av_info.geometry.base_height,
             g_av_info.timing.fps, g_av_info.timing.sample_rate);
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        return true;
    case RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT:
        *(unsigned *)data = RETRO_SAVESTATE_CONTEXT_NORMAL;
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        struct retro_log_callback *cb = (struct retro_log_callback *)data;
        cb->log = frontend_log;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE:
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
        const struct retro_message *m = (const struct retro_message *)data;
        if (m && m->msg) LOGI("core message: %s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SHUTDOWN:
        g_shutdown_requested = true;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        /* No core-options UI in v1.0; no rumble support. */
        return false;
    default:
        LOGW("unhandled environment cmd %u", cmd);
        return false;
    }
}

static void video_refresh_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (width == 0 || height == 0 || width > MAX_VW || height > MAX_VH)
        return; /* dup frame or bogus: keep last frame */
    g_vw = (int)width;
    g_vh = (int)height;
    if (data) {
        if (g_pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint8_t *src = (const uint8_t *)data;
            for (unsigned y = 0; y < height; y++) {
                memcpy(g_frame + y * width, src + y * pitch, width * 4);
            }
        } else { /* RGB565 -> XRGB8888 */
            const uint16_t *src = (const uint16_t *)data;
            size_t stride = pitch / 2;
            for (unsigned y = 0; y < height; y++) {
                for (unsigned x = 0; x < width; x++) {
                    uint16_t p = src[y * stride + x];
                    uint32_t r = (p >> 11) & 0x1F;
                    uint32_t g = (p >> 5) & 0x3F;
                    uint32_t b = p & 0x1F;
                    g_frame[y * width + x] = 0xFF000000
                        | ((r << 3) | (r >> 2)) << 16
                        | ((g << 2) | (g >> 4)) << 8
                        | ((b << 3) | (b >> 2));
                }
            }
        }
    }
    g_frame_ready = true;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    int16_t s[2] = {left, right};
    pthread_mutex_lock(&g_audio_mutex);
    for (int i = 0; i < 2; i++) {
        unsigned next = (g_audio_w + 1) % AUDIO_RING_SHORTS;
        if (next == g_audio_r) { /* full: drop oldest */
            g_audio_r = (g_audio_r + 1) % AUDIO_RING_SHORTS;
            g_audio_drops++;
        }
        g_audio_ring[g_audio_w] = s[i];
        g_audio_w = next;
    }
    pthread_mutex_unlock(&g_audio_mutex);
}

static size_t audio_sample_batch_cb(const int16_t *data, size_t frames) {
    pthread_mutex_lock(&g_audio_mutex);
    size_t shorts = frames * 2;
    for (size_t i = 0; i < shorts; i++) {
        unsigned next = (g_audio_w + 1) % AUDIO_RING_SHORTS;
        if (next == g_audio_r) {
            g_audio_r = (g_audio_r + 1) % AUDIO_RING_SHORTS;
            g_audio_drops++;
        }
        g_audio_ring[g_audio_w] = data[i];
        g_audio_w = next;
    }
    pthread_mutex_unlock(&g_audio_mutex);
    return frames;
}

static void input_poll_cb(void) { /* nothing to do: state pushed from Java */ }

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= 2) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id >= 16) return 0;
        return (g_buttons[port] >> id) & 1;
    }
    if (device == RETRO_DEVICE_ANALOG && index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
        if (id == RETRO_DEVICE_ID_ANALOG_X) return g_analog[port][0];
        if (id == RETRO_DEVICE_ID_ANALOG_Y) return g_analog[port][1];
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* Blit to ANativeWindow                                                */
/* ------------------------------------------------------------------ */

static long long now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void blit_frame(void) {
    pthread_mutex_lock(&g_window_mutex);
    ANativeWindow *w = g_window;
    if (w) ANativeWindow_acquire(w);
    pthread_mutex_unlock(&g_window_mutex);
    if (!w || !g_frame_ready || g_vw <= 0 || g_vh <= 0) return;

    int winW = ANativeWindow_getWidth(w);
    int winH = ANativeWindow_getHeight(w);
    if (winW <= 0 || winH <= 0) { ANativeWindow_release(w); return; }

    if (winW != g_win_w || winH != g_win_h) {
        ANativeWindow_setBuffersGeometry(w, winW, winH, WINDOW_FORMAT_RGBX_8888);
        g_win_w = winW; g_win_h = winH;
    }

    /* Destination rect: 4:3 centered, or full stretch. */
    int dw = winW, dh = winH, dx = 0, dy = 0;
    if (g_screen_mode == 0) {
        dh = winH;
        dw = dh * 4 / 3;
        if (dw > winW) { dw = winW; dh = dw * 3 / 4; }
        dx = (winW - dw) / 2;
        dy = (winH - dh) / 2;
    }

    ANativeWindow_Buffer buf;
    ARect dirty = {0, 0, winW, winH};
    if (ANativeWindow_lock(w, &buf, &dirty) == 0) {
        uint32_t *dst = (uint32_t *)buf.bits;
        int stride = buf.stride;
        /* letterbox */
        for (int y = 0; y < winH; y++)
            for (int x = 0; x < winW; x++)
                dst[y * stride + x] = 0xFF000000;
        /* nearest-neighbor scale */
        for (int y = 0; y < dh; y++) {
            int sy = y * g_vh / dh;
            uint32_t *drow = dst + (dy + y) * stride + dx;
            uint32_t *srow = g_frame + sy * g_vw;
            for (int x = 0; x < dw; x++)
                drow[x] = srow[x * g_vw / dw];
        }
        ANativeWindow_unlockAndPost(w);
    }
    ANativeWindow_release(w);
}

/* ------------------------------------------------------------------ */
/* JNI                                                                 */
/* ------------------------------------------------------------------ */

static bool load_core(const char *path) {
    core_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!core_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return false;
    }
    LOAD_SYM(retro_init);
    LOAD_SYM(retro_deinit);
    LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_get_system_info);
    LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_environment);
    LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample);
    LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll);
    LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_set_controller_port_device);
    LOAD_SYM(retro_reset);
    LOAD_SYM(retro_run);
    LOAD_SYM(retro_serialize_size);
    LOAD_SYM(retro_serialize);
    LOAD_SYM(retro_unserialize);
    LOAD_SYM(retro_load_game);
    LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_cheat_reset);
    LOAD_SYM(retro_get_region);
    LOAD_SYM(retro_get_memory_data);
    LOAD_SYM(retro_get_memory_size);
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeInit(JNIEnv *env, jobject thiz,
        jstring corePath, jstring systemDir, jstring saveDir) {
    if (g_inited) return JNI_TRUE;
    const char *cp = (*env)->GetStringUTFChars(env, corePath, NULL);
    const char *sd = (*env)->GetStringUTFChars(env, systemDir, NULL);
    const char *vd = (*env)->GetStringUTFChars(env, saveDir, NULL);
    strncpy(g_system_dir, sd, sizeof(g_system_dir) - 1);
    strncpy(g_save_dir, vd, sizeof(g_save_dir) - 1);

    if (!load_core(cp)) goto fail;
    if (core_retro_api_version() != 1) {
        LOGE("unsupported libretro API version %u", core_retro_api_version());
        goto fail;
    }
    core_retro_set_environment(env_callback);
    core_retro_set_video_refresh(video_refresh_cb);
    core_retro_set_audio_sample(audio_sample_cb);
    core_retro_set_audio_sample_batch(audio_sample_batch_cb);
    core_retro_set_input_poll(input_poll_cb);
    core_retro_set_input_state(input_state_cb);
    core_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    core_retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
    core_retro_init();
    g_inited = true;
    LOGI("bridge init OK, core=%s", cp);

    (*env)->ReleaseStringUTFChars(env, corePath, cp);
    (*env)->ReleaseStringUTFChars(env, systemDir, sd);
    (*env)->ReleaseStringUTFChars(env, saveDir, vd);
    return JNI_TRUE;

fail:
    if (core_handle) { dlclose(core_handle); core_handle = NULL; }
    (*env)->ReleaseStringUTFChars(env, corePath, cp);
    (*env)->ReleaseStringUTFChars(env, systemDir, sd);
    (*env)->ReleaseStringUTFChars(env, saveDir, vd);
    return JNI_FALSE;
}

static void basename_of(const char *path, char *out, size_t n) {
    const char *b = strrchr(path, '/');
    b = b ? b + 1 : path;
    size_t len = strlen(b);
    const char *dot = strrchr(b, '.');
    if (dot && dot > b) len = (size_t)(dot - b);
    if (len >= n) len = n - 1;
    memcpy(out, b, len);
    out[len] = '\0';
}

JNIEXPORT jboolean JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeLoadGame(JNIEnv *env, jobject thiz, jstring path) {
    if (!g_inited || g_game_loaded) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    basename_of(p, g_game_basename, sizeof(g_game_basename));

    struct retro_system_info si;
    core_retro_get_system_info(&si);
    LOGI("core: %s %s", si.library_name ? si.library_name : "?",
         si.library_version ? si.library_version : "?");

    struct retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = p;
    bool ok = core_retro_load_game(&gi);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }
    core_retro_get_system_av_info(&g_av_info);
    g_av_info_valid = true;
    save_ram_load();
    g_frame_count = 0;
    g_fps_t0 = now_ns();
    g_fps_frames = 0;
    g_game_loaded = true;
    LOGI("game loaded");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeRunFrame(JNIEnv *env, jobject thiz) {
    if (!g_inited || !g_game_loaded) return;
    core_retro_run();
    blit_frame();
    g_frame_count++;
    g_fps_frames++;
    /* periodic memory-card flush (~every 5s at 60fps) */
    if ((g_frame_count % 300) == 0) save_ram_store();
    long long t = now_ns();
    long long dt = t - g_fps_t0;
    if (dt >= 1000000000LL) {
        g_fps = (float)g_fps_frames * 1e9f / (float)dt;
        g_fps_frames = 0;
        g_fps_t0 = t;
        if (g_audio_drops) { LOGW("audio ring dropped %u samples so far", g_audio_drops); g_audio_drops = 0; }
    }
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    pthread_mutex_lock(&g_window_mutex);
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; g_win_w = g_win_h = 0; }
    if (surface) {
        g_window = ANativeWindow_fromSurface(env, surface);
        LOGI("surface attached");
    } else {
        LOGI("surface detached");
    }
    pthread_mutex_unlock(&g_window_mutex);
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeSetButtons(JNIEnv *env, jobject thiz, jint port, jint mask) {
    if (port >= 0 && port < 2) g_buttons[port] = (uint32_t)mask;
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeSetAnalog(JNIEnv *env, jobject thiz,
        jint port, jint x, jint y) {
    if (port >= 0 && port < 2) {
        if (x > 32767) x = 32767; if (x < -32767) x = -32767;
        if (y > 32767) y = 32767; if (y < -32767) y = -32767;
        g_analog[port][0] = (int16_t)x;
        g_analog[port][1] = (int16_t)y;
    }
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeSetScreenMode(JNIEnv *env, jobject thiz, jint mode) {
    g_screen_mode = (mode == 1) ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeSaveState(JNIEnv *env, jobject thiz, jstring path) {
    if (!g_inited || !g_game_loaded) return JNI_FALSE;
    size_t sz = core_retro_serialize_size();
    if (!sz) { LOGW("core reports no save-state size"); return JNI_FALSE; }
    void *buf = malloc(sz);
    if (!buf) return JNI_FALSE;
    bool ok = core_retro_serialize(buf, sz);
    if (ok) {
        const char *p = (*env)->GetStringUTFChars(env, path, NULL);
        FILE *f = fopen(p, "wb");
        if (f) {
            ok = fwrite(buf, 1, sz, f) == sz;
            fclose(f);
        } else ok = false;
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    free(buf);
    LOGI("save state: %s", ok ? "ok" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeLoadState(JNIEnv *env, jobject thiz, jstring path) {
    if (!g_inited || !g_game_loaded) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    bool ok = false;
    if (f) {
        fseek(f, 0, SEEK_END);
        long fsz = ftell(f);
        fseek(f, 0, SEEK_SET);
        void *buf = malloc(fsz > 0 ? (size_t)fsz : 1);
        if (buf && fsz > 0 && fread(buf, 1, (size_t)fsz, f) == (size_t)fsz)
            ok = core_retro_unserialize(buf, (size_t)fsz);
        free(buf);
        fclose(f);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    LOGI("load state: %s", ok ? "ok" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeReset(JNIEnv *env, jobject thiz) {
    if (!g_inited || !g_game_loaded) return JNI_FALSE;
    core_retro_reset();
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeGetAudioSamples(JNIEnv *env, jobject thiz,
        jshortArray out, jint maxShorts) {
    pthread_mutex_lock(&g_audio_mutex);
    unsigned avail = (g_audio_w + AUDIO_RING_SHORTS - g_audio_r) % AUDIO_RING_SHORTS;
    unsigned n = avail < (unsigned)maxShorts ? avail : (unsigned)maxShorts;
    if (n > 0) {
        /* copy in contiguous chunks (ring may wrap) */
        jshort tmp[8192];
        unsigned copied = 0;
        while (copied < n) {
            unsigned chunk = n - copied;
            unsigned contig = AUDIO_RING_SHORTS - g_audio_r;
            if (chunk > contig) chunk = contig;
            if (chunk > 8192) chunk = 8192;
            memcpy(tmp, g_audio_ring + g_audio_r, chunk * sizeof(int16_t));
            (*env)->SetShortArrayRegion(env, out, copied, chunk, tmp);
            g_audio_r = (g_audio_r + chunk) % AUDIO_RING_SHORTS;
            copied += chunk;
        }
    }
    pthread_mutex_unlock(&g_audio_mutex);
    return (jint)n;
}

JNIEXPORT jfloat JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeGetFps(JNIEnv *env, jobject thiz) {
    return g_fps;
}

JNIEXPORT jfloat JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeGetTargetFps(JNIEnv *env, jobject thiz) {
    if (g_av_info_valid && g_av_info.timing.fps > 1.0)
        return (jfloat)g_av_info.timing.fps;
    return 60.0f;
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeFlushSaveRam(JNIEnv *env, jobject thiz) {
    if (g_inited && g_game_loaded) save_ram_store();
}

JNIEXPORT void JNICALL
Java_com_aeonps1_tv_EmulatorBridge_nativeShutdown(JNIEnv *env, jobject thiz) {    if (g_game_loaded) {
        save_ram_store();
        core_retro_unload_game();
        g_game_loaded = false;
    }
    if (g_inited) {
        core_retro_deinit();
        g_inited = false;
    }
    if (core_handle) { dlclose(core_handle); core_handle = NULL; }
    pthread_mutex_lock(&g_window_mutex);
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }
    pthread_mutex_unlock(&g_window_mutex);
    g_frame_ready = false;
    g_vw = g_vh = 0;
    LOGI("bridge shutdown");
}
