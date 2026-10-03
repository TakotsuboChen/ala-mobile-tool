#include "slip_feedback.h"

#include "native_log.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>

// ── IL2CPP 字段偏移（8.0.6 / 200150）─────────────────────────────────────
// 与 pedal_hook.c 的其它 OFF_* 同规矩：偏移写在本处，方法 RVA 一律由 Java
// 侧从 OffsetTable.kt 注入。升级游戏版本时这些常量必须与 dump.cs 逐一对拍
//（CLAUDE.md「字段偏移核对清单 = native 全部 OFF_* 常量」）。
//
// 字段归属（dump.cs 8.0.6 逐条核对）：
//   IRDSWheel        0x104 slipRatio / 0x108 slipVelo / 0x170 slipAngle
//                    0x1A8 maxSlip   / 0x1AC maxAngle  / 0x138 normalForce
//   IRDSCarControllInput 0x28 wheels / 0x84 carSpeed / 0x168 allTiresOffGround
#define OFF_CAR_WHEELS          0x28   // IRDSCarControllInput.wheels (IRDSWheel[])
#define OFF_CAR_SPEED           0x84   // IRDSCarControllInput.carSpeed (m/s)
#define OFF_CAR_ALL_OFF_GROUND  0x168  // IRDSCarControllInput.allTiresOffGround (bool)
#define OFF_CAR_THROTTLE_INPUT  0x174  // IRDSCarControllInput._inputTorque（原始油门输入）
#define OFF_CAR_BRAKE_INPUT     0x178  // IRDSCarControllInput._brake（原始刹车输入）
// bodyVelocity —— **车体坐标系**下的速度矢量（FixedUpdate 内
// Rigidbody.get_velocity() → Transform.InverseTransformDirection() 的结果，
// 反汇编实证 0x1A66CBC~0x1A66CC8）。x = 侧向分量，z = 前进分量。
// 车身侧滑角 β = atan2(|vx|, |vz|) 即"车横着滑"的物理定义，**与油门和轮速无关**
// —— 这正是四通道（σ/α）在"松油惯性打转"时集体失明的那个量（2026-10-03 实证：
// 松油后 σ→0、后轮 α→1.2、前轮 α 被封顶 0.25，四路全灭）。
#define OFF_CAR_BODY_VEL_X      0x120  // IRDSCarControllInput.bodyVelocity.x（侧向，m/s）
#define OFF_CAR_BODY_VEL_Y      0x124  // IRDSCarControllInput.bodyVelocity.y（垂直，m/s）
#define OFF_CAR_BODY_VEL_Z      0x128  // IRDSCarControllInput.bodyVelocity.z（前进，m/s）
#define OFF_IL2CPP_ARRAY_DATA   0x20   // IL2CPP 数组元素起始偏移
#define OFF_IL2CPP_ARRAY_LEN    0x18   // IL2CPP 数组长度

#define OFF_WHEEL_SLIP_RATIO    0x104  // IRDSWheel.slipRatio（有符号，被夹到 [-1,+1]；**不再是主信号**）
#define OFF_WHEEL_SLIP_VELO     0x108  // IRDSWheel.slipVelo = |LVelocity| = **接触点滑移速度（m/s）**
#define OFF_WHEEL_SLIP_ANGLE    0x170  // IRDSWheel.slipAngle（有符号，**度**）
#define OFF_WHEEL_MAX_SLIP      0x1A8  // IRDSWheel.maxSlip（轮胎峰值滑移）
#define OFF_WHEEL_MAX_ANGLE     0x1AC  // IRDSWheel.maxAngle（轮胎峰值滑移角，**度**）
#define OFF_WHEEL_NORMAL_FORCE  0x138  // IRDSWheel.normalForce（离地时被清零）
#define OFF_WHEEL_ANGULAR_VELO  0x100  // IRDSWheel.angularVelocity（rad/s，诊断/备用）
#define OFF_WHEEL_RADIUS        0x060  // IRDSWheel.radius（m，诊断/备用）
#define OFF_WHEEL_LVELOCITY_X   0x24C  // IRDSWheel.LVelocity 分量（诊断用；坐标系未定案）
#define OFF_WHEEL_LVELOCITY_Y   0x250
#define OFF_WHEEL_LVELOCITY_Z   0x254
#define OFF_WHEEL_UNIT_SLIP     0x3F8  // IRDSWheel.unitSlip（诊断用，游戏自己算的归一化）
#define OFF_WHEEL_UNIT_ANGLE    0x3FC  // IRDSWheel.unitAngle（诊断用）
#define OFF_WHEEL_SLIP_CLAMP    0x35C  // IRDSWheel.slipRatioClamp（**前后轮不同！实测前 0.55 / 后 1.50**）
// `IRDSWheel.hit`(0x1C8) 是 UnityEngine.WheelHit，其子字段由 **PhysX** 直接填充，
// **不经过游戏的 slipRatioClamp / 低通**——这是 v9 的候选主信号源：
//   m_Force(0x1C8+0x30) / m_ForwardSlip(0x1C8+0x34) / m_SidewaysSlip(0x1C8+0x38)
#define OFF_HIT_FORWARD_SLIP    0x1FC  // WheelHit.forwardSlip —— PhysX 原始纵向滑移
#define OFF_HIT_SIDEWAYS_SLIP   0x200  // WheelHit.sidewaysSlip —— PhysX 原始横向滑移
#define OFF_HIT_FORCE           0x1F8  // WheelHit.force —— PhysX 接触力
// 轮胎力与载荷（摩擦圆利用率的候选量：|F| / (μ·Fz)）。
#define OFF_WHEEL_FX            0x2F0  // IRDSWheel.Fx（纵向力）
#define OFF_WHEEL_FY            0x2EC  // IRDSWheel.Fy（侧向力）
#define OFF_WHEEL_FN            0x210  // IRDSWheel.Fn（法向力，与 0x138 normalForce 区分；当前仅登记备用）
#define OFF_WHEEL_GRIP          0x078  // IRDSWheel.grip（运行时摩擦系数 μ）

#define WHEEL_COUNT 4

// ── 归一化的地板分母 ──────────────────────────────────────────────────────
// maxSlip / maxAngle 由 `UpdateMaxSlips`(0x1a7f800) 每帧按载荷从 100 点 LUT
//（`slipR[]`(0x2A0) / `slipA[]`(0x2A8)）插值得到，正常着地时 > 0。但：
//   - 首帧 / InitSlipMaxima 未跑完时可能是 0；
//   - 载荷趋 0（轮子轻掠地面）时峰值本身趋 0。
// 分母为 0 会造出 inf，除以极小值会造出天文数字。取 0.03 作为地板。
// ⚠️ 注意两个字段**单位不同**（实测 + 常量实证）：maxSlip 实测 0.093~0.103；
// maxAngle 是**度**，实测 12.1~13.4（0x92a088 = 57.2958 = 180/π 实证）。
// 0.03 对两者都远低于真实值，只防爆不改行为。
#define PEAK_FLOOR 0.03f

// ── 信号源：z 分数（2026-09-22 第八轮，量纲终于对上）───────────────────
//
// 前七轮的共同病根是**量纲没对上**。这一轮把 `IRDSWheel` 全字段拉出来逐条
// 对拍（dump.cs L14651 起 172 个字段）＋反汇编 `SlipRatio` 全函数，终于闭合：
//
//   `slipRatio`(0x104) = `SlipRatio(radius, wMagnitud)` 的**输出**，而那个函数
//   只做一件事（0x1a7da24-0x1a7daa4）：
//
//       ω  = |angularVelocity|(0x100)                     （先按 radius 归一）
//       s  = min(1, ω_norm / 8)
//       σ  = clamp(−LV.z(0x254) / max(wMagnitud, 1), ≥0) · s
//
//   **它本身就是一个"轮胎利用率"（ψ 的纵向分量）**：物理上的滑移比是
//   `(v − ωR)/max(ωR, v)`，而"滑移速度"就是 `v − ωR` —— 正是 `−LV.z`。
//   游戏把分母写成 `max(wMagnitud, 1)`、再乘一个 `s` 权重。所以：
//
//   ⚠️ **`slipRatio` 除以 `maxSlip` 是双重归一化**（ψ(σ) / ψ_peak），
//   这就是"锁死只有 1/0.1 = 10、普通起步也有 3~7、两者差不到 1 个数量级"
//   的根源——**纵向永远拉不开差距**。
//
//   改用**归一化滑移的峰值倍数** `z = |σ| / maxSlip`（z = 1 即"该轮此刻的
//   轮胎峰值"）。**不除 `slipRatioClamp`(0x35C)**——除它会把量程压到
//   ≤1/1.05，满振点永远够不到。z 的上限是 σ 的饱和值 ÷ maxSlip ≈ 10.6，
//   锁死（z≈10.5）与跟车（z≈0.1）天然差两个数量级。
//
//   ⚠️ 另一件必须修的：**膝盖以内不能是纯线性**。轮胎是弹性体，滑移比在
//   峰值以内对侧向力的贡献接近线性，但要"清晰地感觉到手柄的线性手感"就必须
//   让电平时时跟着 σ 动。线性映射下 σ 在静默点之内电平恒 0，手感是
//   "台阶"；改**幂次**（σ/maxSlip 的 0.6 次方）⇒ 小滑移就有一点点、大滑移
//   迅速上来，全程连续可感。这是"线性马达清晰手感"的实现方式。
#define MIN_Z          1.00f   // 静默上限：普通过弯/巡航都在此之下（实测中位 z≈1.2）
#define KNEE_Z         4.00f   // "用尽抓地力"处（用户规格的 1/4 档）
#define LEVEL_AT_PEAK  0.25f   // = 膝盖处电平，用户规格的"用尽抓地力 ≈ 1/4"
#define FULL_Z         10.00f  // 完全打滑 ⇒ 满振（σ 被游戏夹到 ±1 的饱和点，实测 10.2~10.75）
//
// ⚠️ **锚点位置的实测依据（2026-10-02，逐帧直方图重标定）**——
// 之前的锚点（MIN_Z=0.40 / 膝盖=1.00 / FULL_Z=3.50）把"任何中等力度过弯"
// （实测 z 中位数 0.88~1.57、峰值 2.4）都映射到 0.22~0.66 的电平上，这正是
// 用户「低速拐弯还是太振了，不符合抓地力实际情况」的量化根因。
//
// 真正的分档间隔远大于旧锚点假设：
//
//   巡航        z ≈ 0.02~0.17   （直行，轮胎几乎不滑）
//   普通过弯    z ≈ 0.88~2.40   （轮胎在峰值附近但车没失控）
//   极限过弯    z ≈ 3.9~7.7     （响胎推头）
//   锁死/空转   z ≈ 10.2~10.75  （σ 被 RoadForce 夹到 ±1 的**硬天花板**）
//
// 新锚点把静默区抬到 z=1、膝盖抬到 z=4、满振锚在饱和点 10，于是
// 普通过弯落到 0.05~0.17（几乎无感）、极限过弯落到 0.25（= 规格 #3）、
// 锁死/空转仍是 1.0（= 规格 #1/#2）。
//
// ⚠️ **v11 追加**：上表是"四轮取大"的纵向通道。油门有输入时另有**后轮空转
// 通道**（阈值更低更陡，见 map_spin_to_level），因为用户规格明确
// 「油门有输入时后轮空转的优先级最高」——后轮空转 = 牵引力用尽 = 车尾要出去，
// 比"完全锁死"更急，所以满振点从 10 提前到 6。
//
// 膝盖段（KNEE_Z ≤ z ≤ FULL_Z）**刻意保持线性、不给幂次**：用户抱怨过
// 「滑了就突然爆发」，膝盖之后的上升段斜率只降不升（0.25 → 0.125）是那条
// 抱怨的结构性保证。幂次只用在膝盖以内（那里斜率本来就小）。

