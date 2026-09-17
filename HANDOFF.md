# HANDOFF — 读全文再开始干活

生成时间: 2026-09-18T00:09:00+08:00 · Git HEAD: `05e8695`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `05e8695`（2026-09-18 00:09）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `05e8695`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md「Auto DRS / 主动空力（AA）」条目（本会话新增，设计红线全在里面）+ `native/src/drs_hook.c`

## 1. 当前目标
**已完成**：自动 DRS / 主动空力（AA）功能落地（重写为捕捉游戏 `OnDRSStateChanged` 的 Deployable 信号）+ 配置读侧解禁 + 设置页开关 + 运行时热更新；顺带修掉旧实现"模块存在即吞掉玩家所有 DRS 按键"的行为缺陷。**地效车与空力车两条路径均已实机验证通过**（用户确认）。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作已提交推送**：`95a629c`（feat: native 信号 hook 重写）、`87da608`（feat: 配置读侧解禁 + UI + 运行时同步）、`05e8695`（docs: CLAUDE.md/README 同步）。三次 push 均成功。
- [V] **门槛**：`:app:lint` exit=0；`:app:assembleRelease -x lintVital*` exit=0。
- [V] **已装机**：`adb install -r app-release.apk` → `Success`，设备 MEIZU 20（serial `381QYFCN22B9A`，无线 `192.168.50.142:5555`）。版本号**未改动**（1.0.4 Alpha 1 / 104100）。
- [V] **用户实机验收通过**：地效车与 2026 空力车都确认可用。
- [V] **hook 安装日志实锤**：新会话 pid 7058 `Hooked OnDRSStateChanged at 0x7737369fa4 (auto_drs=1)` + `ManualDRSUsage resolved at 0x7737371d94`（base 0x7735c0a000）——地址全部映射正确。
- [V] **per-zone 闩锁修复经 A/B 实证**：旧实现（无闩锁）154 条/2.5min、峰值 43 条/秒；修复后 5 条/6min、全为稀疏单发（相邻两条最小间隔 ~19s，符合单圈节奏）。
- [V] 无原生崩溃文件（`ala_tool_crash_native.log` 不存在 = 新会话零 native 崩溃）。
- [V] 工作区 clean（除本 HANDOFF.md 与归档文件）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint        → EXIT=0, BUILD SUCCESSFUL
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                             → EXIT=0, BUILD SUCCESSFUL
$ adb install -r app/build/outputs/apk/release/app-release.apk → Success
$ adb shell grep -c 'Auto DRS deployed'（pid 7058）→ 9 条 / 23:48–00:07（~19min）
```

## 3. 决策与理由
- **捕捉信号而非自行判定赛道区域** [V]——hook `carModifier.OnDRSStateChanged`（RVA 0x175FFA4）：状态机进入 `Deployable(3)` 时游戏在此播 DRS 提示音（反汇编 `0x17600C4` `cmp #3` → 播 drsAlert）。**用户一句"能开时有提示音"直接省掉整个赛道数据解析层**。收到信号调 `carModifier.ManualDRSUsage()`（0x1767D94）= 玩家按键真实入口，3→4 的校验/动画/音效/HUD 全归游戏。
- **一个 hook 点覆盖两种车、两套规则** [V]——地效车 DRS 走物理触发区（`OnTriggerEnter` + `isDRSGloballyEnabled`(0x101) + 圈数门槛 `lapForDRS`）；2026 主动空力走 waypoint 数组协程（`CheckActiveAeroAvailability` 查 `activeAerozone[]` + `isSafeForBoostAndActiveAero`(0x102) + `carType==DoubleDRSEra` 硬门）。区域判定方式截然不同（= "同赛道两套区域不同"的代码本质），但都汇聚到同一状态机事件。
- **per-zone 闩锁是必需（非优化）** [V]——2026 车状态机逐帧 3↔4 循环（`HandleDRS` 把 Active 打回 Idle → `ManageActiveAero` 见仍在区域内又置 3）→ 回调每帧触发。闩锁用游戏自己的 `drsAlertPlayedThisZone`(0x47C)，**必须在 orig 之前读**（orig 会把它置 1，之后再读恒为 1）。
- **闩锁只对 `DoubleDRSEra` 生效** [V]——0x47C 的唯一清零点在 `ManageActiveAero`（2026 专属方法）；地效车用了会因为整场不复位而"每场只能自动开一次"。判据由**清零点归属**推出（这是本次最有价值的方法论，已入记忆）。
- **玩家车白名单必需** [V]——`carModifier` 每车一份、回调在所有车上触发（AI 车同样跑 DRS 状态机）。首选 `icinp`(0xD8) == `pedal_get_controller()`（同源 TC/ABS 白名单）；回落 `playercar`(0x9C)。
- **hook 恒装上，不因开关为假而跳过** [V]——信号源是旁路观察不是拦截点，开关在回调内判（`g_auto_active`）；且旧实现"开关假就不装 hook 并把 drsToggle 全吞掉"= 模块存在即禁用玩家 DRS，是必须修的行为缺陷。
- **`ManualDRSUsage` 地址 install 时解析缓存** [V]——回调属每车必经路径，内部不能调 `dl_iterate_phdr`（持锁 + 高频）。
- **配置读侧解禁** [V]——`ModConfig.read()`/`fromJson()` 曾硬编码 `enableAutoDrs = false`（写侧一直写、读侧永远读回 false）。改为读真实配置。

