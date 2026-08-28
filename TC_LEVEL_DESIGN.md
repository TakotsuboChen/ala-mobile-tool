# TC 档位调节设计（TC Level Design）

生成时间: 2026-08-28 · 状态: **v1.4 已实装并实机验证通过**（2026-08-28，四档时机全部生效、默认档打滑回归；演化史见 §10）
证据来源: 三路并行调研——游戏侧机制（TECHNICAL_ANALYSIS.md §3 + dump.cs）、模块管线（pedal_hook.c / ModConfig / ConfigReceiver）、行业先例（Tavily 网络调研，来源 URL 见 §7）。

---

## 1. 目标与现状

**现状**：模块对 TC 只有二选一——游戏默认（TractionFilter hook 纯透传）或全关（`enableTc=false` 时 `proxy_traction_filter` 直接 `return accel`，pedal_hook.c:430-440）。

**目标**：提供两个正交维度——**削减强度**（介入后削多狠）与**介入时机**（何时开始削），覆盖"原厂"与"全关"之间的连续谱。（v1.1 增补：用户明确要求介入时机可调，并报告了起步不介入+高速闪烁的实测现象，解剖见 §2b。）

## 2. 游戏 TC 机制（证据基础）

完整数据流（每物理帧 50 Hz，证据 TECHNICAL_ANALYSIS.md §3:580-959，dump.cs 行号见文末）：

```
① 感知  IRDSWheel.SlipRatio (0x1A7B244) → wheel.slipRatio (0x104)
② 决策  carController (0x1A645CC) +0xBC 调 TractionFilter(accel) (0x1A64CE4)
        门控: carSpeed≥TCLminSPD(0x38)=1.0 → TCLSlip(0x34)≠0(默认0.45) → tclEnable(0xC6) → gear≠1
        W = max(|σ/maxSlip(0x1A8)|, |α/maxAngle(0x1AC)|)，削减因子 = smoothstep(clamp01((1−ε)·W−1))
③ 执行  carController 内联合成（无独立方法）: τ' = τ + (τ−filtered)·(−0.85) = τ·(1−0.85·S)
        写 actualInputTorque (0x16C)，削减上限 85%，残量 15% 地板
④ 覆写  carModifier.Update → TractionControlDynamicAssist (0x176935C) 每渲染帧覆写 tclEnable
        → TC 实际生效区间 ≈ 3.6–79 km/h（高速自动关闭是游戏原生语义）
```

关键事实（均 [V] 已验证）：

- **−0.85 系数烧在 carController 内联代码里**（.rodata @ 0x929E7C），无独立"读取者方法"可 hook；.rodata 只读段不可直接改。
- **旧线索证伪**：CarControllerMobile 的 slipRatio ≥ 0.2 比较只控制**松油门释放斜率**（手感通道），不是 TC 削减本体。
- `tclEnable` 被管理器每帧覆写——档位逻辑绝不能依赖写 tclEnable，必须放在 TractionFilter 入口 hook（MODULE_ABS_NOTES §5.2 两条死路教训）。
- **游戏原生 TC 设置 UI 本身就是 TMP_Dropdown 多档位**（settingsHandler.TractionControl 0x120，dump.cs:73917）→ 实机截图游戏内 TC 下拉框可白嫖原生档位语义（待办）。
- **关键字段路径全部确认**（dump.cs:12396-12525，v1.1 增补）：`TCLSlip` (0x34) / `TCLminSPD` (0x38) / `drivetrain` 指针 (0x98) / `wheels` 数组 (0x28)，`IRDSDrivetrain.poweredIRDSWheels` (0x58)、`drivetrain.gear` (0xC0，§3.1)；另有 `SetTCLSlip()` 公开方法（dump.cs:12525，直写字段更简单）。

### 2b. 实测现象解剖（v2，2026-08-28 反汇编复核后修正）