// 归一化滑移曲线（= 轮胎利用率的纵向分量）的**幂次**。
// 0.6 < 1 ⇒ ease-out：小滑移就成比例地给出可感电平，大滑移趋于饱和。
// 见上文"膝盖以内不能是纯线性"。
#define SLIP_POW       0.60f

// ── 横向 max 通道的锚点（**信号源 = 侧向滑移速度**，2026-10-03 第三版定案）──
// 信号 = `sv = v · sin(α_max)`（m/s）。`α_max = max_i |slipAngle_i|`（原始滑移角，
// **度**，未归一化），`v = sqrt(bodyVel.x² + bodyVel.z²)`（速度模长）。
//
// ⚠️ **物理含义**：轮胎接触点相对地面的**侧向滑动速度**。这是"响胎"的直接
// 物理量——胎面以多快的速度侧向刮擦地面（轮胎声学里 squeal 的频率/强度就是
// 它驱动的）。与"用了多少抓地力"不同，它**只在真的滑起来时才大**。
//
// ## 三代信号源的实测对比（同一份 1110 窗日志，见下方分档）
//   ① α 原始值（旧）      ：随车速漂移（各档 P90 2.63~5.54），锚点无法通用
//   ② |Fy|/(μ·Fn)（上一版）：**不区分转弯强度**——悠闲转弯 0.66 / 响胎 0.76
//      只差 15%，锚点怎么放都"只要在转弯就顶满"（实机 69% 窗口钉死 0.25，
//      用户报「低速悠闲转弯居然也保持 1/4 振感」）。根因：正常转弯时轮胎本来
//      就接近摩擦圆饱和，"利用率"衡量"用了多少"而非"用过头了"。
//   ③ α/maxAngle 归一化（第二版）：能分开（悠闲 0.73 / 响胎 1.19），但仍要
//      靠锚点卡在 0.85~1.25 的窄缝里；且**低速打满舵**（v<20、α 冲到 30°+）
//      会把 a 推到 1.0+ 误触发。用户报「高速打方向明显响胎却几乎不振」——
//      响胎窗口 a P50 只有 0.94，离锚点 1.25 还远。
//
// **③ 的问题正是"归一化"本身**：α/maxAngle 是"滑移角占峰值的比例"，与速度
// 无关 ⇒ 高速小滑移角（响胎）被算得很小。而 `sv = v·sin α` 把速度乘回来，
// 高速下 5° 的滑移角就产生 7 m/s 的侧向刮擦 —— 这才是响胎的成因。
//
// ## 实测分档（sv，m/s）
//   高速直线巡航      1.3（P90 4.5）  → 0
//   直线刹车          0.5（P90 2.1）  → 0   ⚠️ 纵向滑移不计入（sin 只取侧向分量）
//   低速悠闲转弯      5.0（P90 6.9）  → 0
//   低速打满舵        0.04（P90 0.8） → 0   ⚠️ 速度小 ⇒ sv 天然小，不误触发
//   高速响胎          19.0（P90 23.9）→ 0.25
//   高速甩尾          17.5（P90 27.3）→ 0.25（②③通道接管满振）
// 分离度：响胎 / 悠闲 = 3.8×；响胎 / 直线 = 14×。锚点 8→15 落在两簇之间。
//
// ⚠️ 这一条通道的**用途是"最佳抓地力提示器"**（用户原话：「要的就是过弯感受到
// 1/4 振感的时候证明过得非常好」），不是失控报警——真失控（甩尾/空转/锁死）
// 由 ②③④⑤⑥ 给满振。故本通道硬封顶 1/4（规格第 3 条）。
#define LAT_MIN_Z      8.0f    // 静默上限（m/s；悠闲转弯 P90=6.9 在此之下）
#define LAT_KNEE_Z     15.0f   // "用尽抓地力"处（m/s，电平 = LEVEL_AT_PEAK = 0.25）

//
// ⚠️ **横向必须是两条通道，不是一个 max**（2026-10-02 第二轮修正）——
// 用户报「漂移侧滑几乎消失，明明已经滑起来了也不振」。根因：只取 `max_i`
// 再硬封顶 1/4 ⇒ **推头（可控）与漂移（失控）输出完全相同的 0.25**。
//
// ⚠️ **第三轮（2026-10-02 深夜）：判别量必须是「后轮」的 min，且阈值下调。**
// 实测后轮 min α（= min(|α_RL|,|α_RR|)/maxAngle）的分布：
//
//   普通过弯/跟车  0.4 ~ 1.0
//   极限推头       1.5 ~ 3.1   （后轮还抓着，只有前轮滑）
//   漂移侧滑       4.4 ~ 6.6   （后轴整体横滑）
//
// 取「全四轮 min」不行：外侧前轮转弯时 α 天然低（转过去了但没滑），
// 会把 min 拉到 1~2，把真漂移淹没（实测多例 min_all 1.0~2.0 而 min_rear 5+）。
// **甩尾在物理上就是后轴现象**，用后轮 min 既干净又合物理。
#define LAT_REAR_KNEE_Z 3.50f  // 后轮 min α 的起振点（静默上限）
#define LAT_REAR_FULL_Z 5.50f  // 后轮 min α 的满振点（实测漂移 4.4~6.6）

// ── 后轮空转通道的锚点（**最高优先级**，油门门控，见 map_spin_to_level）────
// 用户规格第 2 条（「出弯轮胎打滑空转 → 最大振动强度」）判的就是**后轮打滑**，
// 用户原话：「后轮打滑基本上就等于不可控」「中速给油打方向甩尾不就是后轮打滑」
// —— 后轮打滑 = 车尾要出去 = 不可控，本身就是满振级别，**不需要**等 β 或
// mean4 这些"整车滑移"量来确认（那些是规格 3「用尽抓地力但未失控」的范畴）。
//
// ⚠️ **旧锚点（2.5 → 6.0）是拿低速数据标定的，系统性偏高**（2026-10-03 修正）：
// `σ = slipRatio / maxSlip` 而 `slipRatio` 的分母是**轮面线速度 ωR** ——
// 同样的物理空转，ωR 越大 σ 越小。实测同一台车同一动作：
//   低速（v≈4 m/s）甩尾：后轮 σ = 9.4 ~ 10.75（撞满量程）
//   中速（v≈35 m/s）甩尾：后轮 σ = 3.0 ~ 5.0
// 旧锚点把中速甩尾（σ=3.9~5.0）压到 0.41~0.79 ⇒ 用户报「80-150 溜冰但振得轻微」。
//
// 新锚点依据（给油 + 车速>10 的 744 个窗口交叉统计）：
//   σ 0~0.5  n=239 前α中位 0.09（正常巡航/直线给油）
//   σ 1.5~2.5 n=95  前α中位 1.03~1.34（开始横滑）
//   σ 2.5~4.0 n=141 前α中位 1.27~1.67（明显在滑）
//   σ 4.0+    n=98  前α中位 1.34~1.55（失控）
// **后轮 σ 一上去前轮 α 同步上去**——σ 本身就是"车在滑"的最早信号。
#define SPIN_KNEE_Z    1.80f   // 起振点（避开 0.5~1.5 的正常给油区）
#define SPIN_FULL_Z    3.00f   // 满振点（实测中速甩尾恰在 3~5）

// ── 后轮空转的门控：**轮子自己的滑移速度**，不是车速 ──────────────────
// ⚠️ **旧门控 `carSpeed > 2` 有一个致命盲区：原地烧胎**（2026-10-03 实机修复）。
// 烧胎的定义就是"车速不动、轮子在转" ⇒ 车速判据恰好把最该响的场景全挡掉。
// 用户报「顶着墙满油、后轮疯狂烧胎空转、但没有满振」的量化根因：实测该窗口
// 每一帧 carSpeed 都是 **0.0**（顶着墙），而 `SPIN_MIN_SPEED=2` 把整段门死。
//
// 改用 `IRDSWheel.slipVelo`(0x108) = **该轮接触点的滑移速度**（m/s，游戏自算）。
// 实测它 ≡ `|ω·R − v|`（轮胎纵向滑移速度的教科书定义），同时答对两个问题：
//   • 生成/重生的伪影帧：carSpeed=0、σ=8.76，但 ω 只有 **1.9 rad/s**
//     （轮面线速度 0.7 m/s）⇒ slipVelo = **0.67** → 正确排除
//   • 真烧胎帧：carSpeed=0、σ=9.31，ω=97 rad/s ⇒ slipVelo = **34.7** → 正确保留
// 旧判据之所以能"歪打正着"挡掉伪影，只是**巧合**：伪影恰好也发生在车速 0 时。
// 用轮子自己的速度判断"轮子在不在转"，才是这件事的本体。
//
// ⚠️ 只用后轮（i>=2）的 slipVelo 做门控——前轮在刹车锁死时同样会大，但那是
// 纵向通道的职责，与本通道（给油空转）无关。
#define SPIN_MIN_SLIP_VELO 2.00f   // 2 m/s ≈ 轮面相对地面 7.2 km/h 的刮擦

