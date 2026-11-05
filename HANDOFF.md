# HANDOFF — 读全文再开始干活

生成时间: 2026-11-05T21:05:00+08:00 · Git HEAD: `8cb5718`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8cb5718`（工作切片 `914393a` + 固化切片 `8cb5718`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `8cb5718`——HEAD 必是本次 handoff 提交（步骤 7），其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5 标 `[?]` 的条目
- 先读: [`docs/notes/NPATCH_CACHE_CRASH_NOTES.md`](docs/notes/NPATCH_CACHE_CRASH_NOTES.md) 顶部五 bug 对照表 + §15（Bug E）

## 1. 当前目标
无进行中的功能切片。本会话完成：**修复「抓地力反馈选视觉模式仍有振动」**
（`OverlayManager` 一处改动），实机验证通过并已装机。

## 2. 已验证状态 — 工作实际停在哪
- [V] **修复已提交**：`914393a`，`OverlayManager.kt:533` 改为
  `m.tick(if (slipHapticOn) slipLv else 0f, kerbLv, kerbRate, nowMs)`。
- [V] **根因**：`wantHaptic = slipHapticOn || kerbOn` 决定 mixer 建不建（路肩单独开也必须建，
  两条振感要合成一条波形），但旧实现随后**无条件**把 `slipLv` 传进 `tick` ⇒
  「视觉 + 路肩开」时抓地力照样振。
- [V] **lint**：`./gradlew :app:lint` → `BUILD SUCCESSFUL in 2m 5s`，0 error。
- [V] **构建**：`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease`
  → `BUILD SUCCESSFUL in 1m 40s`，APK 11,425,343 B。
- [V] **产物实证三层**：① APK `12:02:58` > 源码 `11:58:56`；② `javap` 见
  `addGamingOverlays$lambda$2$0` 的 `ifeq/fconst_0` 分支；③ APK 内 dex 的 `Lcm1;`
  （`public final synthetic`，字段 `e:Lsn0;`/`f:Z`）里 `iget-boolean → if-eqz → move v1, v7(0f)`。
- [V] **已装机**：`MEIZU_20`（192.168.50.142:5555），`lastUpdateTime=2026-11-05 20:56:02`，
  版本仍 `1.0.5 Alpha 1 (105110)`，**未升版**。
- [V] **实机验证通过**：用户配置 `slip_feedback_mode=visual` + `kerb_haptic_enabled=true`
  （读设备 `ala_tool_config.json` 确认）。本次会话 10 条 `HapticMixer` 日志全部
  `shape none→kerb-only slip=0`，**无任何 `grip-flat`**；路肩照常 `level=1.00`。
  对照 11-04 旧构建同配置是 `shape none→grip-flat slip=27`。
- 工作区: 干净，`main...origin/main` 已同步。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 2m 5s
Lint found 67 warnings, 4 hints (2 errors/14 warnings filtered by baseline)

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 40s

$ adb install -r app-release.apk
Performing Streamed Install
Success
```

## 3. 决策与理由
- **归零落在实参层，不改 `tick` 内部的 `peak` 判据** [V]——`buildMixedAmps` 会把
  `slipCarrier` 逐段取大进波形，只改判据会让路肩脉冲之间的静默段露出抓地力载波
  （= 用户 2026-10-05 禁止的「松松散散」）。副作用：`shape ... slip=N` 日志字段
  得以继续作为判据（若改判据，日志会显示 `slip=27` 而波形无载波，判据失效）。
- **不拆 mixer、不按模式跳过创建** [V]——路肩振感挂在同一 mixer 上，两条振感必须合成
  一条波形（`HapticMixer` 类注释「一」）。拆了会让路肩与抓地力互相打断。
- **现象 2（「全部」模式踩油门不振）判定为预期行为，未改代码** [V]——日志核对：
  高速直道满油门时 `maxRearZ≈0.5`、`maxLon≈0.6`，均低于起振点（`SPIN_KNEE_Z=1.80` /
  纵向 `1.00`）；而低速起步烧胎（`maxRearZ=4.7`）与锁死会振。抓地力反馈是「轮胎
  用到极限」的提示器，不是油门反馈。用户已选「先看修完视觉 bug 后的表现」。

## 4. 失败的尝试 — 不要再试
- [X] **只改 `tick` 内部的 `peak` 判据来屏蔽抓地力**——载波仍会进波形（见 §3 第一条）。
- [X] **视觉模式不建 mixer**——会连带掐死路肩振感。
- [X] 继承死路（详 `.handoffs/20261105210241-handoff.md` §4 及更早）：靠业务日志判断模块是否注入 /
  `Mailbox write OK` 当游戏侧通道证据 / 「不崩溃+功能全无」直接判 Bug C /
  `@{围场用户名}` 做 TG 提及降级 / 无身份时"只显示名字不加 @" / 容器加 `HTTP_PROXY` 不配 `NO_PROXY` /
  修改已应用的迁移文件 / `text_mention` 当 `@username` / `teloxide` / 共用 QQ 的 `SendJob` /
  启动时自动 `setWebhook` / 本地共用生产 token / `getUpdates` 探活 / `try_send` 入队 /
  `handle_*` 返回空串表示静默 / 整圈五维全填 0 / `strings | grep` 搜 APK 中文 /
  `baksmali` 后 grep 中文 / 条件调用 `remember` / `PickVisualMedia` / `createChooser` /
  `ResolveInfo.loadLabel()` / 相信 `UP-TO-DATE` / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / `scrollToItem` 跨数据集归位 /
  `firstVisibleItemIndex > 0` 才归位。

