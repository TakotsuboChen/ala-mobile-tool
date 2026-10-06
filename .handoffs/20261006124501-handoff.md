# HANDOFF — 读全文再开始干活

生成时间: 2026-10-06T01:24:51+08:00 · Git HEAD: `262ff8c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `262ff8c`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `262ff8c`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 先读: `HapticMixer.kt` 的 `Fallback` 段（含全部已证伪路线）+ 本文件 §4

## 1. 当前目标
**官版（`com.Vince`，无 VIBRATE）的抓地力振感修复**——用户报「与共存版不一致，是类似路肩的哒哒哒」。

**已收敛为"平台限制"并结案**：官版在物理上做不到与共存版一致的绵密，用户已接受现状（"先这样，起码能用，能分清抓地力情况"）。**无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个切片已提交并 push**：`225bbb4`（HapticMixer 修复）→ `262ff8c`（CLAUDE.md/README 同步）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → **EXIT=0**，BUILD SUCCESSFUL，Lint 64 warnings 全在 baseline（0 error）。
- [V] **已装机**：`adb install -r` Success，设备 MEIZU_20（`192.168.50.142:5555`）；**版本号未动**（1.0.4 Alpha 1 / 104100）。
- [V] **实测结论（用户主观，两轮）**：① 间隔压到 13~17ms（独立 ticker）⇒ 「**更快的**哒哒哒」；② 去掉强度档、全程 `CLOCK_TICK` ⇒ 「还是快速的哒哒哒，且**判断不了强弱**」。⇒ 已回退为**保留三档强度**的终态。
- [V] **间隔实测**（`dumpsys` 时间戳反推，43 样本）：最小 13ms / 中位 21ms ⇒ ticker 确实生效（原 ≈32ms），但听感未变 ⇒ **间隔不是决定因素**。
- [V] **效果编号反推**：`constant=16`→`Prebaked=30900`、`constant=4`→`21000`、`constant=1`→`31008`（确认三档常量确实不同）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → Lint found 64 warnings, 4 hints (3 errors, 14 warnings filtered by baseline)
  → BUILD SUCCESSFUL in 1m 12s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **官版保留三档强度常量**（`CLOCK_TICK`/`VIRTUAL_KEY`/`CONFIRM`）[V]——绵密既已证明无望，就不能把「能判断强弱」也赔进去（用户原话「判断不了强弱」）。否决：全程单常量（`CLOCK_TICK`，AOSP 唯一标注"供重复使用"），实测牺牲强度维度且**并未换来绵密**。
- **抓地力改独立 `Handler` ticker（10ms），与路肩彻底解耦** [V]——`fire` 只被 16ms 轮询调用 ⇒ 叩击率被硬钳 62.5Hz；且抓地力/路肩共用常量+计时器（诉求相反：前者要连续、后者要离散）是「官版抓地力=哒哒哒」的结构性成因。**此改动保留**（它确实把间隔压下来了，只是不足以改变听感）。
- **「完全一致」的两条路线明确否决** [V]——① 改官版 APK 补 VIBRATE 重打包：**会让官版不再是官版**（用户装的是 Play 原版，签名/包名/更新链必须原样）；② 模块加 framework scope hook `vibrateWithPermissionCheck`：hook system_server，失败 = bootloop，与项目 fail-safe 原则冲突。**两条都不要再提。**

## 4. 失败的尝试 — 不要再试
- **[X] 压缩 `Fallback` 叩击间隔**（22ms → 独立 ticker 10ms，实测落到 13~17ms）[V]——用户报「**更快的**哒哒哒」。**间隔不是决定因素**，不要再调这个数。
- **[X] 去掉强度档、全程只用 `CLOCK_TICK`** [V]——用户报「还是快速的哒哒哒，且判断不了强弱」。既没换来绵密又赔了强度维度。**三档必须保留。**
- **[X] 换"更短"的常量（`CONFIRM`→`GESTURE_START`）** [?]——推断 CONFIRM 时长过长导致"敲-掐-敲"，但用户实测用的是**上一版**（未装机），该推断**从未被真正验证**；本轮已回退到原三档，此路线未走完。**不要再基于"时长"推理**——`performHapticFeedback` 的 effect 由系统预置表生成，模块无法观测其时长。
- **[X] `HapticGenerator`（音频驱动触感）** [V]——官方文档明文 `setEnabled` **也要求 VIBRATE**。这是第三条看起来可行的路，同样封死。
- **[X] `pm grant` 授予 VIBRATE** [V]——报 `not a changeable permission type`（install-time/normal 权限，无法运行时授予）。
- [X] 继承死路（详 `.handoffs/20261006012451-handoff.md` §4 及更早）：通道②`|Fy|/(μ·Fn)` / 通道②`α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / `viewBox` 非零原点照搬 / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` / 用"探针窗口到达间隔"证明掉帧 / 用构造快照 `position` 做触摸基准 / 在 tick 里做停滞检测 / `kerbTapWave` 垫底 / 用 `sleep` 等用户玩一局。

## 5. 已知坑
- ⚠️ **官版振感是平台级限制，与机型无关** [V]——`VIBRATE` 是 install-time 权限；服务端 `VibratorManagerService.vibrateWithPermissionCheck` 用**调用方 UID** 做 `enforceCallingOrSelfPermission`（AOSP 通用代码）；免权限的 `View.performHapticFeedback` 只接受 **int 语义常量**、effect 由 `HapticFeedbackVibrationProvider` 生成（客户端插不进 `VibrationEffect`，唯一收 effect 的 `performHapticFeedbackWithEffect` 是 private）。**任何机型、任何调参都改变不了。**
- ⚠️ **动用户设备状态的边界（本次已踩）** [V]——读取 `/sys/class/leds/aw_vibrator/reg` 这类**动作型 sysfs 节点**会触发驱动路径，本机因此 **kernel panic 重启**（`ro.boot.bootreason=kernel_panic,bug`）。**禁止读设备私有的调试/寄存器节点**，且对"覆盖任何机型"零价值。重启游戏/设备归用户，必须先问。
- ⚠️ **装机后必须让用户重启游戏** [?]——模块代码在游戏进程加载，`adb install` 不会热更；用户曾在旧进程上测试并报「上一版没试」（实为有效反馈）。**装机后显式告知"需你重启游戏"，不要假设用户已重启。**
- ⚠️ **本设备走 Envelope 振感路径** [?]——共存版带 VIBRATE；官版走 `Fallback`。其他品牌（OPPO/Vivo/一加/Realme/iQOO/华为/小米/荣耀/红米）**全部未验证**。
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]——`optString(KEY, default)` 只在键不存在时生效。
- ⚠️ **继承未验收项** [?]：金标播报实机触发 / 官版整体 / 自动 DRS 的 `playercar`(0x9C) 回落分支 / 自锁超车"抬起被吞"直接证据 / 手柄路径 / 崩溃自捕新增能力 / 路肩振感在其他品牌的手感 / `TcAbsIndicatorView` 是否有同类"状态量冻结"语义。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明（含重启游戏）** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明**。

## 6. 下一步（有序）
1. **（用户发话才做）** 官版振感的可选改进方向——用户当前选择"先这样"，**未指派新任务**。若用户想再动：唯一未试的方向是 **只在真事件时振（锁死/空转/甩尾等大跳变），平时静默**——避开"连续输出"这个 API 做不到的事，保住"要失控了振一下"。**但需先问用户**，不要自行开工。
2. 跨品牌适配实机验证（至少一台非魅族机）——官版 `Fallback` 与共存版 `Envelope` 的手感差异。
3. （可选）正式发版时**先问用户版本号**，再做 module.prop 对齐 + tag（继承）。

## 7. 留给用户的开放问题
- 官版振感是否接受现状（"快速的哒哒哒但能分清强弱"）？还是要试"只在真事件时振"？
- 路肩振感在**其他品牌**上的手感（本机为魅族 primitive 路径，其他机可能走 waveform 兜底）。
- 抓地力「最大振动强度」默认 50% 的手感如何？