// ── 车身侧滑通道的锚点（**第五通道**，2026-10-03，见 map_beta_to_level）────
// 前四通道有一个共同盲区：**松油门后车还在横着滑**的时候，四个量全部归零。
// 逐帧实测（12:19:49~12:20:02 三次原地打转，50Hz 序列）：
//
//   打转中 β（车头指向与行进方向夹角）：62° ~ 90°
//   同期  后轮空转 σ：被油门闸门关掉 → 0
//         纵向 σ：车轮在正常滚动，衰减到 1~2
//         后轮 α：1.0~2.1（够不到 LAT_REAR_KNEE_Z=3.5）
//         前轮 α：被封顶 0.25
//   ⇒ 电平在 β 的峰值处跌到 0.23（**最强的失控瞬间反馈最弱**）
//
// 对照（同会话 96 个非打转窗口：滑行/过弯/直线）：
//   β 最大值 16°，第 90 百分位 3° ⇒ 与打转段（62~90°）**几乎不重叠**。
//
// ⚠️ 低速是噪声源：β 是比值，v→0 时 vx/vz 的小数噪声也能算出 84°（实测
// 车停稳时 β 序列恒 86）。**必须用速度模长门控**，且**不能用 carSpeed**
// ——反汇编实证 carSpeed(0x84) = |bodyVelocity.z|（车体坐标系的**前进分量**），
// 车横过来时 vz≈0 ⇒ carSpeed≈0，用它做门控会恰好在最需要的时候把通道关掉。
#define BETA_MIN_Z      20.0f  // 起振点（°）
#define BETA_FULL_Z     45.0f  // 满振点（°）
// ⚠️ **速度模长门控**。两条实测依据（2026-10-03 第二轮）：
//   ① β 是比值，v→0 时纯噪声能算出 80°+（实测车停稳时 β 恒 84~86）。
//   ② 门限**必须够高**：v=3.4 m/s 时实测仍有 6 帧 β 冲到 45~85°（那是甩尾
//      刚结束、车体还在惯性旋转但轮胎已停转的余波，不是可控滑移）。
//      把门限提到 5 m/s 后，正常驾驶的 β 上限从 85° 掉到 26°。
// 代价：v<5 时本通道不参与 —— 但那种情况必然伴随后轮空转 σ 满振（油门在给），
// 由通道④覆盖，不构成盲区。
#define BETA_MIN_SPEED  5.00f

// ── 四轮集体饱和通道的锚点（**第六通道**，2026-10-03）────────────────────
// 前五通道的共同结构是"**某一条**通道爆表"：纵向锁死、后轮空转、后轴甩尾、
// 车身横滑。但高速宽滑是**四只轮均匀地一起用到 3~4 倍峰值**——谁都没到
// "彻底用尽"，于是每条通道都只给中低电平。
//
// 实测（2026-10-03，1437 个窗口）四轮利用率 `util_i = max(纵向z, 横向z)` 的均值：
//   纯正常驾驶（v>10，四通道安静）：中位 0.17  P99 0.68  最大 0.86
//   本次高速四轮滑事件（12:35:55~56）：2.89 ~ 3.12
//   ⇒ 分离度 3.4 倍，门槛两侧余量都很大。
//
// ⚠️ 用 **mean4** 而不是 min4/min2：min4 会被"搭便车"的单只轮拉低（实测
// 12:32:03.747 事件里 min4=0.40 而 mean4=2.3），mean4 更稳。
#define SAT_MEAN_KNEE   1.00f  // 起振点（四轮平均利用率）
#define SAT_MEAN_FULL   3.00f  // 满振点
#define SAT_MIN_SPEED   5.00f  // 速度模长门控（与 β 同因：低速轮胎量不可信）

// ── 运行参数（Java 低频下发；volatile 供物理线程读）──────────────────────
static volatile int   g_enabled = 0;
// 满振点（z 空间，见 FULL_Z）。做成可配只是为了让探针/回归能在不动代码的
// 前提下改锚点；正常路径恒 FULL_Z。
static volatile float g_full_u = FULL_Z;

// ── 生产者 → 消费者信号（物理线程写、Java 主线程读，单字对齐天然原子）──
static volatile float g_level = 0.0f;

// ── 电平样本环（物理线程写、Java 主线程读；**每帧的抖动就是振感质感**）────
// 为什么要有它：物理 50Hz / Java 轮询 60Hz 不同步，只取"最新值"会让某些帧被
// 读两次、某些帧被漏掉，采样率不确定 ⇒ 手感飘。更根本的是——**振动的"质感"
// 不该由人造调制频率提供**（那只能调出"音调"，且能渲染的频率必然落在
// 波动/嗡的感知区，就是用户说的"糊"），而应当**直接跟随本信号的帧间抖动**：
// 它是真实路面/滑移的动态，非周期、不糊。
//
// 单写者（物理线程）单读者（Java 主线程），`seq` 单调递增、只在写完槽位后
// 自增，读者靠"先取 seq 再读槽位"保证不会读到半成品。容量 256 是 2 的幂
// （掩码取模），>5s 缓冲，远超任何轮询间隔。
#define SLIP_RING_BITS 8
#define SLIP_RING_SIZE (1 << SLIP_RING_BITS)   // 256
#define SLIP_RING_MASK (SLIP_RING_SIZE - 1)
static volatile float    g_ring[SLIP_RING_SIZE];
static volatile uint32_t g_ring_seq = 0;       // 已产生的样本总数（单调递增）

// ── 标定探针（默认关，开启后每 PROBE_INTERVAL_FRAMES 帧落一组数据）────
#define PROBE_INTERVAL_FRAMES 25    // 50Hz 物理帧 ⇒ 约 0.5 秒一组
// ⚠️ 从 150（3s）降到 25（0.5s）（2026-10-03）：3 秒窗口只有峰值、**分辨不出
// "打转了好多次"**——一次甩尾持续 1~2s，在 3s 窗里被压成一个 max 值，看不出
// 抖动结构，导致"分析不符合实际"。0.5s 窗能把一次甩尾展开成 2~4 个窗。
#define PROBE_U_BINS          20    // 纵向 u 分布直方图档数（档宽 = FULL_Z/20 = 0.5）
#define PROBE_MAX_FRAMES      720000 // 自限时：50Hz ⇒ 4 小时
// ⚠️ 从 30000 帧（10 分钟）提到 720000 帧（4 小时）（2026-10-03）：
// 10 分钟自限时导致"用户报某段有问题时，那段恰好已经被探针关掉了"（实测：
// 13:03 自限时到点，13:08 的反打救车段零数据）。每窗 0.5s 只落几行，
// 4 小时也不会淹日志（native_log 自身有 2MB 截断保护）。
// 重启游戏即重新计时。

static volatile int g_probe_enabled = 0;
static uint32_t g_probe_frame = 0;
static uint32_t g_probe_total = 0;
static int      g_probe_windows = 0;
// 本窗内的 u 分布与聚合统计（只由物理线程写，窗口结束时一次性落盘）。
static uint32_t g_probe_hist[PROBE_U_BINS];
static float    g_probe_max_lon = 0.0f;   // 纵向 z（轮胎利用率，峰值=1）
static float    g_probe_max_lat = 0.0f;   // 横向 max_i（用尽抓地力）
static float    g_probe_max_rear = -1.0f; // 后轮 min α（甩尾判别量）
static float    g_probe_max_spin = 0.0f;  // 后轮 z（空转判别量）
static uint32_t g_probe_throttle_frames = 0; // 本窗油门有输入的帧数
static float    g_probe_max_slip = 0.0f;  // 本窗 |σ| 原始值峰值（= 归一化滑移）
static float    g_probe_max_speed = 0.0f; // 本窗车速峰值——诊断"低速假象"必需
// 车身侧滑角 β = atan2(|vx|,|vz|)，单位**度**。本窗的峰值与**首帧值**
// （首帧 ≈ 采样瞬间的瞬时值，用来看"用户操作的那一刻到底滑了多少"）。
static float    g_probe_max_beta = 0.0f;
static float    g_probe_first_beta = -1.0f;
// β 直方图（0~90°，档宽 3°）—— 标定车身侧滑通道的起振/满振门槛用。
#define PROBE_BETA_BINS 30
static uint32_t g_probe_beta_hist[PROBE_BETA_BINS];
// **逐帧序列**（2026-10-03）——窗口 0.5s 只能看包络，用户要求"打转一次"级别的
// 分辨率，故在窗内把**每一物理帧**的 β 与输出电平原样存下来，窗末一次性打一行。
// 这样分辨率 = 物理帧率（50Hz），窗口宽度不再限制可读细节。
static float g_probe_series_beta[PROBE_INTERVAL_FRAMES];
static float g_probe_series_lvl[PROBE_INTERVAL_FRAMES];
static float g_probe_series_blev[PROBE_INTERVAL_FRAMES];  // 侧滑通道单独的电平（验证它有没有在给）
static float g_probe_series_slev[PROBE_INTERVAL_FRAMES];  // 四轮饱和通道单独的电平
// 逐轮分量的峰值：z_slip / z_angle 分开记，才能从日志看出"这一轮是
// 纵向饱和还是横向饱和"，以及"高电平到底来自哪个通道"。
static float    g_probe_peak_norm_slip[WHEEL_COUNT];
static float    g_probe_peak_norm_angle[WHEEL_COUNT];
static float    g_probe_peak_fn[WHEEL_COUNT];
static uint32_t g_probe_gated_frames = 0;   // 被地面/离地门控掉的帧数
static uint32_t g_probe_triggered = 0;      // 纵向 z 越过起振点的帧数
// 原始量与峰值本身。只打归一化后的 z 看不出"z 高是真的打滑还是分母太小"，
// 必须连分子分母一起打。布局 = [轮][4] = maxSlip / maxAngle / |σ| / |α|。
static float g_probe_raw_max[WHEEL_COUNT * 4];
// 布局：[轮][12] —— 见 tick 内 probe_diag 的填充顺序。
// ⚠️ 这 12 槽是**第九轮**为"σ 在低速系统性饱和"（实测 z 中位数 10.3 @ <30km/h
// vs 0.63 @ 85km/h）专门加的候选信号源清单，用来一次性判定下一轮该锚哪个量。
//
// 已知的饱和机理（`SlipRatio` 反汇编 + 实测闭合）：
//   σ = min(1, ω_norm/8) · clamp(−LV.z / **max(wMagnitud, 1)**, ≥0)
// 那个 `max(..., 1)` 是**量纲陷阱**——ωR < 1 m/s（车速 < 3.6 km/h）时分母被钳到
// 1，σ 退化成"滑移速度的 m/s 数"，1 m/s 的轮下搓动就报 σ=1 ⇒ s = 1/maxSlip ≈ 10
// ⇒ 满振。这就是「起步用很小的油门、后轮完全没打滑也满振」的根因。
//
// 候选替代（本轮探针全部打出来，下一轮用数据选）：
//   · `slipVelo`(0x108) = |LVelocity| —— 若它就是"滑移速度模长"，则它天然
//     同时覆盖"车在动而轮锁死"（≈车速）与"轮在转而车不动"（≈ωR），
//     可作为**不需要量纲推断**的绝对滑移量；
//   · `WheelHit.forwardSlip`(0x1FC)/`sidewaysSlip`(0x200) —— **PhysX 原生、
//     不经游戏 clamp/低通**的原始滑移；
//   · `hypot(unitSlip, unitAngle)` —— 游戏自己的摩擦圆利用率；
//   · `hypot(Fx, Fy)` / Fn = 用"力/附着力"直接算利用率（完全绕开滑移量）。
#define PROBE_DIAG_SLOTS 12
#define PROBE_DIAG_NAMES \
    "angVel", "slipVelo", "radius", "sClamp", \
    "hitFwd", "hitSide", "hitForce", "Fx", "Fy", "grip", "unitSlip", "unitAngle"
