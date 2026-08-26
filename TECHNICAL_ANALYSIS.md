# Ala Mobile 技术解析

> 对 Ala Mobile（com.Vince.AlamobileFormula）游戏引擎机制的逆向分析文档。
>
> 分析基于 IL2CPP dump（v8.0.4 / versionCode 200146）的静态字段布局、方法签名，
> 以及模块 native hook 的运行时验证。证据来源标注规则：
> - **[V]** = 已验证（dump.cs 字段定义 / native 运行时验证）
> - **[?]** = 推断（基于字段名和类型推断，未反汇编验证）
> - **[X]** = 已证伪

---

## 目录

- [ABS 防抱死制动系统](#abs-防抱死制动系统)
  - [1. 两层控制架构](#1-两层控制架构)
  - [2. 调用链](#2-调用链)
  - [3. 触发条件](#3-触发条件)
  - [4. 执行机制](#4-执行机制)
  - [5. 速度区间控制](#5-速度区间控制)
  - [6. 差速锁 ABS](#6-差速锁-abs)
  - [7. 刹车偏置系统](#7-刹车偏置系统)
  - [8. ABS / TC / ESC 三位一体](#8-abs--tc--esc-三位一体)
  - [9. 轮胎模型：Pacejka 魔术公式](#9-轮胎模型pacejka-魔术公式)
  - [10. 多线程轮子物理](#10-多线程轮子物理)
  - [11. 模块当前的 ABS 控制方式](#11-模块当前的-abs-控制方式)
  - [12. 证据置信度](#12-证据置信度)
  - [13. 改进启示](#13-改进启示)

---

## ABS 防抱死制动系统

### 1. 两层控制架构

游戏使用**车辆级 + 轮子级**两层 ABS 控制。

#### 车辆级 — `IRDSCarControllInput` (TypeDefIndex: 328→332)

负责 ABS 的全局门控和策略层。

| 字段 | 偏移 | 类型 | 说明 | 置信度 |
|---|---|---|---|---|
| `ABSSlip` | 0x30 | float | 滑移率阈值 | [V] |
| `absEnable` | 0xC4 | bool | 全局 ABS 开关 | [V] |
| `absTriggered` | 0xC8 | bool | ABS 已触发标志 | [V] |
| `overrideBrake` | 0x169 | bool | 覆盖刹车标志 | [V] |
| `_brake` | 0x178 | float | 刹车输入值 | [V] |
| `actualBrake` | 0x170 | float | 实际刹车值 | [V] |
| `speedCutoff` | 0x19C | float | 速度截止 | [V] |
| `tempRearBrakeBalancerSpeed` | 0x1A0 | float | 后刹平衡器速度 | [V] |
| `kP` | 0x1A4 | float | PID 比例增益 | [V] |
| `wheels` | 0x28 | IRDSWheel[] | 轮子数组（4 个） | [V] |

关键方法：

| 方法 | RVA | 说明 | 置信度 |
|---|---|---|---|
| `FixedUpdate()` | 0x1A64524 | 物理帧入口（50Hz） | [V] |
| `carController()` | 0x1A645CC | ABS 逻辑被内联于此 | [V] |
| `HandleABS()` | 0x1A65258 | **被内联到 carController，不单独调用** | [V] |
| `ApplyDiffLockABS(left, right, strength)` | 0x1A653D0 | 差速锁 ABS | [V] |
| `MoveBrakeBias(towardFront)` | 0x1A64CA8 | 动态调节前后刹车偏置 | [V] |

#### 轮子级 — `IRDSWheel` (TypeDefIndex: 367)

每个轮子实例持有独立的 ABS 状态和执行参数。

| 字段 | 偏移 | 类型 | 说明 | 置信度 |
|---|---|---|---|---|
| `brakeFrictionTorque` | 0x88 | float | 刹车摩擦扭矩 | [V] |
| `frictionTorque` | 0x8C | float | 摩擦扭矩 | [V] |
| `grip` | 0x78 | float | 抓地力 | [V] |
| `brake` | 0xF0 | float | 当前刹车力 | [V] |
| `angularVelocity` | 0x100 | float | 轮子角速度 | [V] |
| `slipRatio` | 0x104 | float | **纵向滑移率（ABS 核心判据）** | [V] |
| `slipVelo` | 0x108 | float | 滑移速度 | [V] |
| `normalForce` | 0x138 | float | 垂直正压力 | [V] |
| `slipAngle` | 0x170 | float | 侧滑角 | [V] |
| `friction` | 0x174 | float | 摩擦力 | [V] |
| `maxSlip` | 0x1A8 | float | 最大滑移 | [V] |
| `staticFrictionCoefficient` | 0x28C | float | 静摩擦系数 | [V] |
| `isFront` | 0x2C4 | bool | 是否前轮 | [V] |
| `isLeft` | 0x2C5 | bool | 是否左轮 | [V] |
| `isPoweredWheel` | 0x3CC | bool | 是否驱动轮 | [V] |
| `usesABS` | 0x3CE | bool | **per-wheel ABS 启用标志** | [V] |
| `rawBrakeBiasValue` | 0x3E0 | float | 刹车偏置值 | [V] |
| `legacyLowBrakePressureFront` | 0x3E4 | float | 前轮低刹车压力 | [V] |
| `legacyLowBrakePressureRear` | 0x3E8 | float | 后轮低刹车压力 | [V] |
| `tempBrakeF` | 0x3EC | float | 临时前刹值 | [V] |
| `brakeReducerMultiplier` | 0x388 | float | **刹车减弱乘数** | [V] |
| `pulseBrakes` | 0x408 | bool | **脉冲刹车（ABS 核心执行）** | [V] |
| `kP` | 0x40C | float | PID 比例增益（per-wheel） | [V] |
| `lowAbsDisableSpeed` | 0x410 | float | ABS 禁用速度下限 | [V] |
| `fullAbsEnableSpeed` | 0x414 | float | ABS 完全启用速度 | [V] |
| `brakePressure` | 0x418 | float | 当前刹车压力 | [V] |

关键方法：

| 方法 | RVA | 说明 | 置信度 |
|---|---|---|---|
| `SlipRatio(radius, wMagnitud)` | 0x1A7B244 | 计算纵向滑移率 | [V] |
| `SlipAngle(wMagnitud)` | 0x1A7B2C8 | 计算侧滑角 | [V] |
| `CalcLongitudinalForce(slip)` | 0x1A78EC0 | 基于滑移率计算纵向力 | [V] |
| `CalcLateralForce(slipAngle, camber)` | 0x1A79050 | 计算横向力 | [V] |
| `CombinedForce(Fz, slip, slipAngle, freeRolling)` | 0x1A79314 | 组合力 | [V] |
| `ComputeWheelPhysics(...)` | 0x1A7BE44 | 每帧轮子物理计算 | [V] |
| `RoadForce(pos1, radius, groundNormal)` | 0x1A7B35C | 道路力计算 | [V] |
| `PseudoAtan(x)` | 0x1A7D91C | Pacejka 特殊反正切 | [V] |
| `InitSlipMaxima()` | 0x1A796CC | 初始化最大滑移点 | [V] |
| `UpdateMaxSlips()` | 0x1A7D020 | 更新最大滑移 | [V] |

架构图：

```
┌─────────────────────────────────────────────────────────────┐
│  IRDSCarControllInput (车辆控制器)                           │
│  ─ 车辆级 ABS 门控和策略 ─                                   │
│                                                             │
│  absEnable (0xC4) ← 全局 ABS 开关                            │
│  ABSSlip   (0x30) ← 滑移率阈值                               │
│  absTriggered (0xC8) ← 触发标志                              │
│                                                             │
│  FixedUpdate() → carController() ← ABS 逻辑被内联于此         │
│  HandleABS() ← 被内联，不单独调用                            │
│  ApplyDiffLockABS() ← 差速锁 ABS                            │
└──────────────────────────┬──────────────────────────────────┘
                           │ 遍历 wheels[4]
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  IRDSWheel × 4 (轮子物理)                                   │
│  ─ 轮子级 ABS 执行 ─                                         │
│                                                             │
│  slipRatio (0x104) ← 纵向滑移率（ABS 判据）                  │
│  angularVelocity (0x100) ← 轮子角速度                       │
│  pulseBrakes (0x408) ← 脉冲刹车（核心执行）                  │
│  brakeReducerMultiplier (0x388) ← 刹车减弱乘数              │
│  lowAbsDisableSpeed (0x410) / fullAbsEnableSpeed (0x414)    │
│  brakePressure (0x418) ← 当前刹车压力                       │
│  usesABS (0x3CE) ← per-wheel ABS 标志                       │
│  kP (0x40C) ← PID 比例增益                                  │
│                                                             │
│  SlipRatio() / CalcLongitudinalForce() / CombinedForce()    │
│  ComputeWheelPhysics() ← 每帧物理计算                       │
└─────────────────────────────────────────────────────────────┘
```

---

### 2. 调用链

**[V]** 已通过 native 代码运行时验证。

```
Unity FixedUpdate (50Hz)
  └→ IRDSCarControllInput::FixedUpdate() [RVA: 0x1A64524]
       ├─ [模块 proxy_fixed_update hook] ← 在 orig 前写 absEnable=0
       │
       ├→ carController() [RVA: 0x1A645CC]
       │    ├─ [内联 HandleABS 逻辑] [V]
       │    │    1. if (!absEnable) skip;       ← 门控检查
       │    │    2. 评估车速 vs 速度阈值
       │    │    3. 遍历 wheels[4]:
       │    │         a. 读 wheel.slipRatio (0x104)
       │    │         b. 比较 ABSSlip (0x30) 阈值
       │    │         c. 超阈值 → 设置 pulseBrakes / brakeReducerMultiplier
       │    │         d. 设置 absTriggered (0xC8) = true
       │    │    4. ApplyDiffLockABS(leftWheel, rightWheel, strength)
       │    │
       │    ├→ TractionFilter(accel) [RVA: 0x1A64CE4] — TC
       │    ├→ escFilter() [RVA: 0x1A65090] — ESC
       │    ├→ SteerHelp(steer) — 转向辅助
       │    └→ LockSteerAtVelocity / LockSteerAtSlipAngle
       │
       └→ [orig FixedUpdate 继续]
```

关键发现：`HandleABS()` 作为独立方法存在于 IL2CPP dump 中，但**被编译器内联到 `carController()`**。
这意味着 hook `HandleABS` 方法入口完全无效——方法从不被单独调用。
此结论由 native 代码运行时验证确认（`[V]` 标记）。

---

### 3. 触发条件

**[?]** 基于字段名和类型推断，未通过反汇编验证。

| 条件 | 字段 | 偏移 | 说明 |
|---|---|---|---|
| 全局开关 | `absEnable` | 0xC4 | 车辆级门控，必须为 true |
| 速度下限 | 车速 > `lowAbsDisableSpeed` | 0x410 (IRDSWheel) | 低速时 ABS 不工作 |
| 速度上限 | 车速 ≥ `fullAbsEnableSpeed` | 0x414 (IRDSWheel) | 完全启用阈值 |
| 滑移率超限 | `slipRatio` > `ABSSlip` | 0x104 / 0x30 | 轮子即将抱死 |

#### 滑移率计算 [?]

`SlipRatio(float radius, float wMagnitud)` 方法计算纵向滑移率：

```
slipRatio = (ω × r - v) / max(|v|, ε)

  ω = wheel.angularVelocity (0x100) — 轮子角速度
  r = wheel.radius (0x60)           — 轮子半径
  v = 车身速度（通过 wheelVelo 或 carSpeed 推导）
```

当 `slipRatio` 为负且绝对值超过 `ABSSlip` 时，说明轮子在减速时比车身快——
即将抱死。

---

### 4. 执行机制

**[?]** 基于字段名和类型推断。

#### 脉冲刹车（pulseBrakes）— 真实 ABS 的核心

```
                    pulseBrakes (0x408)
                    ┌──────────────────────────────────┐
原始刹车力 ─────────▶│  ████ ████ ████ ████ ████ ████  │──▶ 实际刹车力
                    │  ↑  ↑ ↑  ↑ ↑  ↑ ↑  ↑ ↑  ↑ ↑  ↑  │
                    └──────────────────────────────────┘
                     释放 施加 释放 施加 释放 施加
                     ←─── 脉冲周期 ~10-20Hz ───→
```

- `pulseBrakes` (0x408) = true 时，轮子的 `brake` (0xF0) 被周期性切断
- ABS 防抱死的核心执行方式——不是持续释放，而是快速脉冲

#### 刹车减弱乘数（brakeReducerMultiplier）— 渐进控制

```
brakePressure (0x418) = 原始刹车压力 × brakeReducerMultiplier (0x388)
```

- `brakeReducerMultiplier` 在 0.0~1.0 之间
- 滑移率超出阈值越多，乘数越低（刹车力减弱越多）
- `kP` (0x40C) 可能是 PID 比例增益，用于动态计算 `brakeReducerMultiplier`

---

### 5. 速度区间控制

**[?]** 基于字段名推断。

```
  车速
  ────┬──────────────────────────────┬──────────────
      │                              │
      │  lowAbsDisableSpeed (0x410)  │  fullAbsEnableSpeed (0x414)
      │                              │
  ABS │     渐进区间（线性插值）       │     ABS
  禁用│                              │    完全启用
      │  multiplier = (v-low)/(high-low) │
```

低速时 ABS 禁用是因为：在很低速度下，轮子短暂抱死是正常且有益的
（摩擦力更大），ABS 干预反而会延长刹车距离。

---

### 6. 差速锁 ABS

**[V]** 方法签名来自 dump.cs。

```csharp
private void ApplyDiffLockABS(IRDSWheel leftWheel, IRDSWheel rightWheel, float diffLockStrength)
// RVA: 0x1A653D0
```

ABS 的补充机制，作用于驱动轮（`isPoweredWheel` 0x3CC）：

- 比较左右轮的 `angularVelocity` (0x100) [?]
- 当内侧轮打滑（速度差大）时，通过 `diffLockStrength` 限制两侧转速差 [?]
- 模拟限滑差速器（LSD）行为 [?]
- 主要用于出弯加速时防止内侧驱动轮空转 [?]

---

### 7. 刹车偏置系统

**[V]** 字段和方法签名来自 dump.cs。

| 字段/方法 | 位置 | 偏移/RVA | 说明 |
|---|---|---|---|
| `MoveBrakeBias(bool towardFront)` | IRDSCarControllInput | 0x1A64CA8 | 动态调节前后刹车分配 |
| `rawBrakeBiasValue` | IRDSWheel | 0x3E0 | 每轮的刹车偏置值 |
| `legacyLowBrakePressureFront` | IRDSWheel | 0x3E4 | 前轮低刹车压力 |
| `legacyLowBrakePressureRear` | IRDSWheel | 0x3E8 | 后轮低刹车压力 |
| `tempBrakeF` | IRDSWheel | 0x3EC | 临时前刹值 |
| `tempRearBrakeBalancerSpeed` | IRDSWheel / CarControllInput | 0x400 / 0x1A0 | 后刹平衡器速度 |
| `currentBrakeBiasFront` | (另一个类) | 0x3D4 | 当前前刹偏置 |

ABS 在前后轮上独立工作——前轮和后轮的滑移率不同，
因为重量转移导致前轮承受更多压力。

---

### 8. ABS / TC / ESC 三位一体

**[V]** 字段定义来自 dump.cs。

游戏实现了完整的车辆动态稳定系统：

| 系统 | 开关 | 触发标志 | 阈值/因子 | 入口方法 | 作用场景 |
|---|---|---|---|---|---|
| **ABS** | `absEnable` (0xC4) | `absTriggered` (0xC8) | `ABSSlip` (0x30) | `HandleABS()` (内联) | 刹车时防抱死 |
| **TC** | `tclEnable` (0xC6) | `tclTriggered` (0xCA) | `TCLSlip` (0x34) | `TractionFilter(accel)` | 加速时防空转 |
| **ESC** | `escEnable` (0xC5) | `escTriggered` (0xC9) | `escFactor` (0x3C) | `escFilter()` | 维持稳定性 |
| **SteerHelp** | `steerHelpEnable` (0xC7) | `steerHelpTriggered` (0xCB) | `steerHelp` (0x40) | `SteerHelp(steer)` | 转向辅助 |

三者共享 `slipRatio` 和 `slipAngle` 数据，通过不同阈值和方向干预：

- **ABS**：检测纵向滑移（刹车时）→ 脉冲释放刹车
- **TC**：检测纵向滑移（加速时）→ 削减油门
- **ESC**：检测横向滑移 → 独立制动单轮纠正方向

---

### 9. 轮胎模型：Pacejka 魔术公式

**[?]** 基于 `a/b/cCoefficients` + `PseudoAtan` 方法名推断，高度可信但未反汇编确认。

IRDSWheel 中的 `a[]` (0xB0), `b[]` (0xC8), `cCoefficients` (0xE0) 是
**Pacejka 魔术公式**系数：

```
F = D × sin(C × arctan(B × slip - E × (B × slip - arctan(B × slip))))
```

相关方法：

| 方法 | RVA | 职责 |
|---|---|---|
| `CalcLongitudinalForce(slip)` | 0x1A78EC0 | 纵向力（用 a, b 系数） |
| `CalcLateralForce(slipAngle, camber)` | 0x1A79050 | 横向力 |
| `CombinedForce(Fz, slip, slipAngle, freeRolling)` | 0x1A79314 | 组合力 |
| `PseudoAtan(x)` | 0x1A7D91C | Pacejka 特殊反正切 |
| `InitSlipMaxima()` | 0x1A796CC | 初始化最大滑移点 |
| `UpdateMaxSlips()` | 0x1A7D020 | 更新最大滑移 |
| `CalcMaxLongitudinalLateralSlips(Fn1)` | 0x1A79828 | 计算峰值滑移 |

#### ABS 与 Pacejka 曲线的关系 [?]

```
  纵向力 F
  │        ╱╲
  │       ╱  ╲ ← 峰值 (maxSlip, peakForce)
  │      ╱    ╲
  │     ╱      ╲╲
  │    ╱        ╲╲ ← 超过峰值后：更多滑移 = 更少抓地力 = 抱死
  │   ╱          ╲╲
  │  ╱            ╲╲
  │ ╱              ╲╲___
  │╱                     ╲___
  └─────────────────────────── slipRatio
     0    ABSSlip  maxSlip
          ↑
     ABS 触发阈值
     （在峰值附近，防止滑移继续增大）
```

`ABSSlip` (0x30) 阈值定义在 Pacejka 曲线峰值附近——
ABS 在即将超过峰值（开始抱死）时触发，
将滑移率控制在峰值附近以保持最大抓地力。

---

### 10. 多线程轮子物理

**[V]** 来自 dump.cs。

`multithreadWheelManager` (TypeDefIndex: 375) 使用 Unity Jobs System 并行计算轮子物理：

| Job 结构 | TypeDefIndex | 职责 |
|---|---|---|
| `BuildRaycastCommandsJob` | 374 | 构建射线检测命令 |
| `GenerateAllWheelRaysJob` | 373 | 生成所有轮子射线 |
| `ComputeAveragedHitsJob` | 372 | 计算平均碰撞结果 |

轮子物理在多线程中计算（射线检测、碰撞、悬架力），
但 ABS 逻辑在主线程的 `FixedUpdate` → `carController` 中同步执行。
轮子物理结果（`slipRatio`, `angularVelocity` 等）通过 `ComputeWheelPhysics()`
写入 IRDSWheel 实例字段，供 ABS 读取。

---

### 11. 模块当前的 ABS 控制方式

**[V]** 来自 `native/src/pedal_hook.c` 运行时验证。

现有 native 代码通过四层机制控制 ABS：

#### 层 1: FixedUpdate 入口写 absEnable=0 + per-wheel usesABS=false

```c
// proxy_fixed_update() — pedal_hook.c
if (!g_config.enable_abs && is_player_controller(this)) {
    write_bool_field(this, 0xC4, false);  // absEnable = false
    // per-wheel: usesABS = false
    void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
    for (int i = 0; i < 4; i++) {
        void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
        if (wheel != NULL) write_bool_field(wheel, 0x3CE, false);
    }
}
```

**关键发现** [V]：只写 `absEnable=false` 不足以禁用 ABS。内联的 ABS 逻辑同时检查
车辆级 `absEnable` (0xC4) 和轮子级 `usesABS` (0x3CE) 做双重门控。
必须同时写两者才能真正禁用 ABS。

#### 层 2: carController 入口双重保险

```c
// proxy_car_controller() — pedal_hook.c
// carController = FixedUpdate RVA + 0xA8（同类内方法统一偏移）
if (!g_config.enable_abs && is_player_controller(this)) {
    write_bool_field(this, 0xC4, false);
    // per-wheel: usesABS = false（同层 1）
}
```

**关键发现** [V]：carController 没有被编译器内联——它是被 FixedUpdate
独立调用的方法。logcat 确认 proxy_car_controller 被触发。

#### 层 3: PlayerControls.Update 第三重保险

```c
// proxy_player_controls_update() — pedal_hook.c
if (!g_config.enable_abs && is_player_controller(car_inputs)) {
    write_bool_field(car_inputs, 0xC4, false);  // absEnable = false
}
```

每帧再写一次，防止被游戏恢复。

#### 层 4: HandleABS 入口 hook（无效但保留）

```c
// proxy_handle_abs() — pedal_hook.c
if (!g_config.enable_abs && is_player_controller(this)) {
    return;  // 跳过 HandleABS
}
```

⚠️ 此方法被内联到 `carController`，从不被单独调用。
hook 永远不触发，保留只是防御性编程。

#### 模块配置路径

```
ConfigActivity (模块进程)
  └→ ModConfig.enable_abs (JSON 配置)
       └→ 广播到达游戏进程
            └→ ConfigReceiver → NativeBridge.setTcAbs(enableTc, enableAbs)
                 └→ pedal_set_tc_abs() → g_config.enable_abs
                      └→ proxy_fixed_update / proxy_car_controller / proxy_player_controls_update 读 g_config
```

---

### 12. 证据置信度

| 发现 | 置信度 | 证据来源 |
|---|---|---|
| ABS 两层控制架构（CarControllInput + IRDSWheel） | **[V]** | dump.cs 字段定义 |
| HandleABS 被内联到 carController | **[V]** | native 代码运行时验证 |
| carController 没有被内联，是独立调用的 | **[V]** | logcat: proxy_car_controller 被触发 |
| absEnable + usesABS 双重门控 | **[V]** | 只写 absEnable=false 无效，加 usesABS=false 后生效 |
| absEnable=false 写入成功且 orig 不恢复 | **[V]** | logcat: readback=0，无 after_orig 日志 |
| per-wheel usesABS=false 是禁用 ABS 的关键 | **[V]** | 用户实测：加 per-wheel 写入后 ABS 禁用生效 |
| TractionFilter 未被内联，hook 有效 | **[V]** | native 代码运行时验证 |
| pulseBrakes 是 ABS 脉冲执行机制 | **[?]** | 字段名 + 类型推断 |
| brakeReducerMultiplier 渐进减弱刹车 | **[?]** | 字段名推断 |
| lowAbsDisableSpeed / fullAbsEnableSpeed 速度区间 | **[?]** | 字段名推断 |
| kP 是 PID 比例增益 | **[?]** | 字段名推断 |
| Pacejka 魔术公式轮胎模型 | **[?]** | a/b/cCoefficients + PseudoAtan 方法名 |
| ABSSlip 在 Pacejka 峰值附近触发 | **[?]** | 物理原理推断 |
| ApplyDiffLockABS 作用于驱动轮 | **[?]** | 方法签名 + isPoweredWheel 字段 |

---

### 13. 改进启示

1. **更精细的 ABS 控制**：当前模块只能全开/全关 ABS。可调 ABS 强度的路径：
   - 写 `ABSSlip` (0x30) 字段调整触发阈值（更高 = ABS 更晚介入 = 更激进）
   - 写 `brakeReducerMultiplier` (0x388) 控制减弱程度
   - 写 `lowAbsDisableSpeed` (0x410) / `fullAbsEnableSpeed` (0x414) 调整速度区间

2. **per-wheel 选择性 ABS**：`usesABS` (0x3CE) 已实现 per-wheel 写入（全部禁用）。
   可扩展为只禁用后轮 ABS（赛车常见设置）或只禁用前轮——
   在遍历 wheels 时检查 `isFront` (0x2C4) 选择性写入。

3. ~~竞态风险~~：已通过 hook `carController` 入口解决（RVA: 0x1A645CC = FixedUpdate + 0xA8）。
   carController 确认为独立调用的方法，未被内联。[V]

4. **ABS 触发可视化**：`absTriggered` (0xC8) 可以读取来在 overlay 上
   显示 ABS 是否正在介入，类似真实赛车的 ABS 指示灯

5. **正规化 carController offset**：当前用 `fixed_update_offset + 0xA8` 固定偏移
   计算 carController 地址。可通过配置传递 offset 提高版本兼容性。