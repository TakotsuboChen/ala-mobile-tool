# HANDOFF — 读全文再开始干活

生成时间: 2026-09-14T22:33:00+08:00 · Git HEAD: `94b1691`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `94b1691`（2026-09-14 22:31）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `94b1691`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/PaddockClient.kt` 的 `wipeAuthIfFreshInstall`（进程门）/ `flushAuthToMailbox`（本会话产出的核心修复）

## 1. 当前目标
**已完成**：修复 NPatch 全新流程下「第一次开游戏误显示未登录 + 第二次开游戏登录态读到了却提示网络异常」。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因定案（两个 bug 同源）**：模块 App 与游戏进程**都**执行 `wipeAuthIfFreshInstall`，各持自己包的 `INSTALL_PREFS`。NPatch「清数据」把两进程内部 prefs 一并清掉 →「清数据」与「卸载重装」物理上不可区分 → 模块 App 登录写好权威信箱（60B）后，游戏进程首次冷启动也判「新安装」→ `clearAuth` 把信箱清成 12B 空 token → 信箱"存在即权威"否决 daemon 里明明存在的 token → 误判已登出（零 hook + 循环 Toast）。第二现象是同一空信箱的下游：门控走 ConfigProvider 读到 token 放行，但 `fetchMe` 用被否决的 `authToken=null` → 兜底文案 `TOAST_TEXT_OFFLINE`（"网络连接异常…"）。
- [V] **实机取证（修复前）**：`build/logs/logcat_paddock_repro_20260914_213821.log` — 21:40:48 模块 App `Mailbox write OK (60B)`；21:41:03 游戏进程 14871 `fresh install detected` → `Mailbox write OK (12B)` → `auth: mailbox authoritative → logged OUT` → `ACTIVATED (not logged in)`。21:41:19 游戏进程 18304 `gate pass` 与 `网络连接异常` 同时出现，中间夹 `Mailbox read OK (12B)` → `logged OUT`。
- [V] **修复后对照**：`build/logs/logcat_paddock_fix_20260914_222000.log` — 反例指纹全清零：`logged OUT` 0 / `网络连接异常` 0 / `no token (laps` 0 / `ACTIVATED (not logged in` 0。两次游戏启动（PID 2765 / 5925）均 `auth restored from mailbox (authoritative)` + `gate pass` + 问候「已成功连接到围场，欢迎 1 号车手 Takotsubo!」。游戏进程**从未**打印 `fresh install detected`（P0 生效）；模块 App PID 5112 打出 `Mailbox write OK (60B)`（P1 生效）。
- [V] **进程身份判据自证**：PID 2969/5112（模块 App）打出 `no remote reader injected (module process expected)`，PID 2765/5925（游戏）从不打——`remoteTokenReader != null` 与"谁是游戏进程"在实机日志中完全对齐。
- [V] **已提交**：修复切片 `6f77b66` + CLAUDE.md 约定 `94b1691`，均已 push。
- [V] **构建/lint**：`:app:lint --rerun-tasks` EXIT=0（62 warnings 全为既有基线）；`:app:assembleRelease`（跳过 `lintVital*` 环境崩溃）成功并装机验证。
- [V] 工作区 clean，与 origin/main 同步（`94b1691`）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint --rerun-tasks   → EXIT=0
BUILD SUCCESSFUL in 1m 39s
Lint found 62 warnings, 4 hints (and 3 errors and 15 warnings filtered by baseline lint-baseline.xml)

$ git status --short --branch -uall → ## main...origin/main（clean）
```

## 3. 决策与理由
- **P0 用进程身份门关闭游戏进程的 fresh-install 清理** [V]——游戏进程没有独立安装周期语义，它清信箱纯属误伤；`remoteTokenReader != null` 是既有模式里的可靠进程指纹（NPatch 下两进程包名相同，靠进程名判不出来）。否决方案：改 `INSTALL_PREFS` 命名加包名后缀——治不了「NPatch 清数据」这一触发源，游戏进程下次冷启动仍会另建标记再清一次。
- **P1 在 daemon flush 点同步补写信箱而非新增独立时机** [V]——`App.onServiceBind` 是"模块 App 冷启动且 service 已绑"的既有收敛点，会话修复正好复用其触发（模块 App 21:21:46 启动 → 21:21:49 游戏启动，3s 内信箱已被补写）。否决方案：在 `loadAuth` 里由游戏进程反向补写——违反 P0 的"写侧只归模块 App"收敛，且游戏进程无 AFA 时根本写不进他包 media。
- **`flushAuthToMailbox` 在 token 为空时 no-op** [V]——保住反向红线：绝不能用空 token 覆盖权威的「已登出」信号。调用点放在 `peekAuthToken() ?: return` 之后，helper 内再兜一层。
- **两修合为一片提交** [V]——P0/P1 是"存在即权威"设计被两头（不该写的写坏了 / 该写的没写全）各撕开一次的缺口，分开提交会让中间态仍不自洽。

## 4. 失败的尝试 — 不要再试
- **[X] 加"仲裁版本号 + 早退豁免"（踏板）** [V]——单向仲裁下对方的值已被丢弃，版本号只能触发"重发当前（错误的 0）值"。已整套回退。
- **[X] 只改 `resetPedalState` 给刹车 view 补送油门（踏板）** [V]——刹车 view 手上没有油门的真值，补送出来仍是 0，需"重建式"才有。
- **[X] 用"刹车抬起后第一条油门 MOVE 的间隔"当延迟判据（踏板）** [V]——那只是"手指下一次动"的时间，混入用户行为。
- 继承死路 [X]（详 `.handoffs/20260914210237-handoff.md` §4 与 `.handoffs/20260914223116-handoff.md`）：`coroutineScope{}` 内多源竞速 / 只加源数改 scope / `result::class.simpleName` 记日志 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / `MarkdownText` 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / 旧快照覆盖 configs 表 / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / `screencap` 裁图目测尺寸。

## 5. 已知坑
- ⚠️ **踏板日志无法反映"静默恢复"** [?](继承)——`pedal[*] MOVE` 只在对应 view 自身触摸事件时打印，跨 view 补送不可见。排查同类问题别只 grep MOVE 时间戳，要看状态机终止态。
- ⚠️ **同类坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无 weight 的可滚动正文 + 按钮同列，平板可能重演窄条。修法见 CLAUDE.md 弹窗排版铁律。
- ⚠️ **踏板修复未在反馈用户真机做"修复后仍能复现"的对照** [?](继承)——反馈用户已口头确认"好了"，但日志只能证明"0 个抬起未恢复"。
- ⚠️ **权限门视觉未实机验收 / bot 投递未端到端验证 / 信箱探针残留 / 永久 4xx 先提示后丢弃 / OkHttp 败者不可中断** [?](继承)——均未动。
- ⚠️ **`clearAuth` 的 `no service bound, daemon stores NOT cleared` 是设计内 digest** [V](本会话新)——NPatch 清数据后模块 App 首启、AFA 未授时必现一次；此时 daemon 侧确无残留（信箱写失败与 daemon 无关），随后的登录写会覆盖。**不要把它当故障指纹**。
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志去反驳用户体感。

## 6. 下一步（有序）
1. （可选，等用户决定）给 NPatch #147 补 `bug` 标签——网页表单重提，或等维护者补。
2. （可选）同法加固 `EulaDialog`/`UpdateDialog` 正文 weight（见 §5）。
3. （可选）验收权限门控页 / 真实群验证 bot 投递 / 清理信箱探针残留 / ABSdiag·TCdiag 降频。
4. （可选）让反馈用户日志确认出现 `pedal[DUAL] rearbitrate@...` 行——独立证实其装的是踏板修复版。

## 7. 留给用户的开放问题
- 本次围场修复是否要正式发版（涉及版本号，需用户定）？还是先转 release APK 私发验证？
- 踏板修复那行低频诊断 `pedal[DUAL] rearbitrate@...` 长期保留还是下个版本删掉？
- 信箱「存在即权威」的写方收敛是否够——是否要从根上改为由服务端 401 判定登出（需权衡反向红线）？
- EulaDialog / UpdateDialog 是否加同样 weight 加固？
