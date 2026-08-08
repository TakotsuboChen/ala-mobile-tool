#include "unlock_hook.h"
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <inttypes.h>
#include <link.h>
#include <malloc.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "shadowhook.h"

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// NPatch 日志文件只能记 Java 层 xposedInterface.log() 的输出，
// 不记 native __android_log_print（logcat 才有）。NPatch 用户多为小白，
// 要求他们抓 adb logcat 不现实。所以 native 关键诊断日志同步写一份
// 到 NPatch 日志目录下的 ala_native.log，用户用 NPatch 导出日志时
// 这个文件也能被一起带出来（同一目录）。
//
// 路径推导：读 /proc/self/cmdline 拿进程名（main 进程 = 包名，
// 子进程 = 包名:Suffix），strip 冒号后得包名，拼成
// /sdcard/Android/media/<pkg>/npatch/log/ala_native.log。
// 游戏进程对自己的外部 media 目录天然可写，无需权限。
static char g_log_path[256] = {0};
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

static void resolve_log_path(void) {
    if (g_log_path[0] != '\0') return;
    char cmdline[256] = {0};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) return;
    cmdline[n] = '\0';
    // strip :suffix (子进程)
    char *colon = strchr(cmdline, ':');
    if (colon) *colon = '\0';
    snprintf(g_log_path, sizeof(g_log_path),
             "/sdcard/Android/media/%s/npatch/log/ala_native.log", cmdline);
}

// 把一行日志写入 NPatch 日志目录下的 ala_native.log。
// 同时仍打 logcat（adb 调试时方便）。带时间戳和 tid。
static void npatch_log(int prio, const char *tag, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    // logcat
    __android_log_print(prio, tag, "%s", buf);

    // 文件
    resolve_log_path();
    if (g_log_path[0] == '\0') return;
    pthread_mutex_lock(&g_log_mutex);
    int f = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (f >= 0) {
        char line[1200];
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        int ms = (int)(ts.tv_nsec / 1000000);
        const char *prio_str = (prio == ANDROID_LOG_INFO) ? "I" :
                               (prio == ANDROID_LOG_WARN) ? "W" :
                               (prio == ANDROID_LOG_ERROR) ? "E" : "D";
        snprintf(line, sizeof(line),
                 "[%04d-%02d-%02dT%02d:%02d:%02d.%03d pid=%d tid=%d][%s/%s] %s\n",
                 tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                 tm.tm_hour, tm.tm_min, tm.tm_sec, ms,
                 (int)getpid(), (int)gettid(), prio_str, tag, buf);
        write(f, line, strlen(line));
        close(f);
    }
    pthread_mutex_unlock(&g_log_mutex);
}

// 重定向关键宏到 npatch_log（同时打 logcat + 写文件）。
#undef LOGI
#undef LOGD
#undef LOGE
#undef LOGW
#define LOGI(...) npatch_log(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) npatch_log(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) npatch_log(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) npatch_log(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static unlock_hook_config_t g_config = {0};

// Original function pointers
typedef void (*awake_func_t)(void *this_ptr);
typedef void (*init_billing_func_t)(void *this_ptr);
typedef void (*on_owned_none_func_t)(void *this_ptr);
typedef void (*on_purchase_failed_func_t)(void *this_ptr);
typedef void (*set_unlocked_func_t)(void *this_ptr, bool unlocked);

static awake_func_t orig_awake = NULL;
static init_billing_func_t orig_init_billing = NULL;
static on_owned_none_func_t orig_on_owned_none = NULL;
static on_purchase_failed_func_t orig_on_purchase_failed = NULL;
static set_unlocked_func_t orig_set_unlocked = NULL;

// BillingManager.GetInstance() 的函数指针类型：静态方法，无 this，
// 返回 BillingManager* 单例。任何代码访问 BillingManager 都会调它，
// 比 Awake 触发概率高得多 —— Awake 只在 MonoBehaviour 生命周期触发，
// BillingManager 如果不是 MonoBehaviour 就没有 Awake。
typedef void *(*get_instance_func_t)(void);
static get_instance_func_t orig_get_instance = NULL;

