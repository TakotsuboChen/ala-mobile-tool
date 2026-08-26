#include "hide_pedals_hook.h"
#include "native_log.h"
#include <dlfcn.h>
#include <elf.h>
#include <inttypes.h>
#include <link.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGW(...) NLOGW(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

// Unity / IL2CPP method RVA constants (Ala Mobile 8.0.4, versionCode 200146)
#define RVA_IRDS_UI_MOBILE_CONTROLS_GET_INSTANCE 0x174D268L
#define RVA_GAME_OBJECT_SET_ACTIVE      0x329D704L
#define RVA_GAME_OBJECT_GET_ACTIVE_SELF 0x329D748L
#define RVA_GAME_OBJECT_GET_TRANSFORM   0x329D5C8L
#define RVA_COMPONENT_GET_GAME_OBJECT   0x329E3B0L
#define RVA_TRANSFORM_GET_CHILD         0x32ABCBCL
#define RVA_TRANSFORM_GET_CHILD_COUNT   0x32AB654L
#define RVA_OBJECT_GET_NAME             0x32A1838L

// IL2CPP memory layout
#define OFFSET_IMC_MOBILE_CONTROLS_LAYOUTS 0x20
#define OFFSET_MC_LAYOUT_OBJECT 0x18
#define IL2CPP_LIST_ITEMS_OFFSET 0x10
#define IL2CPP_LIST_SIZE_OFFSET  0x18
#define IL2CPP_ARRAY_ELEMENTS_OFFSET 0x20
#define IL2CPP_STRING_LENGTH_OFFSET 0x10
#define IL2CPP_STRING_CHARS_OFFSET  0x14

#define MAX_RECURSION_DEPTH 10
#define TICK_INTERVAL 30   // 每 30 帧执行一次（≈0.5s @60fps），平衡延迟和性能

// Function pointer types — IL2CPP 实例方法: (this, args..., MethodInfo*)
typedef void *(*get_irds_ui_instance_t)(void *method_info);
typedef void *(*go_get_transform_t)(void *go, void *method_info);
typedef void *(*component_get_game_object_t)(void *component, void *method_info);
typedef void *(*object_get_name_t)(void *obj, void *method_info);
typedef int (*transform_get_child_count_t)(void *transform, void *method_info);
typedef void *(*transform_get_child_t)(void *transform, int index, void *method_info);
typedef void (*go_set_active_t)(void *go, bool active, void *method_info);
typedef bool (*go_get_active_self_t)(void *go, void *method_info);

// IL2CPP runtime API (via ELF symbol lookup)
typedef void *(*il2cpp_object_get_class_t)(void *obj);
typedef void *(*il2cpp_class_get_method_from_name_t)(void *klass, const char *name, int argc);
typedef void *(*il2cpp_runtime_invoke_t)(void *method, void *obj, void **params, void **exc);
typedef const char *(*il2cpp_class_get_name_t)(void *klass);

static get_irds_ui_instance_t      g_get_irds_ui_instance = NULL;
static go_get_transform_t          g_go_get_transform = NULL;
static component_get_game_object_t g_component_get_game_object = NULL;
static object_get_name_t           g_object_get_name = NULL;
static transform_get_child_count_t g_transform_get_child_count = NULL;
static transform_get_child_t       g_transform_get_child = NULL;
static go_set_active_t             g_go_set_active = NULL;
static go_get_active_self_t        g_go_get_active_self = NULL;

static il2cpp_object_get_class_t          g_il2cpp_object_get_class = NULL;
static il2cpp_class_get_method_from_name_t g_il2cpp_class_get_method_from_name = NULL;
static il2cpp_runtime_invoke_t            g_il2cpp_runtime_invoke = NULL;
static il2cpp_class_get_name_t            g_il2cpp_class_get_name = NULL;
static void *g_set_active_methodinfo = NULL;
static void *g_get_gameobject_methodinfo = NULL;

static volatile bool g_enabled = false;
static bool g_first_traverse = true;
static int g_hidden_count = 0;
static int g_tick_count = 0;

// ═══════════════════════════════════════════════════════════════════════════
// Module base + ELF symbol lookup
// ═══════════════════════════════════════════════════════════════════════════
typedef struct { const char *name; uintptr_t base; } find_module_ctx_t;

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

