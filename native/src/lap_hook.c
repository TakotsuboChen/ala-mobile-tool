// ═══════════════════════════════════════════════════════════════════════════
// lap_hook.c — 计时赛有效圈速监听（log-only，无 UI、不干预游戏行为）
//
// 信号链（全部字段已由 8.0.4 dump.cs 实证，见 OffsetTable.kt）：
//   IRDSLevelLoadVariables::Awake → 捕获 LLV 单例（DontDestroyOnLoad 常驻），
//     trackToRace(0xB8, IL2CPP string) = 赛道名（游戏中 16 条 GP 赛道，
//     stringliteral: Australian/Austrian/Bahrain/Belgian/Brazilian/British/
//     Canadian/Chinese/Emirates/German/Hungarian/Italian/Japanese/Monaco/
//     Motorvalley/Spanish GP）。
//   odometerHandler::HandleSectorsTimes(sectorOrder, sectorTime,
//     sectorQuality, validLap, totalLapTime) — 游戏自己的圈段事件，
//     validLap 是游戏切弯/逆行判定的最终产物，模块直接采信不复算。
//
// 对照读链（全部从回调 this 出发，回调中对象必存活，无悬空指针风险）：
//   odometerHandler+0xF8 stGUI (IRDSManager)
//     → +0x68 stadistics (IRDSStatistics)
//       → absoluteFastestLap(0xC0) / fastestTimeAuthor(0xC4)
//   odometerHandler+0xE8 targetSpeed (IRDSCarControllInput，玩家车)
//     → +0xF0 navigateTWp (IRDSNavigateTWaypoints)
//       → bestLapTimeInfo(0x240, LapTimeInfo*)
//         → sectorOne/Two/Three (0x10/0x14/0x18)
//
// 红线自查：纯透传只读，不写任何游戏字段，AI 车零影响；指令级拦截未使用，
// 无 FPSIMD 污染问题（见 docs/MODULE_ABS_NOTES §2c）。
// ⚠️ 记录门禁红线：**只记计时赛（TimeAttack），其他会话一律挂起**——
//    非计时赛 session 中 LAPgate 首行提示 + 全部 LAP 各行不打。用户需求
//    定案（2026-08-31）：正赛 LM 被误记为 LAPinv 一例即证明必须 gate。
//
// 实机已裁决（2026-08-31，165 行 LAP 日志）：
//   - HandleSectorsTimes 是**圈段过线事件簇**调用（过段后 HUD 刷新期连发
//     数帧）：order 0/1/2 各自过段时触发；sectorOrder 0-based。
//   - order==2 事件本身即"圈完成"：totalLapTime 携带完整圈时（= S1+S2+S3），
//     order 0/1 的 totalLap 恒 0。圈完成判定在此刻做，不等 order 2→0 回绕
//     （回绕在下一圈 S1 过线才发生——第一版在此吃了 30s+ 延迟假象）。
//   - validLap 是持续状态位（切弯即降 0 并保持到本圈结束）。
//   - trackToRace = 'MobileScene'（场景名，非赛道名）——16 赛道识别需另接
//     信号源（候选：CommonUtilities.GetGPIndex(activeScene) / 场景
//     buildIndex，待反汇编）。
//   - IRDSStatistics.absoluteFastestLap 有值（author=4），但更新滞后于
//     圈完成（SubmitForFastestTime 提交时机待查）。
//   - IRDSNavigateTWaypoints.bestLapTimeInfo(0x240) 是"当前圈实时分段"，
//     999.0 = 段未开始哨兵，非历史最佳。
// ═══════════════════════════════════════════════════════════════════════════
#include "lap_hook.h"
#include "native_log.h"
#include <dlfcn.h>
#include <elf.h>
#include <inttypes.h>
#include <link.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)
#define LOGW(...) NLOGW(__VA_ARGS__)
#define LOGE(...) NLOGE(__VA_ARGS__)

#include "shadowhook.h"

// ── IL2CPP 内存布局 ──
#define IL2CPP_STRING_LENGTH_OFFSET 0x10
#define IL2CPP_STRING_CHARS_OFFSET  0x14

// ── 实例字段偏移（8.0.4 dump.cs 实证，类布局跨 8.0.x 不变）──
#define OFF_LLV_TRACK_TO_RACE         0xB8    // IRDSLevelLoadVariables.trackToRace (string)
#define OFF_ODOMETER_ST_GUI           0x100   // odometerHandler.stGUI (IRDSManager) — 8.0.6: centralMessagesContainer(0x30) 插入致后续全 +8
#define OFF_ODOMETER_TARGET_SPEED     0xF0    // odometerHandler.targetSpeed (IRDSCarControllInput) — 8.0.6 +8
#define OFF_ODOMETER_CHAMP_MANAGER    0x4E0   // odometerHandler.champManager (ChampionshipManager) — 8.0.6 +8
#define OFF_CHAMP_IS_TIME_ATTACK      0x20    // ChampionshipManager.isTimeAttack (bool)
#define OFF_ODOMETER_IS_QUALI         0x2A8   // odometerHandler.isQuali (bool) — 8.0.6 +8
#define OFF_ODOMETER_TA_TIMES         0x2E8   // odometerHandler.timeAttackTimes (List<float>) — 8.0.6 +8
#define OFF_LLV_RACE_MODES            0x158   // IRDSLevelLoadVariables.raceModes (enum int)
#define OFF_LIST_SIZE                 0x18    // IL2CPP List<T>.size (int32)
#define OFF_MANAGER_STADISTICS        0x68    // IRDSManager.stadistics (IRDSStatistics)
#define OFF_STATS_ABS_FASTEST_LAP     0xC0    // IRDSStatistics.absoluteFastestLap (float)
#define OFF_STATS_FASTEST_AUTHOR      0xC4    // IRDSStatistics.fastestTimeAuthor (int)
#define OFF_CAR_INPUTS_NAVIGATE_WP    0xF0    // IRDSCarControllInput.navigateTWp (IRDSNavigateTWaypoints)
#define OFF_NAV_BEST_LAP_TIME_INFO    0x240   // IRDSNavigateTWaypoints.bestLapTimeInfo (LapTimeInfo*)
#define OFF_LAP_INFO_SECTOR_ONE       0x10    // LapTimeInfo.sectorOne (float)
#define OFF_LAP_INFO_SECTOR_TWO       0x14    // LapTimeInfo.sectorTwo (float)
#define OFF_LAP_INFO_SECTOR_THREE     0x18    // LapTimeInfo.sectorThree (float)