static volatile int g_hooks_installed = 0;
// force_unlock 只调一次：Awake hook 和 GetInstance hook 都可能触发，
// 避免重复调 OnAlreadyOwned（Unity 侧可能崩或重复弹窗）。
static volatile int g_force_unlock_done = 0;

// Use dl_iterate_phdr to get the actual load base of libil2cpp.so.
// dlopen() returns an opaque soinfo* handle, NOT the load base address,
// so we must not use it for offset calculations.
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

// IL2CPP string 创建：调用 libil2cpp.so 的 il2cpp_string_new 函数。
// OnAlreadyOwned(string productId) 需要一个 IL2CPP string 参数 "unlock_alamobile"。
// 我们通过 dlsym 找到 il2cpp_string_new，把 C string 转成 IL2CPP string。
static void *(*g_il2cpp_string_new)(const char *str) = NULL;

// dl_iterate_phdr 回调里记录的 libil2cpp.so 完整路径。dlopen 必须用
// 实际加载时的名字（可能是绝对路径如 /data/app/.../libil2cpp.so），
// 用 "libil2cpp.so" 这个 basename 在 NPatch 的 linker namespace 下查不到。
static char g_il2cpp_path[512] = {0};

static int find_il2cpp_path_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    (void) data;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, "libil2cpp.so") != NULL) {
        strncpy(g_il2cpp_path, info->dlpi_name, sizeof(g_il2cpp_path) - 1);
        return 1;
    }
    return 0;
}

static void resolve_il2cpp_string_new(void) {
    if (g_il2cpp_string_new != NULL) return;

    // 先用 dl_iterate_phdr 找到 libil2cpp.so 的完整加载路径，再用这个
    // 路径 dlopen —— dlopen 同一个已加载的 .so 会返回同一 handle，不会重复加载。
    if (g_il2cpp_path[0] == '\0') {
        dl_iterate_phdr(find_il2cpp_path_cb, NULL);
    }
    void *handle = NULL;
    if (g_il2cpp_path[0] != '\0') {
        handle = dlopen(g_il2cpp_path, RTLD_NOW | RTLD_NOLOAD);
        LOGI("resolve_il2cpp_string_new: dlopen(%s) = %p", g_il2cpp_path, handle);
    }
    if (!handle) {
        // fallback：basename 试一次（部分 linker namespace 下能查到）
        handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
        LOGW("resolve_il2cpp_string_new: fallback dlopen(libil2cpp.so) = %p", handle);
    }
    if (handle) {
        g_il2cpp_string_new = (void *(*)(const char *))dlsym(handle, "il2cpp_string_new");
        LOGI("il2cpp_string_new resolved: %p", g_il2cpp_string_new);
    } else {
        LOGE("Failed to dlopen libil2cpp.so for il2cpp_string_new: %s", dlerror());
    }
}

// BillingManager.OnAlreadyOwned(string) 的函数指针类型。
// IL2CPP 实例方法的调用约定：第一个参数是 this（BillingManager 实例），
// 第二个参数是方法的第一个参数（IL2CPP string 指针）。
typedef void (*on_already_owned_func_t)(void *this_ptr, void *il2cpp_string);

static on_already_owned_func_t g_on_already_owned = NULL;

// BillingManager.SetUnlocked(bool) 的函数指针类型。
// IL2CPP 实例方法：第一参数 this，第二参数 bool（C99 bool 在 arm64 上是 1 字节，
// 但 IL2CPP 调用约定里 bool 参数用寄存器传，w0/x0 低 8 位，值 1=true）。
// 复用文件顶部已定义的 set_unlocked_func_t（void*, bool），不再重定义。
static set_unlocked_func_t g_set_unlocked = NULL;

