# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T14:24:27+08:00 · Git HEAD: `88b152d`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `88b152d`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `88b152d`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: 无（§5 唯一一条已在本会话升级为 [V]）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/HapticMixer.kt` 的 `AMP_GAMMA` / `gripStrength`（本次核心改动点）

## 1. 当前目标
**振感子系统三项已完成两项**：① 96 段 → 1 段（上会话，`ef4a482`）、② 幅度 gamma 上弯（本会话，`7f8d68d`）。
两项都已提交推送、本机验证无回归，**但本机都不是验收环境**。剩余第 3 项（用户量级/数据回收）未动。
**下一步 = 等远端用户反馈**（用户定案：暂不发版）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作切片已提交并 push**：`7f8d68d`（抓地力幅度 gamma 上弯）。
- [V] **持久文档已提交并 push**：`88b152d`（CLAUDE.md 固化两条红线）。
- [V] **编译通过**：`./gradlew :app:compileReleaseKotlin` → `BUILD SUCCESSFUL in 4s`，1 task executed。
- [V] **lint 全绿**：`./gradlew :app:lint` → `BUILD SUCCESSFUL in 1m 18s`，0 error（65 warning 全在 baseline 外，既有）。
- [V] **release 构建成功**：`assembleRelease -x lintVital*` → `BUILD SUCCESSFUL in 1m 7s`，APK `13:04:48`。
- [V] **产物实证**：`unzip -p ... classes.dex | strings | grep gamma=` → 命中
  `HapticMixer: flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5 gamma=0.5`。
- [V] **已装机**（MEIZU 20 `192.168.50.142:5555`，`install -r` → `Success`）。另一台 `e98a35cb`(OPD2413) 有线连接，未动。
- [V] **实机确认新构建生效**：`ala_tool.log` 14:16:05 打出 `flatSegs=1 ... gamma=0.5`，全程 `path=env`。
- [V] **实机无回归**：电平分布与改前一致（普通过弯 0.09~0.23、事件到 1.00）、`shape grip-flat↔kerb-only` 切换正常、电平归零停机正常。
- [V] **用户实测结论**：用户原话「我感觉一切正常」。**本机只能证明不回归，证明不了变强**。
- [V] **版本号未动**：`1.0.4 Alpha 4` / `104140`。未打 tag、未发 Release（用户定案「暂不发版」）。
- **工作区**: 干净。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:compileReleaseKotlin
  → BUILD SUCCESSFUL in 4s        1 executed, 6 up-to-date

$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1m 18s    0 error（65 warnings，均在 baseline 外）

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 7s     50 tasks: 10 executed

$ unzip -p app-release.apk classes.dex | strings | grep gamma=
  → HapticMixer: flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5 gamma=0.5

$ adb -s 192.168.50.142:5555 install -r app-release.apk
  → Success

$ adb shell grep -a gamma= /sdcard/Android/media/<pkg>/ala_tool.log
  → 2026-10-18 14:16:05.739 ... gamma=0.5
