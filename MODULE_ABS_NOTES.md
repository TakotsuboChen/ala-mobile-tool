# ABS 模块控制笔记（工程向）

> 本文档承接 `TECHNICAL_ANALYSIS.md`（论文体逆向分析）中拆分出的**工程实现内容**：
> 模块当前的 ABS 控制方式、可调参数路径、以及升级游戏版本时的验证清单。
> 游戏引擎机制的科学结论（控制律、死代码判定、效果评估）见 TECHNICAL_ANALYSIS.md。
>
> 所有 RVA / 偏移 / 常数为 8.0.4 (200146) 专属。

---

## 1. 模块当前的 ABS 控制方式

native 层四层机制（`native/src/pedal_hook.c`），全部经 logcat 与实测验证：

### 层 1 — FixedUpdate 入口写 per-wheel usesABS

```c
// proxy_fixed_update() — pedal_hook.c
// ⚠️ 拦截判定用白名单 is_target_player_car（this == g_player_controller）。
// 旧版用 is_player_controller（0x108 字段探测），AI 车该字段可能非空，
// 会把 absEnable=false + usesABS=false 误写到 AI 车（与关 TC 波及 AI 同根因，
// 2026-08-27 修复）。
if (!g_config.enable_abs && is_target_player_car(this)) {
    write_bool_field(this, 0xC4, false);   // absEnable — 无读者，仅防御性保留
    void *wheels_arr = *(void **)((uintptr_t)this + 0x28);
    for (int i = 0; i < 4; i++) {
        void *wheel = *(void **)((uintptr_t)wheels_arr + 0x20 + i * 8);
        if (wheel != NULL) write_bool_field(wheel, 0x3CE, false);  // usesABS ← 真正生效
    }
}
```

### 层 2 — carController 入口双重保险

carController（FixedUpdate RVA + 0xA8，尾跳目标）入口再写同组字段。
carController 被独立调用（logcat 确认 proxy 触发），但它本身不含 ABS 逻辑——
写入生效靠轮子 `usesABS`。

### 层 3 — PlayerControls.Update 第三重保险

每帧再写一次 `absEnable` + `usesABS`。

### 层 4 — HandleABS 入口 hook（无效但保留）

hook 安装正常（配置链完整），但 `HandleABS` 是无调用者的死方法，永不触发。纯防御性保留。

### 禁用原理（对应技术解析 §3/§4）

```
模块写 usesABS = false
  → RoadForce 门控: usesABS==false 且 carModifier.playercar==true（玩家车）
    → 跳过 ABS 段 → tempBrakeF 保持 brakeFrictionTorque（满刹车扭矩上限）
      → 车轮锁死（用户实测确认："重刹轮子直接锁死，非常明显"）
```

---

## 2. 运行时可调参数路径（潜在功能）

基于反汇编确认的活跃字段，可作为未来的 ABS 强度/手感调节选项：

| 目标 | 字段（偏移） | 效果 |
|---|---|---|
| 等效关 ABS（另一种路径） | 写 `lowAbsDisableSpeed` = `fullAbsEnableSpeed` (0x410/0x414) 相同值 | 速度因子恒 0 → 跳过 ABS 段，无需写 usesABS |
| ABS 更早满强度 | 写大 `fullAbsEnableSpeed` (0x414) | 低速即满强度（更保护） |
| 缓解低速刹不住 | 抬高 `legacyLowBrakePressureFront/Rear` (0x3E4/0x3E8) | 提升低速压力下限（技术解析 §9.2 的 32% 上限问题） |
| 释放深度 | 写 per-wheel `rawBrakeBiasValue` (0x3E0) | 调 pulse 相位的刹车释放比例 |
| per-wheel 选择性 ABS | 按 `isFront` (0x2C4) 选择性写 `usesABS` (0x3CE) | 如只禁前轮/后轮 ABS |
| ABS 灯可视化 | 读 `pulseBrakes` (0x408) | 真实的 ABS 介入标志（**不要用** `absTriggered`——恒 false 死字段） |

**不建议**：滑移阈值 0.15 位于 `.rodata`（0x929A54），修改需改只读内存页，风险高。

---

## 3. 升级游戏版本时的验证清单

以下结论全部为 8.0.4 (200146) 专属，升级后必须重新验证：

1. `bl`/`b` 目标解码扫描 `HandleABS`（0x1A65258）——确认仍为死方法；
2. `#0x3CE`（usesABS）访问扫描——确认门控位置（预计仍在 `RoadForce`）；
3. `.rodata` 0x929A54 浮点值检查——确认阈值仍为 0.15；
4. 重新 dump IL2CPP 并更新 `OffsetTable.kt` / `offsets_sheet.csv`；
5. 实测：写 `usesABS=false` 后重刹是否锁死。

---

## 4. 分析工具链备忘

```bash
# 提取 libil2cpp.so（从共存版 APK）
unzip -o -q "安装包/Ala Mobile 8.0.4 Takotsubo 共存版.apk" "lib/arm64-v8a/libil2cpp.so"

# 反汇编（RVA == VA，可直接按地址）
NDK_OBJDUMP=~/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump
$NDK_OBJDUMP -d --start-address=0x1A7B35C --stop-address=0x1A7BE44 lib/arm64-v8a/libil2cpp.so

# VA → file offset 需按 ELF program header 换算后读 .rodata 浮点常数
```

