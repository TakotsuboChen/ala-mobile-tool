#include "pedal_hook.h"
#include <android/log.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "shadowhook.h"

static pedal_hook_config_t g_config = {0};

static volatile float g_throttle_value = 0.0f;
static volatile float g_brake_value = 0.0f;

static volatile int g_throttle_active = 0;
static volatile int g_brake_active = 0;
static volatile int g_disable_auto_gear = 0;

static volatile void *g_last_controller = NULL;
static volatile uintptr_t g_player_drivetrain = 0;

static void *g_throttle_stub = NULL;
static void *g_brake_stub = NULL;
static void *g_shift_up_stub = NULL;
static void *g_shift_down_stub = NULL;
static void *g_fixed_update_stub = NULL;
static void *g_drivetrain_fixed_update_stub = NULL;

static void *g_throttle_orig = NULL;
static void *g_brake_orig = NULL;
static void *g_shift_up_orig = NULL;
static void *g_shift_down_orig = NULL;
static void *g_fixed_update_orig = NULL;
static void *g_drivetrain_fixed_update_orig = NULL;

static volatile int g_hooks_installed = 0;

// Background writer thread. It periodically writes the current desired input
// values into the IL2CPP instance fields, so the car responds even if the
// game's physics loop does not call the hooked setters.
static pthread_t g_writer_thread;
static volatile int g_writer_running = 0;

static inline void write_float_field(void *instance, uintptr_t offset, float value) {
    if (instance == NULL || offset == 0) return;
    *(volatile float *) ((uintptr_t) instance + offset) = value;
}

static inline void write_bool_field(void *instance, uintptr_t offset, bool value) {
    if (instance == NULL || offset == 0) return;
    *(volatile bool *) ((uintptr_t) instance + offset) = value;
}

static inline uintptr_t read_ptr(void *instance, uintptr_t offset) {
    if (instance == NULL || offset == 0) return 0;
    return *(volatile uintptr_t *) ((uintptr_t) instance + offset);
}

// 把当前模块 desired 输入值写到 controller 实例字段。
// 只在对应 active 时写字段——不踩时绝不写，避免覆盖游戏自带输入
// （方向键 ButtonsSteering 模式的转向辅助依赖油门/刹车值）。
// 松开瞬间的清零由 pedal_set_throttle_value/pedal_set_brake_value
// 主动调 clear_*_field 完成，不在此处轮询清零。
static void apply_inputs_to_controller(void *controller) {
    if (controller == NULL) return;

    if (g_throttle_active) {
        write_float_field(controller, g_config.throttle_field_offset, g_throttle_value);
        if (g_config.actual_throttle_field_offset != 0) {
            write_float_field(controller, g_config.actual_throttle_field_offset, g_throttle_value);
        }
    }

    if (g_brake_active) {
        write_float_field(controller, g_config.brake_field_offset, g_brake_value);
        if (g_config.actual_brake_field_offset != 0) {
            write_float_field(controller, g_config.actual_brake_field_offset, g_brake_value);
        }
    }
}

// 用户松开踏板时主动清零一次对应字段，避免值卡在最后值。
// 只清对应的那个字段（油门或刹车），不互相干扰。
static void clear_throttle_field(void *controller) {
    if (controller == NULL) return;
    write_float_field(controller, g_config.throttle_field_offset, 0.0f);
    if (g_config.actual_throttle_field_offset != 0) {
        write_float_field(controller, g_config.actual_throttle_field_offset, 0.0f);
    }
}

static void clear_brake_field(void *controller) {
    if (controller == NULL) return;
    write_float_field(controller, g_config.brake_field_offset, 0.0f);
    if (g_config.actual_brake_field_offset != 0) {
        write_float_field(controller, g_config.actual_brake_field_offset, 0.0f);
    }
}

// (Legacy IPC file path helper removed: the file-based IPC fallback was
//  removed in favor of the JNI direct path, which is reliably available in
//  both original and coexistence builds thanks to the dual-ClassLoader
//  guard in AlaMobileModule.)