typedef void *(*orig_llv_awake_t)(void *this, void *method_info);
typedef void *(*orig_handle_sectors_t)(void *this, int sector_order, float sector_time,
                                       int sector_quality, uint8_t valid_lap,
                                       float total_lap_time, void *method_info);

static lap_hook_config_t g_config = {0};

// LLV 单例实例（LLV.Awake hook 捕获，Unity 主线程写；HandleSectorsTimes
// 同在主线程读——同线程无竞态，volatile 仅防编译器跨调用缓存）。
static void *volatile g_llv = NULL;

// 会话（赛道）最佳有效圈。0 = 尚无有效圈（float 正值域内用 0 做哨兵，
// 真·+inf 在 32 位浮点下与 NaN 比较有坑）。LLV.Awake（场景重载/换赛道）
// 重置——与游戏"重新开始"语义对齐。
static volatile float g_best_valid_lap = 0.0f;

// ── 圈事件节流状态（实机 2026-08-31/09-01 两轮裁决：HandleSectorsTimes
// 是**圈段过线事件簇**调用（过段后 HUD 刷新期连发数帧），sectorOrder
// 0-based；order==2 事件本身即"圈完成"，totalLapTime 携带完整圈时，
// order 0/1 的 totalLap 恒 0；validLap 为持续状态位，切弯即降 0）。
// - 日志只在**圈段边界或有效位翻转**时打（每圈 ~4 行，防洪水）；
// - 圈完成判定 = order==2 事件首次出现（不等 2→0 回绕 —— 回绕发生在
//   下一圈 S1 过线，会延迟 30s+ 才消费，第二轮五圈的第 5 圈因此"消失"）。
static int g_last_order = -1;
static int g_last_valid = -1;
static char g_track_name[64] = "?";              // 最近一次读到的 trackToRace（ASCII 摘录）
static volatile int32_t g_current_gp_index = -1; // LAPscene 探测结果（0..15，-1=未知）

// ── 围场上传单槽缓冲 ──
// order==2 有效圈边界写入 (gp_index, lap_ms)；Java 层 1Hz 轮询 pollLapUpload()
// 取走。单槽 + seq 校验：写侧只进（seq 递增），读侧消费后置 consumed；
// 圈完成分钟级一遇，丢槽概率可忽略（轮询窗口 1s vs 事件间隔 30s+）。
// version_code 由 Java 层自填（native 不读游戏版本——VersionGate 已在 Java 判定）。
static volatile int32_t g_upload_seq = 0;        // 事件序号（0=无待传）
static volatile int32_t g_upload_gp = -1;
static volatile int32_t g_upload_lap_ms = 0;
static volatile int32_t g_upload_consumed_seq = 0;  // Java 已消费到的 seq

static void *g_llv_awake_stub = NULL;
static void *g_llv_awake_orig = NULL;
static void *g_handle_sectors_stub = NULL;
static void *g_handle_sectors_orig = NULL;

static volatile int g_hooks_installed = 0;

// ═══════════════════════════════════════════════════════════════════════════
// Module base + ELF symbol lookup（与 hide_pedals_hook.c 同款)
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

// 遍历 dynsym 找 il2cpp 导出符号（与 hide_pedals_hook.c 逐字同款，生产验证）。
static void *find_il2cpp_export(const char *name) {
    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) return NULL;
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *) base;
    Elf64_Phdr *phdr = (Elf64_Phdr *) (base + ehdr->e_phoff);
    Elf64_Dyn *dyn = NULL;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) { dyn = (Elf64_Dyn *) (base + phdr[i].p_vaddr); break; }
    }
    if (dyn == NULL) return NULL;
    Elf64_Sym *symtab = NULL; const char *strtab = NULL; uint32_t *gnu_hash = NULL;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab = (Elf64_Sym *) (base + d->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char *) (base + d->d_un.d_ptr); break;
            case DT_GNU_HASH: gnu_hash = (uint32_t *) (base + d->d_un.d_ptr); break;
        }
    }
    if (symtab == NULL || strtab == NULL || gnu_hash == NULL) return NULL;
    uint32_t nbuckets = gnu_hash[0], symoffset = gnu_hash[1], bloom_size = gnu_hash[2];
    uint64_t *bloom = (uint64_t *) (gnu_hash + 4);
    uint32_t *buckets = (uint32_t *) (bloom + bloom_size);
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
        if (strcmp(strtab + sym->st_name, name) == 0) return (void *) (base + sym->st_value);
    }
    return NULL;
}

// ═══════════════════════════════════════════════════════════════════════════
// IL2CPP runtime API（赛道身份探测用）——官方导出，runtime_invoke 全程
// 走 IL2CPP 机制，避开 Unity 方法直调坑（get_gameObject 直调返回 this）。
// ═══════════════════════════════════════════════════════════════════════════
typedef void *(*il2cpp_domain_get_t)(void);
typedef void **(*il2cpp_domain_get_assemblies_t)(void *domain, size_t *size);
typedef void *(*il2cpp_assembly_get_image_t)(void *assembly);
typedef void *(*il2cpp_class_from_name_t)(void *image, const char *ns, const char *name);
typedef void *(*il2cpp_class_get_method_from_name_t)(void *klass, const char *name, int argc);
typedef void *(*il2cpp_runtime_invoke_t)(void *method, void *obj, void **params, void **exc);

