#include "kerb_haptic.h"
#include "native_log.h"

#include <math.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#define LOG_TAG "AlaMobileTool"
#define LOGI(...) NLOGI(__VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// 字段偏移。**升版核对清单 = 本文件全部 OFF_* 常量**（见 CLAUDE.md
// 「游戏升版时，字段偏移核对清单 = native 里全部 #define OFF_* 常量」条）。
// 对拍方法：在新旧两版 dump.cs 里找 `字段; // 0xXX` 注释逐一对拍。
// ═══════════════════════════════════════════════════════════════════════════
#define OFF_CAR_WHEELS           0x28   // IRDSCarControllInput.wheels (IRDSWheel[])
#define OFF_CAR_BODY_VEL_X       0x120  // IRDSCarControllInput.bodyVelocity.x（侧向，m/s）
#define OFF_CAR_BODY_VEL_Z       0x128  // IRDSCarControllInput.bodyVelocity.z（前进，m/s）
#define OFF_CAR_ALL_OFF_GROUND   0x168  // IRDSCarControllInput.allTiresOffGround (bool)
#define OFF_IL2CPP_ARRAY_DATA    0x20   // IL2CPP 数组元素起始偏移
#define OFF_IL2CPP_ARRAY_LEN     0x18   // IL2CPP 数组长度

#define OFF_WHEEL_MATERIAL_INDEX 0x2E8  // IRDSWheel.materialIndex（**-5 = 路肩**）
#define OFF_WHEEL_NORMAL_FORCE   0x138  // IRDSWheel.normalForce（离地时被显式清零）


// ═══════════════════════════════════════════════════════════════════════════
// 常量
// ═══════════════════════════════════════════════════════════════════════════

// 路肩的 materialIndex 取值。**这不是我们发明的判据**——反汇编
// IRDSCarVisuals.TireModelVisuals(0x1A68B4C) 实证游戏自己就是这么判的：
//   0x1a6a5a8: ldr  w8, [x8, #0x2e8]   ; wheel.materialIndex
//   0x1a6a5ac: cmn  w8, #5             ; == -5 ?
//   0x1a6a5b0: b.ne ...
//   0x1a6a5bc: strb w29, [x8, #0xd7]   ; carController.kerbSound = true
// 而 IRDSSoundController.kerbSoundUpdate(0x1A7881C) 正是读 kerbSound 决定播不播
// 路肩音 ⇒ materialIndex==-5 ⟺ 游戏播路肩音。见 kerb_haptic.h 的完整推导。
#define KERB_MATERIAL_INDEX      (-5)

#define WHEEL_COUNT              4

// ── 颗粒率 = **用户给定的车速映射**（2026-10-04 用户规格，不要再自造曲线）──
//
//     **0.8 m/s（2.88 km/h）→ 1 Hz，之后线性，无上限**
//
// 即 `freq = v(m/s) × 1.25`（等价于 `kmh / 2.88`）。
// 例：10 m/s = 36 km/h → 12.5 Hz；27.8 m/s = 100 km/h → 34.7 Hz；
//     55.6 m/s = 200 km/h → 69.4 Hz。
//
// ⚠️ **不做上限 clamp**（用户 2026-10-04 明确要求"无上限"）。安全性由 Java
// 侧的段长下限兜底（`kerbThrottleAllows` 的 `coerceAtLeast(SEGMENT_MS)`，
// 即最高 200 Hz）——远高于任何现实车速，不会触发。
//
// ⚠️ **注意 60 Hz 的感知分界**：触觉的「离散敲击（flutter）」与「连续嗡
// （hum）」在 60 Hz 分界（Ng et al. 2021）。按本斜率，**172.8 km/h 以上**
// 会越过它，听感由"哒哒哒"转为连绵的嗡。用户明确要求无上限，故不 clamp；
// 若实机觉得高速段糊了，加一条 `min(hz, 60)` 即可（一行改动）。
//
// ⚠️ **静止必须静默**（见 [KERB_MIN_KMH]）：停着压在路肩上时车轮物理上仍在
// 路肩上（`materialIndex` 仍 = -5），不门控就会持续出振（实机症状：停住后
// 马达一直响几分钟）。
//
// ⚠️ **不要再回到"复现游戏音高"那条路**（曾实现过 `clamp(|carSpeed|/20,
// kerbMinPitch, kerbMaxPitch)`）：实机日志证明游戏的 kerbMaxPitch 在本作里
// 恒 = 1.0（`pitch=1.000` 出现 54/54 次），曲线是条常数，没有任何速度响应。
// 用户明确说"频率我来定"，就按上面这条。
// 速度 → 频率的斜率：0.8 m/s → 1 Hz。
#define KERB_HZ_PER_MS           1.25f

