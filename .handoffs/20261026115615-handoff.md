# HANDOFF — 读全文再开始干活

生成时间: 2026-10-26T01:15:00+08:00 · Git HEAD: `0849eda`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0849eda`（已 push）· 服务端未动
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0849eda`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/README.md`](docs/README.md)（文档规范，新）、
  [`docs/notes/CAR_IDENTIFICATION.md`](docs/notes/CAR_IDENTIFICATION.md)（本次核心产出）

## 1. 当前目标
无进行中的功能切片。本会话完成两件事：**运行时读取玩家车 carId**（已验证但**未接上传**）、
**建立 docs 文档规范并重组目录**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **carId 运行时读取成立**：`IRDSCarControllInput+0x190 → carModifier` 的
  `carUniqueID(0x20)` / `carType(0x24)` / `carNumber(0x68)`。5 次实机读数覆盖两代三队，
  `carUniqueID == carNumber == carId` 恒等全部成立：
  22/23 → 2026 法拉利 SF-26、37 → 2026 奥迪 R26、28 → 2026 威廉姆斯 FW48、
  18 → 2023 阿尔法·罗密欧 C43。
- [V] **有效圈可读到车型**：`LAPcar: carId=28 era=1 (lap 1:14.182, track='Monza')`
  与 `LAPbest`/`LAPcfg` 同批（时间戳完全相同）。**仅日志，不参与上传**。
- [V] **游戏内不存在真实型号名**：全 APK 精确搜 `RB22`/`MCL40`/`AMR26`/`FW48`/
  `VF-26`/`MAC-26`/`SF-26` 零命中；`W17`/`A526` 命中经字节上下文核对全是二进制噪声。
- [V] **文档体系重构完成**：`docs/` 分为 `notes/` `design/` `archive/`，新建
  `docs/README.md`（规范 + 索引 + 回收进度）与 `docs/notes/CAR_IDENTIFICATION.md`。
  全仓 35 处引用零悬空。
- [V] **构建/lint**：见下方输出。
- [V] **工作区干净**：三个工作切片 + 固化产物均已 push。
- **未提交**：`HANDOFF.md`（本文件，随后单独提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 50s
EXIT_ASSEMBLE=0

$ ./gradlew :app:lint
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline lint-baseline.xml)
BUILD SUCCESSFUL in 2m 38s
EXIT_LINT=0
```

## 3. 决策与理由
- **圈边界快照而非轮询读实时值** [V]——圈完成到 Java 1Hz 轮询之间存在窗口，
  用户可能已换车，实时值会串到下一辆车。快照把「这一圈用的车」钉死在圈边界。
- **探针用 `cM` 指针判重而非 `carId`** [V]——同一辆车重进赛道 `carId` 不变，
  用 `carId` 判会静默掉最需要观察的那次。
- **上传契约暂不动** [V]——用户明确「现在只是试跑出有效圈的时候能不能读到」。
  `lap_upload_t` / JNI 数组 / `PaddockUploader` 的改动已全部回滚。
- **文档规范用五节式骨架** [V]——用户选的（结论/依据/实现/已否决/待验证）。
  已否决的方案永不删除，因为删除会导致重踩。
- **`.handoffs/` 历史快照不随重构改路径** [V]——那是历史快照，改了就不是快照了。

## 4. 失败的尝试 — 不要再试
- [X] **扫描 `this` 前 0x400 字节找候选指针** → 直接 SIGSEGV
  （`fault_addr=0xa3d70a3f00c4bc`、`pc=模块+0x0`）。`IRDSCarControllInput` 里有大量
  float/bool/int 字段，当指针解引用必崩。**只读已知偏移的具名字段**；加范围校验也没用，
  读之前就崩了。
- [X] **用 `carId` 变化作日志触发判据** → 同一辆车重进赛道不再打印。改用 `cM` 指针。
- [X] **断言「`cM`(0x190) 在赛道内恒 NULL」** → 错。前两轮零命中的真实原因是
  日志滚动截断（`truncate_log_file` **保留后半、丢弃前半**），日志被 1.7MB 的
  `unlock_steer_angle` 刷掉了。**`grep` 零命中 ≠ 不存在**。
- [X] **用 `xxd` 查十六进制** [V]（继承）——本机没装，用 `od -An -tx1`。
- [X] **GNU `objdump` 反汇编 aarch64** [V]（继承）——`architecture UNKNOWN`。
- [X] **`unzip -p ... | nm -D -`** [V]（继承）——nm 要文件不要管道。
- [X] **用 `strings` 搜 dex 里的中文** [V]（继承）——按 ASCII 切分丢中文字节，
  必须 `grep -a "中文"`。
- [X] **`python3 -I` 下 `import UnityPy`** → 隔离模式屏蔽用户 site-packages。
  用 `sys.path.insert(0, '/home/takotsubo/.local/lib/python3.13/site-packages')`。
- [X] 继承死路（详 `.handoffs/20261025214805-handoff.md` §4 及更早）：
  `assets/npatch/` 条目数当指纹 / `PickVisualMedia` / `createChooser` 单目标直通 /
  `ResolveInfo.loadLabel()` / `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 变暗层插 index 0 /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / `baksmali` 搜中文。

## 5. 已知坑
- ⚠️ **探针绝不能扫描未知偏移** [V]——见 §4 第一条。只读 `OffsetTable` 里登记过的字段。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]——下"没有发生"的结论前，先确认日志**覆盖范围**
  （时间跨度 / 文件大小 vs 2MB 截断上限）。已写进 CLAUDE.md 文档规范段。
- ⚠️ **用户给的数据表是唯一事实源** [V]——逐字搬运，不重造措辞、不擅自"优化"。
  见 memory `user-provided-data-is-the-source-of-truth`。
- ⚠️ **「验证/试一下」只授权观测，不授权改数据流** [V]——见 memory
  `fix-only-what-was-asked`（2026-10-26 第三次复发）。
- ⚠️ **`.handoffs/` 里约 2400 条结论无落点** [V]——`docs/README.md` §7 有回收进度表
  （8 主题、约 609 条待回收）。做相关工作时顺手回收。
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
1. **（待你决定）车型上传契约**：`carId` + `era` 已能取到，但服务端字段名、
   是否按车型统计等均未决策。**当前代码不参与上传**。改契约要同时动
   `docs/design/PADDOCK_PLAN.md` 与两侧代码。
2. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表，
   建议从「围场/服务端」（106 条）或「ABS/TC」（91 条）开始。
3. **（待你决定）发版**：`1.0.4 Beta 3` 已含踏板上报降级 + 转向限制警示 + 本次车型识别，
   但**未发布**（无 `v1.0.4*` tag）。发版时机完全由你决定，我不主动提议。
4. **（待你决定）2023 地效代补验**：只验了 1 条读数（carId 18），其余 9 支车队
   依赖资源侧解析，未逐条实机核对。
5. **（待你决定）VPS 时钟修复**：`sudo timedatectl set-ntp true`，需明确许可。

## 7. 留给用户的开放问题
- **车型上传要报什么？** 只报 `carId`+`era` 由服务端查表，还是也报型号串？
- **是否要按车型做榜**（如「SF-26 最快圈」）？这决定服务端要不要建索引。
- **`vcarb` 这个资产名要不要在文档里隐藏？** 资源里确实叫 `vcarb`
  （prefab `vcarb26_1.prefab`），但贴图叫 `alt26`。当前两者都保留、互不覆盖。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
- 圈速卡「间隔 2 字符」是否合适？（继承，未回答）