static float g_probe_diag_max[WHEEL_COUNT * PROBE_DIAG_SLOTS];
// 游戏自算的 unitSlip/unitAngle 已并入 PROBE_DIAG_SLOTS 的槽位 10/11。

// 绝对值 + NaN 归零。探针专用（热路径上只有 4 个字段做同样的事）。
static inline float f_abs(float x) {
    if (x != x) return 0.0f;
    return x < 0.0f ? -x : x;
}

void slip_feedback_set_params(int enabled, float full_z) {
    // 防御：满振点必须严格大于峰值（1.0）且不能大到荒谬。
    if (!(full_z > 1.0f) || !(full_z < 100.0f)) {
        NLOGW("slip_feedback: bad full_z=%.3f, keeping previous (%.3f)",
              full_z, g_full_u);
        g_enabled = enabled ? 1 : 0;
        if (!enabled) g_level = 0.0f;
        return;
    }
    g_full_u = full_z;
    g_enabled = enabled ? 1 : 0;
    if (!enabled) g_level = 0.0f;
    NLOGI("slip_feedback: enabled=%d fullZ=%.3f "
          "(lon: z<=%.2f -> 0, z=%.2f -> %.2f, z>=%.2f -> 1 | "
          "latMax: z<=%.2f -> 0, z>=%.2f -> %.2f | "
          "latRear(rear min_i): z<=%.2f -> 0, z>=%.2f -> 1 | "
          "spin(rear max z, throttle-gated, slipVelo>%.1f): z<=%.2f -> 0, z>=%.2f -> 1)",
          g_enabled, full_z, MIN_Z, KNEE_Z, LEVEL_AT_PEAK, full_z,
          LAT_MIN_Z, LAT_KNEE_Z, LEVEL_AT_PEAK,
          LAT_REAR_KNEE_Z, LAT_REAR_FULL_Z,
          SPIN_MIN_SLIP_VELO, SPIN_KNEE_Z, SPIN_FULL_Z);
}

void slip_feedback_set_probe(int enabled) {
    g_probe_enabled = enabled ? 1 : 0;
    g_probe_frame = 0;
    g_probe_total = 0;
    g_probe_windows = 0;
    for (int i = 0; i < PROBE_U_BINS; i++) g_probe_hist[i] = 0;
    g_probe_max_lon = 0.0f;
    g_probe_max_lat = 0.0f;
    g_probe_max_rear = -1.0f;
    g_probe_max_spin = 0.0f;
    g_probe_throttle_frames = 0;
    g_probe_max_slip = 0.0f;
    g_probe_max_speed = 0.0f;
    g_probe_max_beta = 0.0f;
    g_probe_first_beta = -1.0f;
    for (int i = 0; i < PROBE_BETA_BINS; i++) g_probe_beta_hist[i] = 0;
    g_probe_gated_frames = 0;
    g_probe_triggered = 0;
    for (int i = 0; i < WHEEL_COUNT; i++) {
        g_probe_peak_norm_slip[i] = 0.0f;
        g_probe_peak_norm_angle[i] = 0.0f;
        g_probe_peak_fn[i] = 0.0f;
    }
    for (int i = 0; i < WHEEL_COUNT * 4; i++) {
        g_probe_raw_max[i] = 0.0f;
    }
    for (int i = 0; i < WHEEL_COUNT * PROBE_DIAG_SLOTS; i++) {
        g_probe_diag_max[i] = 0.0f;
    }
    NLOGI("slip_feedback: probe=%d (interval=%d frames, auto-off at %d)",
          g_probe_enabled, PROBE_INTERVAL_FRAMES, PROBE_MAX_FRAMES);
    // 常量随探针落盘：日志自带"这轮跑的是哪套参数"，避免拿旧日志配新代码白对。
    NLOGI("slip_feedback: constants lon(minZ=%.2f kneeZ=%.2f) "
          "latMax(minZ=%.2f kneeZ=%.2f) latRear(kneeZ=%.2f fullZ=%.2f) "
          "spin(kneeZ=%.2f fullZ=%.2f minSlipVelo=%.2f) "
          "beta(minZ=%.0f fullZ=%.0f minV=%.2f) "
          "sat(knee=%.2f full=%.2f minV=%.2f) "
          "levelAtKnee=%.2f fullZ=%.3f peakFloor=%.3f slipPow=%.2f",
          MIN_Z, KNEE_Z, LAT_MIN_Z, LAT_KNEE_Z, LAT_REAR_KNEE_Z, LAT_REAR_FULL_Z,
          SPIN_KNEE_Z, SPIN_FULL_Z, SPIN_MIN_SLIP_VELO,
          BETA_MIN_Z, BETA_FULL_Z, BETA_MIN_SPEED,
          SAT_MEAN_KNEE, SAT_MEAN_FULL, SAT_MIN_SPEED,
          LEVEL_AT_PEAK, g_full_u, PEAK_FLOOR, SLIP_POW);
}

void slip_feedback_query(float *out_level) {
    if (out_level == NULL) return;
    *out_level = g_level;
}

int slip_feedback_drain(float *out, int max, unsigned int from_seq,
                        unsigned int *out_next_seq) {
    if (out == NULL || max <= 0) return 0;

    const uint32_t seq = g_ring_seq;   // 单次读，保证 start/end 是同一时刻
    if (out_next_seq != NULL) *out_next_seq = seq;

    // 首次调用（from_seq == 0）不回放历史，只从当前开始。
    if (from_seq == 0) return 0;

    uint32_t start = from_seq;
    // 落后超过一圈 ⇒ 快进到"还留有完整一圈"的位置（丢最旧的，不撕裂）。
    if (seq - start > SLIP_RING_SIZE) start = seq - SLIP_RING_SIZE;

    int n = 0;
    while (start < seq && n < max) {
        out[n++] = g_ring[start & SLIP_RING_MASK];
        start++;
    }
    return n;
}

/**
 * **纵向通道**：归一化滑移 `s = |σ| / maxSlip` → 反馈电平。
 *
 *     s ≤ 1.0           → 0
 *     1.0 < s ≤ 4.0     → 0 .. 0.25   幂次（SLIP_POW）上弯（"用尽抓地力"在 4.0）
 *     4.0 < s ≤ full_z  → 0.25 .. 1   线性（**不给幂次**，见文件头常量区）
 *     s ≥ full_z        → 1（full_z 默认 10.0 = σ 饱和点）
 *
 * ⚠️ **为什么 s 不是"轮胎利用率"本身**：`slipRatio`(0x104) 是
 * `SlipRatio()` 的**输出**，那个函数内部已经做了一次 `clamp` 与 `min(1, ω/8)`
 * 归一（见文件头推导）。所以 `s = σ/maxSlip` 是"归一化滑移"，而真正的
 * 轮胎利用率纵向分量还要再除以 `slipRatioClamp`(0x35C)。**这里不除**——除它
 * 会把整个量程压到 ≤ 1/1.05，满振点（`full_z = 10`）就永远够不到，马达又回到"从不
 * 满幅"的老problem。`s` 才是与 `maxSlip` 同一量纲、能自然长到 ~10.6 的量。
 * （clamp 只用来做探针的诊断对照，不参与映射。）
 */
static inline float map_lon_to_level(float s) {
    if (s <= MIN_Z) return 0.0f;

    // 第一段：静默上限 → "用尽抓地力"膝盖（z=4），电平 0 → 0.25，**幂次上弯**。
    // 幂次让"小滑移就有一点点反馈"成为可能，同时不引入膝盖后的陡变。
    if (s <= KNEE_Z) {
        const float t = (s - MIN_Z) / (KNEE_Z - MIN_Z);
        return LEVEL_AT_PEAK * powf(t, SLIP_POW);
    }

    // 第二段：膝盖 → 满振点（z=10，σ 饱和点），电平 0.25 → 1，**线性**。
    const float span = g_full_u - KNEE_Z;
    if (span <= 1e-6f) return 1.0f;
    const float l = LEVEL_AT_PEAK + (1.0f - LEVEL_AT_PEAK) * ((s - KNEE_Z) / span);
    return l > 1.0f ? 1.0f : l;
}