用户实测：踩死 100% 油门，约 50 km/h 才见 TC 介入，油门开度闪烁持续到 120-130 km/h；对比全关 TC 则 30 多滑到 120-130 下压力建立才稳；换挡点八九十 km/h。**此实测推翻了 §3.10"TC 生效区间 3.6–79 km/h"的普遍化结论**，经直接反汇编 libil2cpp.so（/tmp/abs-analysis，llvm-objdump）复核：

**[V] TractionFilter 四道门控逐条指令级确认**（0x1A64CE4-0x1A64E28）：
1. `carSpeed(0x84) ≥ TCLminSPD(0x38)`（fcmp/b.mi；ctor 默认 1.0 m/s，但**运行时真实值 11.0 m/s**——游戏进赛道经 SetPlayerSettings 写入，TCdiag 实测，v1.4 确认）
2. `TCLSlip(0x34) ≠ 0`（fcmp #0.0/b.eq）
3. `tclEnable(0xC6)`（ldrb/cbz）
4. `drivetrain(0x98)→gear(0xC0) == 1 → 直通`（`cmp w9, #1; b.ne` 继续）——**豁免的是空挡而非一挡**（gear 编码见下）
- W = max 遍历 `poweredIRDSWheels`（数组长度在 +0x18，数据从 +0x20）：每轮 `|slipRatio(0x104)/[0x1A8]|` 与 `|slipAngle(0x170)/[0x1AC]|` 取大；0x1A8/0x1AC 是 **IRDSWheel 轮级字段**（载重相关峰值）。削减 = smoothstep(clamp01((1−ε)W−1))，filtered = τ·(1−S)，tclTriggered 置位。

**[V→修正] "79 km/h 高速关闭"实为维修区/赛道限速逻辑**（0x176935C-0x17696FC 复核）：
- `TractionControlDynamicAssist` 步骤①**每帧无条件写 tclEnable=true**；条件 A/B 写 false 均被运行时 singleton 谓词门控：
  - 条件 A：`singleton1.bool(0x48) && singleton1.child(0xA0).int(0x70)==1 && !inPits` → false（谓词语义未解 [?]，实测未生效）
  - 高速块整体被 `CheckTrackLimitRespected()` 与 `singleton2->byte(0)` 双重门控；其内 `setBrakeInput(1.0)` + `setThrottleInput(0.0)`（22 m/s ≈ 79 km/h ≈ **F1 维修区 80 km/h 限速**）+ 里程累计——是**维修区限速器**，不是 TC 截止
- **推论 [V]：正常赛道行驶 tclEnable 恒为 true，TC 全速域活跃（用户 100-130 km/h 仍被削即证据）**。TECHNICAL_ANALYSIS §3.10"实际生效区间 3.6–79 km/h"过度普遍化，已在该文档加勘误。
- 顺带静态解决 §3.7 的 [?]：管理器每帧强制 tclEnable=true → **游戏内 TC 开关位（enableTCL）无法关掉 TC**；唯一游戏侧关闭路径是下拉框把 `tcl` 写 0（触发门控 2）。

**排除法收尾（2026-08-28 第三轮深挖）**——50 km/h 介入点的嫌疑人逐一排除：

| 嫌疑 | 判定 | 证据 |
|---|---|---|
| 79 km/h 高速截止 | [X] 维修区限速器（singleton 门控，正常赛道不生效） | §2b 前文 |
| 一挡豁免 | [X] 实为空挡豁免，前进挡全活跃 | gear 编码反汇编 |
| TC 下拉框预设 tclMinSpd≈14 m/s | [X] **游戏设置根本没有 TC/ABS 档位可选**（仅手柄生效的开/关，用户实测确认）→ ε/tclMinSpd 恒为序列化默认 0.45 / 1.0 m/s | 用户 + SetPlayerSettings 默认值 |
| 物理步长 1/30 → 感知衰减到 48 km/h | [X] **TimeManager 实测 Fixed Timestep = 0.02**（data.unity3d 内 TimeManager 对象，UnityPy 解出）→ 感知衰减终点 = 8·Δt/K = 28.8 km/h，在 50 之前就饱和 | [V] 引擎设置 |
| 门控 1（carSpeed ≥ 1.0 m/s） | [X] 阈值过小，不构成 50 km/h 阻挡 | 默认值 |

