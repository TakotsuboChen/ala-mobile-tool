# HANDOFF — 读全文再开始干活

生成时间: 2026-08-30T13:30:00+08:00 · Git HEAD: `2183174`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `2183174` (2026-08-30)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `2183174`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `MODULE_ABS_NOTES.md` §2c（v6 FPSIMD 污染死法——本次回归核心）+ `ABS_LEVEL_DESIGN.md` §6 验证记录 7

## 1. 当前目标
ABS 档位全线失效回归已修复并实机验证通过（用户反复测过关/低/中/高/最高五档）。主线等待项不变：①多指踏板污染修复等复现用户日志回传裁决；②Bug B（重启 remote 配置旧值）无新证据。

## 2. 已验证状态 — 工作实际停在哪
- [V] **ABS 档位失效根因 = 指示灯拦截器 FPSIMD 污染（`cf869fd`）**：`abs_rf_intercept_pre` 的 `0xF0>0.01f` 浮点比较经 s0 执行，污染被拦截指令 `str s0,[x19,#0x3EC]` 重放值——pulse 泄压帧 tempBrakeF 被写垃圾（≈0）→ 全泄压 → 方波平均恒 0.5×F_base = 原厂 b=0，所有档位手感=默认最高档。修复 = 回调零浮点（IEEE754 位型整数比较 `>0x3C23D70Au`），flags 回退 DEFAULT。
- [V] **修复后实机验证**：ABSdiag pulse 帧 tf/F_base 比值主峰 0.80/0.59/0.40 精确对应三档 b 值（预期 0.80/0.60/0.40），用户确认各档手感恢复差异化（低≈锁死、高=强保护）。
- [V] **拦截器地址写入侧一直正常**：全程日志 `bCfg`（Java 下发）与轮上 `b`（0x3E0 实值）完全一致——配置链、覆写链自 `d516717` 标定以来无回归，问题只在"游戏消费端"。
- [V] **构建+lint**：`./gradlew :app:assembleDebug :app:lint :app:assembleRelease` → BUILD SUCCESSFUL，EXIT=0（全新 shell 重跑）。实机已装 release（设备 381QYFCN22B9A）。
- [V] **持久文档已同步**：MODULE_ABS_NOTES §2c 补 v6 死法 + ABS_LEVEL_DESIGN §6 补验证记录 7 + CLAUDE.md pedal_hook 条目补零浮点红线（`2183174`，197 行 < 200）。README 核对无需改（用户可见行为规格未变）。
- [V] **记忆已写**：`shadowhook-instr-intercept-fpsimd.md`（FPSIMD 污染机制 + flags 不可信 + 症状指纹 + 诊断方法）。
- 工作区: 干净，`main` 与 origin 同步（`cf869fd` 工作 + `2183174` 持久文档 + 本次 handoff 提交）。
- 继承 [?]：多指污染修复等红米用户日志回传；Bug B 未复现未修。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:lint :app:assembleRelease → BUILD SUCCESSFUL in 4s，EXIT=0
adb install -r app-release.apk → Success（修复版已实机）
实机验证：用户五档反复实测 + ABSdiag 日志 pulse 帧 ratio 主峰逐档吻合 → 通过
git push → 42f594a..cf869fd..2183174 main（工作+持久文档两切片，成功）
```

## 3. 决策与理由
- **回调零浮点而非 FPSIMD flags** [V]——两轮实机实证：DEFAULT 下垃圾=0xF0 归一化值（1.0/0.88…），改 WITH_FPSIMD_WRITE_ONLY 后垃圾变 0.0——shadowhook 的 FPSIMD 保存/恢复路径恢复进的是零，flags 补救同样不可信。唯一可靠防御是回调体不含任何浮点操作（编译器无从分配 FP 寄存器）。0xF0>0.01f 用位型整数比较替代（正 float 位型与无符号整数同序，语义等价）。
- **A/B 日志二分定位法** [V]——官方版旧日志（无拦截器，指示灯提交前）pulse 帧 ratio=0.59（=b 正常消费）vs 共存版（有拦截器）ratio≈1.0 或 0~1.5 垃圾值，一次对照锁定拦截器。ABSdiag 的 bCfg+b 双列设计（下发侧+生效侧同帧采样）让二分只花两轮日志。
- **0.01f 阈值语义不变** [V]——0x3C23D70A 即 0.01f 的 IEEE754 位型，过滤行为与修复前设计一致（0xF0≈0 时泄压无物理效果，不算介入）。
- 继承：指令级拦截而非字段读取、0xF0 物理效果过滤、帧号 age 判介入、相位时钟单一写点、透明度 75%——见 `.handoffs/20260830133000-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录 + MODULE_ABS_NOTES §2c。

### 本轮新增（FPSIMD 污染两轮死法，全部实机实证）
- [X] **拦截回调用 SHADOWHOOK_INTERCEPT_DEFAULT + 回调内浮点比较** → s0 被污染，重放把 0xF0 归一化值（0~1.0）写进 tempBrakeF 扭矩字段 → pulse 泄压帧扭矩≈0 全泄压 → ABS 档位全线失效（手感=默认最高档，用户实测）。**拦截浮点指令的回调严禁任何浮点操作**。
- [X] **改 WITH_FPSIMD_WRITE_ONLY 补救** → 垃圾变 0.0（shadowhook FPSIMD 保存/恢复路径恢复进的是零），pulse 泄压仍被毁（tf=0.0 实机实证）。**flags 补救不可信，别再走 flags 路线**。
- 症状指纹 [V]：tempBrakeF 是扭矩量纲（百~千级），日志出现 0~1.5 的小值即 FPSIMD 污染复发。验证法：反汇编回调不得出现 vmov/vldr/fcmp（static 函数被去符号，按地址段查）。

