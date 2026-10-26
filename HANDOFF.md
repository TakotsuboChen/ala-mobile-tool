# HANDOFF — 读全文再开始干活

生成时间: 2026-10-26T16:10:36+08:00 · Git HEAD: `0ad2f34`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0ad2f34`（已 push）· 服务端 `main` @ `75def15`（已 push，**已部署线上**）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0ad2f34`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/notes/CAR_SETUP.md`](docs/notes/CAR_SETUP.md) §0（调教读取的两个坑，
  **本轮两次踩过**）、[`docs/README.md`](docs/README.md) §2（写结论前先看分流表）

## 1. 当前目标
无进行中的功能切片。本会话完成：**车型与调教接入围场上传**（端到端已验证并部署）
+ **圈速详情弹窗**（多轮 UI 迭代后定稿）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **调教读取修正（本会话最重要的发现）**：`ApplyCurrentSetupPreRun` 按
  `simpleRace` 分叉写**两个不同位置**——`true` → `GlobalVariables+0x10`，
  `false` → `ChampionshipData+0x68`。实机计时赛 `raceMode==3` 走后者，
  `GlobalVariables` 保持 `AlaCarSetup..ctor` 默认常量。模块已改为
  **先读 ChampionshipData、为空回落 GlobalVariables**。
- [V] **13 项调教全是连续浮点**，UI 显示整数是 `UpdateSlidersStrings`(`0x18AD0C8`)
  的 `fcvtzs`（向零截断）结果。实机全项非整数读数（Monza 1:15.443，用户确认）：
  `aero=55.397/37.331 ride=36.854/37.616 brake=54.702 stiff=64.193/25.165
  rollbar=31.566/31.457 camber=-2.862/-1.565 toe=-0.033/0.088`。
- [V] **上传链路端到端通**：库里有 `car_id=20 era=1 setup_front_wing_aero=65
  setup_brake_bias=60`（Takotsubo，Shangai 1:25.527，2026-10-26 13:04）。
- [V] **服务端已部署**：`paddock-api:1.0.1` 载入 VPS，迁移 `0011` 已跑
  （`_sqlx_migrations version=11`），`laps` 15 个新列已建，
  `/v1/driver` 返回 `car_id`/`car_era`/`setup` 三个新字段。
- [V] **真库测试 5 项全过**：`cargo test --release car_setup_sql_tests
  -- --ignored --test-threads=1` → `5 passed; 0 failed`。
- [V] **构建/lint**：见下方输出。
- [V] **装机**：魅族 20（`192.168.50.142:5555`），最后一次 `Success`。
- [V] **工作区干净**：两仓工作切片 + 固化产物均已 push。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease :app:lint -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 3m 36s
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline) → 0 error

$ DATABASE_URL=... cargo test --release car_setup_sql_tests -- --ignored --test-threads=1
test result: ok. 5 passed; 0 failed; 0 ignored; 0 measured

$ cargo test --release -- --test-threads=1
test result: ok. 18 passed; 0 failed; 19 ignored

$ curl https://paddock.takotsubo.cloud/v1/health
{"status":"ok","version":"1.0.1"}
```

## 3. 决策与理由
- **车型只存整数 `car_id`+`car_era`，展示名由模块 `CarCatalog` 反查** [V]——
  改车队译名只发模块、历史圈自动跟着变，服务端永不认识车型字。
  否决：服务端存展示串（历史圈文案被冻结）/ 服务端自建映射表（两表必然漂移）。
- **调教存原始 float，展示层按项分档**（角度 3 位 / 其余 2 位、固定位数不去尾零）[V]——
  用户要做「应用调教」功能，回写必须用原始值。否决：四舍五入到整数（丢精度）/
  去尾零（`54.70`→`54.7`，同列位数参差）。
- **读调教用"哪份非空"而非按 raceMode 硬分支** [V]——`raceMode` 语义在不同
  上下文不一致（`ComputeFuelToLoad` 里 2 是计时赛判定，实际跑圈时是 3）。