**最后-standing 解释 [?]：W 窗口 × 轮胎模型动态峰值的交互**——削减条件 W > 1/(1−ε) = 1.82（ε=0.45），而 W = max(\|σ/maxSlip\|, \|α/maxAngle\|)，maxSlip/maxAngle 是**每轮随当前载荷查 LUT 的动态最优值**（UpdateMaxSlips 0x1A7D020：索引 = 载荷×N/基准，LUT 由 InitSlipMaxima 从 Pacejka 曲线扫描构建）。即：**滑移要超过"当前载荷下最优滑移的 ~1.8 倍"才触发**。30-50 km/h 区间在滑但未超 1.8×；50 km/h 附近滑移量越过窗口 → 削减 + 极限环闪烁 → 120-130 下压力建立、滑移回落 → 停。各轮 σ/α/maxSlip/maxAngle 的实际数值静态不可得（LUT 内容运行时构建），**插桩是唯一裁决手段**。

**用户纠正的并入（同日）**：游戏 TC/ABS 设置仅手柄生效的开/关、无档位——与反汇编一致（enableTCL 被管理器每帧强制 true，开关位无效；tcl/tclMinSpd 无 UI 入口恒为默认）。**推论：模块的 TC 档位是移动端唯一的 TC 调节途径**，§6-Q2"对齐游戏原生档位"作废（原生无档位）。

**[V→修正] gear 编码 = {0:R, 1:N, 2:一挡, 3:二挡, ...}，UI 显示值 = gear − 1**（换挡逻辑反汇编 + 用户实测锁定）：
- `ShiftUp`（0x1A6CD88）：从 gearWanted==0 升挡要求车速 ≤1 m/s（R→N 静止换入）；**gearWanted 增至 2 时触发专用事件**（进入一挡 = 起步相关逻辑）
- `ShiftDown`（0x1A6CF0C）：下界保护 `gearWanted < 1 → 拒绝`（N 为降挡地板）；**gearWanted==2 降挡有速度条件特判**（一挡→N 防高速摘挡）
- `changeGearToTarget`（0x1A6CC58）：离开 gear==1（N→一挡）有 RPM 阈值检查（.rodata 常数）+ 换挡计时
- 用户实测：TC 在 UI 显示"1 挡"期间活跃（gear=2 ≠ 1）——**门控 4 豁免空挡，文档旧结论"一挡豁免"证伪**，TECHNICAL_ANALYSIS §3.4 已加勘误

**闪烁机制 [V]**：零时间常数 + 无迟滞（§3.5 特征 3）→ 极限环（TC 削→滑移落→W 出窗→释放→滑移升→再削，50 Hz 循环）。起步段 TC 不动 = 低速感知衰减（σ×clamp01(|v|/8)，28.8 km/h 以下线性压缩，§3.3）+ W>1.82 窗口（滑移须超当前载荷最优值 1.8 倍）叠加的结果，与"一挡豁免"无关（该豁免不存在）。

## 3. 注入点选型（三方调研交叉结论）