typedef void *(*il2cpp_class_get_fields_t)(void *klass, void **iter);
typedef const char *(*il2cpp_field_get_name_t)(void *field);
typedef void (*il2cpp_field_static_get_value_t)(void *field, void *value);

static il2cpp_domain_get_t               g_domain_get;
static il2cpp_domain_get_assemblies_t    g_domain_get_assemblies;
static il2cpp_assembly_get_image_t       g_assembly_get_image;
static il2cpp_class_from_name_t          g_class_from_name;
static il2cpp_class_get_method_from_name_t g_class_get_method;
static il2cpp_runtime_invoke_t           g_runtime_invoke;
static il2cpp_class_get_fields_t         g_class_get_fields;
static il2cpp_field_get_name_t           g_field_get_name;
static il2cpp_field_static_get_value_t   g_field_static_get_value;

// IRDSStatistics 静态模式三布尔（isRaceSession/isFreePracticeSession/timedSession）
// 的 FieldInfo 缓存——LAPmode 诊断行与未来终版 gate 的信号源。
static void *g_f_race = NULL;    // isRaceSession
static void *g_f_fp = NULL;      // isFreePracticeSession
static void *g_f_timed = NULL;   // timedSession
// GlobalVariables（游戏全局模式身份证，dump.cs:37457-37480）——模式入口 UI
// 选定后写入的静态位。isGrandFestival 是 GRAND FESTIVAL 快速模式专用位；
// championshipData 静态引用指向 ChampionshipData 实例（生涯/比赛周会话编号）。
static void *g_f_gf = NULL;      // GlobalVariables.isGrandFestival
static void *g_f_israce = NULL;  // GlobalVariables.isRace
static void *g_f_ismp = NULL;    // GlobalVariables.isMultiplayerMatch
static void *g_f_champ_data = NULL; // GlobalVariables.championshipData (ChampionshipData*)
static int g_mode_resolved = 0;  // FieldInfo 解析完成标记（含失败）

static void *g_m_scene_name = NULL;    // SceneManagerHelper.get_ActiveSceneName
static void *g_m_scene_idx = NULL;     // SceneManagerHelper.get_ActiveSceneBuildIndex
static void *g_m_gp_index = NULL;      // CommonUtilities.GetGPIndex(int)

// 前向声明（定义在文件下方 string helpers 区）
static void il2cpp_string_read_ascii(void *il2cpp_str, char *out, int out_len);

static int g_probe_state = 0;  // 0=未做 1=成功 2=失败（防每圈重试风暴；Awake 重置）

// 模式门禁状态：正赛/排位等非计时赛会话挂起记录。g_mode_skip_logged 只打
// 一次挂起提示（防洪水）；从非计时赛切回计时赛时复位并清最佳圈（模式间
// 数据互不污染）。champManager==NULL 时不判模式（保持记录）——宁可漏 gate，
// 不可把 champManager 引用链未就绪的计时赛全部堵死。
static int g_mode_skip_logged = 0;
static int g_mode_gated = 0;   // 当前事件是否处于挂起态（本事件点判得）
// LAPsession 诊断行双门控：
// - g_session_diag_logged = LLV.Awake 行（场景加载瞬间，无驾驶数据）；
// - g_sector_diag_logged  = 首个圈段事件行（带 odometer 链真实值）。
// 两行各自单发防洪水；玩家只进赛道不驾驶 = 只有 Awake 行（采样最省时）。
static int g_session_diag_logged = 0;
static int g_sector_diag_logged = 0;

static void resolve_il2cpp_api(void) {
    if (g_runtime_invoke != NULL) return;
    g_domain_get = (il2cpp_domain_get_t) find_il2cpp_export("il2cpp_domain_get");
    g_domain_get_assemblies = (il2cpp_domain_get_assemblies_t) find_il2cpp_export("il2cpp_domain_get_assemblies");
    g_assembly_get_image = (il2cpp_assembly_get_image_t) find_il2cpp_export("il2cpp_assembly_get_image");
    g_class_from_name = (il2cpp_class_from_name_t) find_il2cpp_export("il2cpp_class_from_name");
    g_class_get_method = (il2cpp_class_get_method_from_name_t) find_il2cpp_export("il2cpp_class_get_method_from_name");
    g_runtime_invoke = (il2cpp_runtime_invoke_t) find_il2cpp_export("il2cpp_runtime_invoke");
    g_class_get_fields = (il2cpp_class_get_fields_t) find_il2cpp_export("il2cpp_class_get_fields");
    g_field_get_name = (il2cpp_field_get_name_t) find_il2cpp_export("il2cpp_field_get_name");
    g_field_static_get_value = (il2cpp_field_static_get_value_t) find_il2cpp_export("il2cpp_field_static_get_value");
    LOGI("lap_hook: il2cpp api domain=%p asms=%p img=%p cls=%p getm=%p invoke=%p",
         (void *) g_domain_get, (void *) g_domain_get_assemblies, (void *) g_assembly_get_image,
         (void *) g_class_from_name, (void *) g_class_get_method, (void *) g_runtime_invoke);
}