扫描脚本要点：
- `bl`/`b` 扫描：操作码 `w >> 26 ∈ {0x25, 0x05}`，目标 = PC + sext(imm26)×4；
- 偏移访问扫描：须同时覆盖 `(w & 0x3B800000) == 0x38000000`（含 ldrb/strb/SIMD）
  与 `add imm`（`(w & 0x7F800000) ∈ {0x11000000, 0x91000000}`）——
  初版扫描曾因掩码遗漏 `ldrb` 编码组（0x39xxxxxx）而系统性漏报；
- **地址域陷阱**（TC 分析时新发现）：全文件调用图扫描的目标必须换算到
  与指令同一地址域。该 so 代码段 `文件偏移 = VA − 0x4000`，`.rodata` 段
  `VA = 文件偏移`；直接拿 dump.cs 的 RVA 匹配"文件偏移 + imm×4"会系统性零命中
  （旧版 `find_bl.py` 即有此 bug，ABS 的死方法结论靠 proxy hook 无日志交叉验证才未翻车）；
- **浮点 ldr/str 的 imm12 缩放**：实际偏移 = imm12 << 2（float scale=4）。
  把 imm12 直接当偏移会大量误报（如 imm12=0x34 实为偏移 0xD0）；
  `ldrb`/`strb` 的 scale 为 1 不受影响；
- 双字访问漏报：相邻 float 字段可能被 `ldr d0`/`stur d0`（0xFD40xxxx/0xFC03xxxx）
  一次拷贝 8 字节（如 `SetPlayerSettings` 写 `TCLSlip`+`TCLminSPD`），
  单 float 偏移扫描看不见，须补 `0xFC`/`0xFD` 开头的 load/store 指令组；
- 命中点归属用 `script.json` 的方法地址表二分定位。

---

## 5. TC（牵引力控制）工程笔记

> 详细控制律与证据见 `TECHNICAL_ANALYSIS.md` §3。TC 与 ABS 相反：车辆级实现
> 是活代码（`TractionFilter` @ 0x1A64CE4，每物理帧经 `carController`+0xBC 调用）。

### 5.1 模块潜在的 TC 功能路径

| 目标 | 路径 | 要点 |
|---|---|---|
| TC 介入灯可视化 | 读 `tclTriggered` (0xCA) | **活跃字段**（每帧先复位后由削减动作置位），与 `absTriggered` 死字段不同 |
| TC 强度调节 | 写 `TCLSlip` (0x34) / `TCLminSPD` (0x38) | 被活跃读取；但会被 `SetPlayerSettings` 在设置变更时覆盖（双字写） |
| 阈值微调（等效） | 无需 hook——游戏设置 `tcl`/`tclMinSpd` 链有效 | 模块只需调游戏设置即可，增益有限 |
| TC 状态灯扩展 | 读 `escTriggered` (0xC9) | ESC 介入标志，同样活跃 |

### 5.2 关 TC 的陷阱（模块已有实现，此处是原理注解）

**直接写 `tclEnable` (0xC6) = false 无效**。玩家车的该字段被
`carModifier.Update` → `TractionControlDynamicAssist`（0x176935C，仅玩家车调用）
每帧重算：先无条件写 true，再按条件写 false（高速 > 22 m/s、不在维修区时关闭）。
写 false 后下一物理帧即被覆盖。

**模块现行实现**（`pedal_hook.c` 的 `proxy_traction_filter`，已实测生效）：
hook `TractionFilter` 入口，TC 关闭时直接 `return accel` 不调 orig——
完全绕过游戏削减逻辑，不依赖任何字段状态。与 `carController` 的补偿合成
数学兼容：返回原值 → 削减量 Δ = 0 → `actualInputTorque = τ`（无副作用）。

> ⚠️ **玩家车判定必须是白名单比对**（`is_target_player_car`，
> `this == g_player_controller`），不能用 `is_player_controller`（0x108
> 字段探测）做拦截判定。AI 车的 `playerControls` (0x108) 可能非空，实测
> 曾导致关 TC 波及全部 AI（AI 无 TC 保护 → 打滑失控 → 失误率暴增）。
> `TractionFilter` 是每辆车每物理帧的必经路径，误判必现；setter 路径 AI
> 未必经过，所以油门修复后此缺陷潜伏到 TC hook 上线才暴露。拦截类 hook
> 一律白名单；`is_player_controller` 只用于 setter 透传的宽松过滤和野指针
> 二次校验。

其他理论路径（均未采用）：
1. hook `TractionControlDynamicAssist` 直接 return——会跳过其中的圈速无效化
   逻辑（`PlayerGotOutOfTrack` → `InvalidateLap`），有副作用；
2. 临时改写实例 `tclEnable` 再恢复——比现行方案复杂且需处理重入，无收益。

### 5.3 TC 行为速查（实测对照用）

- 生效车速区间：约 3.6–79 km/h（`TCLminSPD` = 1.0 m/s 下限；> 22 m/s 被管理器关闭）；
- 一挡（gear == 1）完全不干预——起步不受 TC 影响；
- 满削减保留 15% 油门（`c_T` = −0.85，`.rodata` @ 0x929E7C，只读不建议改）；
- 判据是**综合滑移指标** W = max(|slipRatio/maxSlip|, |slipAngle/maxAngle|)，
  纵向滑移与横向侧偏都会触发，不限于驱动轮空转；
- 移动端松油门回落斜率在 `drivetrain.slipRatio`（0xCC，驱动轮聚合）≥ 0.2 时
  切换为更快的 `throttleReleaseTimeTraction`——手感上"TC 激活时松油门更灵"。