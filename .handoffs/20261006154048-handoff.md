# HANDOFF — 读全文再开始干活

生成时间: 2026-10-06T13:21:00+08:00 · Git HEAD: `6f82b4e`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `6f82b4e`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `6f82b4e`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/configure/ConfigurePagerMiuix.kt` 顶部注释块

## 1. 当前目标
**配置页改版已交付并装机，待用户实机验收。无进行中目标。**

原本一页到底的四个区块（原生特性控制 / Overlay 控件 / 响应曲线 / 杂项）改成**四张互不相连的入口卡片**，
点进去各是一个**独立二级页**。卡片无描述文字、高度撑到两行（72dp）。「游戏原生特性控制」按用户要求改名「原生特性控制」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三切片已提交并 push**：`de44cb8`（图标）→ `cc369be`（Hub + 四二级页）→ `e0ce0a9`（VM 共享修复）→ `6f82b4e`（持久文档）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → **BUILD SUCCESSFUL in 2m 31s**，`Lint found 64 warnings, 4 hints (3 errors/14 warnings filtered by baseline)` = 0 error。
- [V] **已装机**：`adb install -r app/build/outputs/apk/release/app-release.apk` → Success，设备 MEIZU_20（`192.168.50.142:5555`）；**版本号未动**（1.0.4 Alpha 1 / 104100）。
- [V] **四个 SVG 图标三重验证**：① `rsvg-convert` 渲染原图 vs「包一层 `<g transform="translate(-minX,-minY)>`」参考图，`compare -metric AE` = **0**（四个全 0）；② 从 Kotlin 源抽回 `d` 字符串与源 SVG **逐字符 diff 全 OK**（path 数 6/2/8/4 逐条对齐）；③ 目视确认（Code=方框内 `</>`、Overlay=双层菱形、Curve=虚线坐标轴+曲线、Other=四块圆角方块）。
- [V] **无遗留旧结构**：`grep "Section [1-4]\|SmallTitle"` 在 `ConfigurePagerMiuix.kt` 零命中。
- **工作区**: clean。
- ⚠️ **未做**：用户选择「自己看，我不动设备」⇒ **实机截图验证未做**，四张卡片与四个二级页的**实际渲染**尚未被人眼确认过（仅编译 + 装机 + 图标像素对拍）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → Lint found 64 warnings, 4 hints (3 errors, 14 warnings filtered by baseline)
  → BUILD SUCCESSFUL in 2m 31s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **「响应曲线」二级页不再按 `pedalMode` 收起** [V]（用户定案）——旧配置页把整块包在 `pedalMode != OFF` 的 `AnimatedVisibility` 里（"没有踏板就没有曲线可调"），拆成独立页后该条件会让**点进去只有标题栏、一片空白**。曲线在踏板关闭时确实不生效，但保留可见性优于空页面。
- **`ConfigViewModel` 提到 Activity 层共享** [V]——见 §4 死路；这是本次唯一的**非表面改动**，修的是拆页**引入**的真 bug（不是预先存在）。
- **卡片高度用 `heightIn(min = 72.dp)` 而非空描述行** [V]——`BasicComponent` 自身下限只有 56dp，Hub 卡片只有标题一行会比普通设置项矮一截；撑高度不能靠塞一个空 `Text`（那会留一行无意义高度）。
- **「游戏原生特性控制」→「原生特性控制」** [V]（用户要求）；`SmallTitle` 区块标题全部移除（卡片本身即标题）。
- **hook/功能行为零改动** [V]——二级页内容是从旧区块**逐行搬运**，未改任何一项的语义。