// 静止门限：低于此车速视为"停住"，完全不出振（见上方用户规格说明）。
#define KERB_MIN_KMH             0.5f

// ── 强度 = **恒 1.0**（力度完全交给"振动强度"滑条）────────────────────────
//
// ⚠️ **不要再引入轮数因子 / 速度因子**。它们曾经把 level 压到 0.71~0.95，
// 于是用户把滑条拉到 100% 时实际 `scale` 只有 0.80 —— 用户报「我早改成 100%
// 了还是很轻」，这就是根因：**滑条的百分比必须等于实际力度**，中间任何
// 隐藏衰减都会让"100%"名不副实。
//
// 力度维度只保留一个旋钮（配置页的"振动强度"，默认 50%）。要更强就让用户
// 往上拉，不要用"车速/轮数"这类看不见的系数偷偷打折。
#define KMH_PER_MS               3.6f

// ── 停滞看门狗（与 slip_feedback 同构，同一根因）──────────────────────────
// 生产者是物理帧 tick；**暂停 / 退回主界面 / 结算 / 读盘时物理帧停推 ⇒ tick
// 不再被调用 ⇒ 电平冻结在最后一次驾驶的值上** ⇒ Java 侧 60Hz 轮询永远读到
// 同一个非零值 ⇒ 马达无限循环波形一直响。判据只能放**读侧**（Java 主线程，
// 与物理帧率无关），参照时钟必须是**墙钟**（帧计数在停帧时同样冻住）。
#define STALE_LEVEL_TIMEOUT_MS   300

// ⚠️ 哨兵取 INT64_MIN（= "从未 tick"）不是 0：CLOCK_MONOTONIC 的零点是开机
// 时刻，进程启动时它已是"开机时长"。初值 0 会把"进程刚起"误判成"停滞"。
#define LEVEL_STAMP_UNSET        INT64_MIN

static volatile int     g_kerb_enabled = 0;
static volatile float   g_kerb_level = 0.0f;     // 电平 0..1
static volatile float   g_kerb_rate_hz = 0.0f;   // 目标颗粒率（Hz）
static volatile int64_t g_kerb_stamp_ms = LEVEL_STAMP_UNSET;

// 上一次的触地路肩轮数（-1 = 从未 / 已离开）。**只在物理线程读写**，无需原子。
static int g_kerb_last_n = -1;
// 上一次打过日志的车速（日志节流用，见 tick 尾部）。
static float g_kerb_last_logged_kmh = -1e9f;

// ── 路肩状态的**滞回计数**（2026-10-04，修"没完全屏蔽"）──────────────────
//
// ⚠️ **问题**：`materialIndex == -5` 是**逐轮**判定，而四只轮子会**交替**
// 压上路肩（路肩有宽度、车身姿态在变），于是 `kerb_n` 在 0/1/2 之间来回跳。
// 实机日志实证：每 60 条 `kerb:` 记录里有 **13 次 off**（≈22% 的帧被判"离开"）。
// 每次 off 都让抓地力接管一瞬，听感就是"路肩哒哒哒里混进了抓地力"——
// 用户报的「还是没完全屏蔽」。
//
// **修法**：进入立即（0 → 1 帧，保证响应），离开需要**连续 N 帧**都 n==0。
//
// ⚠️ **N 不能贪大（2026-10-04 二次实测修正：10 → 4）**：滞回窗口的语义是
// "这段时间内信号仍被当作**在路肩上**"——所以窗口长度 = **抓地力被压制的时长**，
// 不是"多响 200ms 路肩无所谓"。首版取 10 帧（200ms）时，用户报「出路肩之后
// 抓地力振感没有无缝接着，有明显延迟」——那 200ms 就是延迟本身。
//
// ⚠️ **N 的取值依据（逐帧日志统计，198 个"连续 on"间隔）**：轮子交替造成的
// 短间隔分布为 0~40ms 有 29 个、40~80ms 有 42 个、80ms 以上有 127 个。滞回
// 能盖住的是**前两类**（≤80ms 的真交替，共 71/198）；80ms 以上的间隔是车轮
// **真的短暂离开路肩**（高速掠过凸起），用滞回盖住只会把"真离开"的恢复一起
// 拖慢——两个代价方向相反，单靠调 N 无解。
//
// ⇒ 取 **1 帧（20ms）**——最小可用的噪声免疫，把离场延迟压到最低。
// ⚠️ 为什么敢取这么小：**接续波形（floor）让"假离开"的代价变得极低**。旧版
// 假离开 = 抓地力接管（形态全变，听得出来）；现在 floor 段本来就一直在输出
// 抓地力电平，假离开只是"漏掉一拍撞击"（tap 消失 20ms），几乎无感。
// 于是滞回不再需要靠"窗口长度"去兜交替——1 帧足够。
#define KERB_OFF_HOLD_FRAMES 1
static int g_kerb_off_frames = 0;

