# HANDOFF — 读全文再开始干活

生成时间: 2026-10-26T12:00:00+08:00 · Git HEAD: `0c64db2`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0c64db2`（已 push）· 服务端未动
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0c64db2`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/README.md`](docs/README.md)（文档分流规范，**写任何结论前先看 §2**）、
  [`docs/notes/CAR_SETUP.md`](docs/notes/CAR_SETUP.md)（本次核心产出）

## 1. 当前目标
无进行中的功能切片。本会话完成：**有效圈读取车辆调教**（已验证、已装机、
**未接上传**）+ **载油量议题查证后关闭**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **调教读取成立，零 hook**：走静态字段 `GlobalVariables.currentCarSetup`
  （static_fields `+0x10`），`il2cpp_field_static_get_value` 直接取值。
  实机 2026-10-26 11:47:05 Monza（carId=22，有效圈 1:14.105）：
  ```
  lap_hook: GlobalVariables.currentCarSetup resolved: 0x6c336835d8 (state=1)
  LAPsetup: aero=65.000/58.000 ride=40.000/40.000 brake=60.000
            stiff=50.000/50.000 rollbar=50.000/50.000
            camber=-3.000/-1.500 toe=-0.050/0.150 (lap 1:14.105)
  ```
- [V] **存的就是 UI 显示值本身，不做换算**（下压力 65.0 不是 0.65，
  外倾角 -3.0 度不是弧度）。用户 2026-10-26 确认示例只是格式示范。
- [V] **刹车平衡只有一个 `brakeBias` 字段存「前」值，后 = 100 − 前**——
  用户原话「现在 60 是前，也就是后是 40」。UI 显示 `60/40` 是派生量。
- [V] **载油量议题关闭**：`carModifier.Start` 在 `raceMode==2`（游戏自身
  计时赛判定）时走 `ComputeFuelToLoad` 的「按赛道长度算」分支
  （读 `trackSelStatus[trackIndex]` × 系数），玩家改不了；
  `IRDSDrivetrain.fuelQty` 全 dump 零方法引用且该类无质量字段。
  详见 `TECHNICAL_ANALYSIS.md` §4.5。
- [V] **构建/lint**：见下方输出。
- [V] **装机验证**：魅族 20（`192.168.50.142:5555`），`lastUpdateTime=11:43:32`，
  从设备拉回 APK 后 `LAPsetup` 四个特征串全部命中。
- [V] **工作区干净**：两个工作切片 + 固化产物均已 push。
- **未提交**：`HANDOFF.md` + `.handoffs/20261026115615-handoff.md`（随后单独提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 6s
（首次构建失败：13 个 undeclared identifier —— OFF_SETUP_* 宏插在了
  lapsetup_diag() 之后；把宏块上移到 resolve_setup_field() 之前后通过）

$ ./gradlew :app:lint
BUILD SUCCESSFUL in 1m 46s
Lint found 67 warnings, 4 hints（与基线一致，0 error）

$ adb -s 192.168.50.142:5555 install -r app-release.apk
Success · lastUpdateTime=2026-10-26 11:43:32
$ unzip -p <设备拉回 APK> lib/arm64-v8a/libala-core.so | strings | grep LAPsetup
4 条特征串全部命中
```

## 3. 决策与理由
- **走静态字段而非 hook 写入点** [V]——`ApplyCurrentSetupPreRun` 三个调用方
  全是「进赛道前」路径 ⇒ 赛道内对象稳定不变，圈边界读一次语义更准、零 hook
  风险。否决方案：hook `ApplyCurrentSetupPreRun`（只能拿到写入瞬间的值，
  而需要的是「跑这一圈用的是哪套」）；走 `SetupManager.acs` 实例链
  （`SetupManager` 是车库 UI MonoBehaviour，赛道内不一定存在）。
- **不做任何数值换算** [V]——实机读数 `65.000`/`-3.000`/`-0.050` 量纲自洽，
  若真存归一化值外倾角会是 `-0.052` 这类小量。
- **载油量不进探针** [V]——见 §2 第三条，用户明确否决且查证支持。
- **原理类结论拆到 `TECHNICAL_ANALYSIS.md` §4.5，工程笔记只留指针** [V]——
  按 `docs/README.md` §2 分流规则（判据：模块重写一遍还成立吗）。