// ── 解析 IRDSStatistics 的三个静态模式布尔 FieldInfo（一次性；找齐/失败
//    都置 g_mode_resolved 防每圈重扫）。静态布尔经 il2cpp_field_static_
//    get_value 读取——IRDSStatistics 在比赛场景必然已初始化（[?] 若
//    static_fields 未就绪，读取值可能为 0，诊断行可观测）。
static void resolve_mode_signals(void) {
    if (g_mode_resolved) return;
    if (g_class_from_name == NULL || g_class_get_fields == NULL ||
        g_field_get_name == NULL || g_field_static_get_value == NULL) {
        g_mode_resolved = 1;   // 导出缺失 → sessBits 恒 -1，不重试
        return;
    }
    size_t n = 0;
    void **asms = g_domain_get_assemblies(g_domain_get(), &n);
    if (asms == NULL) { g_mode_resolved = 1; return; }
    for (size_t i = 0; i < n && (g_f_race == NULL || g_f_gf == NULL); i++) {
        void *image = g_assembly_get_image(asms[i]);
        if (image == NULL) continue;
        if (g_f_race == NULL) {
            void *klass = g_class_from_name(image, "IRDS.Game", "IRDSStatistics");
            if (klass != NULL) {
                void *iter = NULL;
                void *field;
                while ((field = g_class_get_fields(klass, &iter)) != NULL) {
                    const char *fn = g_field_get_name(field);
                    if (fn == NULL) continue;
                    if (strcmp(fn, "isRaceSession") == 0) g_f_race = field;
                    else if (strcmp(fn, "isFreePracticeSession") == 0) g_f_fp = field;
                    else if (strcmp(fn, "timedSession") == 0) g_f_timed = field;
                    if (g_f_race != NULL && g_f_fp != NULL && g_f_timed != NULL) break;
                }
                LOGI("lap_hook: IRDSStatistics fields resolved: race=%p fp=%p timed=%p",
                     g_f_race, g_f_fp, g_f_timed);
            }
        }
        if (g_f_gf == NULL) {
            void *klass = g_class_from_name(image, "", "GlobalVariables");
            if (klass != NULL) {
                void *iter = NULL;
                void *field;
                while ((field = g_class_get_fields(klass, &iter)) != NULL) {
                    const char *fn = g_field_get_name(field);
                    if (fn == NULL) continue;
                    if (strcmp(fn, "isGrandFestival") == 0) g_f_gf = field;
                    else if (strcmp(fn, "isRace") == 0) g_f_israce = field;
                    else if (strcmp(fn, "isMultiplayerMatch") == 0) g_f_ismp = field;
                    else if (strcmp(fn, "championshipData") == 0) g_f_champ_data = field;
                    if (g_f_gf != NULL && g_f_israce != NULL &&
                        g_f_ismp != NULL && g_f_champ_data != NULL) break;
                }
                LOGI("lap_hook: GlobalVariables fields resolved: gf=%p israce=%p ismp=%p champData=%p",
                     g_f_gf, g_f_israce, g_f_ismp, g_f_champ_data);
            }
        }
    }
    g_mode_resolved = 1;
}

// ChampionshipData 会话标识字段的实例偏移（dump.cs:36254-36292 实证）。
// championshipData 静态引用在生涯/比赛周会话非 NULL；快速模式/GF 预期为
// NULL（未实测，诊断行可观测）。
#define OFF_CD_CURRENT_SESSION 0x4C   // ChampionshipData.currentSession (int)
#define OFF_CD_ROUND_NUMBER    0x38   // ChampionshipData.roundNumber (int)
#define OFF_CD_CURRENT_TRACK   0x48   // ChampionshipData.currentTrack (int)
#define OFF_CD_FULL_QUALI      0x2B   // ChampionshipData.fullQuali (bool)

// ── LAPsession 诊断行：把全部候选模式信号一次打齐，供各模式采样表落定
//    终版 gate 组合（champManager 链在快速模式正赛实测为 NULL，单信号
//    不可靠——见 2026-08-31 Shanghai 正赛误记事件）。
//
// ⚠️ 触发时机 = LLV.Awake（场景加载瞬间，无需驾驶）。odometer 实例链
//    （champ/isQuali/taCount）在 Awake 时尚不可得，打 -1 占位——这些字段
//    已有 [V] 实测结论，采样只需静态信号集（进赛道即出，玩家可立即退出）。
static void lapmode_diag(void *odometer, void *champ, int is_ta, const char *phase) {
    int rm = -1, iq = -1, ta_n = -1, sess = -1;
    int gv = -1, cd_session = -1, cd_round = -1, cd_track = -1, cd_fullquali = -1;
    if (g_llv != NULL) {
        rm = (int) *(volatile int32_t *) ((uintptr_t) g_llv + OFF_LLV_RACE_MODES);
    }
    if (odometer != NULL) {
        iq = *(uint8_t *) ((uintptr_t) odometer + OFF_ODOMETER_IS_QUALI) ? 1 : 0;
        void *list = *(void *volatile *) ((uintptr_t) odometer + OFF_ODOMETER_TA_TIMES);
        if (list != NULL) {
            ta_n = (int) *(volatile int32_t *) ((uintptr_t) list + OFF_LIST_SIZE);
        }
    }
    if (g_f_race != NULL && g_f_fp != NULL && g_f_timed != NULL) {
        uint8_t r = 0, f = 0, t = 0;
        g_field_static_get_value(g_f_race, &r);
        g_field_static_get_value(g_f_fp, &f);
        g_field_static_get_value(g_f_timed, &t);
        sess = (r ? 1 : 0) | (f ? 2 : 0) | (t ? 4 : 0);
        // bit0=isRaceSession bit1=isFreePracticeSession bit2=timedSession
    }
    // GlobalVariables 位（gf/isRace/isMP）+ championshipData 实例字段。
    // isMultiplayerMatch 兼带 1 位（0/1），三位置一个字节避免行过长。
    if (g_f_gf != NULL && g_f_israce != NULL && g_f_ismp != NULL && g_f_champ_data != NULL) {
        uint8_t gf = 0, israce = 0, ismp = 0;
        void *champ_data = NULL;
        g_field_static_get_value(g_f_gf, &gf);
        g_field_static_get_value(g_f_israce, &israce);
        g_field_static_get_value(g_f_ismp, &ismp);
        g_field_static_get_value(g_f_champ_data, &champ_data);
        gv = (gf ? 4 : 0) | (israce ? 2 : 0) | (ismp ? 1 : 0);
        // bit0=isMultiplayerMatch bit1=isRace bit2=isGrandFestival
        if (champ_data != NULL) {
            cd_session = (int) *(volatile int32_t *) ((uintptr_t) champ_data + OFF_CD_CURRENT_SESSION);
            cd_round = (int) *(volatile int32_t *) ((uintptr_t) champ_data + OFF_CD_ROUND_NUMBER);
            cd_track = (int) *(volatile int32_t *) ((uintptr_t) champ_data + OFF_CD_CURRENT_TRACK);
            cd_fullquali = *(uint8_t *) ((uintptr_t) champ_data + OFF_CD_FULL_QUALI) ? 1 : 0;
        }
    }
    LOGI("LAPsession[%s]: ta=%d champ=%p isQuali=%d taCount=%d sessBits=%d "
         "gvBits=%d cdSess=%d cdRound=%d cdTrack=%d fullQuali=%d raceModes=%d",
         phase, is_ta, champ, iq, ta_n, sess, gv, cd_session, cd_round,
         cd_track, cd_fullquali, rm);
}

