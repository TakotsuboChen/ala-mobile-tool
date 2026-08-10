#ifndef MUSIC_HOOK_H
#define MUSIC_HOOK_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uintptr_t handle_music_volume_update_offset;  // handleMusicVolume.Update() RVA
    uintptr_t handle_music_volume_start_offset;   // handleMusicVolume.Start() RVA
    uintptr_t audio_source_set_volume_offset;     // AudioSource.set_volume(float) RVA
} music_hook_config_t;

bool music_install_hooks(const music_hook_config_t *config);
void music_uninstall_hooks(void);

// 设置替换音乐开关（由 Java 端 JNI 调）
void music_set_replace_enabled(int enabled);

// 检查是否仍在主菜单（最近一次 Update 心跳 < 2s 则认为在菜单）
bool music_is_in_main_menu(void);

// 获取当前替换音乐开关状态
bool music_is_replace_enabled(void);

#ifdef __cplusplus
}
#endif

#endif // MUSIC_HOOK_H