- **单条原理结论并入已有篇章而非新开篇** [V]——`TECHNICAL_ANALYSIS.md` 的
  编号是成篇追加式，载油量不够成篇，并入 §4 圈速篇为 §4.5。

## 4. 失败的尝试 — 不要再试
- [X] **把 `OFF_SETUP_*` 宏插在 `lapsetup_diag()` 之后** → 13 个
  `use of undeclared identifier`。C 预处理器要求 `#define` 在**使用点之前**；
  报错指向使用点，容易误以为是拼写问题。
- [X] **提议「把载油量纳入探针用于榜单公平性」** → 用户「补牛魔，你自己看看
  计时赛能不能调油量」。查证后两条理由都不成立该提议。**提案前先查证前提**，
  见 memory `check-memory-before-asking`。
- [X] **把引擎机制结论写进 `notes/`** → 用户「原理类记到哪忘了？」。
  违反 `docs/README.md` §2。见 memory `docs-routing-rule`。
- [X] **假设用户示例是那一圈的真实调教** → 用户「我只是示例」。
  用户给的数据表是**格式示范**还是**实测值**，不确定时要问，别当基准去对拍。
- [X] 继承死路（详 `.handoffs/20261026011502-handoff.md` §4 及更早）：
  `assets/npatch/` 条目数当指纹 / `PickVisualMedia` / `createChooser` 单目标直通 /
  `ResolveInfo.loadLabel()` / `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 变暗层插 index 0 /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / `baksmali` 搜中文 /
  扫描 `this` 前 N 字节找候选指针（直接 SIGSEGV）。

## 5. 已知坑
- ⚠️ **提建议前先查证前提** [V]——2026-10-26 第三次同类事件（`check-memory-before-asking`）。
  建议被推翻的成本比沉默高：用户要额外解释一遍为什么它不成立。
- ⚠️ **写研究结论前先看 `docs/README.md` §2 分流表** [V]——见 memory `docs-routing-rule`。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]——下"没有发生"的结论前，先确认日志**覆盖范围**
  （时间跨度 / 文件大小 vs 2MB 截断上限）。
- ⚠️ **`| tail` 会吞掉退出码** [V]——`${PIPESTATUS[0]}` 在管道后另起命令时也已重置；
  判据改用 `BUILD SUCCESSFUL` / `BUILD FAILED` 字样。
- ⚠️ **`.handoffs/` 里约 2400 条结论无落点** [V]——`docs/README.md` §7 有回收进度表
  （8 主题、约 609 条待回收）。做相关工作时顺手回收。
- ⚠️ **冠军赛路径未验** [?]——`ApplyCurrentSetupPreRun` 的 `simpleRace` 分叉把 setup
  写到 `ChampionshipData+0x68` 而非 `GlobalVariables+0x10`。计时赛已验走后者；
  若日志出现 `LAPsetup: currentCarSetup is NULL`，需补兜底。依赖：跑一场冠军赛。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）调教上传契约**：`carId`+`era`+13 个 setup 字段均已能取到，
   但服务端字段名、精度（外倾角保留几位）、是否按调教统计均未决策。
   **当前代码不参与上传**（`lap_upload_t` 未改）。改契约要同时动
   `docs/design/PADDOCK_PLAN.md` 与两侧代码。
2. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现
   `currentCarSetup is NULL`；若出现需补 `ChampionshipData+0x68` 兜底。
3. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表，
   建议从「围场/服务端」（106 条）或「ABS/TC」（91 条）开始。
4. **（待你决定）发版**：`1.0.4 Beta 3` 已含本次调教读取，但**未发布**
   （无 `v1.0.4*` tag）。发版时机完全由你决定，我不主动提议。
5. **（待你决定）2023 地效代补验**：只验了 1 条 carId 读数，其余 9 支车队未实机核对。

## 7. 留给用户的开放问题
- **调教上传要报什么？** 13 个字段全报，还是只报几个关键的？精度如何？
- **是否要按调教做榜**（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，
  要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.050/0.150`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
- 圈速卡「间隔 2 字符」是否合适？（继承，未回答）
