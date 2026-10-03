# HANDOFF — 读全文再开始干活

生成时间: 2026-10-04T01:13:40+08:00 · Git HEAD: `7a44414`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `7a44414`（**已 push**，见 §2）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `7a44414`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5
- 先读: `native/src/native_log.c` 头部「异步落盘队列」注释块（本次修复的全部设计约束）+ `native/src/slip_feedback.c` 常量区（信号源定案的全部实测依据）

## 1. 当前目标
**「游戏一卡一卡」已定位并修复。** 根因 = 标定探针每 0.5s 在 **Unity 主线程**同步写 15 行日志到 `/sdcard/Android/media/`（Android 上是 **FUSE 转发**），主线程被阻塞中位 17ms / P90 34ms / 峰值 100ms ⇒ 每 0.5s 掉 1~2 帧。用户秒表独立测出「每 0.5 秒卡一次」，与数据吻合。修复后用户确认「一点都不卡了」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个切片已提交并 push**：`d763ad5`（native 日志异步落盘）→ `3932873`（探针开关与功能解耦、常开）→ `7a44414`（CLAUDE.md 持久文档）。
- [V] **门槛**：`./gradlew :app:assembleDebug` EXIT=0；`./gradlew :app:lint` EXIT=0（BUILD SUCCESSFUL，0 error）；`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` EXIT=0。
- [V] **已装机**：APK `1.0.4 Alpha 1 / 104100`（**版本号未动**，用户红线）→ `adb install -r` Success；设备 MEIZU 20（无线 `192.168.50.142:5555`）。
- [V] **修复效果实机验证**（用户实测 + 日志对照）：窗口内 15 行时间戳跨度 中位 17ms→12ms、**峰值 100ms→43ms**；逐行间隔降到 1~3ms；`lines dropped` 告警 **0 次**（队列 256 行 vs 实际 ~30 行/s）；时间戳乱序 **0 行**；agg 间隔 >600ms 占比 **0.00%**。
- [V] **探针常开接线生效**：日志实证 `slip_feedback: probe=1 (interval=25 frames)` + `first tick ... enabled=1 probe=1`。
- [V] 工作区 clean（除 HANDOFF.md 自身）。
- [?] **崩溃自捕新增能力仍未实机触发验证**（继承）：日志环快照 + 钩子登记表 + 新增的 `native_log_flush()` 都只在代码层完成，本会话无真实崩溃可验。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:assembleDebug
  → BUILD SUCCESSFUL in 6s          EXIT=0
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 3s          EXIT=0
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 2s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success   EXIT=0
```

## 3. 决策与理由
- **native 日志改异步落盘** [V]——生产者只做格式化（含时间戳）+ `snap_push` + 无锁 SPSC 环形队列入队，后台线程 `logq_writer` 做 open/fstat/write/close + 滚动截断。否决方案：①"只在探针路径异步"——其余高频日志（ABSdiag 3 行/0.5s）同样在 FixedUpdate 上，只修一条等于留一半卡顿；②"退回同步 + 关掉探针"——等于放弃"随时可抓数据"的诊断能力。
- **时间戳必须在生产者侧取** [V]——落盘时刻 ≠ 产生时刻，否则探针那种每 0.5s 一批的数据会被写成同一时间点，整份日志时间轴作废。
- **队列满丢新而非阻塞** [V]——日志可丢，帧不能掉。丢行计数 + 每 256 行由消费者线程直接写告警（不经队列，避免破坏单生产者前提）。
- **探针开关与功能开关解耦、常开** [V]——旧接线只在 `AlaMobileModule` 启动路径下发，`ConfigReceiver` 热切换路径从不调 `setSlipFeedbackProbe`，导致"关掉功能停不了探针"。探针是诊断数据采集，与"用户要不要这个功能"无关。
- **诊断周期性卡顿要量「每周期持续时长」** [V]——上一会话用"窗口间隔恒 500ms"证明"物理帧率稳定"判据本身正确，但**从结构上就看不到周期性开销**：卡顿在周期**内部**，不在周期长度抖动上。已写入 CLAUDE.md。

## 4. 失败的尝试 — 不要再试
- **[X] 用"探针窗口到达间隔"证明掉帧** [V]——间隔 P50=500.0ms、>600ms 占比 0.0%，被误读为"无卡顿"。**该判据对周期性开销天然免疫**：每周期固定写 15 行，周期长度不变而周期内部有停顿。诊断此类问题必须量周期内部耗时（如一批日志行的时间戳跨度）。
- **[X] 通道②用 `|Fy|/(μ·Fn)`（侧向摩擦圆利用率）** [V]——不区分转弯强度（悠闲 0.66 / 响胎 0.76），锚点怎么放都"只要转弯就顶满"。
- **[X] 通道②用 `α/maxAngle`（归一化滑移角）** [V]——归一化把速度除掉，高速响胎算得小（P50 仅 0.94）。
- **[X] 通道④门控用 `carSpeed > 2`** [V]——烧胎时车速恒 0，把最该响的场景全挡掉。
- 继承死路 [X]（详 `.handoffs/20261004011340-handoff.md` §4）：hook `HybridComponent.EnableOTK/DisableOTK` / 给 `HybridComponent` 写身份白名单 / `viewBox` 非零原点照搬 / hook `IRDSCarControllInput.drsToggle` / 模块自行解析赛道 DRS 区域 / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` 扫 libil2cpp / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` 清 token / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽 / 管理端 lapEdit 传旧 data-* 键名 / `load_rules` 里"见空就补"。

## 5. 已知坑
- ⚠️ **native 日志写路径绝不能退回同步** [V]——FUSE 转发 + 调用方在 Unity 主线程 = 每写一行阻塞帧预算。见 CLAUDE.md 日志红线条。改 `native_log.c` 前先读其头部注释块。
- ⚠️ **探针常开让 2MB 滚动窗口缩到 ~7 分钟** [V]——异步化消除了卡顿，但没减少写入量。用户报问题若隔十几分钟再拉日志，那段已被覆盖。拉日志要趁热。
- ⚠️ **本设备实测走 Envelope 振感路径** [?]——日志 `path=env` 说明共存版已带 VIBRATE 权限；官版（com.Vince）无此权限会走 `Fallback`（`performHapticFeedback` 22ms 密集叩击），**该路径未实机验证**。
- ⚠️ **金标金光观感 / 金标播报实机触发未验收** [?]（继承）。
- ⚠️ **官版（com.Vince）未验证** [?]（继承）——本会话全部日志来自共存版。
- ⚠️ **自动 DRS 的 `playercar`(0x9C) 白名单回落分支未实机验证** [?]（继承）。
- ⚠️ **自锁型超车按键"抬起被吞"缺直接日志证据** [?]（继承）；**手柄路径未 hook** [?]（继承）。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——每次先 `adb devices -l`。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 **不要主动提版本号**。
- ⚠️ **`_currentDRSState` / 编辑模式 / 弹窗挂载 等长期约定**见 `CLAUDE.md`（持久文档，本会话已同步异步日志条）。

## 6. 下一步（有序）
1. 回归**官版（com.Vince）**：滑移率反馈走 `Fallback` 振感路径，验证它确实出声/出振、且不抛 `SecurityException`；同时确认官版下卡顿修复同样生效（官版日志路径同）。
2. 让用户实机确认**金标观感**（榜单金字 + 金标播报），按反馈调 `GOLD_BASE`/周期或模板文案（继承，未验收）。
3. （可选）造一次"零辅助破纪录"实测金标播报（继承）。
4. （可选）正式发版时**先问用户版本号**，再做 tag/Cargo.toml/compose 三处对齐（继承）。

## 7. 留给用户的开放问题
- 卡顿修复后**多跑几圈是否稳定**（长时驾驶、多车场景）？还有没有别的周期性卡顿？
- 滑移率反馈的**振感强度默认值**合适吗？通道②（1/4 提示）的频率在长时间过弯时会不会烦？
- 官版（无 VIBRATE 权限）的 `Fallback` 振感可接受吗？（需用户用官版跑一次）