- **常驻一致性校验保留** [V]——每圈对拍 `brakeBias` vs `carModifier.currentBrakeBiasFront`，
  只在不等时打 `LAPsetup: MISMATCH`（正常零输出）。这是防升版重蹈覆辙的唯一护栏。
- **弹窗值列宽度量真实数据**（`items.filter { it.v2 != null }`）而非猜基准串 [V]——
  猜基准串已踩三次（`"-100.00"` 截断单值项、`%.4f` 后基准失准、量全部值把单值项
  长文本算进去撑宽双值项间距）。

## 4. 失败的尝试 — 不要再试
- [X] **从 UI 截图反推数据库记录** → 拿截图里的蒙扎 1:12.028 去库里找，
  找不到就断言"没上传"，被用户当场质问「怎么没上传，你弱智吗，刚刚跑的」。
  实际那圈是 Shangai 1:25.527 且已入库。**正确顺序：先看日志时间戳确认
  "这圈什么时候跑的"，再去库里找对应行**。
- [X] **把用户已确认的信息再问一遍** → 用户说过「对的」（确认整组数值含 aero=0），
  我又单独问"下压力 0 是真实的吗"，被回「我前面不是说"对的"吗你还问什么」。
  **已确认的字段不要重复确认**。
- [X] **给值位设固定宽度并猜最坏情况** → 三次截断（见 §3 最后一条）。
  改为量真实数据。
- [X] **`onDismissRequest` 与 `onDismissFinished` 合并成同一 lambda** →
  挂载在动画开始那一帧就消失，退出无动画。用户：「你每次做弹窗退出都没动画，
  每次都要额外提醒」。**必须双回调**。
- [X] 继承死路（详 `.handoffs/20261026115615-handoff.md` §4 及更早）：
  `assets/npatch/` 条目数当指纹 / `PickVisualMedia` / `createChooser` 单目标直通 /
  `ResolveInfo.loadLabel()` / `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 变暗层插 index 0 /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / `baksmali` 搜中文 /
  扫描 `this` 前 N 字节找候选指针（直接 SIGSEGV）。

## 5. 已知坑
- ⚠️ **"有输出 ≠ 读对了"** [V]——`LAPsetup` 一直有日志、格式正确、值看起来合理，
  但读的是从未被填充的默认构造对象。**验证逆向结论必须做常量表对拍**
  （`.rodata` 默认值逐字节）+ **与车上实际生效值对拍**。见 §4 与
  memory `setup-two-storage-locations`。
- ⚠️ **提建议前先查证前提** [V]——2026-10-26 第四次同类事件
  （`check-memory-before-asking`）。
- ⚠️ **写研究结论前先看 `docs/README.md` §2 分流表** [V]——见 memory `docs-routing-rule`。
- ⚠️ **`/handoff` 必须额外做 `docs/` 固化** [V]——skill 固定流程只覆盖
  CLAUDE.md/记忆/Skill。见 memory `handoff-must-also-update-docs`。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]——下"没有发生"的结论前先确认日志覆盖范围。
- ⚠️ **`| tail` 会吞掉退出码** [V]——判据改用 `BUILD SUCCESSFUL`/`FAILED` 字样。
- ⚠️ **冠军赛路径未验** [?]——现在两处都读（ChampionshipData 优先），
  冠军赛理论上走同一处，但未实测。若日志出现 `LAPsetup: no setup object`，需查。
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
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 **已确认的信息不要重复问**（本轮新增，见 §4）。

## 6. 下一步（有序）
1. **（待你决定）「应用调教」功能**：调教数据已能取到并已入库，回写游戏的路径未做。
   存储保持原始 float 就是为它留的余地。
2. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现
   `LAPsetup: no setup object`。
3. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表，
   建议从「围场/服务端」（106 条）或「ABS/TC」（91 条）开始。
4. **（待你决定）发版**：`1.0.4 Beta 3` 已含本轮改动，但**未发布**（无 tag）。
   发版时机完全由你决定，我不主动提议。
5. **（待你决定）2023 地效代补验**：carId 0–19 只验过 4 号（红牛 RB19）。

## 7. 留给用户的开放问题
- 是否要按调教做榜（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.033/0.088`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
