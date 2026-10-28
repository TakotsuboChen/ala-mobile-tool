# HANDOFF — 读全文再开始干活

生成时间: 2026-10-28T12:09:10+08:00 · Git HEAD: `53c4bde`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `53c4bde`（已 push）· 服务端无改动（仍 `75def15`，线上 `1.0.1`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `53c4bde`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/design/PADDOCK_PLAN.md`](docs/design/PADDOCK_PLAN.md) §4 的
  `/v1/leaderboard/track` 段（2026-10-28 新增「圈速详情的取数路径」）

## 1. 当前目标
无进行中的功能切片。本会话完成：**计时赛赛道榜点行弹圈速详情**（数据走客户端
二次请求，服务端零改动），底部改为左灰「关闭」+ 右蓝「查看主页」；车手主页不动。

## 2. 已验证状态 — 工作实际停在哪
- [V] **功能已实现**：`DriverScreen.kt`（`LapDetailDialog` 参数化 + 正文抽成
  `LapDetailBody`）、`LeaderboardScreen.kt`（点击二次请求 + 弹窗挂载）。
  `git diff --stat`：+273 / −100。
- [V] **构建**：`./gradlew :app:assembleRelease :app:lint
  -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` →
  `BUILD SUCCESSFUL in 2m 31s`；lint `67 warnings, 4 hints`（2 errors / 14 warnings
  被 baseline 过滤）→ **0 error**。
- [V] **产物实证**：APK 时间戳 `2026-10-28 11:59:39` 晚于最后源码修改；
  `grep -ac "查看主页"` / `"加载失败"` 各命中 1；`mapping.txt` → `LeaderboardScreenKt -> i11`、
  `DriverScreenKt -> ha0`；`baksmali` 得 `i11.smali` 含 `const-string "lap_asc"` +
  `const/4 v5,0x1`(best) + `const/4 v7,0x1`(limit=1) + 加载中/加载失败分支；
  `yv.smali` 含 `"查看主页"` + **2 处** `"关闭"`（对应单/双按钮两分支）。
- [V] **装机并人工验收**：魅族 20（`192.168.50.142:5555`）`Success`，
  `lastUpdateTime=2026-10-28 12:02:48`，`firstInstallTime=2026-08-13` 未变（覆盖非重装，
  登录态未被 `wipeAuthIfFreshInstall` 清）。版本仍 `1.0.4 Beta 3 (104230)`（未升版）。
  **用户确认「测试没问题」**。
- [V] **工作区干净**：三个切片均已 push。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease :app:lint -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 31s
78 actionable tasks: 25 executed, 53 up-to-date
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline lint-baseline.xml)
```

## 3. 决策与理由
- **数据走客户端二次请求 `/v1/driver?best=1&sort=lap_asc&limit=1`**（用户 2026-10-28
  定案）[V]——`best_laps` 是榜单读模型（窄），详情要的 `created`/`pedal`/`tc`/`abs`/
  `car_id`/`setup` 全在 `laps` 全量表里。两处同源：榜单 `DISTINCT ON (user_id)
  ORDER BY lap_ms ASC`，driver 的 `best=1` 按 `lap_ms` 相等判。
  否决：服务端扩展榜单端点 JOIN laps（响应体 ×3、要改契约 + 部署 VPS，只省一次往返）。
  见 memory `lap-detail-via-driver-endpoint`。
- **加载态 = 立即弹窗 + 正文区「加载中…」**（用户定案）[V]——点下去立刻有反馈；
  「查看主页」不依赖详情数据，加载期间也可点。否决：等数据回来再弹（弱网时点了没动静）。
- **「查看主页」先关弹窗、`onDismissFinished` 里再 push**（用户定案）[V]——
  直接 push 会让弹窗与页面转场动画重叠。
- **`visibleBoard` 从 `Pair(tab,track)` 扩成 `Triple(tab,track,version)`** [V]——
  榜单切换有 150ms 淡出窗口，期间点到的仍是旧行；现读 `selectedVersion` 会拿新条件
  查旧行 ⇒ 版本对不上**且不报错**。

## 4. 失败的尝试 — 不要再试
- [X] **用 `strings | grep` 搜 APK 里的中文** → 2026-10-28 实测恒零命中
  （GNU strings 2.44 只输出可打印 ASCII，CJK 字节直接跳过），差点误判成"新代码没进包"。
  **改用 `grep -ac`**；CLAUDE.md 里的旧写法已修。见 memory `apk-artifact-verification-chain`。
- [X] **`baksmali` 后用中文直接 grep** → 输出是 `\uXXXX` 转义，恒零命中。
  正确搜法：先 `python3 -c` 生成转义串再 grep，或搜可打印 ASCII 前缀。
- [X] **条件调用 `remember`** → `LapDetailDialog` 里若写 `if (entry == null)` 包住
  依赖 `entry` 的三处 `remember`，Compose 会因调用次数在重组间变化而崩溃。
  **必须把正文抽成独立 composable**（`ColumnScope.LapDetailBody`）。
- [X] 继承死路（详 `.handoffs/20261028120910-handoff.md` §4 及更早）：
  替用户决定"原始值"的实现口径 / 用 `AskUserQuestion` 问"精度用哪种实现"/
  从 UI 截图反推数据库记录 / 把已确认的信息再问一遍 / 值位设固定宽度猜最坏情况 /
  `onDismissRequest` 与 `onDismissFinished` 合并 / `assets/npatch/` 条目数当指纹 /
  `PickVisualMedia` / `createChooser` 单目标直通 / `ResolveInfo.loadLabel()` /
  `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / `baksmali` 搜中文 /
  扫描 `this` 前 N 字节找候选指针（直接 SIGSEGV）。

## 5. 已知坑
- ⚠️ **产物验证的中文假阴性有两条独立来源** [V]——`strings`（ASCII-only）与
  `baksmali`（`\uXXXX` 转义）。两者都表现为「零命中」，结论都是错的。
  **判据：只要搜的是中文，就必须用 `grep -a` 打在原始字节上。**
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——`"%.${digits}f".format(v)`
  在 dex 里只剩 `"%."` + `"f"`，位数是 `const/4` 立即数。**必须走三层对拍**。
- ⚠️ **`Float.toString()` 对 `100f - x` 会暴露浮点尾差** [V]（继承）——42% 的刹车档位
  派生后值出现 `69.979996` 这类尾数。
- ⚠️ **文档可能描述着被推翻的旧方案，比文档陈旧更危险** [V]（继承）。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **提建议前先查证前提** [V]（继承）——见 memory `check-memory-before-asking`。
- ⚠️ **写研究结论前先看 `docs/README.md` §2 分流表** [V]（继承）。
- ⚠️ **`/handoff` 必须额外做 `docs/` 固化** [V]（继承）。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认日志/搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——两处都读（ChampionshipData 优先），
  冠军赛理论走同一处但未实测。若日志出现 `LAPsetup: no setup object` 需查。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，未重新反汇编）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 **数据展示口径（精度/小数位/间距）先问再定**。

## 6. 下一步（有序）
1. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
2. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现
   `LAPsetup: no setup object`。
3. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。
4. **（待你决定）发版**：`1.0.4 Beta 3` 已含本轮改动，但**未发布**（无 tag）。
   发版时机完全由你决定，我不主动提议。

## 7. 留给用户的开放问题
- 是否要按调教做榜（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