/**
 * **横向通道 A：用尽抓地力（最佳抓地力提示器）** → **硬封顶 1/4**。
 *
 *     sv ≤ 8.0    → 0
 *     8.0 < sv ≤ 15.0 → 0 .. 0.25（幂次上弯）
 *     sv > 15.0   → 0.25（不再增长）
 *
 * 其中 `sv = v · sin(α_max)`（**侧向滑移速度**，m/s），
 * `α_max = max_i |slipAngle_i|`（原始滑移角，度），`v` = 速度模长。
 *
 * ## 为什么是"侧向滑移速度"（三代信号源的教训，详见常量区）
 * ① **α 原始值**：随车速漂移，锚点无法通用。
 * ② **|Fy|/(μ·Fn)**：物理正确但不区分转弯强度（悠闲 0.66 / 响胎 0.76，只差
 *    15%）⇒ 只要在转弯就顶满，用户报「低速悠闲转弯也保持 1/4 振感」。
 * ③ **α/maxAngle**：归一化把速度除掉了，高速小滑移角（响胎）算得很小 ⇒
 *    用户报「高速打方向明显响胎却几乎不振」（响胎窗口 P50 仅 0.94，够不到 1.25）。
 *
 * `sv = v·sin α` 把速度乘回来，正是响胎的物理成因（胎面侧向刮擦速率）。
 * 实测分离度：响胎 19.0 vs 悠闲转弯 5.0 = 3.8×；vs 高速直线 1.3 = 14×。
 * ⚠️ 用 `sin` 而非 `tan`：sin 有界（≤v），α 异常大时不会爆表；且纵向滑移
 * 不进入分子 ⇒ **直线刹车不误触发**（实测刹车窗口 P50=0.5）。
 *
 * ## 用途：**最佳抓地力提示器**，不是失控报警
 * 用户原话：「要的就是过弯感受到 1/4 振感的时候证明过得非常好」。真失控
 * （甩尾/空转/锁死）由 ②③④⑤⑥ 给满振。封顶 1/4 = 规格第 3 条。
 */
static inline float map_lat_max_to_level(float sv) {
    if (sv <= LAT_MIN_Z) return 0.0f;
    if (sv >= LAT_KNEE_Z) return LEVEL_AT_PEAK;
    const float t = (sv - LAT_MIN_Z) / (LAT_KNEE_Z - LAT_MIN_Z);
    return LEVEL_AT_PEAK * powf(t, SLIP_POW);
}

/**
 * **后轮空转通道（最高优先级）**：`z = max(|σ_RL|,|σ_RR|) / maxSlip`，**油门门控**。
 *
 *     z ≤ 2.5           → 0（正常给油，后轮在峰值附近但没空转）
 *     2.5 < z ≤ 6.0     → 0 .. 1（线性，**比纵向通道陡得多**）
 *     z ≥ 6.0           → 1（满振）
 *
 * ## 为什么单独开一条，且阈值比纵向低
 *
 * 用户规格：「油门有输入时**后轮空转的优先级最高**」。这不是审美偏好，
 * 而是驾驶语义——后轮空转 = 牵引力已经用尽 = 车尾随时会甩出去。
 *
 * 它和纵向通道（`max_i σ/maxSlip`，四轮取大）的区别在于**门控 + 陡峭**：
 *   · **门控**：只有 `throttle_on` 才计入 ⇒ 不踩油门时后轮 σ 高是刹车锁死
 *     或噪声，不该进这条；刹车锁死由纵向通道（四轮同锁）负责；
 *   · **陡峭**：纵向通道满振点锚在 σ 饱和点 10（那是"完全锁死"），但空转
 *     的"车尾要出去了"发生在更早——实测高速甩尾帧后轮 z 只有 8.3，按纵向
 *     通道只给 0.79。这条把膝盖提到 2.5、满振提前到 6.0 ⇒ 同一帧给 1.0。
 *
 * ⚠️ **为什么阈值 2.5 不会误触发**：正常给油（含起步、出弯加油）后轮 z
 * 实测 0.5~2.5（车还在滚，σ 只在峰值附近）；而**打滑空转**实测 6~10.6。
 * 2.5 落在两者之间，且因为油门门控，减速/刹车工况根本进不来。
 */
static inline float map_spin_to_level(float z) {
    if (z <= SPIN_KNEE_Z) return 0.0f;
    if (z >= SPIN_FULL_Z) return 1.0f;
    return (z - SPIN_KNEE_Z) / (SPIN_FULL_Z - SPIN_KNEE_Z);
}

/**
 * **横向通道 B：失控侧滑（甩尾）**——输入是**后轮的** `min(|α_RL|,|α_RR|)/maxAngle`，
 * **不封顶**。
 *
 *     a ≤ 3.5           → 0（后轴还咬着地，可能只是推头）
 *     3.5 < a ≤ 5.5     → 0 .. 1（线性）
 *     a ≥ 5.5           → 1（满振）
 *
 * ⚠️ **为什么是"后轮的 min"而不是"全四轮 min"**（第三轮修正）：
 * 转弯时**外侧前轮**的 α 天然低（车头已经转过去了、那只轮在正常滚动），
 * 于是"全四轮 min"会被这只无辜的前轮拉到 1~2，把真正的后轴横滑淹没。
 * 实测同帧 `min_all=1.0` 而 `min_rear=5.2` 的例子比比皆是。
 * **甩尾在物理上就是后轴现象**——后轴两只轮同时大幅横滑才是失控。
 *
 * ⚠️ **为什么不会误触发**：
 *   · 普通过弯：后轮 min α 0.4~1.0（后轮老实跟随）⇒ 静默；
 *   · 推头：后轮 min α 1.5~3.1（后轴还抓着）⇒ 静默，只由通道 A 给 1/4；
 *   · 低速打舵假象：只推高**前轮** α（后轮 `v·sin(δ)`≈0）⇒ 本通道天然免疫。
 */
static inline float map_lat_rear_to_level(float a) {
    if (a <= LAT_REAR_KNEE_Z) return 0.0f;
    if (a >= LAT_REAR_FULL_Z) return 1.0f;
    return (a - LAT_REAR_KNEE_Z) / (LAT_REAR_FULL_Z - LAT_REAR_KNEE_Z);
}

/**
 * **第五通道：车身侧滑角 β**（2026-10-03）。
 *
 * β = atan2(|bodyVelocity.x|, |bodyVelocity.z|)（度）—— 车头指向与**实际行进
 * 方向**的夹角。它度量的是"车在横着走"，与油门、轮速、轮胎滑移全部无关，
 * 因此能测到前四通道集体失明的那段：**松油门后车仍在惯性打转**。
 *
 * 为什么必须有它（逐帧实测，见常量区）：打转中 β 冲到 62~90°，而同时
 * 后轮空转 σ 被油门闸门关掉、纵向 σ 在衰减、后轮 α 只有 1~2、前轮 α 被封顶
 * 0.25 ⇒ 电平在 β 峰值处跌到 0.23。**最强的失控瞬间给出最弱的反馈**，这正是
 * 用户报的「松油门之后继续惯性打转过程中振感很弱」。
 *
 * ⚠️ **必须由调用方先做速度门控**（v_mag > BETA_MIN_SPEED）——β 是比值，
 * v→0 时纯噪声也能算出 80°+。门控放在 tick 里，因为要用 `v_mag` 而非
 * `carSpeed`（后者是车体坐标系的**前进分量**，横过来时趋 0）。
 */
static inline float map_beta_to_level(float beta_deg) {
    if (beta_deg <= BETA_MIN_Z) return 0.0f;
    if (beta_deg >= BETA_FULL_Z) return 1.0f;
    return (beta_deg - BETA_MIN_Z) / (BETA_FULL_Z - BETA_MIN_Z);
}

/**
 * **第六通道：四轮集体饱和**（2026-10-03）。
 *
 * `mean4` = 四轮利用率（`max(纵向z, 横向z)`）的均值。它回答的是"**整车**是不是
 * 整体贴着摩擦圆极限在走"，而不是"哪一只轮失控了"。
 *
 * 为什么必须有它：高速宽滑时四只轮**均匀地**用到 3~4 倍峰值，没有任何一条
 * 通道爆表 ⇒ 前五通道集体只给中低电平（实测 12:35:55.889：前α=4.18、后α=3.95、
 * 纵向=2.31，每条都"差一点"，电平只有 0.63）。
 *
 * ⚠️ 与 β 通道的分工：β 测"车横着走"（方向），本条测"四轮都用尽"（大小）。
 * 两者独立 —— 高速宽滑 β 只有 42°（够不到满振），但 mean4 已到 3.0。
 *
 * ⚠️ 门控同 β：低速时轮胎量不可信（maxSlip/maxAngle 分母在地板附近）。
 */
static inline float map_sat_to_level(float mean4) {
    if (mean4 <= SAT_MEAN_KNEE) return 0.0f;
    if (mean4 >= SAT_MEAN_FULL) return 1.0f;
    return (mean4 - SAT_MEAN_KNEE) / (SAT_MEAN_FULL - SAT_MEAN_KNEE);
}

/**
 * 探针：累积本窗统计量，窗口满时落一组日志。
 *
 * 输出七行，**标定所需的全部信息都在里面**（每约 3 秒一组）：
 *
 * 行 1 `agg(...)` —— 聚合量与判据表现：
 *   - `hist`：**纵向** z 的分布（档宽 = FULL_Z/10 = 0.35）
 *   - `maxLon` / `maxLat`：两通道各自的峰值（单位都是"该轮峰值 = 1"）
 *   - `maxSlip`：本窗 `|σ|`（归一化滑移）的峰值——**与 maxLon 同轴对照**，
 *     用来确认"高电平是纵向真打滑给的，不是分母太小"
 *   - `speed`：本窗车速峰值——低速假象的诊断锚点（若 maxLat 高而 speed 低，
 *     就是 atan 假象而不是真滑移）
 *   - `trig/gated`：纵向越过起振点的帧数与被门控掉的帧数
 *
 * 行 2 `wheels(...)` —— 逐轮法向载荷 Fn：
 *   确认"是哪只轮在受力"。后轮单侧空转时应看到某一只明显偏低（举升）。
 *
 * 行 3 `split(...)` —— 逐轮的纵向/横向归一化分量峰值（各 4 个数）：
 *   同一个电平可能来自纯纵向（锁死）或纯横向（响胎过弯），分开记才能验证
 *   "两种工况确实落在设计的两个通道里"。
 *
 * 行 4/5 `raw(...)` —— maxSlip/maxAngle 与 |σ|/|α| 的**原始值**峰值。
 *
 * 行 7 `diag(...)` —— 12 个候选信号源 × 4 轮（见 [PROBE_DIAG_SLOTS] 的清单）。
 */
