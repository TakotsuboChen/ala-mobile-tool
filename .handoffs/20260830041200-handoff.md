# HANDOFF — 读全文再开始干活

生成时间: 2026-08-30T00:49:12+08:00 · Git HEAD: `16db222`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `16db222` (2026-08-30)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `16db222`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/OverlayManager.kt`（position 双轨机制的入口，`resolveLatestSettings` 注释解释了合并链路）

## 1. 当前目标
工具按钮"记忆位置"功能已完成并实机验收通过（用户裁决"可以，测试通过"）。主线等待项不变：①多指踏板污染修复等复现用户日志回传裁决；②Bug B（重启 remote 配置旧值）无新证据。

## 2. 已验证状态 — 工作实际停在哪
- [V] **工具按钮位置记忆已实装（`76606ad`，3 文件）并实机验收通过**：拖动结束 `onPositionChanged` → `saveOverlayPosition(KEY_TOOL_POSITION)` 写游戏进程 externalFilesDir（与踏板/换挡同机制）；`showOverlays` 时 `applySavedPosition()`（原 `resetToDefault` 改名）回放 `settings.toolButtonPosition`。默认启用、无新配置项——未拖过时本地无该 key，落回 `Defaults.TOOL_BUTTON_POSITION`，行为与旧版一致。用户实机测试回传"可以，测试通过"。
- [V] **`KEY_TOOL_POSITION` 纳入 `POSITION_KEYS`**：这是本功能的成立前提——`ConfigReceiver` 合并写跳过该 key（防广播覆盖）+ `mergePositionFromLocalPublic` 回读复制该 key（防重建丢位置）。漏掉就是 M41 位置丢失同款根因。
- [V] **上界钳制补齐（拖动+回放两处）**：位置持久化后"拖出屏外"从自愈问题变永久事故（无 UI 可救），`coerceIn(0, 屏-半钮)` 双向钳制；回放钳制兼防跨设备比例越界。
- [V] **构建+lint**：`./gradlew :app:assembleDebug :app:lint` → BUILD SUCCESSFUL，EXIT=0，lint 0 errors（44 warnings 既有）。`./gradlew :app:assembleRelease` EXIT=0。
- [V] **实机 APK 已装**（adb install -r → Success，设备 381QYFCN22B9A，release 构建）。
- [V] **README 同步（`16db222`）**：工具按钮"不持久化"描述改"默认记忆"，路线图勾选"工具按钮位置记忆"。
- 工作区: 干净，`main` 与 origin 同步（`76606ad` 工作 + `16db222` README + 本次 handoff 提交）。
- 继承 [?]：多指污染修复等红米用户日志回传；Bug B 未复现未修。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:lint → BUILD SUCCESSFUL in 1m 40s，EXIT=0（lint 0 errors）
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 48s，EXIT=0
adb install -r app-release.apk → Success
git push → 9fb14bd..76606ad..16db222 main（工作+README 两切片，成功）
```

