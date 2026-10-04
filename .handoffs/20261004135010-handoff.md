# HANDOFF — 读全文再开始干活

生成时间: 2026-10-04T12:33:15+08:00 · Git HEAD: `cf198a9`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `cf198a9`（**已 push**，见 §2）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `cf198a9`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5（全是继承项，本会话未复核）
- 先读: 本轮是**纯文案改名**，无源码逻辑改动；若续做抓地力/振感，先读 `native/src/slip_feedback.c` 的 `read_level_with_watchdog()` 附近 + `native/src/native_log.c` 头部异步落盘注释块

## 1. 当前目标
**本轮目标已完成：UI 文案「自锁型超车按键」→「自锁式超车按键」**（用户只改这一个词）。全仓 23 处 / 13 文件一次替换（UI 开关 title + Kotlin/native 注释 + 持久文档），`.handoffs/` 归档不动。**现无进行中目标**——等用户指派下一项。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三个切片已提交并 push**：`9ff57f5`（源码改名，10 文件）→ `cf198a9`（CLAUDE.md/README.md 同步）→ 本 HANDOFF.md 提交。工作区 clean。
- [V] **门槛全绿**：`:app:lint` + `:app:assembleDebug` 一次 run EXIT=0（lint 0 error，64 warning 全在 baseline）；`:app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` EXIT=0。
- [V] **已装机**：`adb install -r` Success，设备 MEIZU_20（`192.168.50.142:5555`）；装机前后版本 **1.0.4 Alpha 1 / 104100**（**版本号未动**，用户红线）。
- [V] **改动范围**：纯文案/注释替换，**零逻辑改动**。配置键 `enableLatchOvertake` / 字段 `enable_latch_overtake` / UI 状态 `enableOvertakeLatch` 均为英文，未被触碰——无标识符复用，全量替换安全。
- [V] 用户 `/handoff` 前已确认 ok（未要求截图核对设置页观感）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint :app:assembleDebug
  → BUILD SUCCESSFUL in 1m 50s        EXIT=0  (lint: 0 errors, 64 warnings 全在 baseline)
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 20s        EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **改名走 `sed` 全量替换而非逐文件手改** [V]——已先 `grep` 确认「自锁型」在仓库里**没有标识符复用**（配置键/字段/状态全是英文），故 `sed` 只命中注释与 UI 字符串，不会误伤配置兼容性。反例见 §5 改名坑。
- **`.handoffs/` 下的归档文件不改** [V]——它们是历史快照（各自带生成时间戳 + 当时 Git HEAD），保留"当时的原文"才有时间证据价值。
- **未主动提版本号** [V]——用户全局红线；本轮只做文案，无版本诉求。

## 4. 失败的尝试 — 不要再试
- **[X] 用"电平会自己衰减到 0"解释暂停时的持续反馈** [V]——实机反证：暂停瞬间电平 = 1.00（满电平），物理帧停推后 tick 不再执行，冻结值无任何机制自行衰减。**教训：静态日志里"电平归零"的行绝大多数是正常衰减，只有一条对应暂停——不看时序、只数事件就会误判。**
- **[X] 在 tick 里做停滞检测** [V]——tick 是那个不再被调用的函数，检测不到自己的缺席。
- **[X] 停滞日志每次归零都打（不节流）** [V]——暂停 27 秒实测刷 1681 行，会通过 2MB 滚动截断销毁之前跑圈的探针证据。
- 继承死路 [X]（详 `.handoffs/20261004123315-handoff.md` §4 及更早）：通道②用 `|Fy|/(μ·Fn)` / 通道②用 `α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / 给 `HybridComponent` 写身份白名单 / `viewBox` 非零原点照搬 / hook `IRDSCarControllInput.drsToggle` / 模块自行解析赛道 DRS 区域 / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` 扫 libil2cpp / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` 清 token / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽 / 用"探针窗口到达间隔"证明掉帧。

## 5. 已知坑
- ⚠️ **中文词全量替换前必须 grep 标识符复用** [?]——本轮「自锁型」无复用故安全；反例是「滑移率→抓地力」那类，若该词恰是资源 ID / JSON key 的一部分，全量替换会破坏配置兼容性。改名前先 `grep -rn` 确认词的边界。
- ⚠️ **"生产者按帧推送的状态量"在生产者停摆后会永久冻结** [V]——任何"物理帧写 → 异步读"的信号都必须自带存活判据，判据只能放**读侧**（生产者正是停掉的那个），参照时钟必须独立于生产者帧率。本仓库目前只有抓地力反馈做了看门狗；`TcAbsIndicatorView`（25Hz 二值闪烁）若也有类似语义需排查。
- ⚠️ **native 日志写路径绝不能退回同步** [V]——FUSE 转发 + 调用方在 Unity 主线程 = 每写一行阻塞帧预算。见 CLAUDE.md 日志红线条。
- ⚠️ **探针常开让 2MB 滚动窗口缩到 ~7 分钟** [V]——拉日志要趁热。
- ⚠️ **默认值只在"键不存在"时生效** [?]——存量用户配置不会被新默认值覆盖（`ModConfig` 走 `optString(KEY, default)`）。本机曾存 `"slip_feedback_mode":"off"`，装新 APK 后仍显示「关闭」。
- ⚠️ **本设备实测走 Envelope 振感路径** [?]——`path=env` 说明共存版已带 VIBRATE 权限；官版（`com.Vince`）无此权限走 `Fallback`，**该路径未实机验证**。
- ⚠️ **金标金光观感 / 金标播报实机触发未验收** [?]（继承）。
- ⚠️ **官版（com.Vince）整体未验证** [?]（继承）——全部日志来自共存版。
- ⚠️ **自动 DRS 的 `playercar`(0x9C) 白名单回落分支未实机验证** [?]（继承）。
- ⚠️ **自锁式超车按键"抬起被吞"缺直接日志证据** [?]（继承）；**手柄路径未 hook** [?]（继承）。
- ⚠️ **崩溃自捕新增能力仍未实机触发验证** [?]（继承）：日志环快照 + 钩子登记表 + `native_log_flush()` 只在代码层完成。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——每次先 `adb devices -l`。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明"做什么/为什么/多久/你该做什么"，禁止静默等待**。

## 6. 下一步（有序）
1. **回归官版（`com.Vince`）**：验证 `Fallback` 振感路径确实出振、不抛 `SecurityException`；确认抓地力反馈停滞看门狗在官版下同样生效（日志路径同）。
2. 让用户实机确认**设置页「自锁式超车按键」文案观感**（本轮只装机未截图核对），以及抓地力反馈新文案/位置/默认值与**金标观感**。
3. （可选）排查 `TcAbsIndicatorView` 是否有同类"状态量冻结"语义（见 §5 第二条）。
4. （可选）造一次"零辅助破纪录"实测金标播报（继承）。
5. （可选）正式发版时**先问用户版本号**，再做 tag/Cargo.toml/compose 三处对齐（继承）。

## 7. 留给用户的开放问题
- 「自锁式」比「自锁型」更合你的语感吗？（本轮按你的要求改，若还想微调随时说）
- 抓地力反馈**默认开启全部**后，新装用户首次进游戏就持续有振感/光斑，是否合适？（旧默认是关闭）
- **最大振动强度默认 50%** 的手感如何？
- 官版（无 VIBRATE 权限）的 `Fallback` 振感可接受吗？
- 停滞看门狗 300ms 的响应延迟体感是否合适？