## 4. 失败的尝试 — 不要再试
- **[X] hook `IRDSCarControllInput.drsToggle`（0x1A673E0）做自动 DRS** [V]——那是**玩家按键入口**（`IRDSPlayerControls.CarControllerAnalogueInput` / `odometerHandler.OpenDrsTouch` 经它进 `ManualDRSUsage`）。自动模式下无人按键，hook 永不触发；旧实现装上了却只会把所有玩家 DRS 请求吞掉。**自动部署必须走信号侧**。
- **[X] 模块自行解析赛道 DRS/AA 区域数据（`activeAerozone[]` / telemetry 轮询）** [V]——两套规则判定管线不同（见 §3），重复实现冗余且必然出错；游戏已有信号。旧 TODO(human) 条目已删除。
- **[X] 直接写 `carModifier._currentDRSState`（或调 setter 绕过回调）** [V]——绕过 `OnDRSStateChanged` 会丢部署动画、音效、HUD 通知与 `previousDRSState` 维护，状态机不一致。
- **[X] hook `carModifier.FixedUpdate` 或 `ManageActiveAero` 做部署时机** [?]——`FixedUpdate` 每帧高频且全车共享（passthrough 红线）；`ManageActiveAero` 只服务 2026 车，覆盖不到地效车。（未实测，静态推断否决）
- **[X] 用 `drsAlertPlayedThisZone`(0x47C) 做闩锁但不对 carType 分流** [V]——地效车 0x47C 整场不复位（清零点在 2026 专属方法），会导致每场只能自动开一次。
- **[X] 在 `proxy_on_drs_state_changed` 里 `dl_iterate_phdr` 取 base** [V]——回调每车必经，持锁调用纯浪费；改为 install 时缓存。
- 继承死路 [X]（详 `.handoffs/20260918000813-handoff.md` §4 及更早归档）：编辑层尺寸=控件尺寸+clipChildren / 变暗层插 index 0 / `withEndAction` 做淡出收尾 / 提示文字画在编辑框 onDraw / `setShadowLayer` 加阴影 / `result::class.simpleName` 记日志 / 锁屏态 monkey 拉起 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / `***text***` 粗斜体 / MarkdownText 表格 / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` 到主题 textStyles / `FontFamily.Monospace` 做等宽 / miuix `Text` 转发 `fontFeatureSettings` / on-device Typeface 探针 / `tail` 截断 gradle 输出验门槛。

## 5. 已知坑
- ⚠️ **扫描 libil2cpp.so 的内存红线** [V]（本会话踩，已入记忆 `il2cpp-scan-memory-redline`）——**严禁整段 `md.disasm()`**：本会话一个 subagent 对 31.4MB 代码段跑 `detail=True` 全量，Python 申请 14GB+ 触发内核 OOM，**连带 WSL2 宿主 Vsock 超时整机重启**。一律 `disasm_lite()` 流式 + 外层 `ulimit -v 4194304`。
- ⚠️ **libil2cpp.so 的方法代码在 `il2cpp` 节，不在 `.text`** [V]——`il2cpp` 节 0x1e0d844（~31MB，addr 0x16d744c / off 0x16d344c）；只扫 `.text`（0x2c3160）会得到"函数名乱配"的假结果。动手前 `readelf -SW` 按目标 RVA 反查所属节。
- ⚠️ **无线 adb 端口已固定 5555** [V]——用户给魅族 20 装了 ADB Live 模块锁死端口；直接 `adb connect <IP>:5555`，**不要再从 mdns 取端口**（mdns 报的 37799/40457 全被拒）。IP 仍会漂（当前 192.168.50.142）。已更新记忆 `wireless-adb-mdns-rediscover`。
- ⚠️ **`playercar`(0x9C) 白名单回落分支未实机验证** [?]——仅静态分析；实机走的是 `icinp == pedal_get_controller()` 主判据（用户开着踏板替换）。**若用户关掉踏板替换，自动 DRS 需重新验证**。
- ⚠️ **未在官版（com.Vince）验证** [?]——本会话全部日志来自共存版 `com.Takotsubo.AlamobileFormula`。官版 media 目录只有 config/auth 两个 json，无日志（游戏本会话没跑官版）。
- ⚠️ **地效车与空力车的确认粒度** [?]——用户口头确认"都可以"，但会话中我没能从日志区分出哪个 pid/时段是哪种车（无 carType 日志）。若后续要回归，建议在部署日志里带上 `carType`。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——不预设传输方式，每次先 `adb devices -l`。
- ⚠️ **LogExporter 的 3 行诊断日志去留未定** [?]（继承）；**NPatch 段未端到端验证** [?]（继承）；**`Logger.gameMediaDir`/`LogExporter.gameMediaDir` 两份实现** [?]（继承）；**旧 `Android/data/<pkg>/files/` 残留** [?]（继承）。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?]（继承）——无可滚动正文 `weight` 时平板可能重演窄条按钮。
- ⚠️ **编辑模式仅共存版 + SINGLE 模式验证** [?]（继承）——DUAL 双编辑层、换挡控件编辑层、官版包名未回归。
- ⚠️ **榜单 tnum 只在 ColorOS 16 平板验过** [?]（继承）。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明**。

## 6. 下一步（有序）
1. （可选）自动 DRS 在"DLC 已购 vs 未购"两种车辆状态下的回归——本次原生部署的车辆来源未记录。
2. （可选）在部署日志里补 `carType`（`0x24`）/ 圈序号，便于后续按车型统计与回归。
3. （可选）官版（com.Vince）回归：编辑模式 + 日志导出 + 自动 DRS 三处对称验证（继承 + 本会话新增）。
4. （可选）关掉踏板替换后回归 `playercar` 白名单回落分支。
5. （可选）DUAL 模式回归编辑模式；换挡控件编辑层回归（继承）。
6. （可选）决定 LogExporter 3 行诊断日志留几行；其它 ROM 回归榜单 tnum；加固 `EulaDialog`/`UpdateDialog` 正文 `weight`（继承）。

## 7. 留给用户的开放问题
- 自动 DRS 的部署时机体感如何？（当前 = 游戏提示音响起的同一帧，是否过早/过晚）
- 要不要给自动 DRS 加"仅正赛/计时赛生效"的会话限制？（当前无模式门控，任何 DRS 可用场合都会代按）
- 3×slop 长按取消阈值、3 行诊断日志、旧 `Android/data` 残留日志清理（均继承）
- 那两条锁屏态 SIGSEGV（`located=0`，疑与模块无关）是否要单独追？（继承）