## 3. 决策与理由
- **复用双轨 position 机制而非新配置项** [V]——`KEY_TOOL_POSITION` 常量和 `toolButtonPosition` 字段是当年"每次重置"需求实现时预留的（注释明说"为以后加记忆位置开关时零架构改动"），读取管道（fromJson 两路径）早已打通，本次只接写回调 + 补 POSITION_KEYS。零新增配置面。
- **默认启用记忆、不设开关** [V]——用户明示"不用在配置新增任何东西，默认启用"。旧"每次重置"需求被本次需求替代，README/注释同步。
- **`resetToDefault` 改名 `applySavedPosition`** [V]——语义准确：回放的是记忆值，未拖过时恰为默认值。调用边界不变：只在 `showOverlays` 调（进场一次），`toggleOverlays`/`rebuildFromConfigChange` 不回放（拖动位置已在 view layoutParams 上，回放反而覆盖）。
- **上界 = 屏幕减半个按钮** [V]——允许按钮大半出屏但留可抓边缘；下界 0 不变。拖动与回放两处都钳，单缺一处即漏。
- 继承：stash+遍历恢复（hide_pedals）、双源校验（踏板多指）、v6 饱和重映射定案——见 `.handoffs/20260830004912-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 继承死路（制动压力滑条 v2-v5 演化，均已实装后撤销）
- [X] **v2 全局缩 T_b** → F_base 曲线联动压缩。**v3 p₀ 同缩** → 绝对值仍缩。**v4 门控分流** → 与 ABS 开关耦合。**v5 输入端线性缩放** → 满踩被压违反封顶可达。四条全否决，v6 饱和重映射定案（详见 ABS_LEVEL_DESIGN §4）。
- [V] **UI 擅改 summary 文案** → 用户两次纠正"没让你改描述"。UI 文案不动，除非用户明示。
- [?] **ESC 干预与重映射通道叠加**（推演未实测）——ESC 多数用户关闭，实机若发现 ESC 校准异常再做通道隔离。

### 继承死路（更早，全部仍有效）
- [V] 断言"show taps 圆点不跟随"→ 官方文档证伪；GameTurbo 设置层 A/B 无效；MotionEvent.isResampled 编译失败；getRawX/Y 直调 API 29+ lint 报错（统一走 helper）。
- [?] OnTouchListener 探 decorView 层事件（框架推理未实测）。
- [V] 边框缝隙数学无解必须层内不透明；档位值 0.90 贴 native clamp 静默截断；押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够；每帧写 ctor 默认≠真值；画笔模式技术成功被用户否决；commit 时间推 APK 内容单因推断；adb force-stop 用户在用设备；滑块 v1/v2 帧数 1/6 归因错误、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网；proxy_shift_up 打日志洪水（18810 条/21min）；IL2CPP 不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **多指污染待回传裁决** [?]（继承）——修复已给复现用户，回传后按三分支裁决（`.handoffs/20260829205358-handoff.md` §6）。
- ⚠️ **隐藏踏板"重启后仍隐藏"= remote 配置旧值（Bug B）** [?]（继承）——无新证据，实测未复现则搁置。
- ⚠️ **ConfigReceiver 与 15s init 竞态窗口** [?]（继承，窄窗口暂不修）。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）。导出为空排查经验 [V]：72 字节=游戏侧 logEnabled 未开；release 包可直读 `/sdcard/Android/data/<游戏包>/files/ala_tool*.log` 兜底。
- ⚠️ **v6 重映射的 pulse 相位边界** [?]（继承）——pulse 帧 tempBrakeF=0 时 bp 采样可能异常，未深究。
- ⚠️ **ABSdiag 的 0x414 死参数/0x3D4 读法/offsets_sheet 0x1A62E10 勘误** [?]（继承，未恶化不动）。
- ⚠️ 曲线编辑器 QP 高频路径/pager settle 回抓/滑块 v3 快弹簧/冷启动 janky [?]（继承，未恶化不动）。

## 6. 下一步（有序）
1. **等复现用户回传多指日志**（核心等待，继承）——回传后按三分支裁决。
2. （可选）追 ABS 档位 0.80/0.60/0.40 实机手感（继承，v6 定案后档位语义未变）。
3. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码（继承，grep 确认零调用）。
4. 若用户反馈 ESC 场景制动异常 → 按 `.handoffs/20260830004912-handoff.md` §4 做通道隔离。

## 7. 留给用户的开放问题
- 工具按钮记忆位置无 UI 重置入口——用户若想回默认位置，需清应用数据或等未来加"长按重置自身"（当前长按=进编辑模式）。是否需要单独重置手势？
- 继承：v6 手感验收（刹车点/循迹刹车实战）；复现用户 HyperOS 版本；分片丢失修复优先级、冷启动验收标准、ABS 高档手感是否可感。
