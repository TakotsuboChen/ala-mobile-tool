#ifndef HIDE_PEDALS_HOOK_H
#define HIDE_PEDALS_HOOK_H

#include <stdbool.h>

void hide_pedals_init(bool enabled);
void hide_pedals_tick(void);
void hide_pedals_set_enabled(bool enabled);

#endif // HIDE_PEDALS_HOOK_H