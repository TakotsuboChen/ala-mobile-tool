# HANDOFF — 读全文再开始干活

生成时间: 2026-10-30T21:47:00+08:00 · Git HEAD: `726f284`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `726f284`（已 push）· 服务端 `main` @ `04587d1`（已 push，线上已部署）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `726f284`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`docs/notes/LAP_HOOK_NOTES.md`](docs/notes/LAP_HOOK_NOTES.md) §7
  （2026-10-30 新增：辅助配置三态与逐维无效判定）

## 1. 当前目标
无进行中的功能切片。本会话完成：**辅助配置从「整圈一票否决」改为「逐维标记无效」**
——用户圈中只改了 ABS，旧实现把踏板与 TC 也一起记缺失并导致整圈被屏蔽
（实机报障：飞行雪绒 2026-10-29 巴西 0:58.444）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **模块改动已提交推送**：`47f30cf`（4 文件 +195/−45）。
- [V] **服务端改动已提交推送**：`04587d1`（3 文件 +179/−11）。
- [V] **固化产物已提交推送**：`726f284`（CLAUDE.md / LAP_HOOK_NOTES §7 / PADDOCK_PLAN 契约）。
- [V] **服务端已部署**：本地 `docker build -t paddock-api:1.0.1` → `docker save|gzip` →
  scp → VPS `docker load` → `compose up -d --force-recreate app`。容器 `Up`，
  镜像 ID `70d0067fd189` 与本地构建一致。**版本号未升**（仍是 1.0.1，按用户红线）。
- [V] **模块已装机魅族 20**（`192.168.50.123:5555`）：`Success`，
  `lastUpdateTime=2026-10-30 21:21:25`，`firstInstallTime=2026-08-13` 未变（覆盖非重装）。
  版本仍 `1.0.4 Beta 3 (104230)`（未升版）。
- [V] **实机端到端验证通过**（Monza，用户手动跑圈并在圈中改 ABS）：
  ```
  LAPcfg: assist config updated pedal=2 tcm=2 tcs=1 absm=2 abss=1 (changed dims=abs, epochs=1/1/5)
  LAPcfg: pedal usage 3715/3754 frames (98%) >= 50% → report 2
  LAPcfg: config changed mid-lap → abs marked INVALID (epochs 1/1/5 vs lap-start 1/1/3)
  ```
  入库 `pedal=single / tc=custom+off / abs=invalid+invalid`，API 返回
  `hidden=false`、`abs=无效`，踏板与 TC 正常显示。
- [V] **历史圈已回填**：`01a18788-8756-7322-be6b-35ec5ec04f34`（巴西 0:58.444）五维
  由 NULL 改为 `dual / custom+off / invalid+invalid`，`best_laps` 已重算为 58444，
  API 返回 `hidden=false / personal_best=true / abs=无效`。
- [V] 工作区干净（两仓均已 push）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ cd paddock-api && cargo test
test result: ok. 24 passed; 0 failed; 19 ignored; 0 measured; 0 filtered out
EXIT=0

