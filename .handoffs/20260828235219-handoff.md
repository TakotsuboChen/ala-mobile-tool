# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T23:30:50+0800 · Git HEAD: `2ae9f86`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `2ae9f86` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `2ae9f86`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `PedalOverlayView.kt` 的 `onDraw` KDoc（边框缝隙方案的单一事实源，含 ①②③ 历史教训）+ `TC_LEVEL_DESIGN.md` §10 v1.5 + `ABS_LEVEL_DESIGN.md` §4 + `.handoffs/20260828233050-handoff.md` §4（死路总账）

## 1. 当前目标
踏板/换挡控件边框-填充的缝隙+重合双重修复**已实机验证通过**（用户原话"非常完美"）：层内不透明 + 整层 alpha 承担半透明，圆角无缝且半透明无重合。头号任务（多指触摸 bug）不变：插桩链路已通，仍等复现用户装新 APK 复现回传。

## 2. 已验证状态 — 工作实际停在哪
- [V] **边框缝隙修复落地并实机验证**：`PedalOverlayView.kt` + `GearShiftView.kt`（commit `2ae9f86`，+124/-79）——① 层内全部不透明色绘制；② `saveLayer(0,0,w,h, layerPaint)`，`layerPaint.alpha = alphaOf(overlayAlpha)`，半透明由整层 alpha 统一承担；③ 填充 `fillInset = sw - 1.5f`（伸进边框区 1.5px 垫住边框内缘渐变带）+ `clipRect` 裁行程带；④ 边框最后画：API 29+ 用 `drawDoubleRoundRect` 环带（inner=sw，FILL 模式），API 26-28 回退 STROKE；⑤ 不透明边框实体完全遮盖填充溢出。
- [V] **实机验证通过**（MEIZU 20，381QYFCN22B9A）：半透明+圆角+边框组合下圆角处无背景缝、边框处无重合脏色、整体透明度视觉不变。用户确认"非常完美"。
- [V] **构建+lint**：`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL、`EXIT=0`（0 errors）。
- [V] **装机**：`adb install -r` Success（force-stop 后），release APK。
- [V] 工作区：干净，`main` 与 `origin/main` 同步（`2ae9f86` 边框修复；持久文档无改动，本次跳过 docs 切片）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 2s（增量），EXIT=0
首轮完整构建（同改动）：BUILD SUCCESSFUL in 2m 16s，lint 0 errors
adb install -r → Success（force-stop 后）
git push → d50c9f5..2ae9f86 main（成功）
```

## 3. 决策与理由
- **半透明以整层 alpha 承担，层内全不透明** [V]——数学根因：两条渐变 coverage 互补 ≠ over 合成链 alpha 互补（渐变中点 alpha = c+(1-c)² = 0.75，恒透背景 25%）；层内不透明使"边框实体遮盖填充溢出"恢复物理特权，任何渲染路径的圆弧 coverage 亚像素偏差只造成颜色过渡、不产生透明缝。半透明视觉与逐像素方案等价。否决：逐像素半透明下的所有"精确互补"方案（clipPath/Path.op/DRRect 三轮实测全缝）。
- **填充溢出方向 = `fillInset = sw - 1.5f`** [V]——内缩量变小才是伸进边框区；写反成 `sw + 1.5f` 会在边框内缘与填充之间留 1.5px 均匀透明带（实测踩过）。
- **GearShiftView 文字画在层外** [V]——保持不透明白，不随控件透明度变淡（原行为）。
- **API 29+ 走 DRRect、26-28 回退 STROKE** [V]——`drawDoubleRoundRect` 是 API 29+；minSdk 26 需 gate。层内不透明下 STROKE/DRRect 均无缝（偏差被不透明底吸收），fallback 仅是保底。
- **继承（曲线收口）**：曲率最小化已是插值语义数学最优（信息论边界），重启曲线走预设档+强度滑块；数学误差 -40% ≠ 感知改善，先做感知阈值实验。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（边框缝隙，全部 [V] 实机截图证实）
- [X] **Path.op 几何交集精确内缩**（drawPath 画"控件形状∩行程带"，填充精确止于 sw）→ drawPath 的圆弧 AA（tessellation/路径渲染）与 drawRoundRect STROKE 的解析式圆弧 AA 不同源，圆角缝隙回归。**不要再试"两次独立绘制的渐变精确互补"**。
- [X] **DRRect 环（drawDoubleRoundRect）+ 精确内缩**（仍逐像素半透明）→ 环内边缘与填充外边缘是两次独立 over 绘制，渐变 coverage 互补 ≠ alpha 互补（中点透背景 25%），圆角仍有亚像素透明缝。**教训：同源 coverage 也没用，over 链数学上无解，必须换结构（层内不透明）。**
- [X] **层内不透明但溢出方向写反**（`fillInset = sw + 1.5f`，比边框内缘还多缩 1.5px）→ 边框内缘与填充之间留下 1.5px 均匀透明带（比原缝隙更大更匀）。**内缩量变小才是伸进边框。**
- [X] **验证脚本与实现同人同构盲区**（继承）→ regex 类断言先拿真实样例行匹配。
- 继承（触摸诊断链路，全部 [V]）：用 commit 时间推 APK 构建内容（判 APK 内容用运行时指纹）；"导出零数据=模块旧"单因推断（先对比发送量与缓存实收量）；以最坏情况揣测用户操作；adb 调试时 force-stop 用户正在使用的设备（动设备前确认用户不在操作）。
- 继承（曲线研究，全部 [V]）：画笔模式技术成功但被用户否决（"只认控制点"）；"换算法任意形状"三次撞墙（信息论边界）；Python 镜像两截获 bug；"数学误差降低≈可感知"被实测推翻。
- 继承（档位调优，全部 [V]）：enum 重命名顺手改存档 value 字符串 → 旧配置静默 fallback；把过期注释当需求事实源。
- 继承（手势/测量，全部 [V] 证伪，勿重试）：滑块 v1/v2（帧数 1/6）/"设备性能"归因/GestureAxisBlocker 整向量消费/`pointerSlop()` API 无/perfetto shorthand tags/测量期间手触/miuix 查源码先联网。
- 继承（更早，[?] 除标注外）：押 p0 主旋钮被推翻；usesABS 恢复漏置位；档位差异化不足；0x3D4 读法未解；bang-bang 方向反直觉；只写 TCLSlip=eps 挡死起步；每帧写 ctor 默认≠真值；llvm-objdump 传文件偏移；assembleRelease 不跑 lint；TractionControlDynamicAssist 仅玩家车；IRDSPlayerControls.tractionControl 死字段；math 公式内中文/裸竖线/operatorname；AI 车 is_player_controller 过滤不可靠；触摸 bug id 跟踪/findPointerIndex 修不了；proxy_shift_up 打日志洪水（已修）。
- [V] 继承：只写 absEnable 不够（唯一门控 per-wheel usesABS）；后台 pthread 调 Unity API 崩溃；dlopen 不可用改 ELF 符号查找；adb install -r 前必须 force-stop；awaitLsposedSettled 不可靠；kkgithub 404 用 gh-proxy；CHUNK_SIZE=256K 防 TransactionTooLargeException（但分片仍会丢，见 §5）。