// Background writer thread. It periodically writes the current desired input
// values (set by JNI: pedal_set_throttle_value / pedal_set_brake_value) into
// the IL2CPP instance fields, so the car responds even if the game's physics
// loop does not call the hooked setters.
//
// NOTE: this thread NO LONGER reads from an IPC file. The previous file-based
// IPC path raced with RandomAccessFile.seek+write (non-atomic) and with the
// JNI direct path, causing throttle/brake values to flicker between new and
// stale values — the "pedal stutter" bug. With the dual-ClassLoader guard
// in AlaMobileModule, JNI is reliably available in both original and
// coexistence builds, so the file IPC fallback is no longer needed.
//
// 关键:用户没踩踏板时（两个 active 都为 0）整体早返回，不写任何字段。
// 原实现每 2ms 持续往 throttle/brake 字段写 0.0f，会覆盖游戏自带输入——
// ButtonsSteering（屏幕方向键）模式下的转向辅助（steerHelp、
// LockSteerAtVelocity、TractionFilter）依赖油门/刹车值决定辅助力度，
// 被持续清零后转向辅助失效，方向键表现为"不转向"。陀螺仪模式不依赖
// 油门/刹车做转向，所以不受影响。松开瞬间的清零由
// pedal_set_throttle_value/pedal_set_brake_value 在 active→inactive
// 转变时主动调 clear_throttle_field/clear_brake_field 完成，不依赖
// writer 轮询。
static void *input_writer_thread(void *arg) {
    (void) arg;

    while (g_writer_running) {
        // 用户完全没操作时不写字段——让游戏自带油门/刹车/方向键输入
        // 不被覆盖。只有任一 active 为真（用户按住模块踏板）才写。
        if (g_throttle_active || g_brake_active) {
            void *controller = (void *) g_last_controller;
            if (controller != NULL) {
                apply_inputs_to_controller(controller);
            }
        }
        usleep(1000 * 2); // 2 ms -> ~500 Hz
    }

    return NULL;
}

static void start_input_writer(void) {
    if (g_writer_running) return;
    g_writer_running = 1;
    pthread_create(&g_writer_thread, NULL, input_writer_thread, NULL);
}

static void stop_input_writer(void) {
    g_writer_running = 0;
    if (g_writer_thread) {
        pthread_join(g_writer_thread, NULL);
        g_writer_thread = 0;
    }
}

static void disable_automatic_gear(void *drivetrain) {
    if (drivetrain == NULL || g_config.drivetrain_automatic_field_offset == 0) {
        return;
    }
    write_bool_field(drivetrain, g_config.drivetrain_automatic_field_offset, false);
}

static void proxy_set_throttle(void *this, float value) {
    g_last_controller = this;

    // When the overlay is controlling throttle, swallow the game's input.
    // The background writer thread (which reads overlay values from a file)
    // continuously overwrites the instance fields with the overlay's values.
    if (g_throttle_active) {
        return;
    }

    typedef void (*orig_t)(void *, float);
    if (g_throttle_orig != NULL) {
        ((orig_t) g_throttle_orig)(this, value);
    }
}

static void proxy_set_brake(void *this, float value) {
    g_last_controller = this;

    if (g_brake_active) {
        return;
    }

    typedef void (*orig_t)(void *, float);
    if (g_brake_orig != NULL) {
        ((orig_t) g_brake_orig)(this, value);
    }
}

static void proxy_shift_up(void *this) {
    typedef void (*orig_t)(void *);
    LOGI("proxy_shift_up called");
    if (g_shift_up_orig != NULL) {
        ((orig_t) g_shift_up_orig)(this);
    }
}

static void proxy_shift_down(void *this) {
    typedef void (*orig_t)(void *);
    LOGI("proxy_shift_down called");
    if (g_shift_down_orig != NULL) {
        ((orig_t) g_shift_down_orig)(this);
    }
}

static void proxy_fixed_update(void *this) {
    uint8_t car_pilot = *(volatile uint8_t *) ((uintptr_t) this + 0x68);
    if (car_pilot == 0) {
        g_last_controller = this;
        uintptr_t drivetrain = read_ptr(this, g_config.drivetrain_offset);
        if (drivetrain != 0) {
            g_player_drivetrain = drivetrain;
        }
        // Ensure our inputs are present before the physics tick reads them.
        apply_inputs_to_controller(this);
    }

    typedef void (*orig_t)(void *);
    if (g_fixed_update_orig != NULL) {
        ((orig_t) g_fixed_update_orig)(this);
    }

    if (car_pilot == 0) {
        apply_inputs_to_controller(this);
    }
}

static void proxy_drivetrain_fixed_update(void *this) {
    typedef void (*orig_t)(void *);

    if (g_disable_auto_gear && this != NULL) {
        uintptr_t self = (uintptr_t) this;
        if (self == g_player_drivetrain) {
            write_bool_field(this, g_config.drivetrain_automatic_field_offset, false);
        }
    }

    if (g_drivetrain_fixed_update_orig != NULL) {
        ((orig_t) g_drivetrain_fixed_update_orig)(this);
    }
}

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