| 候选 | 机制 | 结论 |
|---|---|---|
| **A. TractionFilter 返回值插值** | 现有 hook 白名单分支内 `f_m = lerp(τ, f, mix)` | ★ **v1 采用**（强度维度）。零新 hook、零 .rodata、零新 dump |
| **B. 写 TCLSlip (0x34) 实例字段** | 现有 hook 入口每帧写绝对值（勿缩放写，防每帧复利衰减） | ★ **v1 采用**（时机维度，v1.1 由用户需求提升）。介入点 W_th = 1/(1−ε)：0.45→0.25 即从 W>1.82 提前到 W>1.33；语义 `tc_slip≤0 = 不写（跟随游戏设置）`；模块覆写时会压过游戏内 TC 下拉框，UI 需说明 |
| C. 覆写 actualInputTorque (0x16C) 突破 85% 上限 | 时序风险（下游可能已消费） | ❌ 不做 |
| D. CarControllerMobile 释放斜率 (0x34) | 需新 hook，仅松油门手感 | 暂不做 |
| E. hook TractionControlDynamicAssist 实现"全程 TC" | 需调 orig 保留圈速无效化副作用 | 暂不做（拟真用户通常不要高速 TC） |

### 核心数学（已验证严格等价）

游戏原生：`τ' = 0.15τ + 0.85·f`，其中 f = τ·(1−S)（S 为 smoothstep 削减因子）。

在 hook 返回点做线性插值 `f_m = τ + (f−τ)·mix`，代入合成：

```
τ' = 0.15τ + 0.85·f_m = τ·(1 − 0.85·mix·S)
```

- `mix = 1.0` → 与游戏原厂**逐位一致**（τ' = τ·(1−0.85·S)）
- `mix = 0.0` → τ' = τ，即现状"全关"（数学同构，非近似）
- `mix = 0.5` → 削减上限 42.5%
- 自动继承游戏全部门控语义：低速豁免、一挡豁免、tclEnable 高速关闭（管理器拉低 tclEnable 时 orig 返回 τ，插值自然退化）

## 4. 档位模型（v1.1：强度 × 时机 双旋钮）

行业先例的档位普遍是**二元组**——ACC "TC1=何时 / TC Cut=切多少"、Haltech 12 档 = (DesiredSlip[i], CutPct[i]) 双表、rFactor2 Range 三元组同时驱动目标滑移与切断强度。本设计对齐：两个独立控件，各自默认值 = 原厂行为，任一偏离原厂即模块接管该维度。

**旋钮 1：削减强度**（插值 mix，见 §3 候选 A）

| 档位 | 标签 | mix | 削减上限（vs 原厂 85%） |
|---|---|---|---|
| 5 | `原厂` | 1.00 | 85% |
| 4 | `75%` | 0.75 | 64% |
| 3 | `50%` | 0.50 | 43% |
| 2 | `25%` | 0.25 | 21% |
| 1 | `关闭` | 0.00 | 0%（return accel，现状路径） |

**旋钮 2：介入时机**（写 TCLSlip，见 §3 候选 B；介入点 W_th = 1/(1−ε)）

| 档位 | 标签 | tc_slip | 介入点 | 语义 |
|---|---|---|---|---|
| 0 | `游戏默认` | ≤0（默认） | 原厂 W>1.82（ε=0.45） | **不覆写字段**，保持游戏序列化默认值 0.45（游戏设置无任何 TC 参数可调，此即游戏实际行为） |
| 1 | `更早` | 0.30 | W>1.43 | |
| 2 | `非常早` | 0.18 | W>1.22 | |
| 3 | `实时` | 0.02 | W>1.02 | 接近"一打滑就削"（保留门控≠0 语义） |

- 方向说明写入 UI：时机数值越小 = 介入越早、削减曲线越陡。
- 初值**待实机标定**（Preset 表集中一处，改表不改结构）；`tc_slip` 上调（如 0.6 → W>2.5）可做"更晚"档，视需求再加。
- 一挡豁免（gear==1）不受这两个旋钮影响——起步 TC 依旧不介入，解禁是独立实验（§6-Q4）。
- 与游戏原生下拉框的融合：若实机截图证实原生档位语义，可对齐命名，见 §6 开放问题。

## 5. 端到端改动清单（v1）

配置管线复用现有模式（样板 = `setTcAbs`），12 项：