## 5. 已知坑
- ⚠️ **日志推送分片丢失（待修，用户裁决搁置）** [?]——LogReceiver 分片拼装无完整性校验：发 476632 缓存 216619（丢尾）、发 311568 缓存 0 增量（全丢）。修复方向：分片序号+总数对齐才落盘、缺片重发，或模块进程直读游戏 externalFilesDir 为主路径。
- ⚠️ **flyme 冻结后台游戏进程，REQUEST_LOGS 延迟送达** [?]——广播延迟 2min 送达，导出拿旧快照。加固方向：游戏 onPause 时主动推送。
- ⚠️ **多指触摸 bug 未解（头号任务）** [?]——插桩链路已打通（`pedal[` 日志 + 24h 导出），等复现用户装新 APK 复现回传。三候选根因：(a) 意外事件重排 (b) 坐标混入他指 (c) pairip relayout；附加链：reset→游戏接管抽搐。注意：复现用户设备可能一直跑旧模块，2b5997c 修复从未被真正验证。
- ⚠️ **配置页"任何时候满帧"未达成** [?]（继承）——冷启动 18-19% janky，主因候选 baseline profile 缺失；动手前重跑 A/B 基线。
- ⚠️ 曲线编辑器拖点高频路径含 QP 求解 [?]（继承）——LRU 缓存 + 早停，未见性能问题。
- ⚠️ pager settle 回抓 / 滑块 v3 快弹簧回落 [?]（继承）——用户未抱怨，暂不动。
- ⚠️ 0x414 疑似死参数 / 0x3D4 读法未解 / TC 开关位每帧重算（已绕过）/ 游戏内 ABS 设置对物理无效 / 计时赛 IRDSUIMobileControls 晚 ~2s / LSPosed 下 Remote Preferences 受限 / BillingHook NPatch 永远失败 / flyme 后台白名单 / 广播 JSON 不含 position / offsets_sheet 0x1A62E10 勘误未订正 [?]（继承）；GitHub 公式/Mermaid 渲染坑（先读持久记忆）；IL2CPP ARM64 扫描三坑。

## 6. 下一步（有序）
1. **等复现用户回传新日志**（头号，等待中）——指引：装 22:14 构建 APK（含插桩+24h 过滤）→ 开 NPatch 管理器 → force-stop 游戏 → 复现"一手踏板+一手点空白" → 前台导出。成功标志：日志无 `proxy_shift_up called` 且有 `pedal[` 行。
2. **日志到手后裁决三候选根因**——(a) 跳变瞬间 action 序列；(b) MOVE 值链是否混入他指；(c) DOWN 布局对比是否漂移。若 reset→游戏接管抽搐成立 → 评估 native 踏板开启期间每帧强制写。
3. （用户裁决搁置，重启时做）**日志推送分片丢失修复**——完整性校验或直读主路径。
4. （可选加固）**onPause 主动推送**——防 flyme 冻结导致导出旧快照。
5. 冷启动满帧结构性修复（长期）——baseline profile + 配置页拆分，动手前重跑 A/B 基线。
6. ABS 二期（低优先级）；AI 车白名单长期观察。

## 7. 留给用户的开放问题
- 复现用户装新 APK 后：proxy 洪水是否消失（NPatch 唤醒/模块更新成功的标志）？若仍出现 → NPatch 注入机制需要单独排查。
- 分片丢失修复的优先级：等下一轮日志回传若再次零数据就升级优先级。
- 冷启动验收标准：首分钟不卡（baseline profile）还是完全追平 KernelSU 满帧（拆配置页结构）？
- TC 新档位 + 制动压力 50% 下限的实机手感（继承，两轮未答）。
- 曲线话题若重启：预设档+强度滑块方案是否采用（见上会话 handoff §3）。