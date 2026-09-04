#ifndef HIDE_PEDALS_HOOK_H
#define HIDE_PEDALS_HOOK_H

#include <stdbool.h>

// RVA 注入（来自 Java 侧 OffsetTable，升版只改 OffsetTable.kt）
void hide_pedals_set_offsets(unsigned long long get_instance,
                             unsigned long long go_set_active,
                             unsigned long long go_get_active_self,
                             unsigned long long go_get_transform,
                             unsigned long long component_get_game_object,
                             unsigned long long transform_get_child,
                             unsigned long long transform_get_child_count,
                             unsigned long long object_get_name);

void hide_pedals_init(bool enabled);
void hide_pedals_tick(void);
void hide_pedals_set_enabled(bool enabled);

#endif // HIDE_PEDALS_HOOK_H