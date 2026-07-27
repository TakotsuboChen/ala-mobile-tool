#ifndef IL2CPP_HOOKS_H
#define IL2CPP_HOOKS_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool install_hooks(void);
void uninstall_hooks(void);

#ifdef __cplusplus
}
#endif

#endif // IL2CPP_HOOKS_H