## 5. 已知坑
- ⚠️ **`LogExporter` 对「段缺失」是静默的** [?]——`foundAny` 只要模块日志读到就是 `true`
  （`util/LogExporter.kt:180`），「游戏进程日志缺失」**完全不提示**。**待改**：每段显式
  输出 `[未找到：<路径>]`。本会话未实施，是 §6 第 1 项。
- ⚠️ **`baksmali` 输出目录别放 `/tmp`** [V]（本会话踩到）——本机 `/tmp` 是 4.9G tmpfs，
  3.9MB dex 全量反汇编会报 `Not enough space` 并吐 `hs_err` 段。用 `build/dexverify/`
  （已被 `.gitignore:16` 的 `build/` 覆盖），用完 `rm -rf`。
- ⚠️ **R8 内联会让 `javap` 层看不到调用点** [V]（本会话踩到）——lambda 被内联进目标方法后，
  mapping/dex 里搜不到 `tick` 是正常的，**不能据此判定改动没进包**；要看 lambda 类本身。
- ⚠️ **NPatch 的 `android.util.Log` 只进 logcat 不落文件** [V]——文件日志只有 XLog。
- ⚠️ **Bug E 不需要 logcat，Bug C 必须** [V]——先数导出日志段数。
- ⚠️ **VPS 直连 `api.telegram.org` 被 DNS 污染** [V]（继承）；**`NO_PROXY` 不可省** [V]（继承）。
- ⚠️ **已应用的迁移文件不能改** [V]（继承）。
- ⚠️ **Telegram 隐私模式对已存在的群不立即生效** [V]（继承）；**`entity` 偏移是 UTF-16 code unit** [V]（继承）。
- ⚠️ **`tg_broadcast_chats` 与 `bot_broadcast_groups` 是两套独立配置** [V]（继承）。
- ⚠️ **`@username` 补录需要用户先发过言或点「检查状态」** [V]（继承）。
- ⚠️ **`edited_message` 当前忽略** [V]（继承）。
- ⚠️ **`bot_known_groups` 4 群 vs `bot_broadcast_groups` 2 群** [?]（继承，疑正常）。
- ⚠️ **`reqwest` 对 `NO_PROXY` 的 CIDR 语法未单独验证** [?]（继承）。
- ⚠️ **supergroup 迁移链路未实机触发** [?]（继承）。
- ⚠️ **QQ markdown 通道对纯 `***`/`# ` 的渲染未真机验证** [?]（继承）。
- ⚠️ **魅族 20 的 IP 会来回变** [V]（继承）——`.142`/`.123` 都出现过。
- ⚠️ **覆盖重发后已装用户收不到更新提示** [V]（继承）——更新检查按 versionCode 比较。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间** [V]（继承）——按圈速排用 `lap_desc`/`lap_asc`。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算** [V]（继承）。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 数据展示口径（精度/小数位/间距）先问再定
  17 下断言前先取证 18 大段文档写文件不贴终端 19 日志里有某行 ≠ 该行被执行了
  20 **用户说「我的设备没事但别人的可能有问题」时，不能因为本机复现不了就否定**

## 6. 下一步（有序）
1. **（待你决定）修 `LogExporter` 的「段缺失静默」**：每段显式输出
   `=== 游戏进程日志 (Java) ===` + `[未找到：<路径>]`，让缺失本身成为证据。
   独立小改动，与任何功能无关。§5 第一条有详细说明。
2. **（待你决定）「全部」模式踩油门的体验**：本次判定为预期行为（高速直道不打滑），
   用户选择先看修复后表现。若仍觉得需要油门反馈，那是**功能定位调整**（需先定阈值与幅度），
   不是改 bug。
3. **（待你决定）存量用户的 `@username` 补齐**（服务端）：可考虑启动时后台跑一次
   `backfill_usernames`（需限速，逐群 `getChatMember`）。
4. **（待你决定）Telegram 播报目标群提示**：管理端「勾了 QQ 不等于 TG 也会播报」缺说明。
5. **（待你决定）服务端 1.0.2 发版**——版本号是你的决策域。
6. **（待你决定）「应用调教」功能**：数据已能取到、已入库、已能展示，回写游戏未做。
7. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- `LogExporter` 段缺失提示要不要做？（第 1 项）
- 本次修复要不要进下一个版本的 Release Notes？
- Bug E 要不要写进用户侧 FAQ / 围场指南？目前只在开发侧文档。
- 存量用户 `@username` 补齐要不要做成启动时后台任务？限速策略如何定？
- 管理端要不要提示「QQ 群勾选不等于 TG 群也播报」？
- 服务端这次要不要发版？（版本号由你定）
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