| # | 文件 | 改动 |
|---|---|---|
| 1 | `config/ModConfig.kt` | KEY `tc_mix`（Float 0..1，默认 1.0=原厂）+ KEY `tc_slip`（Float，默认 0=跟随游戏）；read/write/fromJson/defaultSettingsPublic/Settings 字段（**带默认值**，免改 PedalOverlayView 14 字段构造点） |
| 2 | `ui/viewmodel/ConfigViewModel.kt` | ConfigUiState 两字段 + **两个构造点**（:45/:76）+ toSettings + `setTcMix`/`setTcSlip` setter |
| 3 | `ui/screen/configure/ConfigurePagerMiuix.kt` | TC 开关后加两个 dropdown（OverlayDropdownPreference 模式 :212-228）：强度 5 项 + 时机 4 项，AnimatedVisibility 按 enableTc 门控 |
| 4 | `NativeBridge.kt` | `@JvmStatic external fun setTcParams(mix: Float, slip: Float)`（一次传两个，setter 内部分发）——**不动 init() 44 参数签名** |
| 5 | `AlaMobileModule.kt` | 启动读配置，init 后补调 `setTcParams`（模仿 :286 setLogEnabled） |
| 6 | `config/ConfigReceiver.kt` | 实时同步块：`optDouble("tc_mix")`/`optDouble("tc_slip")` → `NativeBridge.setTcParams`（**漏掉则改档要重启游戏**） |
| 7 | `native/src/ala_core.c` | JNI `setTcParams` → `pedal_set_tc_params` |
| 8 | `native/src/pedal_hook.h` | config 加 `volatile float tc_mix, tc_slip;` + setter 声明 |
| 9 | `native/src/pedal_hook.c` | ① `pedal_install_hooks` 里默认 `tc_mix = 1.0f; tc_slip = 0.0f`（**g_config={0} 零初始化陷阱**：mix 0.0 语义是"关"不是"原厂"，必须在 install 时兜底）；② setter（低频 LOGI 允许）；③ proxy_traction_filter 白名单分支：入口处 `tc_slip > 0` 时写 `*(float*)((char*)this+0x34) = tc_slip`（orig 读该字段在门控链，写在调用前即生效）；④ 三路返回：`mix<=0 → return accel`（保留已实测路径）/ `mix>=1 → return orig(...)`（逐位等同现状透传）/ 否则 `orig 后插值` |
| 10 | OffsetTable / module.prop | **不动**（无新 hook 点、无新 IL2CPP 字段） |
| 11 | `.gitignore` / CI | 不涉及 |
| 12 | 文档 | 实现后更新 MODULE_ABS_NOTES.md + HANDOFF（走 /handoff） |

proxy_traction_filter 改造示意：

```c
static float proxy_traction_filter(void *this, float accel) {
    if (is_target_player_car(this)) {
        float mix = g_config.tc_mix;
        if (mix <= 0.0f) return accel;              /* 档位"关闭"（现状路径） */
        float f = orig_traction_filter(this, accel);
        if (mix < 1.0f) f = accel + (f - accel) * mix;
        return f;
    }
    return orig_traction_filter(this, accel);        /* AI 车透传 */
}
```

## 6. 开放问题（实现前需确认）