// 停滞日志上升沿去重（读侧单线程）。
static int g_kerb_stale_reported = 0;

static int64_t wall_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// 刷新电平 + 打时间戳（**唯一的电平写入点**）。所有"归零"分支也必须走它，
// 否则看门狗会看到陈旧时间戳而误判停滞。
static inline void level_store(float v, float hz) {
    g_kerb_level = v;
    g_kerb_rate_hz = hz;
    g_kerb_stamp_ms = wall_ms();
}

// 读侧：看门狗裁决（唯一的电平读取点）。
static void read_level_with_watchdog(float *out_level, float *out_hz) {
    *out_level = 0.0f;
    *out_hz = 0.0f;
    const int64_t stamp = g_kerb_stamp_ms;
    if (stamp == LEVEL_STAMP_UNSET) return;   // 从未 tick（玩家车尚未上场）——静默
    const int64_t now = wall_ms();
    if (now - stamp > STALE_LEVEL_TIMEOUT_MS) {
        if (!g_kerb_stale_reported) {
            g_kerb_stale_reported = 1;
            LOGI("kerb: watchdog stale (%lld ms since last tick) -> level 0",
                 (long long) (now - stamp));
        }
        return;
    }
    g_kerb_stale_reported = 0;
    *out_level = g_kerb_level;
    *out_hz = g_kerb_rate_hz;
}

void kerb_haptic_set_enabled(int enabled) {
    g_kerb_enabled = enabled ? 1 : 0;
    if (!enabled) {
        level_store(0.0f, 0.0f);
        g_kerb_last_n = -1;
        g_kerb_last_logged_kmh = -1e9f;
    }
    // 常量随开关落盘：日志自带"这轮跑的是哪套参数"，避免拿旧日志配新代码白对。
    LOGI("kerb: enabled=%d (level = 1.0 (强度滑条是唯一力度旋钮); "
         "hz = v_mps x %.2f (= 1 Hz @ %.1f m/s), 无上限; 静止门控 kmh<%.1f; material=%d)",
         g_kerb_enabled, KERB_HZ_PER_MS, 1.0f / KERB_HZ_PER_MS, KERB_MIN_KMH,
         KERB_MATERIAL_INDEX);
}

void kerb_haptic_query(float *out_level, float *out_rate_hz) {
    float lv = 0.0f, hz = 0.0f;
    read_level_with_watchdog(&lv, &hz);
    if (out_level != NULL) *out_level = lv;
    if (out_rate_hz != NULL) *out_rate_hz = hz;
}

