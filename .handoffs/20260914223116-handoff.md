# HANDOFF — 读全文再开始干活

生成时间: 2026-09-14T21:02:45+08:00 · Git HEAD: `09ab0af`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `09ab0af`（2026-09-14 21:00）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `09ab0af`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/PedalOverlayView.kt` 的 `arbitrateDual` / `rearbitrateAfterReset`（本会话产出的核心修复）

## 1. 当前目标
**已完成**：修复用户报的「全油门刹车、松开刹车后油门延迟恢复或干脆没反应」；双踏板跨 view 仲裁改重建式 + 松开时主动补送。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因定案**：按刹车数值（`BRAKE_VALUE`）策略下，旧仲裁是**单向**的——每 view 只写自己那半，策略命中时把对方 `arbitrated*` 置 0，但策略**不再命中时无法复原对方**（没有对方的值）。`resetPedalState`（刹车抬起）把共享 arbitrated 翻转后**只送清零**，油门侧真实值没有任何事件替它送出 → native 油门字段卡 0，直到油门 view 下一次触摸事件才被顺带补上。
- [V] **实机取证**：反馈用户日志（`ala_tool_log_20260913_231447.txt`）中"踩满油门+刹车屏蔽"片段共 18 个，其中 **4 个以"油门抬起、从未恢复"结束**；单条静默窗口最长 **15.7s**（用户设备自身日志复现同样签名）。作为对照，修复后的反馈用户日志（`ala_tool_log_20260914_203853.txt`，两段 DUAL 会话）**0 个"抬起未恢复"片段**。
- [V] **日志判据的局限（重要）**：`pedal[THROTTLE] MOVE` 只在**油门 view 自己的触摸事件**时打印，而本修复由**刹车 view** 在抬起瞬间送值，**不产生油门日志**。故"油门恢复延迟"无法从日志测出——只能用"屏蔽片段的结束方式"（恢复 vs 抬起未恢复）作判据。设备事件密的用户（自己 Meizu 20，满油 MOVE 间隔中位 ~75ms）体感始终"瞬间恢复"，不代表无此缺陷。
- [V] **已提交**：修复切片 `c5fcbe2` + CLAUDE.md 约定 `09ab0af`，均已 push。
- [V] **构建/lint**：`:app:lint` 退出码 0（62 warnings 全为既有基线）；`:app:assembleDebug` / `assembleRelease` 成功。
- [V] 工作区 clean，与 origin/main 同步（`09ab0af`）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint   → EXIT=0
BUILD SUCCESSFUL
Lint found 62 warnings, 4 hints (and 3 errors and 15 warnings filtered by baseline lint-baseline.xml)

$ git status --short --branch -uall → ## main...origin/main（clean）
```

## 3. 决策与理由
- **仲裁改"重建式"而非打补丁** [V]——每个 view 记自己的曲线输出（`sharedThrottleMapped`/`sharedBrakeMapped`），仲裁时从两者重建完整一对再按策略裁决，使"屏蔽/解屏蔽"对称。否决方案：给早退加"仲裁版本号"豁免（曾实现又回退），因为需要补发的**正是对方那个值**，而单向写法下那个值已经丢了，版本号无从判断该发什么。
- **`rearbitrateAfterReset()` 直接复用 `arbitrateDual`** [V]——抬起后把本 view 的曲线输出临时置 0 再跑完整仲裁，保证与常规 MOVE 路径结果**逐条一致**，避免两处策略逻辑漂移。否决方案：另写一份简化的 `throttleWins` 判断，会与主路径产生分歧。
- **sentinel 按通道清** [V]——刹车抬起只清 brake 通道的 `lastSent*`/`lastDrawn*`，throttle 侧保留；否则重算出的油门恢复值会与 NaN 比较被值不变早退吞掉。这是上一版没跑通的真正原因。
- **保留一行低频诊断** [V]——`pedal[DUAL] rearbitrate@...` 每次抬脚一条，用于区分新旧构建（旧实现此处只送清零）。

## 4. 失败的尝试 — 不要再试
- **[X] 加"仲裁版本号 + 早退豁免"** [V]——无法工作：单向仲裁下对方的值已被丢弃，版本号只能触发"重发当前（错误的 0）值"，不会恢复真实油门。已整套回退。
- **[X] 只改 `resetPedalState` 给刹车 view 补送油门** [V](中途版本)——刹车 view 手上没有油门的当前值（油门 view 上次写入时写的是被屏蔽的 0），补送出来仍是 0，需"重建式"才有真值。
- **[X] 用"刹车抬起后第一条油门 MOVE 的间隔"当延迟判据** [V]——那只是"手指下一次动"的时间，混入用户行为，无法反映系统恢复时刻（本人设备测得中位 415ms/对方 61ms，纯属手指行为差异）。正确判据 = 屏蔽片段的**结束方式**。
- 继承死路 [X]（详 `.handoffs/20260914210237-handoff.md` §4）：`coroutineScope{}` 内多源竞速 / 只加源数改 scope / `result::class.simpleName` 记日志 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / `MarkdownText` 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / 旧快照覆盖 configs 表 / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / `screencap` 裁图目测尺寸。

## 5. 已知坑
- ⚠️ **踏板日志无法反映"静默恢复"** [?](本会话新定案)——`pedal[*] MOVE` 只在对应 view 自身触摸事件时打印，跨 view 补送不可见。排查同类问题别只 grep MOVE 时间戳，要看状态机终止态。
- ⚠️ **同类坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无 weight 的可滚动正文 + 按钮同列，平板可能重演窄条；手机不触发 largeScreen。修法见 CLAUDE.md 弹窗排版铁律。
- ⚠️ **本次修复未在反馈用户真机做"修复后仍能复现"的对照** [?]——反馈用户已口头确认"好了"，但日志只能证明"0 个抬起未恢复"，不能证明诊断行 `pedal[DUAL] rearbitrate@...` 出现在其日志（可作下次的独立确认）。
- ⚠️ **权限门视觉未实机验收 / bot 投递未端到端验证 / 信箱探针残留 / 永久 4xx 先提示后丢弃 / OkHttp 败者不可中断** [?](继承)——均未动。
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序先拉触摸时间线。**新增 6：日志判不了的结论不要用日志去反驳用户体感**（本会话教训，见 §3/§4）。

## 6. 下一步（有序）
1. （可选，等用户决定）让反馈用户日志确认出现 `pedal[DUAL] rearbitrate@...` 行——独立证实其装的是修复版。
2. （可选，等用户决定）给 NPatch #147 补 `bug` 标签——网页表单重提，或等维护者补。
3. （可选）同法加固 `EulaDialog`/`UpdateDialog` 正文 weight（见 §5）。
4. （可选）验收权限门控页 / 真实群验证 bot 投递 / 清理信箱探针残留 / ABSdiag·TCdiag 降频。

## 7. 留给用户的开放问题
- 修复后的 release APK 是否要正式发版给反馈用户（涉及版本号，需用户定）？还是先转 APK 私发验证？
- 那行低频诊断 `pedal[DUAL] rearbitrate@...` 长期保留还是下个版本删掉？
- 本会话启动的 6 个后台调研 Agent 被用户中断（NPatch 相关，任务已由上会话收尾），无需处理——仅备案。
- NPatch #147 的 `bug` 标签：重提表单 / 不管它？
- EulaDialog / UpdateDialog 是否加同样 weight 加固？