### 继承死路（指示灯信号链 v1→v5，全部实机实证）
- [X] **v1 读 pulseBrakes(0x408) 电平** → 车动即闪。0x408 是 25Hz 相位时钟非介入标志。
- [X] **v2 条件复算** → 用户否决"要真信号"；起步打滑误报。
- [X] **v3 拦截器 + 帧头清零** → 灯恒灭：Unity 回调同帧顺序不定。不要再用清零跨回调传递信号。
- [X] **v4 相位双写点** → 双重翻转抵消，两灯全灭。共享时钟必须单一写点。
- [X] **拦截命中不滤 0xF0** → 起步红绿齐闪（油门打滑也命中）。

### 继承死路（制动压力滑条 v2-v5 演化 + 更早，均已实装后撤销/证伪）
- [X] **v2 全局缩 T_b / v3 p₀ 同缩 / v4 门控分流 / v5 输入端线性缩放** → 四条全否决，v6 饱和重映射定案（详见 ABS_LEVEL_DESIGN §4）。
- [X] 断言"show taps 圆点不跟随"→ 官方文档证伪；GameTurbo 设置层 A/B 无效；MotionEvent.isResampled 编译失败；getRawX/Y 直调 API 29+ lint 报错（统一走 helper）。
- [?] OnTouchListener 探 decorView 层事件（框架推理未实测）。
- [X] 边框缝隙数学无解必须层内不透明；档位值 0.90 贴 native clamp 静默截断；押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够；每帧写 ctor 默认≠真值；画笔模式技术成功被用户否决；commit 时间推 APK 内容单因推断；adb force-stop 用户在用设备；滑块 v1/v2 帧数 1/6 归因错误、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网；proxy_shift_up 打日志洪水（18810 条/21min）；IL2CPP 不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **拦截器回调零浮点是长期红线** [V]——已写入 CLAUDE.md pedal_hook 条目 + MODULE_ABS_NOTES §2c + 记忆。任何人往 `abs_rf_intercept_pre` 加浮点逻辑都会复发档位失效。
- ⚠️ **多指污染待回传裁决** [?]（继承）——修复已给复现用户，回传后按三分支裁决（`.handoffs/20260829205358-handoff.md` §6）。
- ⚠️ **隐藏踏板"重启后仍隐藏"= remote 配置旧值（Bug B）** [?]（继承）——无新证据，实测未复现则搁置。
- ⚠️ **ConfigReceiver 与 15s init 竞态窗口** [?]（继承，窄窗口暂不修）。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）。导出为空排查经验 [V]：72 字节=游戏侧 logEnabled 未开；release 包可直读 `/sdcard/Android/data/<游戏包>/files/ala_tool*.log` 兜底。
- ⚠️ **v6 重映射的 pulse 相位边界** [?]（继承）——pulse 帧 tempBrakeF=0 时 bp 采样可能异常，未深究。
- ⚠️ **ABSdiag 的 0x414 死参数/0x3D4 读法/offsets_sheet 0x1A62E10 勘误** [?]（继承，未恶化不动）。
- ⚠️ **指示灯拦截地址 0x1A7B7DC 硬编码** [V]——8.0.4 专用，与其他 IL2CPP 偏移同受 VersionGate 门控；升级游戏版本必须重新反汇编 RoadForce 定位（MODULE_ABS_NOTES §3 验证清单）。
- ⚠️ **指示灯诊断日志 rfHits/hitAge 行** [V]——abs_diag_log 内，验收已过；下一版可整段移除（与 TCdiag/ABSdiag 同属"标定完可移除"）。
- ⚠️ 曲线编辑器 QP 高频路径/pager settle 回抓/滑块 v3 快弹簧/冷启动 janky [?]（继承，未恶化不动）。

## 6. 下一步（有序）
1. **等复现用户回传多指日志**（核心等待，继承）——回传后按三分支裁决。
2. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码（继承，grep 确认零调用）+ 移除 abs_diag_log 的 rfHits 诊断行。
3. 若用户反馈 ESC 场景制动异常 → 按 `.handoffs/20260830004912-handoff.md` §4 做通道隔离。
4. （可选）追 ABS 档位实战手感（刹车点/循迹刹车）——档位机制本次已实机验证恢复。

## 7. 留给用户的开放问题
- 工具按钮记忆位置无 UI 重置入口——用户若想回默认位置，需清应用数据或等未来加"长按重置自身"（当前长按=进编辑模式）。是否需要单独重置手势？
- TC/ABS 指示灯是否需要亮度/闪烁频率调节项？（当前规格固定：75% 中心 alpha、25Hz 闪烁、无滑条）
- 继承：复现用户 HyperOS 版本；分片丢失修复优先级、冷启动验收标准。
