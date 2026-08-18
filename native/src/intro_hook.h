#ifndef INTRO_HOOK_H
#define INTRO_HOOK_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uintptr_t intro_logo_manager_start_offset;  // IntroLogoManager.Start() RVA
    uintptr_t audio_source_set_volume_offset;    // 真 AudioSource.set_volume(float) RVA
} intro_hook_config_t;

bool intro_install_hooks(const intro_hook_config_t *config);
void intro_uninstall_hooks(void);

// 设置 V10 引擎声浪开关（由 Java 端 JNI 调）
void intro_set_v10_enabled(int enabled);

// one-shot：返回 g_intro_started 并清零。Java 端轮询到此值后播放 V10 MP3。
bool intro_is_started(void);

#ifdef __cplusplus
}
#endif

#endif // INTRO_HOOK_H