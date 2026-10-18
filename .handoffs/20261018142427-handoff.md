# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T12:54:05+08:00 · Git HEAD: `48f5063`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `48f5063`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `48f5063`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的抓地力幅度条目（本会话未动）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/HapticMixer.kt` 的 `buildFlatMinimal`（本次核心改动点）

## 1. 当前目标
**振感子系统第 2 项（波形段数）已完成**：平顶抓地力载波 96 段 → 1 段，已提交推送、本机验证无回归。
剩余：第 3 项（抬高抓地力幅度映射下限）未动。**本机无法验收第 2 项效果，需 HONOR Win 用户复测。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作切片已提交并 push**：`ef4a482`（平顶 96 段 → 1 段 × 480ms）。
- [V] **持久文档已提交并 push**：`48f5063`（CLAUDE.md 振感段落描述更新）。
- [V] **lint 全绿**：`./gradlew :app:lint` → `LINT_EXIT=0`，`BUILD SUCCESSFUL`。
- [V] **编译通过**：`./gradlew :app:compileReleaseKotlin --rerun-tasks` → `BUILD SUCCESSFUL in 34s`，7 tasks executed。
- [V] **产物实证**：release APK 时间戳 `12:39:14`，`unzip -p ... classes.dex | strings | grep flatSegs`
  → 命中 `HapticMixer: flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5`。
- [V] **已装机**（魅族 20 `192.168.50.142:5555`，`install -r` → `Success`）。
- [V] **实机运行确认新构建生效**：`ala_tool.log` 12:40:20 打出
  `HapticMixer: flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5`，且全程 `path=env`。
- [V] **实机行为正常**：`shape none→grip-flat slip=107` / `level=1.00 band=15/16` /
  `电平归零，停止输出` 均正常出现 ⇒ 波形下发、电平跟随、停机链路无回归。
- [V] **用户实测结论**：**振感无变化**，用户原话「测完了，我的手机感觉不到振感的变化，跟原来一样还是正常」。
  **这是预期结果**（本机 `rampStepDurationMs=5`，96 段与 1 段等价），非失败。
- [V] **版本号未动**：`1.0.4 Alpha 4` / `104140`。未打 tag、未发 Release。
- **工作区**: 干净。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1s        LINT_EXIT=0

$ ./gradlew :app:compileReleaseKotlin --rerun-tasks
  → BUILD SUCCESSFUL in 34s       7 actionable tasks: 7 executed

$ unzip -p app-release.apk classes.dex | strings | grep flatSegs
  → HapticMixer: flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5

$ adb -s 192.168.50.142:5555 install -r app-release.apk
  → Success

$ adb shell grep -a flatSegs /sdcard/Android/media/<pkg>/ala_tool.log
  → 2026-10-18 12:40:20.721 ... flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5
```

## 3. 决策与理由
- **只压段数、不改总时长（480ms 保持不变）** [V]——段数压到 1 的同时把段时长拉到 480ms，回绕节奏与原来完全一致。否决方案：单段 5ms（会让回绕频率从 2Hz 跳到 200Hz，引入一个未验证的变量，与"只改段数"的目标无关）。
- **不动 `buildDuty` / 路肩颗粒 / 桥接波形** [V]——这三处的段数**本身就是信息**（通电比例分辨率、周期长度、`repeat=n` 需要 ≥n+1 段），压成 1 段会直接坏掉。判断标准 = 段与段之间有没有差异，不是段数多不多。
- **加 create 期指纹日志 `flatSegs=...`** [V]——本机测不出差别，必须让远端用户回传日志时能区分"装的是旧包"与"新包但硬件无差别"。见 memory `device-dependent-change-needs-fingerprint-log`。
- **如实记录被翻转的框架行为** [V]——`isHapticFeedbackCandidate()` 含「段数 > 3 ⇒ false」，1 段后变成 candidate；仅在属性降级到 `ATTR_NONE` 时归入 `USAGE_TOUCH`，默认 `USAGE_MEDIA` 路径不受影响。写进 `buildFlatMinimal` 注释，避免以后当新 bug 排查。

## 4. 失败的尝试 — 不要再试
- [X] **用 `--rerun-tasks` 强制重编** [V]（继承）——曾致 Gradle daemon G1 GC 段错误。本会话一次侥幸成功（34s），但仍是已知风险，优先 `./gradlew --stop` 后走增量。
- [X] **相信 Gradle 的 `UP-TO-DATE`** [V]（继承）——必须用时间戳 + APK 内 grep 特征串双重确认。
- [X] **「隐藏踏板从未生效是 bug」** [V]（继承）——功能按设计只在比赛中生效。
- [X] **「换 Shizuku 实现能修好诊断导出」** [V]（继承）——根因在 NPatch 的 R8 规则。
- [X] **「放行未知的更高 Fix 号（`>=`）」** [V]（继承）——Fix 后缀标记安装包形态，未验证形态不放行。
- [X] 归因「系统触感滑条压低振动」/「用户装的是官版」/「primitive 合成能修振感」/「速率编码」/「主流游戏不做持续振动」/「Fallback 叩击压到 13~17ms」 [V]（继承）。
- [X] 继承死路（详 `.handoffs/20261018125405-handoff.md` §4 及更早）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。
- [X] **「本机测不出变化 = 改动没生效」** [V]（本会话）——日志 `flatSegs=1` 证明新构建在跑，本机 `rampStepDurationMs=5` 对 96/1 段本就等价。**不要把本机无差别当成失败信号**。

## 5. 已知坑
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [V]（继承）——发新 Fix 共存包时若不同步改常量并重发模块，用户装新包会被门控拦。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [V]（继承）——共存版补了 VIBRATE，永远走 `Envelope`/`Duty`。保留只为防御打包漏权限，**不要在它上面调参**。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]（继承）。
- ⚠️ **抓地力下发幅度常年是个位数** [?]（继承，**未动**）——小米 15 Pro p50=4 / p90=23（满 255）。默认强度 50% ⇒ 幅度 ≈20。注意 `carrier()` 公式是 `强度% × 电平 × 255`，两个都小则乘积更小；改法应在地板/映射，不是调默认强度。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [V]（继承）——机制差异不是巧合。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [V]（继承）——榜单按 version_code 分赛季。
- ⚠️ **新增门控 return 必须排在 `initGameLogger()` 之后** [V]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（继承）——当前约 40 余项。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——曾致 kernel panic。
- ⚠️ **用户日志在 Windows 盘** [V]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`，按段 grep。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明** 11 **对外交流不用内部编号，用现象描述** 12 **数据还有没有用由需求方判断，不替对方筛** 13 **用户要的 APK 先看 `/sdcard/Download/`，不要拉 `/data/app/`**。

## 6. 下一步（有序）
1. **（待用户定优先级）振感第 3 项：抬高抓地力幅度映射下限**——`HapticMixer.carrier()` 现在是
   `(强度%/100) × 电平 × 255`，默认 50% 强度下满电平也只有 127，实际 p50 仅 4~23。
   改法候选：加地板（如 `max(carrier, 地板值)`）或改映射曲线。⚠️ 本机同样验证不了。
2. **请 HONOR Win 用户复测第 2 项**——验证「96 段被 HAL 按 on/off 处理」假说的最直接人选。
   新构建指纹 = 日志出现 `flatSegs=1 flatDurMs=480`。
3. （可选）评估 API 36 `BasicEnvelopeBuilder`。
4. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- **振感第 3 项现在做还是等 HONOR Win 复测结果？**
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 移除官版后，是否需要给仍在用官版的用户一条**明确的迁移提示**（当前是零 hook + 「游戏版本不匹配」Toast）？
