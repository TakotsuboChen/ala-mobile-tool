# HANDOFF — 读全文再开始干活

生成时间: 2026-10-27T22:30:02+08:00 · Git HEAD: `98c49c4`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `98c49c4`（已 push）· 服务端无改动（仍 `75def15`，线上 `1.0.1`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `98c49c4`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/notes/CAR_SETUP.md`](docs/notes/CAR_SETUP.md) §5 末「展示格式」
  （分档表，**10-27 已推翻旧的"去尾零"方案**）

## 1. 当前目标
无进行中的功能切片。本会话完成：**圈速详情弹窗展示格式定稿**（前束角 4 位 /
双值项间距 32dp）+ **修正文档与代码长期不符的描述**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **前束角改 4 位、双值项间隔 24→32dp**：`DriverScreen.kt` 单文件改动。
- [V] **构建**：`./gradlew :app:assembleRelease :app:lint
  -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` →
  `BUILD SUCCESSFUL in 2m 46s`；lint `67 warnings, 4 hints`（2 errors / 14 warnings
  被 baseline 过滤）→ **0 error**。
- [V] **产物实证（三层对拍，因为 `strings` 搜不到 `%.4f`）**：
  `mapping.txt` → `DriverScreenKt -> ia0`；`baksmali` 得 `ia0.smali` 方法
  `e(Float,I)` 内是 `const-string "%."` + `const-string "f"` +
  `Lal;->f(I,String,String)` 模板拼接 ⇒ 位数只存在于调用点实参。
  14 处调用点中 **`const/4 v8, 0x4` 唯一一处**（前束角，行 7700）、
  `const/4 v8, 0x3` 唯一一处（外倾角，行 7602）。改动确已进包。
- [V] **装机**：魅族 20（`192.168.50.142:5555`）`Success`，
  `lastUpdateTime=2026-10-27 22:23:43`，版本仍 `1.0.4 Beta 3 (104230)`（未升版）。
- [V] **文档矛盾已修**：`CAR_SETUP.md` / `CLAUDE.md` 此前写「原样呈现原始 float、
  去尾随零 / `%.4f` + `trimEnd('0')`」，而代码实际是分档固定位数——
  **文档描述的是被推翻的旧方案**。已按 10-27 定案改正。
- [V] **工作区干净**：三个切片均已 push。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease :app:lint -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 46s
78 actionable tasks: 25 executed, 53 up-to-date
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline lint-baseline.xml)
```

## 3. 决策与理由
- **展示格式 = 2/3/4 位分档、不去尾零**（用户 2026-10-27 定案）[V]——
  其余项 2 位 / 外倾角 3 位 / 前束角 4 位。
  理由：外倾角量级 `-2.8624` 有整数位，3 位够；前束角量级 `0.0884` **无整数位**，
  3 位下 `0.0884` 与 `0.088` 都显示 `0.088`（区分度归零）。**两项角度位数刻意不同**。
  否决：`%.4f` 统一（用户原话「不是什么固定"4 位小数"」）/
  `Float.toString()` 最短往返（后值 `100−x` 会暴露 `69.979996` 这类浮点尾差，
  且 42% 的刹车档位会命中）/ 去尾零（同列位数参差）。
- **双值项间隔 32dp**（24→32，只加 8dp）[V]——用户「加宽一点，不要加宽太多」。
  否决：36dp（两个数字散成两列、读不成一组）。

## 4. 失败的尝试 — 不要再试
- [X] **替用户决定"原始值"的实现口径** → 先写 `%.4f`（"4 位够覆盖"），
  被纠正「不是什么"4 位小数"，反正读到原始值是什么精度就怎么样」；
  改 `Float.toString()` 后又被直接给出终稿。**"原始值"是要先查清底层类型
  （`dump.cs` 里全是 `float`）再把事实报给用户选口径，不是我自己换算成 `%.Nf`**。
  见 memory `ask-before-designing-ui` 新增段。
- [X] **用 `AskUserQuestion` 问"精度用哪种实现"** → 两次被中断（`14:12:48` /
  `14:17:05`）。用户要的是先看事实，不是让我把选择题摆出来。
  **先查证 + 报事实，再问口径**。
- [X] 继承死路（详 `.handoffs/20261027223002-handoff.md` §4 及更早）：
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
- ⚠️ **文档描述被推翻的旧方案，比文档陈旧更危险** [V]——`CAR_SETUP.md` / `CLAUDE.md`
  写着「去尾随零 / `%.4f`」，代码却是分档；下个会话照文档改，正好改坏用户已定案的行为。
  **代码注释里带用户原话的细节 > 文档里的抽象规则**（注释写着「用户报『刹车平衡变
  1 位了』」这类可追溯现场，比文档的规则更接近终态）。本次已修，但同类风险仍在。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]——`"%.${digits}f".format(v)` 在 dex 里
  只剩 `"%. "` + `"f"` 两个常量，位数是 `const/4` 立即数。**必须走三层对拍**。
- ⚠️ **`Float.toString()` 对 `100f - x` 会暴露浮点尾差** [V]——7001 档刹车前值中
  2935 档（42%）派生后值出现 `69.979996` 这类尾数。**展示层不截断时，派生值要单独考虑**。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
  验证逆向结论必须做常量表对拍 + 与车上实际生效值对拍。
- ⚠️ **提建议前先查证前提** [V]（继承）——见 memory `check-memory-before-asking`。
- ⚠️ **写研究结论前先看 `docs/README.md` §2 分流表** [V]（继承）。
- ⚠️ **`/handoff` 必须额外做 `docs/` 固化** [V]（继承）。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认日志/搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——两处都读（ChampionshipData 优先），
  冠军赛理论走同一处但未实测。若日志出现 `LAPsetup: no setup object` 需查。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，本会话未重新反汇编）。
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
1. **（待你决定）装机效果验收**：本次已装但未跑圈验证。开游戏跑一圈 →
   围场页 → 圈速详情，确认前束角 4 位、间距 32dp 的观感。
2. **（待你决定）「应用调教」功能**：调教数据已能取到并已入库，回写游戏的路径未做。
3. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现
   `LAPsetup: no setup object`。
4. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。
5. **（待你决定）发版**：`1.0.4 Beta 3` 已含本轮改动，但**未发布**（无 tag）。
   发版时机完全由你决定，我不主动提议。

## 7. 留给用户的开放问题
- 是否要按调教做榜（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
