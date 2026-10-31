# HANDOFF — 读全文再开始干活

生成时间: 2026-10-31T13:09:57+08:00 · Git HEAD: `c18429f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `c18429f`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c18429f`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/LeaderboardScreen.kt`](app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/LeaderboardScreen.kt)
  的 `LaunchedEffect(tabIndex, selectedTrack, selectedVersion, refreshSeq)` 块（183–220 行）

## 1. 当前目标
无进行中的功能切片。本会话完成：**修复切换榜单筛选条件时排行榜不回顶部**，已装机验证，
用户确认「完全正常了」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **榜单切筛选回顶部修复**（commit `c18429f`）：
  `scrollToItem(0)` → `requestScrollToItem(0)`，并去掉 `firstVisibleItemIndex > 0` 前置判据
  （`LeaderboardScreen.kt:204-219`）。根因与修法见 memory `lazylist-key-anchor-fights-scroll-reset`。
- [V] **实机验收通过**：魅族 20（`192.168.50.142:5555`）安装成功，
  `lastUpdateTime=2026-10-31 13:08:01`；用户确认「完全正常了」。
- [V] **版本号未改**（仍 `1.0.5 Alpha 1` / `105110`）——本次是修复，用户未要求升版。
- [V] **`DriverScreen` 未改**：其圈速列表用 `e.lapId` 做 key（跨筛选唯一）+ 请求前
  `entries = emptyList()` 项数归零 ⇒ 框架自然 clamp 回顶，无此 bug。
- [V] 工作区干净，`main...origin/main` 已同步。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 1m 21s        ← 0 error（67 warnings 已在 baseline）

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 53s

$ ls -l --time-style=full-iso app/build/outputs/apk/release/app-release.apk
2026-10-31 13:05:34（晚于最后源码修改）

$ mapping.txt 实证调用点
LeaderboardScreenKt$LeaderboardScreen$3$1.invokeSuspend(...):219
  → LazyListState.requestScrollToItem(int,int):421

$ adb -s 192.168.50.142:5555 install -r ... → Success
$ dumpsys package → versionCode=105110 lastUpdateTime=2026-10-31 13:08:01
```

## 3. 决策与理由
- **用 `requestScrollToItem` 而非 `scrollToItem`** [V]——后者只挪位置不清 key 锚点，
  跨数据集共享 key 时归位被静默撤销；前者内部 `requestPositionAndForgetLastKnownKey()`
  同步清锚点。附带收益：非挂起（避开 `DriverScreen.kt:201` 记录的挂起坑）、可无条件调用。
  否决方案：改榜单行 key 前缀区分 tab（能绕过症状但 key 本就该按实体唯一，且刷新时
  仍会误恢复）。用户已在 AskUserQuestion 中选定「瞬间归位」而非平滑滚动。
- **`DriverScreen` 不动** [V]——它没有这个 bug，改它属超出诉求范围。
- **不升版本号** [V]——版本号是用户决策域，本次只是修复。

## 4. 失败的尝试 — 不要再试
- [X] **用 `scrollToItem(0)` 做跨数据集归位**（2026-10-31 实证）——它不清
  `lastKnownFirstItemKey`，数据替换时框架按 key 把视口拉回原用户所在行。
  判据：`LazyListState.requestScrollToItem` 字节码里 `requestPositionAndForgetLastKnownKey`
  显式置 `lastKnownFirstItemKey = null`。
- [X] **判据 `firstVisibleItemIndex > 0` 才归位**——索引为 0 但向下偏移几百像素时
  不归位，视觉上同样是"没回到顶部"。