bool pedal_install_hooks(const pedal_hook_config_t *config) {
    if (config) {
        g_config = *config;
    }

    if (!g_config.enable_control_replacement) {
        LOGI("Pedal control replacement disabled");
        return true;
    }

    if (g_hooks_installed) {
        return true;
    }

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address");
        return false;
    }
    LOGI("libil2cpp.so base address: 0x%" PRIxPTR, base);

    if (g_config.set_throttle_offset != 0) {
        uintptr_t target = base + g_config.set_throttle_offset;
        g_throttle_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_throttle,
                (void **) &g_throttle_orig);
        if (g_throttle_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setThrottle) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setThrottle at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.set_brake_offset != 0) {
        uintptr_t target = base + g_config.set_brake_offset;
        g_brake_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_set_brake,
                (void **) &g_brake_orig);
        if (g_brake_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(setBrake) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked setBrake at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.shift_up_offset != 0) {
        uintptr_t target = base + g_config.shift_up_offset;
        g_shift_up_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_shift_up,
                (void **) &g_shift_up_orig);
        if (g_shift_up_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(shiftUp) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked shiftUp at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.shift_down_offset != 0) {
        uintptr_t target = base + g_config.shift_down_offset;
        g_shift_down_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_shift_down,
                (void **) &g_shift_down_orig);
        if (g_shift_down_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(shiftDown) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked shiftDown at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.set_gear_offset != 0) {
        LOGI("setGear offset recorded but not installed");
    }

    if (g_config.fixed_update_offset != 0) {
        uintptr_t target = base + g_config.fixed_update_offset;
        g_fixed_update_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_fixed_update,
                (void **) &g_fixed_update_orig);
        if (g_fixed_update_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(FixedUpdate) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked FixedUpdate at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.drivetrain_fixed_update_offset != 0) {
        uintptr_t target = base + g_config.drivetrain_fixed_update_offset;
        g_drivetrain_fixed_update_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_drivetrain_fixed_update,
                (void **) &g_drivetrain_fixed_update_orig);
        if (g_drivetrain_fixed_update_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(DrivetrainFixedUpdate) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("Hooked DrivetrainFixedUpdate at 0x%" PRIxPTR, target);
        }
    }

    g_hooks_installed = 1;
    start_input_writer();
    return true;
}

void pedal_uninstall_hooks(void) {
    if (!g_hooks_installed) {
        return;
    }

    stop_input_writer();

    if (g_throttle_stub != NULL) {
        shadowhook_unhook(g_throttle_stub);
        g_throttle_stub = NULL;
        g_throttle_orig = NULL;
    }
    if (g_brake_stub != NULL) {
        shadowhook_unhook(g_brake_stub);
        g_brake_stub = NULL;
        g_brake_orig = NULL;
    }
    if (g_shift_up_stub != NULL) {
        shadowhook_unhook(g_shift_up_stub);
        g_shift_up_stub = NULL;
        g_shift_up_orig = NULL;
    }
    if (g_shift_down_stub != NULL) {
        shadowhook_unhook(g_shift_down_stub);
        g_shift_down_stub = NULL;
        g_shift_down_orig = NULL;
    }
    if (g_fixed_update_stub != NULL) {
        shadowhook_unhook(g_fixed_update_stub);
        g_fixed_update_stub = NULL;
        g_fixed_update_orig = NULL;
    }
    if (g_drivetrain_fixed_update_stub != NULL) {
        shadowhook_unhook(g_drivetrain_fixed_update_stub);
        g_drivetrain_fixed_update_stub = NULL;
        g_drivetrain_fixed_update_orig = NULL;
    }

    g_last_controller = NULL;
    g_player_drivetrain = 0;
    g_hooks_installed = 0;
    g_config.enable_control_replacement = false;
}

void *pedal_get_controller(void) {
    return (void *) g_last_controller;
}

void pedal_shift_up(void) {
    LOGI("pedal_shift_up");
    void *controller = (void *) g_last_controller;
    if (controller != NULL && g_shift_up_orig != NULL) {
        typedef void (*orig_t)(void *);
        ((orig_t) g_shift_up_orig)(controller);
    }
}

void pedal_shift_down(void) {
    LOGI("pedal_shift_down");
    void *controller = (void *) g_last_controller;
    if (controller != NULL && g_shift_down_orig != NULL) {
        typedef void (*orig_t)(void *);
        ((orig_t) g_shift_down_orig)(controller);
    }
}

void pedal_set_disable_auto_gear(int disable) {
    g_disable_auto_gear = disable ? 1 : 0;
}

void pedal_set_throttle_value(float value) {
    int was_active = g_throttle_active;

    if (value <= 0.0f) {
        g_throttle_active = 0;
        g_throttle_value = 0.0f;
    } else {
        g_throttle_active = 1;
        g_throttle_value = value;
    }

    void *controller = (void *) g_last_controller;
    if (controller == NULL) return;

    if (g_throttle_active) {
        // 用户按住/调整：写当前值。
        apply_inputs_to_controller(controller);
    } else if (was_active) {
        // active→inactive（松开）：主动清零一次，避免值卡住。
        // writer 线程和 FixedUpdate hook 在 !active 时不再写字段，
        // 所以这里必须主动清一次。
        clear_throttle_field(controller);
    }
}

void pedal_set_brake_value(float value) {
    int was_active = g_brake_active;

    if (value <= 0.0f) {
        g_brake_active = 0;
        g_brake_value = 0.0f;
    } else {
        g_brake_active = 1;
        g_brake_value = value;
    }

    void *controller = (void *) g_last_controller;
    if (controller == NULL) return;

    if (g_brake_active) {
        apply_inputs_to_controller(controller);
    } else if (was_active) {
        clear_brake_field(controller);
    }
}