## 4. 失败的尝试 — 不要再试
- [X] **四个二级页各自 `viewModel<ConfigViewModel>()`** [V]——`ViewModelStoreNavEntryDecorator`（lifecycle-viewmodel-navigation3）给**每个 NavEntry** 独立 `ViewModelStoreOwner`（AAR 字节码实证 `rememberViewModelStoreOwner(entry.contentKey, ...)`），Hub + 四页会拿到**五份独立实例**：① 后写者用陈旧快照整份覆盖前写者（跨页丢改动）；② `scheduleSave()` 的 `delay(300)` 在 VM clear 时被 `viewModelScope` 取消 ⇒ **拖完滑条立刻返回即丢改动**。⇒ 必须 Activity 层创建 + `LocalConfigViewModel` provide。KernelSU 的 `viewModel<SettingsViewModel>()` 在多个二级页里看似共享，但那是它自己的场景，不能照抄。
- [X] **hook `OnLapChange` 或 `carModifier.CheckTrackLimitRespected`**（继承）[V]——前者不在出界路径上（零 `bl` 调用者，且读 `alreadyInvalidated` 的时机在圈**起点**）；后者只**读**状态判断、不改任何状态。
- [X] 继承死路（详 `.handoffs/20261006132058-handoff.md` §4 及更早）：压缩 `Fallback` 叩击间隔 / 去掉官版强度档 / 换更短 haptic 常量 / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道②`α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / `viewBox` 非零原点照搬 / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` / 用"探针窗口到达间隔"证明掉帧 / 用构造快照 `position` 做触摸基准 / 在 tick 里做停滞检测 / `kerbTapWave` 垫底 / 用 `sleep` 等用户玩一局。

## 5. 已知坑
- ⚠️ **NavEntry 粒度的 ViewModelStoreOwner** [V]——见 §4 第一条。**任何"多个 NavEntry 共享同一 ViewModel"的写法都必须提到 Activity 层**；页面级 `viewModel()` 的"同 owner 即同实例"只在 owner 相同才成立。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（`HOOK_REG_MAX`）——当前约 40 余项，接近但仍有余量。
- ⚠️ **"作废圈速"不止 `InvalidateLap` 一条路** [V]——`carModifier.OnTriggerEnter`(0x176B86C) 在两处**直接** `strb wzr, [x8, #0x2dc]` 清 `odometerHandler.validLap`（不碰 `alreadyInvalidated`）。将来做类似功能要留意。
- ⚠️ **`LAPinv` 无法区分"只删本圈"与"删本圈+下一圈"** [V]——`validLap` 是持续状态位，两种情况都是 0。
- ⚠️ **官版振感是平台级限制，与机型无关** [V]——`VIBRATE` 是 install-time 权限；`View.performHapticFeedback` 只接受 int 语义常量。**任何机型、任何调参都改变不了。**（继承，未在本会话重验）
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——读 `/sys/class/leds/aw_vibrator/reg` 这类**动作型 sysfs 节点**会触发驱动路径，曾致本机 kernel panic 重启。
- ⚠️ **装机后必须让用户重启游戏** [V]（继承）——模块代码在游戏进程加载，`adb install` 不会热更。（本次改动全在模块 App 进程，游戏进程不受影响，但仍需注意。）
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]（继承）——`optString(KEY, default)` 只在键不存在时生效。
- ⚠️ **继承未验收项** [?]：金标播报实机触发 / 官版整体 / 自动 DRS 的 `playercar`(0x9C) 回落分支 / 自锁超车"抬起被吞"直接证据 / 手柄路径 / 崩溃自捕新增能力 / 路肩振感在其他品牌的手感 / `TcAbsIndicatorView` 是否有同类"状态量冻结"语义 / 跨品牌适配实机验证。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明（含重启游戏）** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明**。

## 6. 下一步（有序）
1. **（用户发话才做）** 等用户实机反馈配置页改版（四张卡片 + 四个二级页的观感、返回键、滑条拖动后返回是否保住改动）。
2. 跨品牌适配实机验证（至少一台非魅族机）——官版 `Fallback` 与共存版 `Envelope` 的手感差异。
3. （可选）正式发版时**先问用户版本号**，再做 module.prop 对齐 + tag（继承）。

## 7. 留给用户的开放问题
- 四张 Hub 卡片的高度（72dp）与四张卡片之间的间距（12dp）是否合适？
- 二级页返回后 Hub 页的滚动位置是否需要保留？（当前 push/pop 会重建，未做滚动位置记忆）
- 官版振感是否接受现状（"快速的哒哒哒但能分清强弱"）？还是要试"只在真事件时振"？
- 路肩振感在**其他品牌**上的手感（本机为魅族 primitive 路径，其他机可能走 waveform 兜底）。