1. **pedalMode=OFF 耦合**：`pedal_install_hooks` 在 `enable_control_replacement=false` 时整体 early-return（pedal_hook.c:518-521）→ 踏板模式 OFF 时 TC 调节失效（现有 enableTc 同病）。解耦需拆 hook 安装，风险独立。**默认：接受约束，不拆**。
2. **游戏原生档位对齐**：待实机截图游戏内 TC 下拉框；若原生有 3-4 档，考虑对齐命名或提供"原生档位+扩展档"。
3. **闪烁治理**（v1.1 新增候选）：对削减量做一阶低通（50 Hz 物理 帧，τ≈40 ms，`cut_m = mix·(τ−f)` 的 EMA），只在 0<mix<1 时启用，原厂路径逐位不变。v1 先不上，实机确认弱档位下闪烁是否仍困扰再说。
4. ~~起步介入 knob = 覆写 `TCLSlip` (0x34)~~（**v1.4 推翻**：minSPD 门控①排在读 ε 之前，只调 ε 时 0~40km/h 起步段整段无感——这是 v1.1/v1.3 "调时机无效"的根因。正解 = (ε, minSPD) 成对覆写，见 §10）。
5. **tclTriggered (0xCA) 介入指示**：可在踏板 overlay 显示 TC 介入灯；注意插值后弱档位"虚亮"（反映 orig 动作非最终削减量）。暂缓。
6. ~~**TCLminSPD (0x38)** 同样可写，但原厂值已极小，不暴露 UI~~（**v1.4 推翻**：运行时真实值 11.0 m/s 而非 ctor 的 1.0，它恰是起步打滑区间的守门人——已作为时机档 (ε, minSPD) 配对参数启用，见 §10）。

## 10. v1.4 演化史（2026-08-28，四轮实测闭环）

**三阶段现象与根因**（同一门控顺序的三种表现）：

| 阶段 | 实现 | 表象 | 根因 |
|---|---|---|---|
| v1.1 | 只写 TCLSlip=eps | 怎么调都=游戏默认 | minSPD 门控①在读 ε 之前，0~40km/h 起步段永远走不到读 ε；ε 只影响高速段阈值 W>1/(1-ε)，日常很少触发 |
| v1.2/1.3 | 每帧无条件写"ctor 默认"0.45/1.0 | 怎么调都=非常早介入 | 写的"原厂值"是 ctor 默认；游戏 SetPlayerSettings 实写 ε=0.40/minSPD=11.0。minSPD 被压到 1.0 → 起步打滑区间整段被咬死；且无条件写把所有档位拉平 |
| v1.4 | (ε, minSPD) 配对覆写 + 基线捕获/恢复 | 四档全部生效 | 门控①④分别管辖低速/高速介入窗口，时机档必须成对降两门 |

**v1.4 档位参数**（eps 阈值 1/(1-ε)，minspd 单位 m/s）：
- 更早 EARLIER：ε=0.30（阈值 1.43）+ minSPD=8.0（29 km/h 起介入）
- 非常早 VERY_EARLY：ε=0.18（阈值 1.22）+ minSPD=4.0（14 km/h 起介入）
- 实时 REALTIME：ε=0.02（阈值 1.02）+ minSPD=0.5（车一动即咬）
- 游戏默认 DEFAULT：不写任何字段；切回时一次性恢复**捕获基线**（首次覆写前记录的运行时真值，实测 0.40/11.0——非 ctor 默认 0.45/1.0）

**日志闭环证据**：`slipPre=0.020`（写入保持，游戏不每帧重写字段）；`W=1.38→out=0.715`（ε 生效削减，默认档此 W 不会介入）；切回默认 `slipPre=0.400`（基线正确恢复）。

**方法论教训**：①覆写 IL2CPP 字段前必须反汇编确认门控顺序，一个前置透传门可让后面的调参静默失效；②ctor 默认 ≠ 运行时真实值，运行时参数只能靠写前值日志实测；③"无条件接管"把修粘连和覆盖游戏配置焊死在一起，高危——条件写 + 基线恢复才是安全模式。

**v1.5 实机手感调优（2026-08-28 同日，用户实测定档）**：强度档 mix 调为 0.15/0.40/0.60（原 0.25/0.5/0.75，用户反馈差异化不足）；时机档 ε 调为 0.35/0.25（原 0.30/0.18，放缓中间两档，minSPD 配对 8.0/4.0 不变）。同轮档位标识符对齐中文 UI 词汇表：WEAK/STRONG/STOCK → LOW/HIGH/MAX，DEFAULT/EARLIER → LATE/EARLY（存档 value 键值不变，无需迁移）。**档位现行值以 `ModConfig.kt` 为单一事实源**，本节保留 v1.4 落地时快照。