void kerb_haptic_tick(void *car_inputs) {
    if (!g_kerb_enabled) {
        level_store(0.0f, 0.0f);
        return;
    }
    if (car_inputs == NULL) {
        level_store(0.0f, 0.0f);
        return;
    }

    // 整车全轮离地（跳台/坠崖）：所有轮子都不着地，materialIndex 是上一帧残留。
    const uint8_t all_off =
        *(uint8_t *) ((uintptr_t) car_inputs + OFF_CAR_ALL_OFF_GROUND);
    if (all_off) {
        level_store(0.0f, 0.0f);
        return;
    }

    void *wheels = *(void **) ((uintptr_t) car_inputs + OFF_CAR_WHEELS);
    if (wheels == NULL) {
        level_store(0.0f, 0.0f);
        return;
    }
    const int n = *(int *) ((uintptr_t) wheels + OFF_IL2CPP_ARRAY_LEN);
    if (n <= 0) {
        level_store(0.0f, 0.0f);
        return;
    }
    const int count = n < WHEEL_COUNT ? n : WHEEL_COUNT;

    // 数一数有几只**触地的**轮子在路肩上。
    int kerb_n = 0;
    for (int i = 0; i < count; i++) {
        void *w = *(void **) ((uintptr_t) wheels + OFF_IL2CPP_ARRAY_DATA + i * 8);
        if (w == NULL) continue;
        // 逐轮地面接触门控：normalForce 在 ComputeWheelPhysics 的离地支被显式
        // 清零（0x1a7e8e0 str wzr,[x19,#0x138]），是最干净的判据。
        const float load = *(float *) ((uintptr_t) w + OFF_WHEEL_NORMAL_FORCE);
        if (load != load || load <= 0.0f) continue;
        const int mi = *(int *) ((uintptr_t) w + OFF_WHEEL_MATERIAL_INDEX);
        if (mi == KERB_MATERIAL_INDEX) kerb_n++;
    }

    if (kerb_n == 0) {
        // ── 离开路肩的**滞回**：连续 KERB_OFF_HOLD_FRAMES 帧都没有轮子在
        //    路肩上，才真正判定"离开"。见 g_kerb_off_frames 处的完整论证。──
        if (g_kerb_last_n > 0) {
            if (g_kerb_off_frames < KERB_OFF_HOLD_FRAMES) {
                // 仍在滞回窗口内 ⇒ 保持路肩状态（电平沿用上一帧，只刷新时间戳，
                // 否则读侧看门狗会把这 ~80ms 当成"物理帧停推"而误判停滞）。
                g_kerb_off_frames++;
                level_store(g_kerb_level, g_kerb_rate_hz);
                return;
            }
            LOGI("kerb: off (hold expired)");
            g_kerb_last_n = 0;
        }
        level_store(0.0f, 0.0f);
        return;
    }
    g_kerb_off_frames = 0;   // 有轮子压上 ⇒ 立即清零（进入无延迟）

    // ── 强度：恒 1.0（见常量区）。车速只用于算频率。──
    // ⚠️ 用**速度模长**（`sqrt(vx²+vz²)`）而非 `carSpeed`(0x84)：后者 =
    // `|bodyVelocity.z|`（车体坐标系的前进分量），车横过来时趋 0，会在最需要
    // 出振的时刻把频率算成 0。与 slip_feedback 的 β/饱和通道同一个坑。
    const float bvx = *(float *) ((uintptr_t) car_inputs + OFF_CAR_BODY_VEL_X);
    const float bvz = *(float *) ((uintptr_t) car_inputs + OFF_CAR_BODY_VEL_Z);
    float v_mag = 0.0f;
    if (bvx == bvx && bvz == bvz) {
        v_mag = sqrtf(bvx * bvx + bvz * bvz);
    }
    const float kmh = v_mag * KMH_PER_MS;

    // ⚠️ **静止即静默**（用户规格「不为 0 时最低 2 Hz」的反面）：停着压在
    // 路肩上时车轮物理上仍在路肩上（materialIndex 仍 -5），不门控就会持续
    // 出振——实机症状是"停住后马达一直响几分钟"。
    if (kmh < KERB_MIN_KMH) {
        // 静止判定**不走滞回**：速度是低通过的平滑量，不像轮子交替那样抖动，
        // 且"停车即静默"必须是确定的（用户实测要求）。
        if (g_kerb_last_n > 0) {
            LOGI("kerb: off (stopped, kmh=%.1f)", kmh);
            g_kerb_last_n = 0;
            g_kerb_last_logged_kmh = -1e9f;
        }
        g_kerb_off_frames = KERB_OFF_HOLD_FRAMES;
        level_store(0.0f, 0.0f);
        return;
    }

    const float level = 1.0f;

    // ── 颗粒率：用户给定的线性映射（见常量区），**无上限** ──
    const float hz = v_mag * KERB_HZ_PER_MS;

    // 进入路肩 / 轮数变化 / **车速变化 >15 km/h** 时落一条日志。**必须节流**——
    // 本函数每物理帧（50Hz）跑，逐帧打会把 native 日志的 2MB 滚动窗口刷没。
    // 车速变化也触发：停车过程 40→0 km/h 会各落一条，这样"停住时到底算出
    // 多少 kmh / hz"在日志里直接可见（否则 n 不变就不打，停住时完全盲区）。
    if (g_kerb_last_n != kerb_n || fabsf(kmh - g_kerb_last_logged_kmh) > 15.0f) {
        LOGI("kerb: on n=%d kmh=%.0f level=%.2f hz=%.1f", kerb_n, kmh, level, hz);
        g_kerb_last_n = kerb_n;
        g_kerb_last_logged_kmh = kmh;
    }
    level_store(level, hz);
}