static void *find_il2cpp_export(const char *name) {
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) return NULL;
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)base;
    Elf64_Phdr *phdr = (Elf64_Phdr *)(base + ehdr->e_phoff);
    Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) { dyn = (Elf64_Dyn *)(base + phdr[i].p_vaddr); break; }
    }
    if (dyn == NULL) return NULL;
    Elf64_Sym *symtab = NULL; const char *strtab = NULL; uint32_t *gnu_hash = NULL;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab = (Elf64_Sym *)(base + d->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char *)(base + d->d_un.d_ptr); break;
            case DT_GNU_HASH: gnu_hash = (uint32_t *)(base + d->d_un.d_ptr); break;
        }
    }
    if (symtab == NULL || strtab == NULL || gnu_hash == NULL) return NULL;
    uint32_t nbuckets = gnu_hash[0], symoffset = gnu_hash[1], bloom_size = gnu_hash[2];
    uint64_t *bloom = (uint64_t *)(gnu_hash + 4);
    uint32_t *buckets = (uint32_t *)(bloom + bloom_size);
    uint32_t *chain = buckets + nbuckets;
    uint32_t max_symidx = 0;
    for (uint32_t i = 0; i < nbuckets; i++) if (buckets[i] > max_symidx) max_symidx = buckets[i];
    if (max_symidx >= symoffset) {
        uint32_t ci = max_symidx - symoffset;
        while ((chain[ci] & 1) == 0) ci++;
        max_symidx = symoffset + ci;
    }
    for (uint32_t i = 0; i <= max_symidx; i++) {
        Elf64_Sym *sym = &symtab[i];
        if (sym->st_name == 0 || ELF64_ST_TYPE(sym->st_info) != STT_FUNC) continue;
        if (strcmp(strtab + sym->st_name, name) == 0) return (void *)(base + sym->st_value);
    }
    return NULL;
}

// ═══════════════════════════════════════════════════════════════════════════
// IL2CPP string helpers
// ═══════════════════════════════════════════════════════════════════════════
static bool il2cpp_string_equals_ascii(void *il2cpp_str, const char *ascii) {
    if (il2cpp_str == NULL || ascii == NULL) return false;
    int32_t length = *(int32_t *)((uint8_t *)il2cpp_str + IL2CPP_STRING_LENGTH_OFFSET);
    size_t ascii_len = strlen(ascii);
    if (length != (int32_t)ascii_len) return false;
    uint8_t *chars = (uint8_t *)il2cpp_str + IL2CPP_STRING_CHARS_OFFSET;
    for (int32_t i = 0; i < length; i++)
        if (chars[i * 2] != (uint8_t)ascii[i] || chars[i * 2 + 1] != 0) return false;
    return true;
}

// ═══════════════════════════════════════════════════════════════════════════
// Resolve Unity methods + IL2CPP runtime API
// ═══════════════════════════════════════════════════════════════════════════
static bool resolve_unity_methods(void) {
    if (g_go_set_active != NULL) return true;
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) return false;
    g_get_irds_ui_instance = (get_irds_ui_instance_t)(base + RVA_IRDS_UI_MOBILE_CONTROLS_GET_INSTANCE);
    g_go_get_transform = (go_get_transform_t)(base + RVA_GAME_OBJECT_GET_TRANSFORM);
    g_component_get_game_object = (component_get_game_object_t)(base + RVA_COMPONENT_GET_GAME_OBJECT);
    g_object_get_name = (object_get_name_t)(base + RVA_OBJECT_GET_NAME);
    g_transform_get_child_count = (transform_get_child_count_t)(base + RVA_TRANSFORM_GET_CHILD_COUNT);
    g_transform_get_child = (transform_get_child_t)(base + RVA_TRANSFORM_GET_CHILD);
    g_go_set_active = (go_set_active_t)(base + RVA_GAME_OBJECT_SET_ACTIVE);
    g_go_get_active_self = (go_get_active_self_t)(base + RVA_GAME_OBJECT_GET_ACTIVE_SELF);
    LOGI("hide_pedals: Unity methods resolved (base=0x%" PRIxPTR ")", base);
    return true;
}

static void resolve_il2cpp_runtime_api(void) {
    if (g_il2cpp_runtime_invoke != NULL) return;
    g_il2cpp_object_get_class = (il2cpp_object_get_class_t)find_il2cpp_export("il2cpp_object_get_class");
    g_il2cpp_class_get_method_from_name = (il2cpp_class_get_method_from_name_t)find_il2cpp_export("il2cpp_class_get_method_from_name");
    g_il2cpp_runtime_invoke = (il2cpp_runtime_invoke_t)find_il2cpp_export("il2cpp_runtime_invoke");
    g_il2cpp_class_get_name = (il2cpp_class_get_name_t)find_il2cpp_export("il2cpp_class_get_name");
    LOGI("hide_pedals: il2cpp runtime API: get_class=%p get_method=%p invoke=%p",
         g_il2cpp_object_get_class, g_il2cpp_class_get_method_from_name, g_il2cpp_runtime_invoke);
}

// ═══════════════════════════════════════════════════════════════════════════
// get_real_gameobject: 通过 il2cpp_runtime_invoke 调用 get_gameObject
//（直接调 RVA 返回的是 RectTransform 而非 GameObject）
// ═══════════════════════════════════════════════════════════════════════════
static void *get_real_gameobject(void *component) {
    if (component == NULL || g_il2cpp_runtime_invoke == NULL) return NULL;
    if (g_get_gameobject_methodinfo == NULL) {
        void *klass = g_il2cpp_object_get_class(component);
        if (klass == NULL) return NULL;
        g_get_gameobject_methodinfo = g_il2cpp_class_get_method_from_name(klass, "get_gameObject", 0);
        if (g_get_gameobject_methodinfo == NULL) return NULL;
    }
    void *exc = NULL;
    return g_il2cpp_runtime_invoke(g_get_gameobject_methodinfo, component, NULL, &exc);
}