// ── MethodInfo 解析：遍历全部程序集找两个类的目标方法（lazy 一次性）。
//    il2cpp_class_from_name 找不到返回 NULL（不抛异常），遍历安全。
static void resolve_track_methods(void) {
    if (g_runtime_invoke == NULL) return;
    if (g_m_scene_name != NULL && g_m_gp_index != NULL) return;

    size_t n = 0;
    void **asms = g_domain_get_assemblies(g_domain_get(), &n);
    if (asms == NULL) return;
    for (size_t i = 0; i < n; i++) {
        void *image = g_assembly_get_image(asms[i]);
        if (image == NULL) continue;

        if (g_m_scene_idx == NULL) {
            void *klass = g_class_from_name(image, "Photon.Pun", "SceneManagerHelper");
            if (klass != NULL) {
                g_m_scene_name = g_class_get_method(klass, "get_ActiveSceneName", 0);
                g_m_scene_idx = g_class_get_method(klass, "get_ActiveSceneBuildIndex", 0);
                LOGI("lap_hook: SceneManagerHelper resolved in image #%zu (name=%p idx=%p)",
                     i, g_m_scene_name, g_m_scene_idx);
            }
        }
        if (g_m_gp_index == NULL) {
            void *klass = g_class_from_name(image, "", "CommonUtilities");
            if (klass != NULL) {
                g_m_gp_index = g_class_get_method(klass, "GetGPIndex", 1);
                LOGI("lap_hook: CommonUtilities.GetGPIndex resolved in image #%zu (%p)", i, g_m_gp_index);
            }
        }
        if (g_m_scene_idx != NULL && g_m_gp_index != NULL) break;
    }
}

// 取静态无参 int 方法的 unbox 结果（Il2CppObject: klass 8 + monitor 8 + data @0x10）。
static int invoke_static_int(void *method, void **exc_out) {
    void *exc = NULL;
    void *boxed = g_runtime_invoke(method, NULL, NULL, &exc);
    if (exc_out != NULL) *exc_out = exc;
    if (boxed == NULL || exc != NULL) return -1;
    return *(int32_t *) ((uintptr_t) boxed + 0x10);
}

// ── 赛道身份探测（一次性/场景变更后重跑）：
//    scene 名 + buildIndex → GetGPIndex(buildIndex)(golf: buildIndex - GP 场景基址)
//    打一行 LAPscene。buildIndex→GP 名的映射表待两三条赛道实测后落定。
static void probe_track_identity(void) {
    if (g_probe_state != 0) return;
    if (g_runtime_invoke == NULL) resolve_il2cpp_api();
    if (g_runtime_invoke == NULL) { g_probe_state = 2; return; }
    resolve_track_methods();
    if (g_m_scene_idx == NULL || g_m_gp_index == NULL) {
        LOGI("LAPscene: probe unavailable (methods not resolved, scene=%p gp=%p)",
             g_m_scene_idx, g_m_gp_index);
        g_probe_state = 2;
        return;
    }

    int build_index = invoke_static_int(g_m_scene_idx, NULL);
    if (build_index < 0) {
        LOGI("LAPscene: probe failed (ActiveSceneBuildIndex < 0 / exc)");
        g_probe_state = 2;
        return;
    }
    char scene[64] = "?";
    if (g_m_scene_name != NULL) {
        void *exc = NULL;
        void *str = g_runtime_invoke(g_m_scene_name, NULL, NULL, &exc);
        if (str != NULL && exc == NULL) il2cpp_string_read_ascii(str, scene, sizeof(scene));
    }
    void *exc2 = NULL;
    int32_t bi = build_index;
    void *params[] = { &bi };
    void *boxed = g_runtime_invoke(g_m_gp_index, NULL, params, &exc2);
    int gp_idx = (boxed != NULL && exc2 == NULL)
                     ? *(int32_t *) ((uintptr_t) boxed + 0x10) : -1;

    LOGI("LAPscene: scene='%s' buildIndex=%d gpIndex=%d", scene, build_index, gp_idx);
    // 记录当前赛道身份供上传链取用（gpIndex 0..15 有效；-1 = 探测失败不上传）
    g_current_gp_index = (gp_idx >= 0 && gp_idx <= 15) ? (int32_t) gp_idx : -1;
    // probe 成功后 g_track_name 升级为 Unity 场景名（权威赛道身份，
    // 见 docs/TRACK_IDENTIFICATION.md）——LAPini/LAPbest/LAPdone 等行从此带真名。
    if (scene[0] != '\0' && strcmp(scene, "?") != 0) {
        snprintf(g_track_name, sizeof(g_track_name), "%s", scene);
    }
    g_probe_state = 1;
}

