# HANDOFF — 读全文再开始干活

生成时间: 2026-10-03T22:25:04+08:00 · Git HEAD: `1e3c36f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `1e3c36f`（**已 push**，见 §2）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `1e3c36f`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5
- 先读: `native/src/slip_feedback.c` 常量区（**信号源定案的全部实测依据都在那里**）+ `app/src/main/kotlin/tools/alamobile/mod/overlay/SlipHaptic.kt` 类注释（振感十二轮迭代的证伪表）

## 1. 当前目标
**滑移率反馈（振感 + 视觉）已落地并实机验收。** 六条通道的信号源经三轮标定定案（α 原始值 → `|Fy|/(μ·Fn)` → `α/maxAngle` → **`v·sin α`**），④ 后轮空转的门控从 `carSpeed` 改为 `slipVelo`。用户原话：「**各个场景感觉都挺贴合实际抓地力表现**」——手感目标达成。

**新发现、未处理的问题**：用户报告游戏「一卡一卡的」（掉帧/卡顿），怀疑由本会话某个版本引入。**本会话已用数据排除物理帧率**（见 §4），根因未定位。

## 2. 已验证状态 — 工作实际停在哪
- [V] **五个切片已提交并 push**：`d8cfdee`（native 崩溃现场增强：日志环快照 + 钩子登记表）→ `451abd2`（滑移率反馈 native 核心）→ `ee863d2`（UI：弓形光斑 + 振感 + 配置项）→ `103d9c2`（coex skill 补 VIBRATE 权限）→ `1e3c36f`（持久文档）。
- [V] **门槛**：`./gradlew :app:lint` → `Lint found 64 warnings, 4 hints (3 errors + 14 filtered by baseline)` + `BUILD SUCCESSFUL in 54s`；`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → `BUILD SUCCESSFUL`。
- [V] **已装机**：APK `1.0.4 Alpha 1 / 104100`（**版本号未动**，用户红线）→ `adb install -r` Success；设备 MEIZU 20（无线 `192.168.50.142:5555`）。
- [V] **工作区 clean**（除 HANDOFF.md 自身）。
- [V] **崩溃自捕新增能力未实机触发验证** [?]——日志环快照与钩子登记表只在代码层完成，本会话无真实崩溃可验。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint
  → Lint found 64 warnings, 4 hints (and 3 errors and 14 warnings filtered by baseline lint-baseline.xml)
  → BUILD SUCCESSFUL in 54s        EXIT=0
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 6s         EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success   EXIT=0
```

## 3. 决策与理由
- **通道②信号源 = `v·sin(max_i|α_i|)`（侧向滑移速度，m/s），锚点 8→15 封顶 1/4** [V]——三轮标定的终局。否决 ①`|Fy|/(μ·Fn)`：物理正确但不区分转弯强度（悠闲 0.66 / 响胎 0.76 只差 15%），实机 69% 窗口钉死 0.25；否决 ②`α/maxAngle`：归一化把速度除掉，高速响胎算得小（P50 仅 0.94，够不到锚点）。`sv` 的分离度：响胎 19.0 vs 悠闲 5.0 = 3.8×、vs 高速直线 1.3 = 14×。⚠️ 用 `sin` 不用 `tan`（有界、直线刹车不误触发）；⚠️ `v` 用**速度模长**不是 `carSpeed`。
- **通道④门控 = 后轮自己的 `slipVelo`(0x108)，不是 `carSpeed`** [V]——烧胎的定义就是"车速不动、轮子在转"。实测顶着墙烧胎窗口 `carSpeed` 恒 **0.0** 而后轮 ω=97~120 rad/s；旧门控把整段门死。`slipVelo ≡ |ω·R − v|` 同时答对两个问题：生成伪影帧 ω 仅 1.9 rad/s ⇒ 0.67（排除），真烧胎 ⇒ 34.7（保留）。**旧判据能挡伪影只是巧合**。
- **通道②的用途 = 最佳抓地力提示器，不是失控报警** [V]——用户原话「要的就是过弯感受到 1/4 振感的时候证明过得非常好」。故锚点落在过弯分布**内部**，不是边缘之外。
- **滑移率采样点挂在 `carController` 的 orig 之后** [V]——不是 `CarControllInput.FixedUpdate`。后者读的字段由 `multithreadWheelManager→ComputeWheelPhysics` 写入，两者是独立 Unity 回调、顺序不保证，挂错会读到上一帧。
- **标定探针与滑移率反馈同开关** [V]——代价 ~5KB/s 日志（15 行/0.5s），native 2MB 滚动窗口缩到 ~7 分钟；换来"用户报手感不对时日志里直接有数据"。
- **配置项写 native 常量而非 Java 下发** [V]——信号源随实机标定频繁调整，放 Java 侧会让调参变成跨语言改动。

## 4. 失败的尝试 — 不要再试
- **[X] 通道②用 `|Fy|/(μ·Fn)`（侧向摩擦圆利用率）** [V]——不区分转弯强度（悠闲 0.66 / 响胎 0.76），锚点怎么放都"只要转弯就顶满"，实机 69% 窗口钉死 0.25。根因：正常转弯时轮胎本就在接近摩擦圆饱和，"利用率"衡量"用了多少"而非"用过头了"。
- **[X] 通道②用 `α/maxAngle`（归一化滑移角）** [V]——归一化把速度除掉了，高速小滑移角算得很小（响胎窗口 P50 仅 0.94），用户报「高速打方向明显响胎却几乎不振」。
- **[X] 通道④门控用 `carSpeed > 2`** [V]——烧胎时车速恒 0，把最该响的场景全挡掉。
- **[X] 用"探针窗口到达间隔"证明掉帧** [V]——探针窗口 = 恰好 25 物理帧，三份日志（n10/n11/n12）的到达间隔 P50=500.0ms、P99≤521ms、>600ms 占比 **0.0%** ⇒ **游戏物理帧率稳定 50Hz**。卡顿不在物理步进层，别再走这条路。
- 继承死路 [X]（详 `.handoffs/20261003222408-handoff.md` §4）：hook `HybridComponent.EnableOTK/DisableOTK` / 给 `HybridComponent` 写身份白名单 / `viewBox` 非零原点照搬 / hook `IRDSCarControllInput.drsToggle` / 模块自行解析赛道 DRS 区域 / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` 扫 libil2cpp / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` 清 token / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽 / 管理端 lapEdit 传旧 data-* 键名 / `load_rules` 里"见空就补"。

## 5. 已知坑
- ⚠️ **【本次新发现，未定位】游戏「一卡一卡」** [?]——用户报告，怀疑本会话某版本引入。已排除物理帧率（§4）。**嫌疑排查顺序**：① 高频日志（~36 行/s 文件写 + 2MB 滚动 `truncate_log_file`）② 滑移率 UI（16ms 主线程轮询 + 电平变化时 `invalidate`）③ 振感 IPC（日志实证 band 变化 median 2/s、P99 9/s、max 10/s，**振感起振 489 次/2057s = 0.24 次/s，不频繁**）④ 本机 iQOO/OriginOS LTPO 刷新率仲裁被 overlay 绘制扰动（**旧定案，见 CLAUDE.md 编辑模式条**——但那是 OriginOS4，本设备是 Flyme）。
- ⚠️ **本设备实测走 Fallback 振感路径** [?]——日志 `path=env`（Envelope）说明**共存版已带 VIBRATE 权限**；但官版（com.Vince）无此权限会走 `Fallback`（`performHapticFeedback` 22ms 密集叩击），**该路径未实机验证**。
- ⚠️ **native 日志 2MB 滚动窗口** [V]——探针常开让窗口缩到 ~7 分钟。用户报问题若隔了十几分钟再拉日志，**那段已被覆盖**。拉日志要趁热。
- ⚠️ **金标金光观感 / 金标播报实机触发未验收** [?]（继承）。
- ⚠️ **官版（com.Vince）未验证** [?]（继承）——本会话全部日志来自共存版。
- ⚠️ **自动 DRS 的 `playercar`(0x9C) 白名单回落分支未实机验证** [?]（继承）。
- ⚠️ **自锁型超车按键"抬起被吞"缺直接日志证据** [?]（继承）；**手势路径未 hook** [?]（继承）。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——每次先 `adb devices -l`。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 **不要主动提版本号**。
- ⚠️ **`_currentDRSState` / 编辑模式 / 弹窗挂载 等长期约定**见 `CLAUDE.md`（持久文档，本会话已同步滑移率反馈条目）。

## 6. 下一步（有序）
1. **定位「一卡一卡」**：先问用户卡顿是否与滑移率反馈开关相关（关掉开关是否恢复）。若是 → 按 §5 的 ①②③ 顺序排查；若否 → 用 `dumpsys gfxinfo tools.alamobile.mod`（需游戏运行中）或 `dumpsys SurfaceFlinger --latency` 抓真实渲染帧率，与探针到达间隔（已证 50Hz）对照，区分"物理不卡但渲染卡"。
2. 回归**官版（com.Vince）**：滑移率反馈会走 `Fallback` 振感路径，验证它确实出声/出振、且不抛 `SecurityException`。
3. 让用户实机确认**金标观感**（榜单金字 + 金标播报），按反馈调 `GOLD_BASE`/周期或模板文案（继承，未验收）。
4. （可选）造一次"零辅助破纪录"实测金标播报（继承）。
5. （可选）正式发版时**先问用户版本号**，再做 tag/Cargo.toml/compose 三处对齐（继承）。

## 7. 留给用户的开放问题
- 卡顿是**什么时候开始的**？和滑移率反馈开关有没有关系（关掉是否恢复）？发生在什么场景（菜单 / 跑圈 / 编辑模式）？
- 滑移率反馈的**振感强度默认值**合适吗？通道②（1/4 提示）的频率在长时间过弯时会不会烦？
- 官版（无 VIBRATE 权限）的 `Fallback` 振感可接受吗？（需用户用官版跑一次）