// 主动注入"已拥有"状态：直接调 BillingManager.SetUnlocked(true)。
// 这是最直接的解锁路径 —— SetUnlocked 负责：
//   IsUnlocked = true → PlayerPrefs.SetInt("AnciTuttu", 1) → OnUnlockedChanged.Invoke()
// 不依赖 OnAlreadyOwned(string)（需要创建 IL2CPP string，且字符串比较可能失败），
// 也不依赖游戏自己发起 BillingBridge.checkOwned → sendUnityMessage 回调链。
static void force_unlock_direct(void *this_ptr) {
    if (this_ptr == NULL) return;

    if (g_set_unlocked == NULL) {
        uintptr_t base = get_module_base("libil2cpp.so");
        if (base == 0) {
            LOGE("force_unlock_direct: libil2cpp.so base not found");
            return;
        }
        g_set_unlocked = (set_unlocked_func_t)(base + g_config.billing_manager_set_unlocked_offset);
        LOGI("force_unlock_direct: SetUnlocked at %p (base=0x%lx + 0x%lx)",
             g_set_unlocked, (unsigned long)base,
             (unsigned long)g_config.billing_manager_set_unlocked_offset);
    }

    LOGI("force_unlock_direct: calling SetUnlocked(true) on BillingManager %p", this_ptr);
    g_set_unlocked(this_ptr, true);
    LOGI("force_unlock_direct: SetUnlocked called successfully");
}

// 主动注入"已拥有"状态（OnAlreadyOwned 路径，保留作辅助）：
// 在 BillingManager.Awake() hook 里直接调 OnAlreadyOwned("unlock_alamobile")。
// 这不依赖游戏自己发起 BillingBridge.checkOwned → sendUnityMessage 回调链。
// 注意：需要创建 IL2CPP string，dlopen 失败时用手写 struct 兜底（klass=NULL
// 可能导致字符串比较失败，所以主路径改为 SetUnlocked，此函数仅作辅助）。
static void force_unlock_via_on_already_owned(void *this_ptr) {
    if (this_ptr == NULL) return;

    // 先把 OnAlreadyOwned 地址算出来（用 dl_iterate_phdr 的 base，不依赖 dlopen）。
    if (g_on_already_owned == NULL) {
        uintptr_t base = get_module_base("libil2cpp.so");
        if (base == 0) {
            LOGE("force_unlock: libil2cpp.so base not found");
            return;
        }
        g_on_already_owned = (on_already_owned_func_t)(base + g_config.billing_manager_on_already_owned_offset);
        LOGI("force_unlock: OnAlreadyOwned at %p (base=0x%lx + 0x%lx)",
             g_on_already_owned, (unsigned long)base,
             (unsigned long)g_config.billing_manager_on_already_owned_offset);
    }

    // il2cpp_string_new 尝试 dlopen 拿 handle（可能因 linker namespace 隔离失败）。
    resolve_il2cpp_string_new();

    void *product_id_str = NULL;
    if (g_il2cpp_string_new != NULL) {
        product_id_str = g_il2cpp_string_new("unlock_alamobile");
        LOGI("force_unlock: il2cpp_string_new(\"unlock_alamobile\") = %p", product_id_str);
    } else {
        // dlopen 失败兜底：直接手写 IL2CPP string 结构。
        // IL2CPP string 内存布局（64-bit）：
        //   +0x00 klass* (Il2CppClass*，8 bytes)
        //   +0x08 monitor* (8 bytes)
        //   +0x10 length (i32, 4 bytes)
        //   +0x14 chars[] (UTF-16，每个 char 2 bytes)
        //   +0x14 + length*2 = null terminator (2 bytes)
        // klass 和 monitor 留 NULL —— Unity 侧 OnAlreadyOwned 只读 length + chars，
        //   不解引用 klass（它不是 Equals/GetType 之类需要 class 的路径）。
        // 如果 Unity 崩了说明 klass 被解引用，得换路径。
        LOGW("force_unlock: il2cpp_string_new unavailable, using manual IL2CPP string struct");
        const char *ascii = "unlock_alamobile";
        size_t len = strlen(ascii);
        size_t total = 0x14 + len * 2 + 2;  // header + UTF-16 + null
        void *str_mem = malloc(total);
        if (str_mem == NULL) {
            LOGE("force_unlock: malloc for manual string failed");
            return;
        }
        memset(str_mem, 0, total);
        // length field
        *(int32_t *)((uint8_t *)str_mem + 0x10) = (int32_t)len;
        // UTF-16 chars (ASCII -> UTF-16LE，直接零扩展)
        for (size_t i = 0; i < len; i++) {
            ((uint8_t *)str_mem + 0x14)[i * 2] = (uint8_t)ascii[i];
            ((uint8_t *)str_mem + 0x14)[i * 2 + 1] = 0;
        }
        product_id_str = str_mem;
        LOGI("force_unlock: manual string at %p len=%zu", product_id_str, len);
    }

    if (product_id_str == NULL) {
        LOGE("force_unlock: product_id_str is NULL, cannot call OnAlreadyOwned");
        return;
    }

    LOGI("force_unlock: calling OnAlreadyOwned(\"unlock_alamobile\") on BillingManager %p", this_ptr);
    g_on_already_owned(this_ptr, product_id_str);
    LOGI("force_unlock: OnAlreadyOwned called successfully");
}

