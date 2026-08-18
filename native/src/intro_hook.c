#include "intro_hook.h"
#include "native_log.h"
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "shadowhook.h"

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGW(...) NLOGW(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

static intro_hook_config_t g_config = {0};
static volatile int g_hooks_installed = 0;
static volatile int g_v10_enabled = 0;

// one-shot 标志：hook IntroLogoManager.Start() 时置 1，Java 端轮询 intro_is_started() 后清零。
static volatile int g_intro_started = 0;

// 真 AudioSource.set_volume(float) 函数指针（从 libil2cpp.so 按 RVA 解析）
// 签名：void set_volume(AudioSource* this, float value);
typedef void (*set_volume_func_t)(void *audio_source, float value);
static set_volume_func_t g_set_volume = NULL;

// IntroLogoManager.Start() 原始函数指针
typedef void (*start_func_t)(void *this_ptr);
static start_func_t orig_start = NULL;

// dl_iterate_phdr 回调：找 libil2cpp.so 基址
typedef struct {
    const char *name;
    uintptr_t base;
} find_module_ctx_t;

static int find_module_callback(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    find_module_ctx_t *ctx = (find_module_ctx_t *) data;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, ctx->name) != NULL) {
        ctx->base = (uintptr_t) info->dlpi_addr;
        return 1;
    }
    return 0;
}

static uintptr_t get_module_base(const char *module_name) {
    find_module_ctx_t ctx = {.name = module_name, .base = 0};
    dl_iterate_phdr(find_module_callback, &ctx);
    return ctx.base;
}

// Hook for IntroLogoManager.Start() — 开场动画入口（一次性调用）。
// 1) 如果 V10 开关打开，读 this+0x48 拿 introSound AudioSource，调 set_volume(0) 静音
// 2) 设 g_intro_started = 1 通知 Java 端播放 V10 MP3
static void hook_intro_start(void *this_ptr) {
    // 先调原始方法（游戏正常初始化开场动画）
    if (orig_start) orig_start(this_ptr);

    LOGI("Intro hook: IntroLogoManager.Start() detected");

    // 如果 V10 开关打开，静音开场引擎声
    if (g_v10_enabled && g_set_volume) {
        // IntroLogoManager 的 introSound 字段偏移 0x48（AudioSource 实例指针）
        void *audio_source = *(void **)((uint8_t *)this_ptr + 0x48);
        if (audio_source) {
            g_set_volume(audio_source, 0.0f);
            LOGI("Intro hook: muted introSound AudioSource at %p", audio_source);
        } else {
            LOGW("Intro hook: introSound AudioSource is null (this+0x48)");
        }
    }

    // 通知 Java 端播放 V10 MP3
    g_intro_started = 1;
}

bool intro_install_hooks(const intro_hook_config_t *config) {
    if (!config) {
        LOGE("intro_install_hooks: config is NULL");
        return false;
    }

    memcpy(&g_config, config, sizeof(intro_hook_config_t));

    LOGI("intro_install_hooks: start_offset=0x%lx set_volume_offset=0x%lx",
         (unsigned long)config->intro_logo_manager_start_offset,
         (unsigned long)config->audio_source_set_volume_offset);

    if (g_hooks_installed) {
        LOGI("Intro hooks already installed, skipping");
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("intro_install_hooks: libil2cpp.so base not found");
        return false;
    }
    LOGI("intro_install_hooks: libil2cpp.so base = 0x%" PRIxPTR, base);

    // 解析真 AudioSource.set_volume(float) 函数指针
    if (config->audio_source_set_volume_offset != 0) {
        g_set_volume = (set_volume_func_t)(base + config->audio_source_set_volume_offset);
        LOGI("AudioSource.set_volume (real) resolved at %p", g_set_volume);
    } else {
        LOGW("audio_source_set_volume_offset is 0, cannot mute intro sound");
    }

    // Hook IntroLogoManager.Start() — 开场动画入口信号 + 静音 introSound
    if (config->intro_logo_manager_start_offset != 0) {
        void *start_addr = (void *)(base + config->intro_logo_manager_start_offset);
        LOGI("Hooking IntroLogoManager.Start() at %p", start_addr);

        void *result = shadowhook_hook_sym_addr(
            start_addr,
            (void *)hook_intro_start,
            (void **)&orig_start
        );

        if (result) {
            LOGI("Successfully hooked IntroLogoManager.Start()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook IntroLogoManager.Start(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("intro_logo_manager_start_offset is 0, skipping");
    }

    g_hooks_installed = 1;
    LOGI("Intro hooks installation complete");
    return true;
}

void intro_uninstall_hooks(void) {
    LOGI("Uninstalling intro hooks");
    orig_start = NULL;
    g_set_volume = NULL;
    g_hooks_installed = 0;
    LOGI("Intro hooks uninstalled");
}

void intro_set_v10_enabled(int enabled) {
    g_v10_enabled = enabled ? 1 : 0;
    LOGI("V10 engine sound enabled: %d", enabled);
}

bool intro_is_started(void) {
    // one-shot：返回并清零，避免 Java 端重复播放
    if (g_intro_started) {
        g_intro_started = 0;
        return true;
    }
    return false;
}