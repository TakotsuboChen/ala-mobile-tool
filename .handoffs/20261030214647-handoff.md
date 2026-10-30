# HANDOFF — 读全文再开始干活

生成时间: 2026-10-28T23:35:29+08:00 · Git HEAD: `e5c36da`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `e5c36da`（已 push）· 服务端无改动（仍 `75def15`，线上 `1.0.1`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `e5c36da`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/notes/CROSS_PROCESS_CHANNELS.md`](docs/notes/CROSS_PROCESS_CHANNELS.md) §10a
  （2026-10-28 新增：`saved_at` 语义边界与 B8）

## 1. 当前目标
无进行中的功能切片。本会话完成：**修复「陈旧本地配置赢得新鲜度仲裁」导致
开关读错**（用户报「解除转向限制一直是开着的，进游戏要重新关-开才生效」）。
根因不在该功能，在 `saveOverlayPosition` 刷新整份文件的 `saved_at`。

## 2. 已验证状态 — 工作实际停在哪
- [V] **修复已实现并推送**：`ModConfig.kt` 两处（`saveOverlayPosition` 删 `saved_at`
  刷新 + `readFromTargetProcess` 加 local ts 上限钳制）+ 补 position 落盘日志。
  `9345ed9`，`git diff --stat`：+51 / −15。
- [V] **构建**（全新 shell，未截断）：`./gradlew :app:assembleRelease :app:lint
  -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → **EXIT_CODE=0**，
  `BUILD SUCCESSFUL in 1m 59s`，lint `67 warnings, 4 hints`（2 errors / 14 warnings
  被 baseline 过滤）→ 0 error。
- [V] **产物三层对拍**：APK `23:23:23` 晚于源码 `23:20:59`；`mapping.txt` 得
  `ModConfig -> ib1`、`saveOverlayPosition -> p`；baksmali 确认该方法内**无**
  `saved_at` 写（仅日志里的 `optLong` 读）、`Lvv;->i0(File,String)` 写盘仍在；
  钳制分支 `move-wide/from16 v13, v16`（0L）落 `:cond_140`；上游为 0 时
  `cmp-long` + `if-lez` 不进钳制。
- [V] **装机实测（魅族 20 `192.168.50.142:5555`）**：`Success`，
  `lastUpdateTime=23:26:30`，`firstInstallTime=2026-08-13` 未变（覆盖非重装）。
  版本仍 `1.0.4 Beta 3 (104230)`（未升版）。**三项判据全部命中**：
  ① `position saved: … saved_at=1793168841693 (not refreshed)` —— 拖十几次
  `saved_at` 恒定不变；② `Config arbitration: local saved_at=1793168841693 超过
  上游 1793167902960 ——疑似被历史 position 写入污染，按 0 处理`（**本机确实
  被污染，local 超上游 15 分 39 秒**）；③ `winner=remote prefs … local=0`，
  四次启动全部 `initSteerLimit unlock=true` 与配置页一致。