## 7. 行业先例来源（网络调研摘要）

- rFactor2 S397 官方 modding 文档：档位 = Range 三元组 `(base, step, count)` 同时驱动目标滑移角与切断强度；cut method / per-gear 缩放 / 低速门控参数齐全。https://docs.studio-397.com/x/JwCYB
- ACC 社区共识：TC1=何时（触发阈值）、TC Cut=切多少。https://solox.gg/tc1-vs-tc2-in-acc 、https://simracingonline.co.uk/threads/traction-control-cut.3894
- RaceRoom 6 档 + 行驶中切档。https://raceroom.miraheze.org/wiki/ABS
- AMS2 setup guide：TC 阈值型语义（越高→越少滑移就激活）。https://automobilista2.wiki.gg/wiki/Automobilista_2_Setup_Guide
- 真实 F1 TC（90s–2000s）：PID→扭矩削减%→24 点断油曲线，保留 ~4% 残余滑移。https://www.f1technical.net/features/10698
- ECUTEK GT-R RaceROM：目标滑移 map × 加速度乘子 + clamp，P 项 + 防风up。https://ecutek.atlassian.net/wiki/spaces/SUPPORT/pages/1670447105/GT-R+RaceROM+Traction+Control
- Haltech 12 档 = (DesiredSlip[i], CutPct[i]) 双表；fuel cut 干净 vs ignition cut 快但伤排气。https://support.haltech.com/portal/en/kb/articles/traction-control-user-s-guide
- F1 game 仅 Full/Medium/Off。https://www.trophi.ai/post/how-to-drive-without-traction-control-in-f1-24
- ShadowHook 指令级拦截（含 FPSIMD 读写）与 .text patch 路线（本项目不需要，仅存档）。https://github.com/bytedance/android-inline-hook/blob/main/doc/manual.md

## 8. 引用（游戏侧证据坐标）

- TECHNICAL_ANALYSIS.md：§3.4 TractionFilter 控制律 (677-734)、§3.5 补偿合成与 85% 上限 (736-749)、§3.6 油门斜率调制 (781-800)、§3.7 玩家设置链 (802-835)、§3.8 escFilter (836-860)、§3.10 TractionControlDynamicAssist (880-899)、附录 C/D (1016-1075)
- MODULE_ABS_NOTES.md §5 (119-167)：return accel 实测无副作用（插值 = 该结论的连续推广）、两条死路
- pedal_hook.c：proxy_traction_filter :430、is_target_player_car :113、proxy_fixed_update :304、pedal_install_hooks :513
- dump.cs：IRDSCarControllInput :12396（TCLSlip 0x34 / TCLminSPD 0x38 / maxSlip 0x1A8 / maxAngle 0x1AC / tclEnable 0xC6 / tclTriggered 0xCA）、IRDSPlayerControls :13819、carModifier :17722、settingsHandler.TractionControl :73917
- offsets_sheet.csv:25 / OffsetTable.kt:53：TRACTION_FILTER = 0x1A64CE4（hook 已在位）

## 9. 红线（实现时必守）

1. TractionFilter 每车每物理帧触发——**hook 内严禁 LOGI**；日志只放 setter（低频）与安装期。
2. 拦截/改写必须 `is_target_player_car` 实例比对白名单，禁用 `is_player_controller` (0x108) 探针（AI 全场失控教训）。
3. 改 Kotlin 后推送前必须 `./gradlew :app:lint` 0 errors（assemble 不跑 lint，CI 是唯一门禁）。
4. 实机测试用 release 构建；冷启动后连续测 3 次才可信（M37 假阳性教训）。
5. 游戏运行时改档要即时生效 → ConfigReceiver 同步块不可漏。