```

## 3. 决策与理由
- **用 gamma 上弯，不用地板 `max(amp, FLOOR)`** [V]——地板让低端整段变平坦，"轻微打滑"与"用尽抓地力"下发同一个值，**毁掉「用振感强弱判断过弯质量」这个核心语义**；且 AOSP 不暴露"最小可驱动振幅"，地板值只能拍脑袋。γ=0.5 保持严格单调。
- **新增 `gripStrength()`，`carrier()` 保持线性专供路肩** [V]——两通道诉求相反（路肩要"每次推到滑条设定值"=撞击硬度；抓地力要"低电平也可感"=连续提示灵敏度），共用一个映射函数必然做错一个。否决方案：直接改 `carrier()`，会让路肩低电平被错误上弯。
- **`Duty` 路径不套 gamma** [V]——无振幅维度，强度已是 50%~100% 占空比，再上弯会把可调区间压没。
- **γ 取 0.5 而非更激进** [V]——0.16 电平 20→51、0.25 电平 32→63，都在"可感但不突兀"区间；满电平精确保持 255（`pct=100` + `level=1` 是硬约束）。
- **加 create 期指纹 `gamma=0.5`** [V]——本机测不出差别，必须让远端回传日志能区分新旧包。见 memory `device-dependent-change-needs-fingerprint-log`。
- **只提交不发版** [V]——用户定案「照常提交，只是暂不发版」。等远端反馈再决定是否继续改。

## 4. 失败的尝试 — 不要再试
- [X] **用 `--rerun-tasks` 强制重编** [V]（继承）——曾致 Gradle daemon G1 GC 段错误。
- [X] **相信 Gradle 的 `UP-TO-DATE`** [V]（继承）——必须用时间戳 + APK 内 grep 特征串双重确认。
- [X] **「隐藏踏板从未生效是 bug」** [V]（继承）——功能按设计只在比赛中生效。
- [X] **「换 Shizuku 实现能修好诊断导出」** [V]（继承）——根因在 NPatch 的 R8 规则。
- [X] **「放行未知的更高 Fix 号（`>=`）」** [V]（继承）——Fix 后缀标记安装包形态，未验证形态不放行。
- [X] 归因「系统触感滑条压低振动」/「用户装的是官版」/「primitive 合成能修振感」/「速率编码」/「主流游戏不做持续振动」/「Fallback 叩击压到 13~17ms」 [V]（继承）。
- [X] **「给抓地力幅度加地板」** [V]（本会话决策阶段否决）——低端变平坦会毁掉强弱判断语义，且地板值无硬件依据。改用 gamma。
- [X] 继承死路（详 `.handoffs/20261018142427-handoff.md` §4 及更早）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。
- [X] **「本机测不出变化 = 改动没生效」** [V]（继承，本会话再次确认）——日志 `gamma=0.5` 证明新构建在跑，本机 HAL 本来就能正确执行。**不要把本机无差别当成失败信号**。

## 5. 已知坑
- ⚠️ **本机（MEIZU 20）永远不是振感类改动的验收环境** [V]——它 `rampStepDurationMs=5`、振幅控制正常，是"本来就正常"的那台。两项改动在本机都只验出"不回归"。真正的验收对象 = 报「拉到 100% 依然微弱」的 ColorOS/OriginOS 用户。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [V]（继承）——发新 Fix 共存包时若不同步改常量并重发模块，用户装新包会被门控拦。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [V]（继承）——共存版补了 VIBRATE，永远走 `Envelope`/`Duty`。**不要在它上面调参**。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [V]（继承）——机制差异不是巧合。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [V]（继承）——榜单按 version_code 分赛季。
- ⚠️ **新增门控 return 必须排在 `initGameLogger()` 之后** [V]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（继承）——当前约 40 余项。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——曾致 kernel panic。
- ⚠️ **用户日志在 Windows 盘** [V]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`，按段 grep。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明** 11 **对外交流不用内部编号，用现象描述** 12 **数据还有没有用由需求方判断，不替对方筛** 13 **用户要的 APK 先看 `/sdcard/Download/`，不要拉 `/data/app/`**。

## 6. 下一步（有序）
1. **等远端用户反馈**（用户定案，暂不发版）。判据：回传日志出现
   `flatSegs=1 flatDurMs=480 kerbSegs=96 segMs=5 gamma=0.5` = 新版；无 `gamma=0.5` = 旧版。
   - 若报「明显变强、区分度还在」→ 两项改动验收通过，可考虑发版。
   - 若报「仍微弱」→ 问题不在幅度映射，转向 HAL 对 waveform 的执行（第 2 项假说或 `BasicEnvelopeBuilder`）。
   - 若报「全糊成一片、分不出强弱」→ γ 上弯过头，回调到 0.7 左右（**不要加地板**）。
2. （可选）评估 API 36 `BasicEnvelopeBuilder`。
3. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 移除官版后，是否需要给仍在用官版的用户一条**明确的迁移提示**（当前是零 hook + 「游戏版本不匹配」Toast）？