// ═══════════════════════════════════════════════════════════════════════════
// IL2CPP string 读取：UTF-16 → ASCII 摘录（非 ASCII 字符以 '?' 占位）。
// 只在 Unity 主线程 hook 回调内调用，str 指针由当前调用链保证存活。
// ═══════════════════════════════════════════════════════════════════════════
static void il2cpp_string_read_ascii(void *il2cpp_str, char *out, int out_len) {
    if (out == NULL || out_len <= 0) return;
    out[0] = '\0';
    if (il2cpp_str == NULL) return;   // NULL → 空串（trackToRace 未赋值时可能为 null）
    int32_t length = *(int32_t *) ((uintptr_t) il2cpp_str + IL2CPP_STRING_LENGTH_OFFSET);
    if (length < 0 || length > 1024) return;  // 畸形长度（悬空指针的典型症状）→ 放弃
    uint16_t *chars = (uint16_t *) ((uintptr_t) il2cpp_str + IL2CPP_STRING_CHARS_OFFSET);
    int n = (length < out_len - 1) ? length : out_len - 1;
    for (int i = 0; i < n; i++) {
        out[i] = (chars[i] >= 0x20 && chars[i] < 0x7F) ? (char) chars[i] : '?';
    }
    out[n] = '\0';
}

static inline float read_float(void *base, uintptr_t offset) {
    if (base == NULL || offset == 0) return 0.0f;
    return *(volatile float *) ((uintptr_t) base + offset);
}

static inline void *read_ptr(void *base, uintptr_t offset) {
    if (base == NULL || offset == 0) return NULL;
    return *(void *volatile *) ((uintptr_t) base + offset);
}

// 秒 → "m:ss.mmm"（与游戏 FormatLapTime 视觉对齐；自己格式化，不调 il2cpp）。
static const char *fmt_lap_time(float seconds, char *buf, int buf_len) {
    if (buf == NULL || buf_len <= 0) return "";
    if (!(seconds > 0.0f) || seconds > 3600.0f) {
        // 负数/NaN/超一小时都视为无效（待验证阶段 totalLapTime=0 常见）
        snprintf(buf, buf_len, "--:---");
        return buf;
    }
    int total_ms = (int) (seconds * 1000.0f + 0.5f);
    int m = total_ms / 60000;
    int s = (total_ms % 60000) / 1000;
    int ms = total_ms % 1000;
    snprintf(buf, buf_len, "%d:%02d.%03d", m, s, ms);
    return buf;
}

// ═══════════════════════════════════════════════════════════════════════════
// Hook 回调
// ═══════════════════════════════════════════════════════════════════════════

// IRDSLevelLoadVariables::Awake() — 捕获单例 + 新会话重置。
// LLV 是 DontDestroyOnLoad 单例：正常一次游戏会话只触发一两次（启动/被销毁
// 重建）。日志一条不会洪水。
static void proxy_llv_awake(void *this, void *method_info) {
    g_llv = this;
    // 新会话/场景重建：最佳圈 + 节流 + 模式门禁状态全部归零，赛道探测重跑。
    // g_track_name 不在此填值（trackToRace='MobileScene' 是废签）——真名由
    // probe_track_identity（LAPscene）成功后写入。
    g_best_valid_lap = 0.0f;  // 0 = "尚无有效圈"（float 正值域哨兵，见声明处）
    g_last_order = -1;
    g_last_valid = -1;
    g_probe_state = 0;
    g_mode_skip_logged = 0;
    g_mode_gated = 0;
    g_session_diag_logged = 0;
    g_sector_diag_logged = 0;
    char track[64];
    il2cpp_string_read_ascii(*(void **) ((uintptr_t) this + OFF_LLV_TRACK_TO_RACE),
                             track, sizeof(track));
    LOGI("LAPtrack: LLV captured, trackToRace='%s' (this=%p)", track, this);

    // LAPsession 单发行：场景加载瞬间打（模式静态信号此时已定死——
    // GlobalVariables / championshipData / IRDSStatistics 三布尔）。玩家
    // 进赛道即可退出下一个，无需驾驶；odometer 链字段打 -1（不可得）。
    {
        resolve_il2cpp_api();
        resolve_mode_signals();
        lapmode_diag(NULL, NULL, -1, "awake");
        g_session_diag_logged = 1;
    }

    typedef void (*orig_t)(void *, void *);
    if (g_llv_awake_orig != NULL) {
        ((orig_t) g_llv_awake_orig)(this, method_info);
    }
}