// Hook for BillingManager.Awake() - completely skip it and set unlock state
static void hook_awake(void *this_ptr) {
    LOGI("BillingManager.Awake() hooked - skipping original, forcing unlock");

    if (!this_ptr) {
        LOGE("Awake: this_ptr is NULL");
        return;
    }

    // Set all unlock-related fields to true
    bool *is_unlocked = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_is_unlocked_field_offset);
    bool *has_store_connection = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_has_store_connection_field_offset);
    bool *has_completed_check = (bool *)((uint8_t *)this_ptr + g_config.billing_manager_has_completed_ownership_check_field_offset);

    *is_unlocked = true;
    *has_store_connection = true;
    *has_completed_check = true;

    LOGI("Set BillingManager fields: IsUnlocked=true, HasStoreConnection=true, HasCompletedOwnershipCheck=true");

    // 主动注入解锁：先调 SetUnlocked(true)（最直接，不需 string），
    // 再调 OnAlreadyOwned("unlock_alamobile")（走 Unity 完整链，辅助）。
    // 两条路径互补：SetUnlocked 直接设字段 + PlayerPrefs 持久化；
    // OnAlreadyOwned 走 Unity 侧完整逻辑（OnUnlockedChanged 回调等）。
    force_unlock_direct(this_ptr);
    force_unlock_via_on_already_owned(this_ptr);

    // DO NOT call original Awake() - it will trigger InitializeBilling() which queries Google Play
}

// Hook for BillingManager.GetInstance() - 兜底注入点。
// Awake 是 MonoBehaviour 生命周期方法，BillingManager 如果不是
// MonoBehaviour 就没有 Awake —— 这时 Awake hook 永远不触发。
// GetInstance 是静态方法，任何代码访问 BillingManager 都会调它，
// 触发概率比 Awake 高得多。在 GetInstance 返回后立即对单例做
// 主动注入，作为 Awake 的兜底。
static void *hook_get_instance(void) {
    void *instance = orig_get_instance ? orig_get_instance() : NULL;
    LOGI("BillingManager.GetInstance() hooked, returned %p", instance);

    if (instance == NULL) return instance;

    // 只做一次：避免每次 GetInstance 都重复调 OnAlreadyOwned。
    if (__atomic_test_and_set(&g_force_unlock_done, __ATOMIC_SEQ_CST)) {
        // 已置位，跳过。
        return instance;
    }

    // 直接强制解锁字段 + 调 OnAlreadyOwned，逻辑同 hook_awake。
    bool *is_unlocked = (bool *)((uint8_t *)instance + g_config.billing_manager_is_unlocked_field_offset);
    bool *has_store_connection = (bool *)((uint8_t *)instance + g_config.billing_manager_has_store_connection_field_offset);
    bool *has_completed_check = (bool *)((uint8_t *)instance + g_config.billing_manager_has_completed_ownership_check_field_offset);

    *is_unlocked = true;
    *has_store_connection = true;
    *has_completed_check = true;

    LOGI("GetInstance: Set fields IsUnlocked=true, HasStoreConnection=true, HasCompletedOwnershipCheck=true");

    force_unlock_direct(instance);
    force_unlock_via_on_already_owned(instance);

    return instance;
}