$ ./gradlew :app:lint
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline)
BUILD SUCCESSFUL in 1m 39s        ← 0 error

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 45s
```

## 3. 决策与理由
- **逐维 epoch 而非整圈一票否决**（用户定案）[V]——旧实现任一维变过就五维全填 0，
  用户圈中改 ABS 会让踏板与 TC 一起丢失且整圈被屏蔽。否决：保持整圈判定 + 只改文案
  （治不了数据丢失本身）。
- **TC/ABS 按折叠后的生效档位比对**（用户定案）[V]——`default` ≡ `custom/stock`
  在计分与展示上完全等价。按原始两列比对会把"切到默认又切回自定义最高"误标无效。
- **踏板维保持现有「实际使用占比」判据不变**（用户定案：「就按原来的不变，不是已经
  做好了吗」）[V]——它测的是物理事实（帧占比），天然免疫配置变更。
- **三态而非两态**（用户定案）[V]——`0`=缺失 / `-1`=无效 / `>=1`=有效。折成两态会让
  用户看不出是自己中途改的配置。
- **invalid 不算缺失但也不加分**（关键）[V]——两条判据方向相反：
  `is_assist_missing` 算成缺失会让圈中改一次配置就整圈被屏蔽；
  `AssistFlags` 若加分则等于竞赛条件被改变过还能享受加成。
- **不屏蔽、圈照常上榜**（用户定案）[V]——否决「仍然屏蔽」「看用户开关」两个选项。
- **踏板总帧为 0 时标无效**（用户定案，改旧行为）[V]——旧行为是降级成 off，
  等于"没证据就断言用了原生按键"。

## 4. 失败的尝试 — 不要再试
- [X] **整圈五维全填 0**（2026-09-18 旧实现）：用户圈中改一维导致全部丢失 + 整圈屏蔽。
- [X] **按原始两列比对 mode/strength**：把 `default ↔ custom/stock` 这种计分等价切换
  误判成变更。
- [X] **只看 API 前 N 条就断言"这是最快圈"**（本次会话犯的错）：`sort=time_desc`
  排的是 `created_at` 不是 `lap_ms`，且分页只给窗口数据。用户当场纠正
  「什么时候是最快圈了，瞎说呢」。判最快必须查 `best_laps` / `records` 或
  `sort=lap_asc`。见 memory `driver-sort-is-upload-time-not-lap-time`。
- [X] 继承死路（详 `.handoffs/20261030214647-handoff.md` §4 及更早）：
  用 `strings | grep` 搜 APK 中文 / `baksmali` 后直接 grep 中文 / 条件调用 `remember` /
  替用户决定"原始值"口径 / 从 UI 截图反推数据库记录 / 把已确认的信息再问一遍 /
  `onDismissRequest` 与 `onDismissFinished` 合并 / `assets/npatch/` 条目数当指纹 /
  `PickVisualMedia` / `createChooser` 单目标直通 / `ResolveInfo.loadLabel()` /
  `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at`。

## 5. 已知坑
- ⚠️ **`sort=time_desc/asc` 排的是上传时间（`created_at`），不是圈速** [V]——
  要按圈速排序用 `lap_desc`/`lap_asc`（`driver.rs::order_by` 实证）。判「是否个人最快」
  必须查 `best_laps` 表，不能看 API 返回的前 N 条。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算才会更新** [V]——`recalc_dims`
  只在管理端编辑/删除时触发。本次用等价 SQL 直接重算（含 records 两维）。
- ⚠️ **魅族 20 的无线调试 IP 由 DHCP 分配会变** [V]——2026-10-30 实测 `.142 → .123`。
  连接失败先假定 IP 变了（`adb kill-server && start-server` → 重试 → 问用户当前 IP），
  不要断定设备离线。端口 5555 固定。见 memory `wireless-adb-mdns-rediscover`。
- ⚠️ **产物验证的中文假阴性有两条独立来源** [V]（继承）——`strings`（ASCII-only）
  与 `baksmali`（`\uXXXX` 转义）。判据：搜中文就用 `grep -a` 打原始字节。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——必须走三层对拍
  （mapping → baksmali → 实参常量）。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **文档可能描述着被推翻的旧方案，比文档陈旧更危险** [V]（继承）——本次正是
  CLAUDE.md 里两处旧描述（「五维全填 0」「整组填 0」）已过时，已更正。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认日志/搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——两处都读（ChampionshipData 优先），
  代码层已确认（`lap_hook.c:564-580`），但冠军赛实跑未测。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，未重新反汇编）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.123:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 **数据展示口径（精度/小数位/间距）先问再定**
  17 **下断言前先取证，没有全量数据就不下"这是最快/最好"这类结论**。

## 6. 下一步（有序）
1. **（待你决定）发版**：模块与服务端改动均**未发版**（模块无 tag，服务端版本号
   仍是 1.0.1）。用户手上是 `1.0.4 Beta 3`，不含本次修复。发版时机完全由你决定。
2. **（待你决定）给报障用户回话**：飞行雪绒那条 0:58.444 已回填并回榜，
   现在显示「ABS 无效」而非「缺失」。
3. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
4. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现 `LAPsetup: no setup object`。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- `position saved` 每帧落盘要不要改成松手才落盘？（继承，未答复）
- 是否要按调教做榜（如「同一赛道同一调教分组」）？这决定服务端要不要建索引。
- `selectedSimples`（0x10，int[]）语义未解析——UI 简单模式的选择索引，要不要一起读？
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`，前负后正）。
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