// odometerHandler::HandleSectorsTimes(int sectorOrder, float sectorTime,
//   int sectorQuality, bool validLap, float totalLapTime) — 圈段过线事件簇。
// 实机语义：sectorOrder 0-based；sectorTime=完成段耗时；totalLapTime 仅
// order==2（圈完成事件）携带整圈累计；validLap 为持续状态位。
// 日志策略（防洪水）：
//   LAPlap   = 圈段边界（order 翻转）或有效位（validLap）变化时打
//   LAPbest  = 圈完成且有效且更快 —— 会话最快有效圈
//   LAPdone  = 圈完成且有效但未破纪录
//   LAPinv   = 圈完成但无效 —— 判定材料
// 读链全部从 this / g_llv（单例）出发，回调中对象必存活。
static void proxy_odometer_handle_sectors_times(void *this, int sector_order,
                                                float sector_time, int sector_quality,
                                                uint8_t valid_lap, float total_lap_time,
                                                void *method_info) {
    // 每帧路径纯透传——先让游戏逻辑完成再落日志（日志在边界分支内，非每帧）。
    typedef void (*orig_t)(void *, int, float, int, uint8_t, float, void *);
    if (g_handle_sectors_orig != NULL) {
        ((orig_t) g_handle_sectors_orig)(this, sector_order, sector_time,
                                         sector_quality, valid_lap, total_lap_time,
                                         method_info);
    } else {
        return;  // orig 未装上时不做任何读链（防御：半安装状态）
    }

    if (this == NULL) return;

    // ══ 模式门禁：只记计时赛（TimeAttack）══
    // 信号链 v2（v1 的"champ==NULL 放行"兜底已证伪：快速模式正赛的
    // odometerHandler.champManager 为 NULL，正赛圈被误记——2026-08-31
    // Shanghai 事件）。v2 语义：
    //   champ 非 NULL → 按 isTimeAttack 硬判（0=挂起 1=记录）
    //   champ == NULL → **模式未知 = 挂起**（宁缺勿滥，正赛绝不能再进日志）
    // LAPsession 诊断行 = 会话首个圈段事件单发（含计时赛路径，模式间对照
    // 用同一格式）——模式信号进赛道时已定死，无需等圈完成采样。
    {
        void *champ = *(void *volatile *) ((uintptr_t) this + OFF_ODOMETER_CHAMP_MANAGER);
        int is_ta = (champ != NULL)
                ? (*(uint8_t *) ((uintptr_t) champ + OFF_CHAMP_IS_TIME_ATTACK) ? 1 : 0)
                : -1;

        if (!g_sector_diag_logged) {
            // 首个圈段事件：补打带 odometer 链真实值的模式信号行
            //（Awake 时 champ/isQuali 不可得，此处为对照补全，单发防洪水）
            resolve_il2cpp_api();
            resolve_mode_signals();
            lapmode_diag(this, champ, is_ta, "sector");
            g_sector_diag_logged = 1;
        }

        if (is_ta <= 0) {
            g_mode_gated = 1;
            g_last_order = -1;
            g_last_valid = -1;
            if (!g_mode_skip_logged) {
                LOGI("LAPgate: non-TimeAttack session, lap logging suspended (is_ta=%d)", is_ta);
                g_mode_skip_logged = 1;
            }
            return;
        }
        if (g_mode_gated) {
            // 从非计时赛切回计时赛（同一游戏进程内连续跑）：清旧挂起
            // 状态与最佳圈，保证记录从计时赛会话干净起步。
            g_mode_gated = 0;
            g_mode_skip_logged = 0;
            g_best_valid_lap = 0.0f;
            g_probe_state = 0;
            LOGI("LAPgate: TimeAttack session detected, logging resumed");
        }
    }

    // ── 圈段边界 / 有效位变化检测（节流核心）──
    int order_changed = (sector_order != g_last_order);
    int valid_changed = ((int) valid_lap != g_last_valid);
    g_last_order = sector_order;
    g_last_valid = (int) valid_lap;

    // ══ 圈完成事件：order==2 首次出现且携带完整圈时 = 过圈瞬间 ══
    // （收到即判定，不等 2→0 回绕——回绕在下一圈 S1 过线才发生）
    if (order_changed && sector_order == 2 && total_lap_time > 0.0f) {
        if (valid_lap) {
            float cur_best = g_best_valid_lap;
            if (cur_best <= 0.0f) cur_best = 1.0e9f;  // 0 = 无有效圈
            char now_buf[16];
            fmt_lap_time(total_lap_time, now_buf, sizeof(now_buf));
            if (total_lap_time < cur_best) {
                g_best_valid_lap = total_lap_time;
                if (cur_best >= 1.0e9f) {
                    LOGI("LAPbest: NEW BEST VALID LAP %s (track='%s', first valid lap)",
                         now_buf, g_track_name);
                } else {
                    char prev_buf[16];
                    fmt_lap_time(cur_best, prev_buf, sizeof(prev_buf));
                    LOGI("LAPbest: NEW BEST VALID LAP %s (prev %s, track='%s')",
                         now_buf, prev_buf, g_track_name);
                }
            } else {
                char prev_buf[16];
                fmt_lap_time(cur_best, prev_buf, sizeof(prev_buf));
                LOGI("LAPdone: valid lap %s (session best %s, track='%s')",
                     now_buf, prev_buf, g_track_name);
            }
            // ── 围场上传通道：有效圈（无论是否破会话纪录）都抛给 Java 层 ──
            // 定案「每有效圈都上传」。gp_index 未知（探测失败）时不上传。
            // 定案：无物理阈值拒收（全放行）——validLap 位是唯一有效性门槛。
            if (g_current_gp_index >= 0) {
                int32_t ms = (int32_t) (total_lap_time * 1000.0f + 0.5f);
                g_upload_gp = g_current_gp_index;
                g_upload_lap_ms = ms;
                g_upload_seq++;   // volatile 递增单写者（主线程）——发布序：先写数据后写 seq
            } else {
                LOGI("LAPup: skip upload (gp_index unknown)");
            }
        } else {
            char inv_buf[16];
            fmt_lap_time(total_lap_time, inv_buf, sizeof(inv_buf));
            LOGI("LAPinv: lap completed but INVALID time=%s (track='%s')",
                 inv_buf, g_track_name);
        }
        return;  // 圈完成行已含全部对照数据，跳过冗余 LAPlap 行
    }

    // ══ 边界/翻转行（每圈至多 3 行 + 有效位翻转行）══
    // 首次边界事件顺带做一次赛道身份探测（主线程，runtime_invoke 安全）
    probe_track_identity();
    if (order_changed || valid_changed) {
        // 对照读链：stGUI(0xF8) → stadistics(0x68) → absoluteFastestLap / author
        void *manager = *(void *volatile *) ((uintptr_t) this + OFF_ODOMETER_ST_GUI);
        float abs_fastest = 0.0f;
        int author = -1;
        if (manager != NULL) {
            void *stats = *(void *volatile *) ((uintptr_t) manager + OFF_MANAGER_STADISTICS);
            if (stats != NULL) {
                abs_fastest = read_float(stats, OFF_STATS_ABS_FASTEST_LAP);
                author = (int) *(volatile int32_t *) ((uintptr_t) stats + OFF_STATS_FASTEST_AUTHOR);
            }
        }

        // 玩家车当前圈实时分段（实机裁决：bestLapTimeInfo 是"当前圈分段"，
        // 999.0 = 段未开始的哨兵值，勿当历史最佳解读）
        float cur_s1 = 0.0f, cur_s2 = 0.0f, cur_s3 = 0.0f;
        void *target_speed = *(void *volatile *) ((uintptr_t) this + OFF_ODOMETER_TARGET_SPEED);
        if (target_speed != NULL) {
            void *nav = *(void *volatile *) ((uintptr_t) target_speed + OFF_CAR_INPUTS_NAVIGATE_WP);
            if (nav != NULL) {
                void *lap_info = *(void *volatile *) ((uintptr_t) nav + OFF_NAV_BEST_LAP_TIME_INFO);
                if (lap_info != NULL) {
                    cur_s1 = read_float(lap_info, OFF_LAP_INFO_SECTOR_ONE);
                    cur_s2 = read_float(lap_info, OFF_LAP_INFO_SECTOR_TWO);
                    cur_s3 = read_float(lap_info, OFF_LAP_INFO_SECTOR_THREE);
                }
            }
        }

        char lap_buf[16], abs_buf[16];
        fmt_lap_time(total_lap_time, lap_buf, sizeof(lap_buf));
        fmt_lap_time(abs_fastest, abs_buf, sizeof(abs_buf));
        LOGI("LAPlap: order=%d time=%.3f qual=%d valid=%d totalLap=%s "
             "absFastest=%s(author=%d) pCur=%.1f/%.1f/%.1f track='%s'",
             sector_order, sector_time, sector_quality, (int) valid_lap,
             lap_buf, abs_buf, author, cur_s1, cur_s2, cur_s3, g_track_name);
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 安装
// ═══════════════════════════════════════════════════════════════════════════
bool lap_install_hooks(const lap_hook_config_t *config) {
    if (config == NULL) return false;
    if (g_hooks_installed) return true;

    g_config = *config;
    g_best_valid_lap = 0.0f;

    uintptr_t base = get_module_base("libil2cpp.so");
    if (base == 0) {
        LOGE("lap_hook: libil2cpp.so not loaded yet");
        return false;
    }
    LOGI("lap_hook: libil2cpp.so base=0x%" PRIxPTR, base);

    if (g_config.llv_awake_offset != 0) {
        uintptr_t target = base + g_config.llv_awake_offset;
        g_llv_awake_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_llv_awake,
                (void **) &g_llv_awake_orig);
        if (g_llv_awake_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(LLV.Awake) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("lap_hook: hooked IRDSLevelLoadVariables.Awake at 0x%" PRIxPTR, target);
        }
    }

    if (g_config.odometer_handle_sectors_times_offset != 0) {
        uintptr_t target = base + g_config.odometer_handle_sectors_times_offset;
        g_handle_sectors_stub = shadowhook_hook_sym_addr(
                (void *) target,
                (void *) proxy_odometer_handle_sectors_times,
                (void **) &g_handle_sectors_orig);
        if (g_handle_sectors_stub == NULL) {
            int err = shadowhook_get_errno();
            LOGE("shadowhook_hook_sym_addr(HandleSectorsTimes) failed: %d (%s)",
                 err, shadowhook_to_errmsg(err));
        } else {
            LOGI("lap_hook: hooked odometerHandler.HandleSectorsTimes at 0x%" PRIxPTR, target);
        }
    }

    bool ok = (g_llv_awake_stub != NULL) && (g_handle_sectors_stub != NULL);
    if (ok) {
        g_hooks_installed = 1;
        LOGI("lap_hook: all hooks installed (log-only, no gameplay writes)");
    }
    return ok;
}
// ═══════════════════════════════════════════════════════════════════════════
// 围场上传通道（Java 轮询出口）
// ═══════════════════════════════════════════════════════════════════════════
// 有未消费的有效圈事件时返回 true 并填充出参（gpIndex 0..15 / lapMs 毫秒），
// Java 侧随后调 lapUploadMarkConsumed() 确认消费。单槽语义：同一时刻最多
// 一个待传圈；Java 1Hz 轮询 + 事件分钟级间隔 ⇒ 实践中不会覆盖。
bool lap_poll_upload(int32_t *out_lap_seq, int32_t *out_gp_index, int32_t *out_lap_ms) {
    if (out_lap_seq == NULL || out_gp_index == NULL || out_lap_ms == NULL) return false;
    int32_t seq = g_upload_seq;
    if (seq == 0 || seq == g_upload_consumed_seq) return false;  // 无新事件
    *out_lap_seq = seq;
    *out_gp_index = g_upload_gp;
    *out_lap_ms = g_upload_lap_ms;
    return true;
}

void lap_mark_upload_consumed(int32_t lap_seq) {
    if (lap_seq > g_upload_consumed_seq) g_upload_consumed_seq = lap_seq;
}
