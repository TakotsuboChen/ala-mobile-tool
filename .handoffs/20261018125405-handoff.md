# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T12:27:34+08:00 · Git HEAD: `33cdba0`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `33cdba0`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `33cdba0`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的振感条目（本会话未动）
- 先读: `native/src/pedal_hook.c` 的 `pedal_install_hooks`（本次核心修复点）

## 1. 当前目标
**振感子系统修复**。本会话完成两项确定性 bug 修复 + 一处 UI 文案，全部实测通过、已推送。振感第 2、3 项（波形段数、幅度下限）仍未动。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个工作切片已提交并 push**：`792fab4`（native 踏板模式 OFF 不再早退）、`b397012`（15s 延迟块重读配置）、`431837c`（实验性标注）。
- [V] **持久文档已提交并 push**：`33cdba0`（CLAUDE.md 三条红线）。
- [V] **lint 全绿**：`./gradlew :app:lint` → `BUILD SUCCESSFUL`，`EXIT=0`。
- [V] **版本号未动**：`1.0.4 Alpha 4` / `104140`。未打 tag、未发 Release。
- [V] **release 已装机**（魅族 20 `192.168.50.142:5555`，`install -r` → `Success`），设备实测通过。
- [V] **修复 ① 实测证据**：关闭踏板模式后 native 日志出现
  `Pedal control replacement disabled (hooks still installed: ...)` +
  `Hooked carController` + `Hooked PlayerControlsUpdate`，且 `SLIPprobe` 共 5869 行、
  `seqLevel [0.85,0.70,0.57,0.44,...]` 是真实逐帧电平序列。
- [V] **修复 ② 实测证据**：日志出现 `15s block re-read config (fresh, not startup snapshot)` +
  `hide_pedals_init: enabled=0`，且**无** `hide_pedals: 'Throttle' hidden OK`。
- [V] **配置已改**：设备当前 `pedal_mode = off`、`hide_game_pedals = false`。
- **工作区**: 干净（除本 HANDOFF 提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1s     EXIT=0     （29 tasks，28 up-to-date）

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 20s   APK 12:21:53，已 grep 确认新代码在 classes.dex

$ adb -s 192.168.50.142:5555 install -r app/build/outputs/apk/release/app-release.apk
  → Success
```

## 3. 决策与理由
- **删掉 `pedal_install_hooks` 的静态早退而非"只装 carController"** [V]——白名单写点 `g_player_controller` 也在被跳过的那段里，单独装 carController 修不好（`is_target_player_car` 恒 false）。否决方案：按功能分别装 hook，改动面大且重复。
- **延迟块重读配置而非"广播后回写快照"** [V]——重读是幂等的，且不依赖"广播一定到达"；回写需要维护一个可变闭包变量，双 ClassLoader 下更易出错。
- **`enable_control_replacement` 字段保留但不再门控** [V]——Java 侧仍按踏板模式下发，只作诊断日志用。删字段要动 JNI 签名，收益不成比例。
- **加「（实验性）」而非改 summary** [V]——用户明确指定文案与位置（"在…后面都加上"）。

## 4. 失败的尝试 — 不要再试
- [X] **用 `--rerun-tasks` 强制重编** [V]——Gradle daemon 的 G1 GC 段错误（`hs_err_pid680233.log`，SIGSEGV in `G1RootRegionScanClosure`）。改用 `./gradlew --stop` 后普通增量构建。
- [X] **相信 Gradle 的 `UP-TO-DATE`** [V]——文件明明改过，`compileReleaseKotlin` / `packageRelease` 仍报 `UP-TO-DATE`，APK 停在旧时间戳。必须用时间戳 + APK 内 grep 特征串双重确认。
- [X] **「隐藏踏板从未生效是 bug」** [V]——**我判断错了**。旧日志里 `enabled=1` 却无 `hidden OK`，是因为游戏没进到有 `IRDSUIMobileControls` 的场景（`hide_pedals: Unity methods resolved` 只在启动后 15 秒出现过一次，那时还在主菜单）。功能按设计只在比赛中生效。
- [X] **「换 Shizuku 实现能修好诊断导出」** [V]（继承）——根因在 NPatch 的 R8 规则。
- [X] **「放行未知的更高 Fix 号（`>=`）」** [V]（继承）——Fix 后缀标记安装包形态，未验证形态不放行。
- [X] 归因「系统触感滑条压低振动」/「用户装的是官版」/「primitive 合成能修振感」/「速率编码」/「主流游戏不做持续振动」/「Fallback 叩击压到 13~17ms」 [V]（继承）。
- [X] 继承死路（详 `.handoffs/20261018122734-handoff.md` §4 及更早）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。

## 5. 已知坑
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [V]（继承）——发新 Fix 共存包时若不同步改常量并重发模块，用户装新包会被门控拦（零 hook + 循环 Toast）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [V]（继承）——共存版补了 VIBRATE，永远走 `Envelope`/`Duty`。保留它只为防御打包漏权限，**不要在它上面继续调参**。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]（继承）——ColorOS 让 `bindService` 同步返回 false。
- ⚠️ **抓地力波形 96 段是跨机型弱振的首要嫌疑（未验证）** [?]（继承）——96 段幅度完全相同（`buildFlat`），纯属多余；HAL 逐段转跳变指令 + Android 时序精度 ~50ms ⇒ 5ms 段对多数 HAL 是噪声。建议 96 段 → 1 段。**未验证、未动手。**
- ⚠️ **抓地力下发幅度常年是个位数** [?]（继承）——小米 15 Pro p50=4 / p90=23（满 255）。默认强度 50% ⇒ 幅度 ≈20，接近不可感。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [V]（继承）——机制差异不是巧合。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [V]（继承）——榜单按 version_code 分赛季。
- ⚠️ **新增门控 return 必须排在 `initGameLogger()` 之后** [V]（继承）——否则拦截时零文件日志。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（继承）——当前约 40 余项。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——曾致 kernel panic。
- ⚠️ **用户日志在 Windows 盘** [V]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`，按段 grep 而非全文。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明** 11 **对外交流不用内部编号，用现象描述** 12 **数据还有没有用由需求方判断，不替对方筛** 13 **用户要的 APK 先看 `/sdcard/Download/`，不要拉 `/data/app/`**。

## 6. 下一步（有序）
1. **（待用户定优先级）振感剩余两项**：
   a. **抓地力波形 96 段 → 1 段**——手感不变（本来就恒定幅度），跨过 HAL 量化阈值。先在本机确认无回归（魅族 `rampStepDurationMs=5`，96 段与 1 段无差别）。
   b. **抬高抓地力幅度映射下限**——p50=4/255 太低。
2. 上述改完随下个版本发出，**请 VER（HONOR Win，「强度调节无效、一直最大」）复测**——验证「96 段被 HAL 按 on/off 处理」假说的最直接人选。
3. （可选）评估 API 36 `BasicEnvelopeBuilder`。
4. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- **振感剩余两项现在做还是先搁置？**
- 抓地力手感取向：保住「连续绵密」（改段数）vs 换「高频颗粒」（速率编码）——**建议前者**。
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 移除官版后，是否需要给仍在用官版的用户一条**明确的迁移提示**（当前是零 hook + 「游戏版本不匹配」Toast）？