static void probe_sample(float z_lon, float z_lat, float z_lat_rear,
                         float z_spin, int throttle_on, int gated,
                         float speed, float beta_deg, float beta_level,
                         float sat_level, float level,
                         float raw_slip, const float *norm_slip,
                         const float *norm_angle, const float *fn,
                         const float *raw, const float *diag) {
    if (!g_probe_enabled) return;

    // 逐帧序列（**先于** gated 分支，保证序列与物理帧一一对齐）：
    // 每帧存 β、各子通道电平、最终电平，窗末打多行。分辨率 = 50Hz。
    {
        uint32_t fi = g_probe_frame;
        if (fi < PROBE_INTERVAL_FRAMES) {
            g_probe_series_beta[fi] = beta_deg;
            g_probe_series_blev[fi] = beta_level;
            g_probe_series_slev[fi] = sat_level;
            g_probe_series_lvl[fi] = level;
        }
    }

    if (gated) {
        g_probe_gated_frames++;
    } else {
        if (z_lon > g_probe_max_lon) g_probe_max_lon = z_lon;
        if (z_lat > g_probe_max_lat) g_probe_max_lat = z_lat;
        if (z_lat_rear > g_probe_max_rear) g_probe_max_rear = z_lat_rear;
        if (z_spin > g_probe_max_spin) g_probe_max_spin = z_spin;
        if (throttle_on) g_probe_throttle_frames++;
        if (raw_slip > g_probe_max_slip) g_probe_max_slip = raw_slip;
        if (speed > g_probe_max_speed) g_probe_max_speed = speed;
        // 车身侧滑角：β 只对**在动**的车有意义（停稳时 atan2(0,0)=0 无歧义，
        // 但车头任意朝向的静止车噪声会污染首帧值），故只在车速 > 0.5 m/s 时采。
        if (speed > 0.5f) {
            if (beta_deg > g_probe_max_beta) g_probe_max_beta = beta_deg;
            if (g_probe_first_beta < 0.0f) g_probe_first_beta = beta_deg;
            int bb = (int) (beta_deg / (90.0f / PROBE_BETA_BINS));
            if (bb < 0) bb = 0;
            if (bb >= PROBE_BETA_BINS) bb = PROBE_BETA_BINS - 1;
            g_probe_beta_hist[bb]++;
        }
        const float bin_w = FULL_Z / PROBE_U_BINS;   // 0.35
        int bin = (int) (z_lon / bin_w);
        if (bin < 0) bin = 0;
        if (bin >= PROBE_U_BINS) bin = PROBE_U_BINS - 1;
        g_probe_hist[bin]++;
        if (z_lon > MIN_Z) g_probe_triggered++;
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (norm_slip[i] > g_probe_peak_norm_slip[i]) {
                g_probe_peak_norm_slip[i] = norm_slip[i];
            }
            if (norm_angle[i] > g_probe_peak_norm_angle[i]) {
                g_probe_peak_norm_angle[i] = norm_angle[i];
            }
            if (fn[i] > g_probe_peak_fn[i]) g_probe_peak_fn[i] = fn[i];
            for (int k = 0; k < 4; k++) {
                const float v = raw[i * 4 + k];
                if (v > g_probe_raw_max[i * 4 + k]) {
                    g_probe_raw_max[i * 4 + k] = v;
                }
            }
            for (int k = 0; k < PROBE_DIAG_SLOTS; k++) {
                const float dv = diag[i * PROBE_DIAG_SLOTS + k];
                if (dv > g_probe_diag_max[i * PROBE_DIAG_SLOTS + k]) {
                    g_probe_diag_max[i * PROBE_DIAG_SLOTS + k] = dv;
                }
            }
        }
    }

    if (++g_probe_frame < PROBE_INTERVAL_FRAMES) return;

    // 直方图字符串化：档数已提到 20（档宽 0.5），逐档写进静态缓冲
    // （探针是冷路径，3 秒一次；静态缓冲避免栈上大数组）。
    static char hist_buf[PROBE_U_BINS * 8 + 1];
    {
        int p = 0;
        for (int i = 0; i < PROBE_U_BINS && p < (int) sizeof(hist_buf) - 8; i++) {
            p += snprintf(hist_buf + p, sizeof(hist_buf) - (size_t) p,
                          i == 0 ? "%u" : ",%u", g_probe_hist[i]);
        }
        hist_buf[p] = '\0';
    }
    NLOGI("SLIPprobe: agg hist=[%s] binW=%.2f "
          "maxLon=%.3f maxLat=%.3f maxRearMin=%.3f maxRearZ=%.3f "
          "maxSlip=%.4f speed=%.1f betaMax=%.1f betaFirst=%.1f "
          "thr=%u trig=%u gated=%u",
          hist_buf, FULL_Z / PROBE_U_BINS,
          g_probe_max_lon, g_probe_max_lat, g_probe_max_rear, g_probe_max_spin,
          g_probe_max_slip, g_probe_max_speed,
          g_probe_max_beta, g_probe_first_beta,
          g_probe_throttle_frames, g_probe_triggered, g_probe_gated_frames);
    // β 分布（0~90°，档宽 3°）——标定车身侧滑通道门槛的直接依据。
    {
        static char beta_buf[PROBE_BETA_BINS * 8 + 1];
        int p = 0;
        for (int i = 0; i < PROBE_BETA_BINS && p < (int) sizeof(beta_buf) - 8; i++) {
            p += snprintf(beta_buf + p, sizeof(beta_buf) - (size_t) p,
                          i == 0 ? "%u" : ",%u", g_probe_beta_hist[i]);
        }
        beta_buf[p] = '\0';
        NLOGI("SLIPprobe: betaHist binW=%.0fdeg [%s]",
              90.0f / PROBE_BETA_BINS, beta_buf);
    }
    // 逐帧序列（50Hz）：β / 侧滑电平 / 饱和电平 / 最终电平，各一行。
    {
        static char sb[PROBE_INTERVAL_FRAMES * 7 + 1];
        static char sbl[PROBE_INTERVAL_FRAMES * 6 + 1];
        static char ssl[PROBE_INTERVAL_FRAMES * 6 + 1];
        static char sl[PROBE_INTERVAL_FRAMES * 6 + 1];
        int pb = 0, pbl = 0, psl = 0, pl = 0;
        for (uint32_t i = 0; i < PROBE_INTERVAL_FRAMES; i++) {
            if (pb < (int) sizeof(sb) - 8)
                pb += snprintf(sb + pb, sizeof(sb) - (size_t) pb,
                               i == 0 ? "%.0f" : ",%.0f", g_probe_series_beta[i]);
            if (pbl < (int) sizeof(sbl) - 8)
                pbl += snprintf(sbl + pbl, sizeof(sbl) - (size_t) pbl,
                                i == 0 ? "%.2f" : ",%.2f", g_probe_series_blev[i]);
            if (psl < (int) sizeof(ssl) - 8)
                psl += snprintf(ssl + psl, sizeof(ssl) - (size_t) psl,
                                i == 0 ? "%.2f" : ",%.2f", g_probe_series_slev[i]);
            if (pl < (int) sizeof(sl) - 8)
                pl += snprintf(sl + pl, sizeof(sl) - (size_t) pl,
                               i == 0 ? "%.2f" : ",%.2f", g_probe_series_lvl[i]);
        }
        sb[pb] = '\0'; sbl[pbl] = '\0'; ssl[psl] = '\0'; sl[pl] = '\0';
        NLOGI("SLIPprobe: seqBeta(deg) [%s]", sb);
        NLOGI("SLIPprobe: seqBetaLvl   [%s]", sbl);
        NLOGI("SLIPprobe: seqSatLvl    [%s]", ssl);
        NLOGI("SLIPprobe: seqLevel     [%s]", sl);
    }
    NLOGI("SLIPprobe: wheels fn=[%.0f,%.0f,%.0f,%.0f]",
          g_probe_peak_fn[0], g_probe_peak_fn[1], g_probe_peak_fn[2],
          g_probe_peak_fn[3]);
    NLOGI("SLIPprobe: split normSlip=[%.2f,%.2f,%.2f,%.2f] "
          "normAngle=[%.2f,%.2f,%.2f,%.2f]",
          g_probe_peak_norm_slip[0], g_probe_peak_norm_slip[1],
          g_probe_peak_norm_slip[2], g_probe_peak_norm_slip[3],
          g_probe_peak_norm_angle[0], g_probe_peak_norm_angle[1],
          g_probe_peak_norm_angle[2], g_probe_peak_norm_angle[3]);
    NLOGI("SLIPprobe: raw maxSlip=[%.4f,%.4f,%.4f,%.4f] "
          "maxAngle=[%.4f,%.4f,%.4f,%.4f]",
          g_probe_raw_max[0], g_probe_raw_max[4], g_probe_raw_max[8],
          g_probe_raw_max[12], g_probe_raw_max[1], g_probe_raw_max[5],
          g_probe_raw_max[9], g_probe_raw_max[13]);
    NLOGI("SLIPprobe: raw sigma=[%.4f,%.4f,%.4f,%.4f] "
          "alpha=[%.4f,%.4f,%.4f,%.4f]",
          g_probe_raw_max[2], g_probe_raw_max[6], g_probe_raw_max[10],
          g_probe_raw_max[14], g_probe_raw_max[3], g_probe_raw_max[7],
          g_probe_raw_max[11], g_probe_raw_max[15]);
    // 候选信号源清单（第九轮）：**12 个量 × 4 轮全打**，一轮跑完就能定下一轮锚谁。
    // 布局见 PROBE_DIAG_SLOTS；这里按列（量）展开成 4 轮一组，便于横向对比前后轮。
    {
        const float *g = g_probe_diag_max;
#define DIAG_K(k) \
        g[(k)], g[PROBE_DIAG_SLOTS + (k)], g[PROBE_DIAG_SLOTS * 2 + (k)], \
        g[PROBE_DIAG_SLOTS * 3 + (k)]
        NLOGI("SLIPprobe: diag " PROBE_DIAG_NAMES " | 轮序 FL,FR,RL,RR");
        NLOGI("SLIPprobe: diagv angVel=[%.1f,%.1f,%.1f,%.1f] "
              "slipVelo=[%.2f,%.2f,%.2f,%.2f] radius=[%.3f,%.3f,%.3f,%.3f] "
              "sClamp=[%.2f,%.2f,%.2f,%.2f]",
              DIAG_K(0), DIAG_K(1), DIAG_K(2), DIAG_K(3));
        NLOGI("SLIPprobe: diagv hitFwd=[%.3f,%.3f,%.3f,%.3f] "
              "hitSide=[%.3f,%.3f,%.3f,%.3f] hitForce=[%.0f,%.0f,%.0f,%.0f]",
              DIAG_K(4), DIAG_K(5), DIAG_K(6));
        NLOGI("SLIPprobe: diagv Fx=[%.0f,%.0f,%.0f,%.0f] Fy=[%.0f,%.0f,%.0f,%.0f] "
              "grip=[%.2f,%.2f,%.2f,%.2f]",
              DIAG_K(7), DIAG_K(8), DIAG_K(9));
        NLOGI("SLIPprobe: diagv unitSlip=[%.4f,%.4f,%.4f,%.4f] "
              "unitAngle=[%.4f,%.4f,%.4f,%.4f]",
              DIAG_K(10), DIAG_K(11));
#undef DIAG_K
    }

    g_probe_frame = 0;
    g_probe_total += PROBE_INTERVAL_FRAMES;
    g_probe_windows++;

    for (int i = 0; i < PROBE_U_BINS; i++) g_probe_hist[i] = 0;
    g_probe_max_lon = 0.0f;
    g_probe_max_lat = 0.0f;
    g_probe_max_rear = -1.0f;
    g_probe_max_spin = 0.0f;
    g_probe_throttle_frames = 0;
    g_probe_max_speed = 0.0f;
    g_probe_max_beta = 0.0f;
    g_probe_first_beta = -1.0f;
    for (int i = 0; i < PROBE_BETA_BINS; i++) g_probe_beta_hist[i] = 0;
    g_probe_triggered = 0;
    g_probe_gated_frames = 0;
    for (int i = 0; i < WHEEL_COUNT; i++) {
        g_probe_peak_norm_slip[i] = 0.0f;
        g_probe_peak_norm_angle[i] = 0.0f;
        g_probe_peak_fn[i] = 0.0f;
    }
    for (int i = 0; i < WHEEL_COUNT * 4; i++) g_probe_raw_max[i] = 0.0f;
    for (int i = 0; i < WHEEL_COUNT * PROBE_DIAG_SLOTS; i++) g_probe_diag_max[i] = 0.0f;

    if (g_probe_total >= PROBE_MAX_FRAMES) {
        g_probe_enabled = 0;
        NLOGI("slip_feedback: probe auto-off after %d frames (%d windows) —— "
              "导出 ala_tool_native.log 即可复核标定",
              g_probe_total, g_probe_windows);
    }
}

