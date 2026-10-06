# HANDOFF — 读全文再开始干活

生成时间: 2026-10-06T12:45:30+08:00 · Git HEAD: `49c9a75`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `49c9a75`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `49c9a75`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 先读: `native/src/next_lap_hook.c` 顶部注释块（含全部反汇编证据与升版核对清单）

## 1. 当前目标
**「禁止删除下一圈成绩」新功能已交付并实机验收。无进行中目标。**

功能：赛道后段（`trackPercentage > 0.8`）冲出赛道限制时，游戏原生会连带作废下一圈
成绩；本功能让这种情况只作废本圈。**默认开启**（用户 2026-10-06 定案）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个切片已提交并 push**：`127a25d`（工作）→ `49c9a75`（CLAUDE.md/README）→ 本 HANDOFF.md。
- [V] **门槛全绿**（`--rerun-tasks` 强制全量）：`rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease --rerun-tasks` → **BUILD SUCCESSFUL in 3m 2s**，`Lint found 64 warnings, 4 hints (3 errors/14 warnings filtered by baseline)` = 0 error。
- [V] **native 符号已导出**：`llvm-nm -D libala-core.so` → `T ..._initNextLap` + `T ..._setProtectNextLap`。
- [V] **已装机**：`adb install -r` Success，设备 MEIZU_20（`192.168.50.142:5555`）；**版本号未动**（1.0.4 Alpha 1 / 104100）。
- [V] **实机验收（Monza 计时赛，756 次拦截，决定性因果链）**：lap A 在 S3 内以 `trackPercentage` **0.896~0.934（全 > 0.8，111 次）** 连续触发 → lap A 照常作废（`LAPinv 1:17.810`）→ **紧随的 lap B 仍出有效成绩并刷新个人最佳（`LAPbest 1:17.064`）**。这正是「只删本圈、不删下一圈」的精确达成。
- [V] **拦截日志分布**：756 次中 **303 次 > 0.8**（真正会连带删下一圈的）、453 次 ≤ 0.8（本就只删本圈，拦截无副作用）；`trackPercentage` 范围 0.318~0.945。
- [V] **配置页开关**位置正确（「自锁式超车按键」下方），安装日志 `initNextLap protect=true`。
- **工作区**: clean。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease --rerun-tasks
  → Lint found 64 warnings, 4 hints (3 errors, 14 warnings filtered by baseline)
  → BUILD SUCCESSFUL in 3m 2s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **改写 `InvalidateLap` 的 `trackPercentage` 入参为 0，而非"orig 之后清 `alreadyInvalidated[1]`"** [V]——反汇编实证 `trackPercentage` 在方法内只被两处 `fcmp s8, #0.8`（`.rodata` 0x929B2C = 0.8f，0x1A0DAE4 / 0x1A0DBB0）使用，是"是否连带删除下一圈"的**唯一判据**。压到 0 后游戏自己走 `<= 0.8` 分支 ⇒ 标志位、**提示文案**（自动变 "Lap time deleted"）、状态机全部自然正确。否决：事后清标志——文案仍是 "This and next lap times deleted"，与模块实际行为矛盾（用户会以为功能没生效）。
- **默认开启** [V]（用户定案）——游戏原生"连带删除下一圈"对刷圈体验损害大，作为默认改善直接生效；用户仍可在配置页关闭。
- **保留拦截日志（756 行/2.5min）** [V]（用户定案「先不改」）——它是"功能真的拦下了连带删除"的唯一可观测证据。不造成主线程卡顿（native 日志已异步落盘），代价只是占 2MB 滚动日志额度。
- **hook 恒装上、开关在回调内判** [V]——与 `drs_hook.c`/`overtake_hook.c` 同策略；关掉功能时逐字节等于原版。