// ═══════════════════════════════════════════════════════════════════════════
// hide_if_active: 检查 activeSelf，如果 active 则 SetActive(false)
// ═══════════════════════════════════════════════════════════════════════════
static void hide_if_active(void *go, const char *name) {
    if (go == NULL) return;
    // 检查 activeSelf — 已 inactive 的跳过
    if (g_go_get_active_self != NULL && !g_go_get_active_self(go, NULL)) return;
    if (g_il2cpp_runtime_invoke == NULL) return;

    // 缓存 SetActive MethodInfo
    if (g_set_active_methodinfo == NULL) {
        void *go_klass = g_il2cpp_object_get_class(go);
        if (go_klass == NULL) return;
        g_set_active_methodinfo = g_il2cpp_class_get_method_from_name(go_klass, "SetActive", 1);
        if (g_set_active_methodinfo == NULL) return;
        if (g_first_traverse) {
            const char *kn = g_il2cpp_class_get_name ? g_il2cpp_class_get_name(go_klass) : "?";
            LOGI("hide_pedals: SetActive MethodInfo=%p (class='%s')", g_set_active_methodinfo, kn);
        }
    }

    bool false_val = false;
    void *params[] = { &false_val };
    void *exc = NULL;
    g_il2cpp_runtime_invoke(g_set_active_methodinfo, go, params, &exc);
    g_hidden_count++;
    if (exc != NULL) LOGW("hide_pedals: SetActive('%s') exc=%p", name, exc);
    else LOGI("hide_pedals: '%s' hidden OK", name);
}

// ═══════════════════════════════════════════════════════════════════════════
// Recursive traversal: find "Throttle"/"Brake" and SetActive(false)
// ═══════════════════════════════════════════════════════════════════════════
static void hide_buttons_recursive(void *transform, int depth) {
    if (transform == NULL || depth > MAX_RECURSION_DEPTH) return;

    int child_count = g_transform_get_child_count(transform, NULL);
    for (int i = 0; i < child_count; i++) {
        void *child_transform = g_transform_get_child(transform, i, NULL);
        if (child_transform == NULL) continue;

        void *child_go = g_component_get_game_object(child_transform, NULL);
        bool hidden = false;
        if (child_go != NULL) {
            void *name_str = g_object_get_name(child_go, NULL);
            if (name_str != NULL) {
                if (il2cpp_string_equals_ascii(name_str, "Throttle") ||
                    il2cpp_string_equals_ascii(name_str, "Brake")) {
                    // 通过 il2cpp_runtime_invoke 获取真正的 GameObject
                    void *real_go = get_real_gameobject(child_transform);
                    if (real_go != NULL) {
                        hide_if_active(real_go,
                            il2cpp_string_equals_ascii(name_str, "Throttle") ? "Throttle" : "Brake");
                    }
                    // 不管 active 还是 inactive，都跳过递归子树（按钮子物体不需要遍历）
                    hidden = true;
                }
            }
        }

        if (!hidden) {
            hide_buttons_recursive(child_transform, depth + 1);
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Main hide logic
// ═══════════════════════════════════════════════════════════════════════════
static void hide_pedals_do_hide(void) {
    if (!resolve_unity_methods()) return;
    resolve_il2cpp_runtime_api();
    if (g_il2cpp_runtime_invoke == NULL) return;

    g_hidden_count = 0;

    void *imc = g_get_irds_ui_instance(NULL);
    if (imc == NULL) return;

    void *list = *(void **)((uint8_t *)imc + OFFSET_IMC_MOBILE_CONTROLS_LAYOUTS);
    if (list == NULL) return;

    void *items = *(void **)((uint8_t *)list + IL2CPP_LIST_ITEMS_OFFSET);
    int size = *(int *)((uint8_t *)list + IL2CPP_LIST_SIZE_OFFSET);
    if (items == NULL || size <= 0) return;

    for (int i = 0; i < size; i++) {
        void *mobile_control = *(void **)((uint8_t *)items + IL2CPP_ARRAY_ELEMENTS_OFFSET + (size_t)i * sizeof(void *));
        if (mobile_control == NULL) continue;

        void *layout_obj = *(void **)((uint8_t *)mobile_control + OFFSET_MC_LAYOUT_OBJECT);
        if (layout_obj == NULL) continue;

        void *transform = g_go_get_transform(layout_obj, NULL);
        if (transform == NULL) continue;

        hide_buttons_recursive(transform, 0);
    }

    g_first_traverse = false;
}

// ═══════════════════════════════════════════════════════════════════════════
// Public API
// ═══════════════════════════════════════════════════════════════════════════
void hide_pedals_init(bool enabled) {
    g_enabled = enabled;
    LOGI("hide_pedals_init: enabled=%d", enabled);
}

void hide_pedals_tick(void) {
    if (!g_enabled) return;
    if (g_tick_count++ % TICK_INTERVAL != 0) return;
    hide_pedals_do_hide();
}

void hide_pedals_set_enabled(bool enabled) {
    if (g_enabled != enabled) {
        g_first_traverse = true;
    }
    g_enabled = enabled;
    LOGI("hide_pedals_set_enabled: %d", enabled);
}