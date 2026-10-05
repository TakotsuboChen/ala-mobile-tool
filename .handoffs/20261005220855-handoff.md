# HANDOFF — 读全文再开始干活

生成时间: 2026-10-04T13:50:10+08:00 · Git HEAD: `2df5bbe`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `2df5bbe`（**已 push**，见 §2）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `2df5bbe`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5（全是继承项，本会话未复核）
- 先读: 若续做 overlay/触摸，先读 `app/src/main/kotlin/tools/alamobile/mod/overlay/PedalOverlayView.kt` 的 `updateValuesFromPointer()` 头部注释块 + CLAUDE.md 的 PedalOverlayView 条

## 1. 当前目标
**本轮目标已完成：修「编辑模式调整位置/大小后，视觉已移动但实际操控仍在旧位置」**（点新位没反应、点旧位反而给油，重启游戏才正常）。根因 = 触摸值换算用构造时快照 `position` 而非运行时布局。顺带补 `saveOverlayPosition` 刷 `saved_at`。**现无进行中目标**——等用户指派下一项。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个切片已提交并 push**：`8da2c90`（坐标基准修复）→ `c283181`（saved_at 刷新）→ `2df5bbe`（CLAUDE.md 同步）→ 本 HANDOFF.md 提交。工作区 clean。
- [V] **门槛全绿**：`:app:lint --rerun-tasks` EXIT=0（0 error，64 warning 全在 baseline）；`:app:lint :app:assembleDebug :app:assembleRelease -x lintVital*` EXIT=0。
- [V] **已装机**：`adb install -r` Success，设备 MEIZU_20（`192.168.50.142:5555`）；装机前后 **1.0.4 Alpha 1 / 104100**（**版本号未动**）。
- [V] **实机验收（用户自测 + 日志双向确认）**：
  - 坐标基准：拖动后 DOWN 的 `onScreen` 与落盘 `single_pedal_position` 逐位吻合（1841×106 → 1871×108，对应 x/y 比例）；把 MOVE 的 `relY/height` 重算曲线对拍日志值，**四点全中**（误差 <2e-3）⇒ 换算确实走 live 布局。
  - saved_at：拖拽后 local ts `1791092220128`（= 13:37:00，拖拽那一刻），旧值 00:42；仲裁行随之由 `winner=remote prefs` 翻为 `winner=local file`（4 次）。
  - 全程 `RAW_MISMATCH`/`switch=true`/`NOT_FOUND` 计数 **全 0**（走干净主路径，无 fallback 干扰）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint --rerun-tasks
  → Lint found 64 warnings, 4 hints (3 errors, 14 warnings filtered by baseline)
  → BUILD SUCCESSFUL in 1m 40s      EXIT=0
$ ./gradlew :app:lint :app:assembleDebug :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL                 EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **坐标基准改回运行时布局，而非"编辑后重建 view"** [V]——重建只是绕开症状（重启之所以有效就是重建拿了新快照），根因是「视觉用 live、判定用快照」两个基准劈叉。改基准与 `onDraw` 同源，一次性消除整类问题。否决：编辑结束时重建踏板 view（丢正在进行的触摸状态、且下次换个改动路径又会复发）。
- **`top` 取 `getLocationOnScreen()[1]` 而非 `view.top`** [V]——`rawY` 是屏幕绝对坐标，`view.top` 是父容器相对坐标，父容器原点非 0 时会错；且该次 `loc` 本来已取（零额外开销）。
- **`saveOverlayPosition` 刷 `saved_at` 前先核"两个写方是否都代表真实变更"** [V]——刷时间戳在"仅回放/补齐"型写方存在时会把陈旧配置顶成最新，是灾难。已核：`saveOverlayPosition`=用户拖拽、`ConfigReceiver`=模块广播，都对应真实改动，且字段不相交。
- **`layoutDiag` 去掉与快照对拍** [V]——快照过期后对拍结果必然"不一致"，是噪声不是信号。