- [V] **工作区干净**：三个切片均已 push。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease :app:lint -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
EXIT_CODE=0
BUILD SUCCESSFUL in 1m 59s
78 actionable tasks: 23 executed, 55 up-to-date
```

## 3. 决策与理由
- **撤销 `saved_at` 刷新，而非拆分 position 时间戳**（用户定案）[V]——`saved_at`
  的语义边界必须是「最后一次**全量**写入」，用整份文件的新鲜度信号表达一次局部
  改动就是伪造。position 靠 `mergePositionFromLocalPublic` **无条件**合并生效，
  本就不需要参与仲裁。否决：新增 `pos_saved_at` + "只信更新的 position"（要改
  字段/合并/旧文件迁移，而现状本来没问题）；否决：local 退出仲裁（会丢"游戏运行中
  改配置、广播送达、之后模块起不来"场景的唯一落地副本）。
- **读取侧加 local ts 上限钳制**（用户定案）[V]——**只修写入侧对存量用户无效**，
  污染值已在他们的本地文件里。判据是一条单向数据流不变量：local 是下游副本
  （`ConfigReceiver` 直接拷贝上游 `saved_at`），ts 不可能超前于
  `max(remote, provider, mailbox)`。守卫 `upstreamTs > 0L` 不能省（三个上游都无
  `saved_at` 时无从判断，不钳以免把旧格式 JSON 用户打回默认）。
- **补 position 落盘日志**（用户定案）[V]——该路径此前零日志，本次只能靠排除法
  定位（`ConfigReceiver: merged` 只 2 条且都在故障时刻之后 → 锁死到唯一无日志写点）。
- **`position saved` 在拖动期间每帧落盘**（0.3s 内 10 条）——既有行为，本次未改。
  已问用户是否改成 ACTION_UP 才落盘，**用户未答复**。

## 4. 失败的尝试 — 不要再试
- [X] **`c283181`（2026-10-04）的立论**：为了让 position 更新"在仲裁里获得话语权"
  而刷新 `saved_at` → 反被利用：陈旧文件靠一次拖拽伪造新鲜度，赢下仲裁后盖掉权威
  配置。**方向错了**——把全局量当局部信号用。2026-10-28 已推翻并写入 CLAUDE.md。
- [X] 继承死路（详 `.handoffs/20261028233529-handoff.md` §4 及更早）：
  用 `strings | grep` 搜 APK 中文 / `baksmali` 后直接 grep 中文 /
  条件调用 `remember` / 替用户决定"原始值"口径 / 用 `AskUserQuestion` 问"精度用哪种
  实现" / 从 UI 截图反推数据库记录 / 把已确认的信息再问一遍 / 值位设固定宽度猜
  最坏情况 / `onDismissRequest` 与 `onDismissFinished` 合并 / `assets/npatch/`
  条目数当指纹 / `PickVisualMedia` / `createChooser` 单目标直通 /
  `ResolveInfo.loadLabel()` / `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 变暗层插 index 0 /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针。

## 5. 已知坑
- ⚠️ **`saved_at` 差值 + 该时刻是否有全量写入日志 = 定位"谁改了本地配置"的两步法**
  [V]——`ConfigReceiver: merged` 出现在故障时刻**之后**即可把嫌疑人锁死到无日志写点。
- ⚠️ **钳制后的 `localTs` 与原始值要分开看** [V]——`winner=` 行里 `local=0` 是
  **钳制后**的值，原始值在 W 级警告里。混看会误判成"本地文件没有 saved_at"。
- ⚠️ **产物验证的中文假阴性有两条独立来源** [V]（继承）——`strings`（ASCII-only）
  与 `baksmali`（`\uXXXX` 转义）。判据：搜中文就用 `grep -a` 打原始字节。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——`"%.${digits}f".format(v)`
  在 dex 里只剩 `"%."` + `"f"`。必须走三层对拍。
- ⚠️ **`Float.toString()` 对 `100f - x` 会暴露浮点尾差** [V]（继承）。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **文档可能描述着被推翻的旧方案，比文档陈旧更危险** [V]（继承）——本次正是
  CLAUDE.md 里留着被推翻的 `c283181` 规则，已改。
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
1. **（待你决定）position 落盘改为 ACTION_UP 才写**：现每帧落盘（0.3s 内 10 次文件写
   + 10 行日志）。功能无害，但日志会吵。属新改动，非本 bug 修复范围。
2. **（待你决定）给报日志的用户回话**：升级后自动自愈（钳制会淘汰污染值），
   或临时自行改一次任意开关也能恢复。**注意：修复尚未发版**，`1.0.4 Beta 3`
   是修复前的构建，用户手上那个版本仍有 bug。
3. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
4. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现 `LAPsetup: no setup object`。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。
6. **（待你决定）发版**：本修复**未发布**（无 tag）。发版时机完全由你决定，我不主动提议。

## 7. 留给用户的开放问题
- `position saved` 每帧落盘要不要改成松手才落盘？
- 是否要按调教做榜（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