// Hook for BillingManager.InitializeBilling() - prevent billing initialization
static void hook_initialize_billing(void *this_ptr) {
    LOGI("BillingManager.InitializeBilling() hooked - skipping");
    // Do nothing - prevent any billing initialization
}

// Hook for BillingManager.OnOwnedNone() - prevent error dialog
static void hook_on_owned_none(void *this_ptr) {
    LOGI("BillingManager.OnOwnedNone() hooked - blocking error dialog");
    // Do nothing - prevent error dialog from showing
}

// Hook for BillingManager.OnPurchaseFailed() - prevent failure dialog
static void hook_on_purchase_failed(void *this_ptr) {
    LOGI("BillingManager.OnPurchaseFailed() hooked - blocking failure dialog");
    // Do nothing - prevent failure dialog from showing
}

bool unlock_install_hooks(const unlock_hook_config_t *config) {
    if (!config) {
        LOGE("unlock_install_hooks: config is NULL");
        return false;
    }

    memcpy(&g_config, config, sizeof(unlock_hook_config_t));

    LOGI("unlock_install_hooks: enable_unlock=%d", g_config.enable_unlock);
    LOGI("unlock_install_hooks: awake_offset=0x%lx get_instance_offset=0x%lx init_billing_offset=0x%lx on_owned_none_offset=0x%lx on_purchase_failed_offset=0x%lx on_already_owned_offset=0x%lx",
         (unsigned long)g_config.billing_manager_awake_offset,
         (unsigned long)g_config.billing_manager_get_instance_offset,
         (unsigned long)g_config.billing_manager_initialize_billing_offset,
         (unsigned long)g_config.billing_manager_on_owned_none_offset,
         (unsigned long)g_config.billing_manager_on_purchase_failed_offset,
         (unsigned long)g_config.billing_manager_on_already_owned_offset);

    if (g_hooks_installed) {
        LOGI("Unlock hooks already installed, skipping");
        return true;
    }

    if (!g_config.enable_unlock) {
        LOGI("Unlock feature is disabled in config");
        return true;
    }

    LOGI("Installing unlock hooks...");

    // Get the actual load base of libil2cpp.so via dl_iterate_phdr.
    // dlopen() returns an opaque handle, NOT the load base address.
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("Failed to locate libil2cpp.so base address");
        return false;
    }
    LOGI("libil2cpp.so base address: 0x%" PRIxPTR, base);

    // Hook 1: BillingManager.Awake()
    if (g_config.billing_manager_awake_offset != 0) {
        void *awake_addr = (void *)(base + g_config.billing_manager_awake_offset);
        LOGI("Hooking BillingManager.Awake() at %p", awake_addr);

        void *result = shadowhook_hook_sym_addr(
            awake_addr,
            (void *)hook_awake,
            (void **)&orig_awake
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.Awake()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.Awake(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("BillingManager.Awake() offset is 0, skipping");
    }

    // Hook 1b: BillingManager.GetInstance() — 兜底注入点。
    // Awake 是 MonoBehaviour 方法，BillingManager 如果不是 MonoBehaviour
    // 就没有 Awake（NPatch/vivo 实测：Awake hook 装上但从不触发）。
    // GetInstance 是静态方法，任何代码访问 BillingManager 都调它，触发概率高。
    // 早期 install 时 libil2cpp.so 可能还没加载 BillingManager 类——但
    // shadowhook 是 inline hook，只要地址对就装上，类加载时机不影响。
    if (g_config.billing_manager_get_instance_offset != 0) {
        void *get_instance_addr = (void *)(base + g_config.billing_manager_get_instance_offset);
        LOGI("Hooking BillingManager.GetInstance() at %p", get_instance_addr);

        void *result = shadowhook_hook_sym_addr(
            get_instance_addr,
            (void *)hook_get_instance,
            (void **)&orig_get_instance
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.GetInstance()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.GetInstance(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    } else {
        LOGW("BillingManager.GetInstance() offset is 0, skipping");
    }

    // Hook 2: BillingManager.InitializeBilling()
    if (g_config.billing_manager_initialize_billing_offset != 0) {
        void *init_billing_addr = (void *)(base + g_config.billing_manager_initialize_billing_offset);
        LOGI("Hooking BillingManager.InitializeBilling() at %p", init_billing_addr);

        void *result = shadowhook_hook_sym_addr(
            init_billing_addr,
            (void *)hook_initialize_billing,
            (void **)&orig_init_billing
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.InitializeBilling()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.InitializeBilling(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    // Hook 3: BillingManager.OnOwnedNone()
    if (g_config.billing_manager_on_owned_none_offset != 0) {
        void *on_owned_none_addr = (void *)(base + g_config.billing_manager_on_owned_none_offset);
        LOGI("Hooking BillingManager.OnOwnedNone() at %p", on_owned_none_addr);

        void *result = shadowhook_hook_sym_addr(
            on_owned_none_addr,
            (void *)hook_on_owned_none,
            (void **)&orig_on_owned_none
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnOwnedNone()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.OnOwnedNone(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    // Hook 4: BillingManager.OnPurchaseFailed()
    if (g_config.billing_manager_on_purchase_failed_offset != 0) {
        void *on_purchase_failed_addr = (void *)(base + g_config.billing_manager_on_purchase_failed_offset);
        LOGI("Hooking BillingManager.OnPurchaseFailed() at %p", on_purchase_failed_addr);

        void *result = shadowhook_hook_sym_addr(
            on_purchase_failed_addr,
            (void *)hook_on_purchase_failed,
            (void **)&orig_on_purchase_failed
        );

        if (result) {
            LOGI("Successfully hooked BillingManager.OnPurchaseFailed()");
        } else {
            int err = shadowhook_get_errno();
            LOGE("Failed to hook BillingManager.OnPurchaseFailed(): errno=%d (%s)",
                 err, shadowhook_to_errmsg(err));
        }
    }

    LOGI("Unlock hooks installation complete");
    g_hooks_installed = 1;
    return true;
}

void unlock_uninstall_hooks(void) {
    LOGI("Uninstalling unlock hooks");

    // No need to dlclose - we used dl_iterate_phdr instead of dlopen
    orig_awake = NULL;
    orig_init_billing = NULL;
    orig_on_owned_none = NULL;
    orig_on_purchase_failed = NULL;
    orig_set_unlocked = NULL;

    LOGI("Unlock hooks uninstalled");
}

// 主动触发一次强制解锁，不依赖 hook 触发时机。
// 在 15s 延迟路径中作为 one-shot 调用：
// 1) 通过 get_Instance() 获取 BillingManager 单例指针
// 2) 如果非空，直接调 SetUnlocked(true) 解锁
// 3) 同时调 OnAlreadyOwned("unlock_alamobile") 走 Unity 完整链
//
// 返回值：true=解锁成功或已解锁，false=拿不到实例
bool unlock_force_now(void) {
    LOGI("unlock_force_now: entering");

    // 解析 get_Instance 地址
    uintptr_t get_instance_offset = g_config.billing_manager_get_instance_offset;
    if (get_instance_offset == 0) {
        get_instance_offset = 0x186C958;
        LOGW("unlock_force_now: get_instance_offset was 0, using fallback 0x186C958");
    }
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("unlock_force_now: libil2cpp.so base not found");
        return false;
    }
    get_instance_func_t get_instance = (get_instance_func_t)(base + get_instance_offset);
    LOGI("unlock_force_now: get_Instance at %p", get_instance);

    void *instance = get_instance();
    LOGI("unlock_force_now: get_Instance() returned %p", instance);
    if (instance == NULL) {
        LOGE("unlock_force_now: BillingManager instance is NULL");
        return false;
    }

    // 设字段
    bool *is_unlocked = (bool *)((uint8_t *)instance + g_config.billing_manager_is_unlocked_field_offset);
    bool *has_store_connection = (bool *)((uint8_t *)instance + g_config.billing_manager_has_store_connection_field_offset);
    bool *has_completed_check = (bool *)((uint8_t *)instance + g_config.billing_manager_has_completed_ownership_check_field_offset);
    *is_unlocked = true;
    *has_store_connection = true;
    *has_completed_check = true;
    LOGI("unlock_force_now: Set fields IsUnlocked=true, HasStoreConnection=true, HasCompletedOwnershipCheck=true");

    // 调 SetUnlocked(true)
    uintptr_t set_unlocked_offset = g_config.billing_manager_set_unlocked_offset;
    if (set_unlocked_offset == 0) {
        set_unlocked_offset = 0x186E440;
        LOGW("unlock_force_now: set_unlocked_offset was 0, using fallback 0x186E440");
    }
    set_unlocked_func_t set_unlocked = (set_unlocked_func_t)(base + set_unlocked_offset);
    LOGI("unlock_force_now: calling SetUnlocked(true) on BillingManager %p", instance);
    set_unlocked(instance, true);
    LOGI("unlock_force_now: SetUnlocked called successfully");

    // 也调 OnAlreadyOwned 辅助
    uintptr_t on_already_owned_offset = g_config.billing_manager_on_already_owned_offset;
    if (on_already_owned_offset == 0) {
        on_already_owned_offset = 0x186E1B4;
        LOGW("unlock_force_now: on_already_owned_offset was 0, using fallback 0x186E1B4");
    }
    on_already_owned_func_t on_already_owned = (on_already_owned_func_t)(base + on_already_owned_offset);
    LOGI("unlock_force_now: OnAlreadyOwned at %p", on_already_owned);

    // 创建 IL2CPP string
    resolve_il2cpp_string_new();
    void *product_id_str = NULL;
    if (g_il2cpp_string_new != NULL) {
        product_id_str = g_il2cpp_string_new("unlock_alamobile");
    }
    if (product_id_str == NULL) {
        const char *ascii = "unlock_alamobile";
        size_t len = strlen(ascii);
        size_t total = 0x14 + len * 2 + 2;
        void *str_mem = malloc(total);
        if (str_mem != NULL) {
            memset(str_mem, 0, total);
            *(int32_t *)((uint8_t *)str_mem + 0x10) = (int32_t)len;
            for (size_t i = 0; i < len; i++) {
                ((uint8_t *)str_mem + 0x14)[i * 2] = (uint8_t)ascii[i];
                ((uint8_t *)str_mem + 0x14)[i * 2 + 1] = 0;
            }
            product_id_str = str_mem;
        }
    }
    if (product_id_str != NULL) {
        LOGI("unlock_force_now: calling OnAlreadyOwned on BillingManager %p", instance);
        on_already_owned(instance, product_id_str);
        LOGI("unlock_force_now: OnAlreadyOwned called successfully");
    }

    return true;
}