## 4. 失败的尝试 — 不要再试
- **[X] 用构造时快照 `position` 做触摸坐标基准** [V]——编辑模式改 `layoutParams` 后快照不更新 ⇒ 视觉/判定劈叉（本轮修复的正是它）。8 月引入时是为绕开"pairip 壳 relayout 漂移"，但**该假设已被 DOWN 布局对比日志证伪**（三次 `onScreen` 完全一致）。教训：**证伪了假设，却没回收为它写的补丁**，是这类 bug 的典型来源。
- **[X] 用"电平会自己衰减到 0"解释暂停时的持续反馈** [V]——实机反证：暂停瞬间电平 = 1.00，物理帧停推后 tick 不再执行，冻结值无任何机制自行衰减。
- **[X] 在 tick 里做停滞检测** [V]——tick 是那个不再被调用的函数，检测不到自己的缺席。
- **[X] 停滞日志每次归零都打（不节流）** [V]——暂停 27 秒实测刷 1681 行，会通过 2MB 滚动截断销毁跑圈探针证据。
- 继承死路 [X]（详 `.handoffs/20261004135010-handoff.md` §4 及更早）：通道②用 `|Fy|/(μ·Fn)` / 通道②用 `α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / 给 `HybridComponent` 写身份白名单 / `viewBox` 非零原点照搬 / hook `IRDSCarControllInput.drsToggle` / 模块自行解析赛道 DRS 区域 / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` 扫 libil2cpp / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` 清 token / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽 / 用"探针窗口到达间隔"证明掉帧。

## 5. 已知坑
- ⚠️ **"视觉用 live、判定用快照"是同一 bug 的变体** [V]——任何新 overlay 控件若绘制用运行时布局、触摸换算却用构造快照，都会复现本轮症状。`GearShiftView` 用 `event.y`（自相对）故不受影响，但它也因此没有"屏幕绝对坐标"这条路。
- ⚠️ **中文词全量替换前必须 grep 标识符复用** [?]——反例是「滑移率→抓地力」那类，若该词恰是资源 ID / JSON key 的一部分，全量替换会破坏配置兼容性。
- ⚠️ **"生产者按帧推送的状态量"在生产者停摆后会永久冻结** [V]——判据只能放**读侧**，参照时钟必须独立于生产者帧率。目前只有抓地力反馈做了看门狗；`TcAbsIndicatorView`（25Hz 二值闪烁）若也有类似语义需排查。
- ⚠️ **native 日志写路径绝不能退回同步** [V]——FUSE 转发 + 调用方在 Unity 主线程 = 每写一行阻塞帧预算。见 CLAUDE.md 日志红线条。
- ⚠️ **探针常开让 2MB 滚动窗口缩到 ~7 分钟** [V]——拉日志要趁热。
- ⚠️ **local config 文件的非 position 内容只在收广播时更新** [?]——拖拽只改 position 字段，故 local 会保留已废弃的死键（如 `slip_feedback_start`，全仓零引用）而 mailbox 没有。不影响行为（无人读），但它说明 local 的"其他字段新鲜度"依赖 `ConfigReceiver` 广播可达。
- ⚠️ **默认值只在"键不存在"时生效** [?]——存量用户配置不会被新默认值覆盖（`optString(KEY, default)`）。
- ⚠️ **本设备实测走 Envelope 振感路径** [?]——`path=env` 说明共存版已带 VIBRATE 权限；官版（`com.Vince`）无此权限走 `Fallback`，**该路径未实机验证**。
- ⚠️ **金标金光观感 / 金标播报实机触发未验收** [?]（继承）；**官版（com.Vince）整体未验证** [?]（继承）——全部日志来自共存版；**自动 DRS 的 `playercar`(0x9C) 白名单回落分支未实机验证** [?]（继承）；**自锁式超车按键"抬起被吞"缺直接日志证据** [?]（继承）；**手柄路径未 hook** [?]（继承）；**崩溃自捕新增能力仍未实机触发验证** [?]（继承）。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——每次先 `adb devices -l`。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明"做什么/为什么/多久/你该做什么"，禁止静默等待**。

## 6. 下一步（有序）
1. **回归官版（`com.Vince`）**：验证 `Fallback` 振感路径确实出振、不抛 `SecurityException`；确认抓地力反馈停滞看门狗与本次坐标基准修复在官版下同样生效（日志路径同）。
2. 让用户实机确认**编辑模式调整位置后的操控手感**（本轮已由用户自测通过 + 日志确认；可再顺手核对设置页「自锁式超车按键」文案观感，上轮只装机未截图核对）。
3. （可选）排查 `TcAbsIndicatorView` 是否有同类"状态量冻结"语义（见 §5 第三条）。
4. （可选）造一次"零辅助破纪录"实测金标播报（继承）。
5. （可选）清掉 local config 里的死键 `slip_feedback_start`（需用户发话，属另一件事）。
6. （可选）正式发版时**先问用户版本号**，再做 tag/Cargo.toml/compose 三处对齐（继承）。

## 7. 留给用户的开放问题
- 编辑模式调整后的操控是否已完全符合预期（本轮日志已确认，仅作最终确认）？
- 抓地力反馈**默认开启全部**后，新装用户首次进游戏就持续有振感/光斑，是否合适？（旧默认是关闭）
- **最大振动强度默认 50%** 的手感如何？
- 官版（无 VIBRATE 权限）的 `Fallback` 振感可接受吗？
- 停滞看门狗 300ms 的响应延迟体感是否合适？