// 把一帧的电平推入样本环。**所有出口都要走它**（含 early-return 的 0），
// 否则 Java 侧按 seq 对齐时会缺帧、波形出现空洞。
static inline void slip_ring_push(float level) {
    const uint32_t s = g_ring_seq;
    g_ring[s & SLIP_RING_MASK] = level;
    g_ring_seq = s + 1;
}

void slip_feedback_tick(void *car_inputs) {
    if (!g_enabled && !g_probe_enabled) {
        g_level = 0.0f;
        slip_ring_push(0.0f);
        return;
    }
    if (car_inputs == NULL) {
        g_level = 0.0f;
        slip_ring_push(0.0f);
        return;
    }

    float level = 0.0f;

    // ── 整车级门控 ────────────────────────────────────────────────────────
    const uint8_t all_off =
        *(uint8_t *) ((uintptr_t) car_inputs + OFF_CAR_ALL_OFF_GROUND);
    float car_speed = *(float *) ((uintptr_t) car_inputs + OFF_CAR_SPEED);
    if (car_speed != car_speed) car_speed = 0.0f;   // NaN 自比不等
    if (car_speed < 0.0f) car_speed = -car_speed;

    // ⚠️ **车速门控整体删除**（2026-09-21 第三轮）。前两轮分别用 2.0 / 0.5 m/s，
    // 都挡掉了「顶着墙踩死油门疯狂空转烧胎」——车不动 ⇒ carSpeed≈0 ⇒ 直接判负，
    // 而用户明确要求那种工况有反馈（两轮原话都是"不会振"）。
    //
    // 删掉是安全的，因为"静止"本身会让 σ 和 α 双双归零：
    //   σ 来自 SlipRatio(radius, wMagnitud)，轮不转、车不动 ⇒ 0；
    //   α = atan(LV.x / max(LV.z,1))·(180/π)，LVelocity≈0 ⇒ atan(0)=0。
    // 所以"停着 + 轮子转了个角度"天然输出 0，不需要车速门控兜。
    // 唯一残留风险是 NaN/未初始化字段，已由下面的逐字段 NaN 自比处理。
    const int car_ok = !all_off;

    // ── 油门输入（**后轮空转通道的优先闸门**，2026-10-02 第三轮）─────────
    // 用户规格：「油门有输入时后轮空转的优先级最高」。
    //
    // 物理依据：后轮由动力总成驱动，**只有踩油门才会空转**。不踩油门时后轮
    // 的 σ 高只可能是刹车锁死（四轮同锁）或测量噪声。用输入做闸门可以把
    // 「后轮独转」从「刹车锁死」和「滑行中单轮离地」里干净地分出来。
    //
    // ⚠️ 读的是 `_inputTorque`(0x174) / `_brake`(0x178) —— **原始输入**，
    // 正是 pedal_hook.c 写入的那两个字段（`throttle_field_offset` /
    // `brake_field_offset`）。不读 `actualInputTorque`(0x16C)（那是游戏
    // 处理后的结果，含 TC 截断，会把"踩了但被 TC 压掉"误判成没踩）。
    const float throttle_in =
        *(float *) ((uintptr_t) car_inputs + OFF_CAR_THROTTLE_INPUT);
    const float brake_in =
        *(float *) ((uintptr_t) car_inputs + OFF_CAR_BRAKE_INPUT);
    // 门限取 0.08：踏板本身有死区，且游戏在某些 HUD 状态下会写极小残值。
    const int throttle_on = (throttle_in == throttle_in) && (throttle_in > 0.08f);
    const int braking = (brake_in == brake_in) && (brake_in > 0.08f);

    // ── 车身侧滑角（**第五通道**，2026-10-03）────────────────────────────
    // β = atan2(|vx|, |vz|)（车体坐标系，度）。见 map_beta_to_level 的完整论证。
    // ⚠️ 速度门控用 **模长 sqrt(vx²+vz²)**，**不是** carSpeed —— 后者是
    // `|bodyVelocity.z|`（前进分量），车横过来时趋 0，用它门控会在最需要
    // 这条通道的瞬间把它关掉（反汇编实证，见常量区）。
    const float bvx = *(float *) ((uintptr_t) car_inputs + OFF_CAR_BODY_VEL_X);
    const float bvz = *(float *) ((uintptr_t) car_inputs + OFF_CAR_BODY_VEL_Z);
    const float v_mag = (bvx == bvx && bvz == bvz) ? sqrtf(bvx * bvx + bvz * bvz) : 0.0f;
    const float beta_deg =
        (v_mag > 0.0f) ? (float) (atan2(f_abs(bvx), f_abs(bvz)) * 57.29577951308232)
                       : 0.0f;

    // ── 逐轮两通道，各取最大值 ───────────────────────────────────────────
    // ⚠️ 这两个是 **z 分数**（= 轮胎利用率，1.0 = 该轮此刻的轮胎峰值），
    // **不是**电平。映射交给 map_lon_to_level / map_lat_*_to_level。
    float z_lon = 0.0f;
    float z_lat = 0.0f;        // max_i（用尽抓地力：推头/响胎）——单位 m/s，见常量区
    float z_lat_rear = -1.0f;  // 后轮 min_i（失控甩尾）——见 map_lat_rear_to_level
    float z_spin_rear = 0.0f;  // 后轮 z 峰值（后轮空转）——见 map_spin_to_level
    float z_alpha = 0.0f;      // 四轮 |slipAngle| 最大值（**度**，sv 的分子）
    float util_sum = 0.0f;     // 四轮利用率之和（见 map_sat_to_level）
    int   util_n = 0;          // 参与累加的轮数（离地轮不计）
    float raw_slip_peak = 0.0f;   // 探针用：归一化滑移 s 的峰值
    int gated = 0;
    // 探针用的逐轮分量（未触发探针时不填，省 4 轮的开销）。
    float norm_slip[WHEEL_COUNT] = {0.0f, 0.0f, 0.0f, 0.0f};
    float norm_angle[WHEEL_COUNT] = {0.0f, 0.0f, 0.0f, 0.0f};
    float fn[WHEEL_COUNT] = {0.0f, 0.0f, 0.0f, 0.0f};
    float probe_raw[WHEEL_COUNT * 4];
    float probe_diag[WHEEL_COUNT * PROBE_DIAG_SLOTS];

    if (car_ok) {
        void *wheels = *(void **) ((uintptr_t) car_inputs + OFF_CAR_WHEELS);
        if (wheels != NULL) {
            const int n = *(int *) ((uintptr_t) wheels + OFF_IL2CPP_ARRAY_LEN);
            if (n > 0) {
                const int count = n < WHEEL_COUNT ? n : WHEEL_COUNT;
                for (int i = 0; i < count; i++) {
                    void *w = *(void **) ((uintptr_t) wheels
                                          + OFF_IL2CPP_ARRAY_DATA + i * 8);
                    if (w == NULL) continue;
                    const uintptr_t wp = (uintptr_t) w;

                    // 逐轮地面接触门控：normalForce 在 ComputeWheelPhysics 的
                    // 离地支被显式清零（0x1a7e8e0），是最干净的判据。
                    const float load =
                        *(float *) (wp + OFF_WHEEL_NORMAL_FORCE);
                    if (load != load || load <= 0.0f) continue;

                    // 轮胎峰值。载荷自适应（UpdateMaxSlips 每帧刷新），必须
                    // 每帧重读——它是"当前这只轮在这一刻的极限"。
                    float peak_s = *(float *) (wp + OFF_WHEEL_MAX_SLIP);
                    float peak_a = *(float *) (wp + OFF_WHEEL_MAX_ANGLE);
                    if (peak_s != peak_s || peak_s < PEAK_FLOOR) {
                        peak_s = PEAK_FLOOR;
                    }
                    if (peak_a != peak_a || peak_a < PEAK_FLOOR) {
                        peak_a = PEAK_FLOOR;
                    }

                    // ── 归一化滑移/滑移角（2026-09-22 第八轮，量纲闭合）──
                    //
                    // ⚠️ 前七轮的病根是**量纲没对上**。反汇编 `SlipRatio`
                    // (0x1a7da24) 全函数后确认：`slipRatio`(0x104) 是那个函数
                    // 的**输出**，函数内部已经做过一次归一化——
                    //
                    //     s = min(1, ω_norm/8) · clamp(−LV.z / max(wMagnitud,1), ≥0)
                    //
                    // 所以 `slipRatio / maxSlip` 是**双重归一化**（ψ(σ)/ψ_peak），
                    // 锁死只有 10、普通起步 3~7，两者差不到一个数量级 ⇒ 不管
                    // 怎么调映射，纵向都拉不开。改用 `|σ| / maxSlip` 作为
                    // **与 maxSlip 同量纲**的量（上限 ≈ 10），锁死与起步自然
                    // 差 3 倍以上。
                    //
                    // ⚠️ 第四轮试过的"滑移速度比"彻底失败（LV.z 坐标系反推
                    // 得 ≈0 ⇒ 退化常数），已永久放弃：**不再自己重算物理量，
                    // 只用游戏现成字段**。
                    float sigma = *(float *) (wp + OFF_WHEEL_SLIP_RATIO);
                    float alpha = *(float *) (wp + OFF_WHEEL_SLIP_ANGLE);
                    if (sigma != sigma) sigma = 0.0f;
                    if (alpha != alpha) alpha = 0.0f;
                    if (sigma < 0.0f) sigma = -sigma;
                    if (alpha < 0.0f) alpha = -alpha;

                    // s / a = "峰值的多少倍"（1.0 = 该轮此刻的轮胎峰值）。
                    // ⚠️ **不要**再除以 slipRatioClamp(0x35C)——那会把量程压到
                    // ≤1/1.05，full_z 永远够不到（见文件头常量区的推导）。
                    const float s = sigma / peak_s;
                    const float a = alpha / peak_a;

                    // ⚠️ 横向 max 通道的信号源是 `sv = v·sin(α_max)`，**在循环
                    // 结束后**由 z_alpha 与 v_mag 合成（见下方"横向 max 通道"段）
                    // ——循环内只累计 α 峰值。这里不再引入新信号源：
                    // `|Fy|/(μ·Fn)` 与 `α/maxAngle` 两版都已被实测证伪（见常量区）。
                    const float fyv = *(float *) (wp + OFF_WHEEL_FY);

                    if (s > z_lon) z_lon = s;
                    if (alpha > z_alpha) z_alpha = alpha;
                    // 后轮 min_i：只统计 i>=2（后轴）。甩尾在物理上是后轴现象，
                    // 用"全四轮 min"会被外侧前轮的低 α 淹没（见 map_lat_rear_to_level）。
                    if (i >= 2 && (z_lat_rear < 0.0f || a < z_lat_rear)) {
                        z_lat_rear = a;
                    }
                    // 后轮 σ 峰值：**油门有输入 且 该轮真的在打滑** 时才计入。
                    // 不踩油门时后轮 σ 高 = 刹车锁死或测量噪声，不该进这条通道；
                    // 轮子门限用 slipVelo（**不是车速**，见常量区）——原地烧胎时
                    // carSpeed 恒 0 而轮子在狂转，用车速门控会把整段烧胎挡死。
                    const float wslip = *(float *) (wp + OFF_WHEEL_SLIP_VELO);
                    if (i >= 2 && throttle_on &&
                        wslip == wslip && wslip > SPIN_MIN_SLIP_VELO &&
                        s > z_spin_rear) {
                        z_spin_rear = s;
                    }
                    if (s > raw_slip_peak) raw_slip_peak = s;
                    // 四轮利用率：本轮"最吃紧的那个方向"用到几成峰值。
                    // 见 map_sat_to_level（第六通道）。
                    util_sum += (s > a ? s : a);
                    util_n++;

                    if (g_probe_enabled) {
                        const int d = i * PROBE_DIAG_SLOTS;
                        norm_slip[i] = s;
                        norm_angle[i] = a;
                        fn[i] = load;
                        probe_raw[i * 4 + 0] = peak_s;
                        probe_raw[i * 4 + 1] = peak_a;
                        probe_raw[i * 4 + 2] = sigma;
                        probe_raw[i * 4 + 3] = alpha;
                        // ── 候选信号源清单（第九轮，见 PROBE_DIAG_SLOTS 的说明）──
                        float av = *(float *) (wp + OFF_WHEEL_ANGULAR_VELO);
                        float svel = *(float *) (wp + OFF_WHEEL_SLIP_VELO);
                        float rr = *(float *) (wp + OFF_WHEEL_RADIUS);
                        float cv = *(float *) (wp + OFF_WHEEL_SLIP_CLAMP);
                        float fwd = *(float *) (wp + OFF_HIT_FORWARD_SLIP);
                        float sid = *(float *) (wp + OFF_HIT_SIDEWAYS_SLIP);
                        float hf = *(float *) (wp + OFF_HIT_FORCE);
                        float fx = *(float *) (wp + OFF_WHEEL_FX);
                        float gr = *(float *) (wp + OFF_WHEEL_GRIP);
                        float us = *(float *) (wp + OFF_WHEEL_UNIT_SLIP);
                        float ua = *(float *) (wp + OFF_WHEEL_UNIT_ANGLE);
                        // 探针只取绝对值峰值（符号对我们没用）；NaN 归零。
                        float *o = probe_diag + d;
                        o[0]  = f_abs(av);    // angularVelocity (rad/s)
                        o[1]  = f_abs(svel);  // slipVelo（疑似=滑移速度模长）
                        o[2]  = f_abs(rr);    // radius (m)
                        o[3]  = f_abs(cv);    // slipRatioClamp（前 0.55 / 后 1.50）
                        o[4]  = f_abs(fwd);   // WheelHit.forwardSlip（PhysX 原始）
                        o[5]  = f_abs(sid);   // WheelHit.sidewaysSlip（PhysX 原始）
                        o[6]  = f_abs(hf);    // WheelHit.force
                        o[7]  = f_abs(fx);    // Fx
                        o[8]  = f_abs(fyv);   // Fy（侧向力，仅供诊断——不再是通道 A 的分子）
                        o[9]  = f_abs(gr);    // grip (μ)
                        o[10] = f_abs(us);    // unitSlip（游戏自算）
                        o[11] = f_abs(ua);    // unitAngle（游戏自算）
                    }
                }
            }
        }

        // ── 横向 max 通道：合成侧向滑移速度 sv = v·sin(α_max) ────────────
        // 必须在轮循环**之后**算（要 z_alpha 的峰值），且要 v_mag 而不是
        // carSpeed——见常量区：carSpeed = |bodyVelocity.z|，车横过来时趋 0。
        // v_mag 已在上面算好（第五通道 β 用同一个）。
        {
            const float rad = z_alpha * 0.017453292519943295f;  // 度 → 弧度
            z_lat = v_mag * sinf(rad);
            if (z_lat != z_lat || z_lat < 0.0f) z_lat = 0.0f;   // NaN / 负值兜底
        }

        if (z_lon <= 0.0f && z_lat <= 0.0f) gated = 1;  // 两通道皆空 ⇒ 无效帧
    } else {
        gated = 1;
    }

    // ── 通道各自映射，取大（**不是 hypot 合成**，理由见文件头常量区）────
    // 纵向：打滑（锁死/空转）可满振。
    // 横向两条：A = max_i（用尽抓地力，封顶 1/4）、B = min_i（失控侧滑，可满振）。
    const float lon_level = map_lon_to_level(z_lon);
    const float lat_max_level = map_lat_max_to_level(z_lat);
    const float lat_rear_level =
        z_lat_rear < 0.0f ? 0.0f : map_lat_rear_to_level(z_lat_rear);
    // 后轮空转：**油门有输入时优先级最高**（用户规格）。因为 z_spin_rear 已由
    // throttle_on 门控（不踩油门恒 0），这里直接取大即可。
    const float spin_level = map_spin_to_level(z_spin_rear);
    // 车身侧滑：**只在车确实在动时**参与（低速下 β 是噪声，见常量区）。
    const float beta_level =
        (v_mag > BETA_MIN_SPEED) ? map_beta_to_level(beta_deg) : 0.0f;
    // 四轮集体饱和：mean4 = 四轮利用率均值（离地轮不计入分母）。
    const float mean4 = util_n > 0 ? util_sum / (float) util_n : 0.0f;
    const float sat_level =
        (v_mag > SAT_MIN_SPEED) ? map_sat_to_level(mean4) : 0.0f;
    float best = lon_level;
    if (lat_max_level > best) best = lat_max_level;
    if (lat_rear_level > best) best = lat_rear_level;
    if (spin_level > best) best = spin_level;
    if (beta_level > best) best = beta_level;
    if (sat_level > best) best = sat_level;
    level = g_enabled ? best : 0.0f;
    g_level = level;
    slip_ring_push(level);

    probe_sample(z_lon, z_lat, z_lat_rear, z_spin_rear, throttle_on, gated,
                 car_speed, beta_deg, beta_level, sat_level, level, raw_slip_peak,
                 norm_slip, norm_angle, fn, probe_raw, probe_diag);

    // 首次 tick 心跳：本函数只在 `is_target_player_car` 白名单内被调用，
    // 也就是**玩家车上场后**才跑。没这行的话"探针一行不输出"有两种可能——
    // 玩家车还没加载（正常）或链路断了（bug）——分不清。
    static int heartbeat_done = 0;
    if (!heartbeat_done) {
        heartbeat_done = 1;
        NLOGI("slip_feedback: first tick on player car (enabled=%d probe=%d) "
              "carSpeed=%.2f allOffGround=%d s=%.3f a=%.3f level=%.3f",
              g_enabled, g_probe_enabled, car_speed, (int) all_off,
              z_lon, z_lat, level);
    }
}
