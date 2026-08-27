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
if (!g_config.enable_abs && is_player_controller(this)) {
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
- 命中点归属用 `script.json` 的方法地址表二分定位。