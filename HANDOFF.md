# HANDOFF — 读全文再开始干活

生成时间: 2026-09-18T02:35:00+08:00 · Git HEAD: `09b0fae`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `09b0fae`（2026-09-18 02:35）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `09b0fae`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md「自锁型超车按键」条目（本会话新增，hook 点红线全在里面）+ `native/src/overtake_hook.c`；逆向选点时用 `.tools/` 四脚本（CLAUDE.md IL2CPP RE 段有用法）

## 1. 当前目标
**已完成**：新增「自锁型超车按键」功能——把 OTK（超车）**屏幕按钮**从「按住才生效」改成「点一下切换」，只改按键抬落语义，**是否允许开超车（ERS 解锁 / 电量）完全仍由游戏判定**（模块一行都没改游戏守卫，点了没反应与原生一致、不补提示）。含配置项 + 设置页开关 + Boost 图标 + native hook + 运行时热更新。**用户实机验收："全部完美"。现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作已提交推送**：`0b3976c`（配置+UI+图标）、`c5ce30c`（native 实现）、`0002c7d`（JNI 接线）、`09b0fae`（持久文档）。四次 push 均成功。
- [V] **门槛**：`:app:lint` → `EXIT=0` + `BUILD SUCCESSFUL`（58 warnings / 4 hints / baseline 过滤后 0 error）；`:app:assembleRelease -x lintVital*` → `EXIT=0`。
- [V] **已装机**：APK 与设备版本核对一致（均 `104100 / 1.0.4 Alpha 1`）后 `adb install -r` → `Success`，设备 MEIZU 20（无线 `192.168.50.142:5555`）。**版本号未改动。**
- [V] **用户实机验收通过**：点按切换行为正确，图标显示完整。
- [V] **hook 安装日志实锤**：`Hooked TouchPressOTK at 0x7736a13e24 / TouchReleaseOTK at 0x7736a13e40 (DisableOTK at 0x7736a2f494, latch_overtake=1)`（base 0x7735c0a000）——三个地址与 OffsetTable 全部对上。
- [V] **行为日志**：Monza 计时赛 8 分钟（01:53:41–02:01:23）45 次点按，开 23 / 关 22 **严格交替**，最长锁定 17.5s；期间零 native 崩溃（`ala_tool_crash_native.log` 不存在）。唯一一次 `locked→locked` 是跨场景切换（回主菜单→进新赛道，旧实例销毁）。
- [V] **图标渲染**：`rsvg-convert` 对拍「原始 viewBox」与「平移后」SVG，`compare -metric AE` = **0**（像素完全一致）。
- [V] 工作区 clean。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint        → EXIT=0, BUILD SUCCESSFUL
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                             → EXIT=0, BUILD SUCCESSFUL
$ adb install -r app/build/outputs/apk/release/app-release.apk → Success
$ grep -c 'tap -> locked' / grep -c 'tap -> released'（pid 25156）→ 23 / 22
```

## 3. 决策与理由
- **hook 点 = OTK 按钮专属方法，不是 `HybridComponent.EnableOTK/DisableOTK`** [V]——这是本会话最关键的一步。两个按钮入口（`odometerHandler.TouchPressOTK` 0x1A0BE24 / `TouchReleaseOTK` 0x1A0BE40）是**按钮专属**（全 `.so` 零 `bl` 指向，引用者只有按钮 prefab：`datapack.unity3d` @122230882 `IRDS.UI.odometerHandler, Assembly-CSharp` + 两方法名 + 同文件 GUID）。而 `EnableOTK/DisableOTK` 虽也覆盖触摸路径（`TouchPressOTK` 尾部就是 `b EnableOTK`），但 `switchHModeUp`(0x1A27484) 是游戏自己的 OTK toggle、被 `onHybridMapChange`/`Update`（**ERS 混动模式键**）调用——挂那里会连带吞掉 ERS 键的"关超车"。**方法论：选 hook 点前先跑 `.tools/find_callers.py` 看调用方集合**。
- **不写任何游戏状态字段** [V]——按下转调游戏自己的 `DisableOTK`（0x1A27494），保手臂动画/HUD/`UpdateHybridRNCs` 联动；开启原样转发 `TouchPressOTK`。绕过游戏入口会丢这些。
- **以 `OvertakeActive`(0xC4) 复核"是否真的开起来了"** [V]——游戏可能因 ERS 未解锁 / 电量不足拒绝开启，此时不进锁定态（避免"点了没反应却锁住"）。这正是"可用性判定仍归游戏"的落地方式。
- **抬起路径额外核 `currentCapacity`(0x74)** [V]——电量耗尽时游戏自己的管理管线会调 `DisableOvertakeModeSwitch(false)` 收尾，此时若仍吞抬起，玩家下次点按会变成"关闭"（体感反的）。
- **不做 this 上的白名单** [V]——`HybridComponent` 没有可靠的"我是玩家车"字段：`isPlayer`(0xD0) 的 7 个读取点全在车队/遥测/HUD 类，推不出语义；0xA0 是 `SteeringWheelUI`（该判据在 `carModifier` 上成立但此处不成立）。可信度落在调用图证据上（AI 超车走 `AIHybridManager.ManageOvertake` → `EnableDisableOvertakeModeSwitch`，不经此路）。**升版必核这个调用图。**
- **hook 恒装上，开关在回调内判** [V]——与自动 DRS 同策略，避免"关功能那一刻玩家正按着 OTK"的边缘态。
- **图标：`viewBox` 四元组的原点必须处理** [V]——`minX minY width height`，Compose `ImageVector` 不支持非零 viewport 原点，需把 path 起点绝对坐标减去 `(minX, minY)`（相对命令不受影响）。`svgIconMulti` 同时新增 `defaultWidth/defaultHeight`（扁长图形沿用 24×24 会纵向拉伸 1.86 倍）。

## 4. 失败的尝试 — 不要再试
- **[X] hook `HybridComponent.EnableOTK`/`DisableOTK`（0x1A274F4 / 0x1A27494）** [V]——虽覆盖触摸路径，但不是按钮专属：`switchHModeUp`(0x1A27484) 被 `odometerHandler.onHybridMapChange`/`Update`（ERS 模式键）调用，会连带吞掉 ERS 键的"关超车"。
- **[X] 给 `HybridComponent` 写身份白名单** [V]——`isPlayer`(0xD0) 语义未经证实（7 reads 全在车队/遥测/HUD 类）；组件内**没有** `IRDSCarControllInput` 字段（0x60=Car 枚举、0x98=IRDSDrivetrain、0xA0=SteeringWheelUI），故 `carModifier` 那条 `icinp` 判据不可移植。写进去只会把猜测固化。
- **[X] 图标照搬 `viewBox` 坐标配 `0..90 × 0..48.49` 的 viewport** [V]——非零原点被丢，图形只显示 `y<48.49` 部分（≈49%，实机"只显示一半"）。
- **[X] hook `IRDSCarControllInput.drsToggle`（0x1A673E0）做自动 DRS** [V]——那是**玩家按键入口**。自动模式下无人按键，hook 永不触发；旧实现装上了却只把所有玩家 DRS 请求吞掉。**自动部署必须走信号侧**。
- **[X] 模块自行解析赛道 DRS/AA 区域数据（`activeAerozone[]` / telemetry 轮询）** [V]——两套规则判定管线不同，重复实现冗余且必然出错；游戏已有信号。
- **[X] 直接写 `carModifier._currentDRSState`（或调 setter 绕过回调）** [V]——会丢部署动画、音效、HUD 与 `previousDRSState` 维护。
- **[X] hook `carModifier.FixedUpdate` 或 `ManageActiveAero` 做 DRS 部署时机** [?]——`FixedUpdate` 每帧高频且全车共享（passthrough 红线）；`ManageActiveAero` 只服务 2026 车。（未实测，静态推断否决）
- **[X] 用 `drsAlertPlayedThisZone`(0x47C) 做闩锁但不对 carType 分流** [V]——地效车该标志整场不复位，会"每场只能自动开一次"。
- **[X] 在 `proxy_on_drs_state_changed` 里 `dl_iterate_phdr` 取 base** [V]——回调每车必经，持锁调用纯浪费；改为 install 时缓存。
- **[X] 整段 `md.disasm(detail=True)` 扫 libil2cpp** [V]——31MB 代码段全量致 Python 申请 14GB+ → 内核 OOM → **WSL2 宿主 Vsock 超时整机重启**。一律 `disasm_lite` 流式 + `ulimit -v 4194304`。
- 继承死路 [X]（详 `.handoffs/` 归档 §4）：编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / 提示文字画在编辑框 onDraw / `setShadowLayer` 阴影 / `result::class.simpleName` 记日志 / 锁屏态 monkey 拉起 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / `***text***` 粗斜体 / MarkdownText 表格 / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽 / miuix `Text` 转发 `fontFeatureSettings` / `tail` 截断 gradle 输出验门槛。

## 5. 已知坑
- ⚠️ **方法代码在 `il2cpp` 节（~31MB），不在 `.text`（0x2c3160）** [V]——只扫 `.text` 会得到"函数名乱配"的假结果；动手前 `readelf -SW` 按目标 RVA 反查所属节。`.tools/` 脚本已内置两节表。
- ⚠️ **无线 adb 端口固定 5555** [V]——用户给魅族 20 装了 ADB Live 锁死端口；直接 `adb connect <IP>:5555`，**不要再从 mdns 取端口**。IP 仍会漂（当前 192.168.50.142）。
- ⚠️ **`playercar`(0x9C) 自动 DRS 白名单回落分支未实机验证** [?]——实机走的是 `icinp == pedal_get_controller()` 主判据。**若用户关掉踏板替换，自动 DRS 需重新验证**。
- ⚠️ **官版（com.Vince）未验证** [?]——本会话全部日志来自共存版 `com.Takotsubo.AlamobileFormula`。官版 media 目录只有 config/auth 两个 json。
- ⚠️ **自锁型超车按键的"抬起被吞"缺直接日志证据** [?]——抬起路径两个分支都没打日志，当前结论由"开/关严格交替"逻辑排除法得出（用户已实机确认体感正确）。若要闭合证据链，在抬起分支各补一行 LOGI。
- ⚠️ **地效车与空力车的确认粒度** [?]——自动 DRS 的日志无 `carType`，无法区分哪个 pid/时段是哪种车。
- ⚠️ **手势路径未 hook** [?]——自锁只作用于屏幕按钮（用户诉求）；手柄 `IRDSPlayerControls.NitroMobile`(0x1A74D30) 语义未实证，未改。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——不预设传输方式，每次先 `adb devices -l`。
- ⚠️ **LogExporter 的 3 行诊断日志去留未定** [?]（继承）；**NPatch 段未端到端验证** [?]（继承）；**`Logger.gameMediaDir`/`LogExporter.gameMediaDir` 两份实现** [?]（继承）；**旧 `Android/data/<pkg>/files/` 残留** [?]（继承）。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?]（继承）——无可滚动正文 `weight` 时平板可能重演窄条按钮。
- ⚠️ **编辑模式仅共存版 + SINGLE 模式验证** [?]（继承）——DUAL 双编辑层、换挡控件编辑层、官版包名未回归。
- ⚠️ **榜单 tnum 只在 ColorOS 16 平板验过** [?]（继承）。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号**（用户已多次强调）。

## 6. 下一步（有序）
1. （可选）关掉踏板替换后回归自动 DRS 的 `playercar` 白名单回落分支（§5 第 3 条）。
2. （可选）官版（com.Vince）回归：编辑模式 + 日志导出 + 自动 DRS + 自锁超车四处对称验证。
3. （可选）DUAL 模式回归编辑模式；换挡控件编辑层回归（继承）。
4. （可选）决定 LogExporter 3 行诊断日志留几行；其它 ROM 回归榜单 tnum；加固 `EulaDialog`/`UpdateDialog` 正文 `weight`（继承）。
5. （可选）若要闭合自锁超车的证据链，抬起分支补日志（§5 第 4 条）。

## 7. 留给用户的开放问题
- 自动 DRS 的部署时机体感如何？（当前 = 游戏提示音响起的同一帧，是否过早/过晚）
- 要不要给自动 DRS 加"仅正赛/计时赛生效"的会话限制？（当前无模式门控，任何 DRS 可用场合都会代按）
- 自锁型超车按键要不要也覆盖手柄 nitroButton 路径？（当前只改屏幕按钮）
- 3×slop 长按取消阈值、3 行诊断日志、旧 `Android/data` 残留日志清理（均继承）
- 那两条锁屏态 SIGSEGV（`located=0`，疑与模块无关）是否要单独追？（继承）
