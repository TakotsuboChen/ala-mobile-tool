#include "music_hook.h"
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

static music_hook_config_t g_config = {0};
static volatile int g_hooks_installed = 0;
static volatile int g_replace_music_enabled = 0;

// 最近一次 handleMusicVolume.Update() 调用的时间戳（秒级）。
// 被 Java 端 timer 轮询：间隔 < 2s → 在主菜单 → 播音乐；
// 间隔 >= 2s → 离开主菜单 → 停音乐。
static volatile time_t g_last_update_time = 0;

// AudioSource.set_volume 函数指针（从 libil2cpp.so 按 RVA 解析）
// 签名：void set_volume(AudioSource* this, float value);
typedef void (*set_volume_func_t)(void *audio_source, float value);
static set_volume_func_t g_set_volume = NULL;

// handleMusicVolume.Update() 原始函数指针
typedef void (*update_func_t)(void *this_ptr);
typedef void (*start_func_t)(void *this_ptr);

static update_func_t orig_update = NULL;
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

// Hook for handleMusicVolume.Update() — 每帧被调用当且仅当在主菜单场景。
// 1) 记录心跳时间戳供 Java 轮询
// 2) 如果替换音乐开关打开，把游戏 AudioSource 音量设为 0（静音游戏音乐）
static void hook_update(void *this_ptr) {
    // 先调原始方法（游戏正常更新音量）
    if (orig_update) orig_update(this_ptr);

    // 更新心跳时间戳（秒级）
    g_last_update_time = time(NULL);

    // 如果替换音乐开关打开，静音游戏的主菜单音乐
    if (g_replace_music_enabled && g_set_volume) {
        // handleMusicVolume 的 asc 字段偏移 0x20（AudioSource 实例指针）
        void *audio_source = *(void **)((uint8_t *)this_ptr + 0x20);
        if (audio_source) {
            g_set_volume(audio_source, 0.0f);
        }
    }
}

// Hook for handleMusicVolume.Start() — 主菜单场景加载时调用。
static void hook_start(void *this_ptr) {
    if (orig_start) orig_start(this_ptr);
    g_last_update_time = time(NULL);
    LOGI("Music hook: handleMusicVolume.Start() detected — in main menu");
}

bool music_install_hooks(const music_hook_config_t *config) {
    if (!config) {
        LOGE("music_install_hooks: config is NULL");
        return false;
    }

    memcpy(&g_config, config, sizeof(music_hook_config_t));

    LOGI("music_install_hooks: update_offset=0x%lx start_offset=0x%lx set_volume_offset=0x%lx",
         (unsigned long)config->handle_music_volume_update_offset,
         (unsigned long)config->handle_music_volume_start_offset,
         (unsigned long)config->audio_source_set_volume_offset);

    if (g_hooks_installed) {
        LOGI("Music hooks already installed, skipping");
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("music_install_hooks: libil2cpp.so base not found");
        return false;
    }
    LOGI("music_install_hooks: libil2cpp.so base = 0x%" PRIxPTR, base);

    // 解析 AudioSource.set_volume(float) 函数指针
    if (config->audio_source_set_volume_offset != 0) {
        g_set_volume = (set_volume_func_t)(base + config->audio_source_set_volume_offset);
        LOGI("AudioSource.set_volume resolved at %p", g_set_volume);
    } else {
        LOGW("audio_source_set_volume_offset is 0, cannot mute game music");
    }

    // Hook handleMusicVolume.Update() — 主菜单心跳 + 静音游戏音乐
    if (config->handle_music_volume_update_offset != 0) {
        void *update_addr = (void *)(base + config->handle_music_volume_update_offset);
        LOGI("Hooking handleMusicVolume.Update() at %p", update_addr);

        void *result = shadowhook_hook_sym_addr(
            update_addr,
            (void *)hook_update,
            (void **)&orig_update
        );

        if (result) {
            LOGI("Successfully hooked handleMusicVolume.Update()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook handleMusicVolume.Update(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("handleMusicVolume.Update() offset is 0, skipping");
    }

    // Hook handleMusicVolume.Start() — 主菜单场景加载信号
    if (config->handle_music_volume_start_offset != 0) {
        void *start_addr = (void *)(base + config->handle_music_volume_start_offset);
        LOGI("Hooking handleMusicVolume.Start() at %p", start_addr);

        void *result = shadowhook_hook_sym_addr(
            start_addr,
            (void *)hook_start,
            (void **)&orig_start
        );

        if (result) {
            LOGI("Successfully hooked handleMusicVolume.Start()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook handleMusicVolume.Start(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("handleMusicVolume.Start() offset is 0, skipping");
    }

    g_hooks_installed = 1;
    LOGI("Music hooks installation complete");
    return true;
}

void music_uninstall_hooks(void) {
    LOGI("Uninstalling music hooks");
    orig_update = NULL;
    orig_start = NULL;
    g_set_volume = NULL;
    g_hooks_installed = 0;
    LOGI("Music hooks uninstalled");
}

void music_set_replace_enabled(int enabled) {
    g_replace_music_enabled = enabled ? 1 : 0;
    LOGI("Music replace enabled: %d", enabled);
}

bool music_is_in_main_menu(void) {
    time_t now = time(NULL);
    time_t last = g_last_update_time;
    // 如果最近一次心跳在 2s 以内，认为仍在主菜单
    return (now - last) <= 2;
}

bool music_is_replace_enabled(void) {
    return g_replace_music_enabled != 0;
}