## 4. 失败的尝试 — 不要再试
- [X] **hook `OnLapChange` 或 `carModifier.CheckTrackLimitRespected`** [V]——前者不在出界路径上（`OnLapChange` 全 `.so` **零 `bl` 调用者**，是 Unity 消息回调，且它读 `alreadyInvalidated` 的时机在圈**起点**，此时早已错过出界时刻）；后者只**读** `alreadyInvalidated` 判断"本圈是否已被作废"来决定是否再作废一次，**不改任何状态**，hook 它无法阻止下一圈被删。
- [X] 继承死路（详 `.handoffs/20261006124501-handoff.md` §4 及更早）：压缩 `Fallback` 叩击间隔 / 去掉官版强度档 / 换更短 haptic 常量 / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道②`α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / `viewBox` 非零原点照搬 / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` / 用"探针窗口到达间隔"证明掉帧 / 用构造快照 `position` 做触摸基准 / 在 tick 里做停滞检测 / `kerbTapWave` 垫底 / 用 `sleep` 等用户玩一局。

## 5. 已知坑
- ⚠️ **"作废圈速"不止 `InvalidateLap` 一条路** [V]——`carModifier.OnTriggerEnter`(0x176B86C) 在两处**直接** `strb wzr, [x8, #0x2dc]` 清 `odometerHandler.validLap`（不碰 `alreadyInvalidated`），紧跟 `OpenTireSelectionWindow`/`HandleFrontWingReplacementToggle`（换胎/换前翼）。这些**不涉及"删除下一圈"**，不影响本功能，但说明清 `validLap` 是多路径的——将来做类似功能要留意。
- ⚠️ **`LAPinv` 无法区分"只删本圈"与"删本圈+下一圈"** [V]——`lap_hook` 读的 `validLap` 是持续状态位，两种情况都是 0、日志行长得一样。要区分只能看 `InvalidateLap` 的入参 `trackPercentage`（本模块的诊断日志即为此时唯一证据）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（`HOOK_REG_MAX`）——当前约 40 余项，接近但仍有余量；再新增 hook 需留意。
- ⚠️ **官版振感是平台级限制，与机型无关** [V]——`VIBRATE` 是 install-time 权限；`View.performHapticFeedback` 只接受 int 语义常量、effect 由系统预置表生成。**任何机型、任何调参都改变不了。**（继承，未在本会话重验）
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——读 `/sys/class/leds/aw_vibrator/reg` 这类**动作型 sysfs 节点**会触发驱动路径，曾致本机 kernel panic 重启。
- ⚠️ **装机后必须让用户重启游戏** [V]（本会话再次验证）——模块代码在游戏进程加载，`adb install` 不会热更。
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]（继承）——`optString(KEY, default)` 只在键不存在时生效。⚠️ 对本次新键意味着：**已装过旧版的用户**升级后 `enable_protect_next_lap` 键不存在 → 取新默认 `true`（符合预期）；但**已存在该键且为 `false`** 的用户不会被翻转。
- ⚠️ **继承未验收项** [?]：金标播报实机触发 / 官版整体 / 自动 DRS 的 `playercar`(0x9C) 回落分支 / 自锁超车"抬起被吞"直接证据 / 手柄路径 / 崩溃自捕新增能力 / 路肩振感在其他品牌的手感 / `TcAbsIndicatorView` 是否有同类"状态量冻结"语义 / 跨品牌适配实机验证。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明（含重启游戏）** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明**。

## 6. 下一步（有序）
1. **（用户发话才做）** 无指派任务。可选方向：把拦截日志改为"每圈只记首次"以降噪（用户当前选择"先不改"，勿自行开工）。
2. 跨品牌适配实机验证（至少一台非魅族机）——官版 `Fallback` 与共存版 `Envelope` 的手感差异。
3. （可选）正式发版时**先问用户版本号**，再做 module.prop 对齐 + tag（继承）。

## 7. 留给用户的开放问题
- 拦截日志的噪音可接受吗？还是想改成"每圈只记首次"？
- 官版振感是否接受现状（"快速的哒哒哒但能分清强弱"）？还是要试"只在真事件时振"？
- 路肩振感在**其他品牌**上的手感（本机为魅族 primitive 路径，其他机可能走 waveform 兜底）。