- [X] 继承死路（详 `.handoffs/20261031130957-handoff.md` §4 及更早）：
  整圈五维全填 0 / 按原始两列比对 mode/strength / 只看 API 前 N 条就断言"最快圈" /
  `strings | grep` 搜 APK 中文 / `baksmali` 后直接 grep 中文 / 条件调用 `remember` /
  替用户决定"原始值"口径 / 从 UI 截图反推数据库记录 / 把已确认的信息再问一遍 /
  `onDismissRequest` 与 `onDismissFinished` 合并 / `assets/npatch/` 条目数当指纹 /
  `PickVisualMedia` / `createChooser` 单目标直通 / `ResolveInfo.loadLabel()` /
  `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / 升测试版号时改 README /
  `gh api .../jobs/<id>/logs` 里的 `echo "::error::"` 当错误输出 /
  把完整 README 稿贴终端 / `grep 'versionName'` 松匹配 / APK 大小相同判"没变" /
  全盘 `find / -name aapt2`。

## 5. 已知坑
- ⚠️ **`LazyColumn` 的位置恢复是"key 优先、index 兜底"** [V]——跨数据集共享 key 的
  列表，任何"归位"操作都必须先清 `lastKnownFirstItemKey`，否则会被静默撤销。
  见 memory `lazylist-key-anchor-fights-scroll-reset`。
- ⚠️ **`scrollToItem` 是 suspend，会卡协程** [V]（继承）——`DriverScreen.kt:201`
  记录过"概览页一直显示加载中"的实机故障。
- ⚠️ **改 `ModConfig` 默认值 ≠ 只改 `Defaults`** [V]（继承）——还要 grep `?: false|true`
  兜底字面量与 `ModConfig.Settings(` 构造点。见 memory `modconfig-add-field-sync`。
- ⚠️ **`enableTc`/`enableAbs` 是派生值**（`tcMode==DEFAULT || tcStrength!=OFF`），
  不是独立开关；改默认值只能动 `mode`+`strength`。
- ⚠️ **默认值改动在 APK 里「看不见」** [V]（继承）——R8 把 `const val` 内联成字节码
  立即数，dex 搜不到符号名。产物验证只能靠间接证据。
- ⚠️ **魅族 20 的 IP 会来回变** [V]（继承）——`.142`/`.123` 都出现过；连上后必须核对
  `ro.product.model`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16）。
- ⚠️ **R8 混淆后 dex 里搜不到原名** [V]（继承）——`requestScrollToItem` 在 dex 中
  是 `k01.q(...)`；验证走 mapping.txt 反查 + 反汇编对拍。
- ⚠️ **Compose `Row` 不自动收缩** [V]（继承）——含动态文本的并排行必须 `weight`+`Ellipsis`。
- ⚠️ **覆盖重发后已装用户收不到更新提示** [V]（继承）——更新检查按 versionCode 比较。
- ⚠️ **镜像 release 列表顺序在单 tag 手动触发时不保证正序** [?]（继承，未实测）。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间** [V]（继承）——按圈速排用 `lap_desc`/`lap_asc`。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算** [V]（继承）。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——走三层对拍。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——代码层已确认，实跑未测。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，未重新反汇编）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 数据展示口径（精度/小数位/间距）先问再定
  17 下断言前先取证，没有全量数据就不下"这是最快/最好"这类结论
  18 大段文档写文件不贴终端 19 日志里有某行 ≠ 该行被执行了
  20 **用户说「我的设备没事但别人的可能有问题」时，不能因为本机复现不了就否定**

## 6. 下一步（有序）
1. **（待你决定）给内测群发 1.0.5 Alpha 1**：APK 已构建并装机验证，含本次修复，
   路径 `app/build/outputs/apk/release/app-release.apk`。
2. **（待你决定）存量用户是否要吃到新默认值**：当前只有全新安装/清数据生效。
   要触达存量用户需做迁移（改 `read()` fallback 或加版本迁移逻辑）。
3. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
4. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现 `LAPsetup: no setup object`。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- 存量用户的默认值要不要迁移？还是让新默认只对全新安装生效？
- 两个反馈都默认关后，打开路肩振感**不会**弹「振感冲突提示」（该弹窗只在抓地力
  含振感时触发）。这是原有设计——是否要改成「两个都关时打开路肩也弹提示」？
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜（如「同一赛道同一调教分组」）？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
