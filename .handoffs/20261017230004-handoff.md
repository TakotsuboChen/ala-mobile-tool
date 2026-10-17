# HANDOFF — 读全文再开始干活

生成时间: 2026-10-16T21:54:14+08:00 · Git HEAD: `9e12f09`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `9e12f09`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9e12f09`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的条目
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/HapticMixer.kt` 类注释（振感十二轮迭代的证伪表）

## 1. 当前目标
**跨品牌「抓地力振感弱」根因调查 —— 已从"逐机型诊断"转向"机制层根因"，但尚未落地任何修复。**
本会话交付了两件**独立于该调查**的确定性成果（重建风暴修复 + 振感诊断能力），版本已升 `1.0.4 Alpha 3`（104130），**未发版**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **四切片已提交并 push**（本次会话）：`be6a9e2`（版本号 Alpha 3）→ `cd71e49`（重建风暴修复）→ `17403ad`（振感诊断）→ `9e12f09`（持久文档）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`./gradlew :app:lint` → **BUILD SUCCESSFUL**（exit 0），0 lint error（64 warnings 为既有基线）。
- [V] **重建风暴修复已实机验证**：`ConfigReceiver registered` **5 → 1**；`Package ready: com.google.*` 变 `not a game package, skipping`（3 条）；`ShadowHook initialized` 5 → 2；`initUnlock` 4 → 1；功能无回归（`Early unlock hooks installed` / `Native hooks installed` 正常）。
- [V] **振感诊断已实机验证**：`device haptics` 行本次会话只打 **1 次**（修复前每次重建刷一遍）。
- [V] **版本号**：`app/build.gradle.kts` + `module.prop` 双处 = `1.0.4 Alpha 3` / `104130`（用户指定）。**未打 tag、未发 Release**。
- **工作区**: 仅 `.handoffs/` 新增归档（本提交带走）。
- ⚠️ **未做**：§6 的修复项全部未动代码。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 2m     EXIT=0     Lint found 64 warnings, 4 hints（0 error）
$ adb shell grep -c 'ConfigReceiver registered' <game log>   # 新会话 pid=2939
  → 1   （修复前同一操作 5）
```

## 3. 决策与理由
- **包名白名单 + receiver 注册幂等** [V]——根因是游戏进程内 gms/webview 也触发 `onPackageReady`（各多注册一个 receiver）+ 登录门控重入多注册一个。两条回调链独立必须各拦一次。**否决"只拦 onPackageReady"**：onPackageLoaded 那条链同样跑 deferred block。**否决"复用 NATIVE_INSTALLED_FLAG 做幂等"**：它只在 15s 块立，会让游戏包自己的 deferred 重入既注册不了 receiver 也读不到 config。
- **`logDeviceHaptics` 进程内只打一次** [V]——`create()` 每次 overlay 重建都调；不守卫会把十行快照刷十几遍，把有用日志挤出 2MB 滚动窗口。
- **HapticMixer 改动定性为"诊断"而非"修复"** [V]——`USAGE_MEDIA` 上报收益有限（`scale()` 逐段乘法且上限 255，已满幅的段放大不了）。价值在让远端用户回传日志能定位根因。
- **跨品牌调查方向：从"逐机型"转向"机制层"** [V]——见 §4/§5，根因是**段数**不是参数。

## 4. 失败的尝试 — 不要再试
- [X] **归因"系统触感滑条把我们的振动压低了"** [V]——`dumpsys vibrator_manager` 实测 `UNKNOWN = MEDIUM, default: MEDIUM` ⇒ `scaleLevel=0` 不缩放；用户调 TOUCH 桶对本模块无影响 ⇒ 我们落在 UNKNOWN 桶，**系统里根本没有作用到我们的滑条**。
- [X] **归因"用户装的是官版 ⇒ Fallback 滑条空转"** [V]——终端用户必是共存版（NPatch 非 root 主流路径），官版结论不能套到用户身上。
- [X] **"改成 primitive 合成路径就能修"** [V]——本机（MEIZU 20 / Flyme）`supportedPrimitives` 全被 HAL 压成 4ms、`pwleSizeMax=0`、`frequencyProfile` 全 NaN ⇒ 该机是验证 primitive 的最差环境。
- [X] **"把抓地力改成速率编码（脉冲密度）"** [V]——**我一度推荐此方案，后被推翻**：它要牺牲用户迭代 12 轮的"连续"手感，而真正的病根是**段数**不是"连续"本身。见 §5。
- [X] **"主流游戏不做持续振动"** [V]——**我说错了**。按摩器 App / 振动测试站都能持续嗡嗡，机制 = `createOneShot(长时长)` = **单段**恒定幅度。差别在段数不在"连续"。
- [X] **"把 Fallback 叩击间隔压到 13~17ms"** [V]（继承）——用户报「**更快的**哒哒哒」；官版绵密是平台级限制。
- [X] **"全程只用 CLOCK_TICK"** [V]（继承）——用户报「还是快速的哒哒哒，且**判断不了强弱**」。
- [X] 继承死路（详 `.handoffs/20261006155046-handoff.md` §4 及更早）：四个二级页各自 `viewModel<ConfigViewModel>()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。

## 5. 已知坑
- ⚠️ **抓地力波形 96 段是跨机型弱振的首要嫌疑（未验证）** [?]——抓地力 = `createWaveform(96×5ms, [c×96], repeat=0)`；**所有段幅度其实完全相同**（`buildFlat`），96 段是**多余的**。框架要把 96 段逐个转成 HAL 跳变指令（AOSP `waveform.addTransition` 每段一次），而 **Android 时序精度 ~50ms**（iOS ~5ms）⇒ 5ms 段对多数 HAL 是噪声。官方文档警告："Playing a waveform on a low-end device without this capability causes the device to vibrate at **the maximum amplitude** for every positive entry" —— **一字不差地匹配 VER（HONOR Win）「强度调节无效、一直最大」**。按摩器 App 做同一件事只用 **1 段**。**建议改法：96 段 → 1 段**（手感不变，因为本来就是恒定幅度）。**未验证、未动手。**
- ⚠️ **抓地力下发幅度常年是个位数** [?]——从 `shape ... slip=` 日志挖出（= `carrier()` 返回值 0–255）：小米 15 Pro **p50=4 / p90=23**；魅族 level p50=0.16。默认强度 50% ⇒ `carrier = 0.5 × level × 255`，level 0.16 ⇒ 幅度 ≈20。**就算 HAL 老实逐段驱动也接近不可感。**
- ⚠️ **"踏板模式=OFF 导致振感信号源整个不跑"——确定性 bug（未修）** [?]——`native/src/pedal_hook.c:1041` `if (!g_config.enable_control_replacement) return true;` 直接返回，**一个 hook 都不装**；而 `slip_feedback_tick` / `kerb_haptic_tick` 挂在 `proxy_car_controller` 里（`:989-997`）⇒ **关掉踏板覆盖 = 连振感传感器一起关掉**。实机证据：林/HONOR Magic 7 `pedalMode=OFF → controls=0 → "Pedal control replacement disabled" → SLIPprobe 0 行`。与机型无关，任何 OFF 用户都拿不到振感。
- ⚠️ **路肩（primitive/离散脉冲）全机型正常，抓地力（连续波形）全机型异常** [V]——5 位用户反馈与 `primClick` 支持度并排看：路肩"正常/脆"（primClick true/false 都有），抓地力"弱/无效/完全没有"。**这是机制差异不是巧合**——路肩依赖厂商校准过的 primitive + "amplitude=0 停马达"（文档保证），抓地力依赖 HAL 可选的逐段幅度。
- ⚠️ **新 API 线索（未采用）** [?]——Android 16(API 36) `VibrationEffect.BasicEnvelopeBuilder` 官方定位 = "consistent haptic perception **across devices**"、"hardware-agnostic"。报障设备全部 SDK ≥36。用前须 `vibrator.areEnvelopeEffectsSupported()`。**未评估、未动手。**
- ⚠️ **官版振感是平台级限制** [V]（继承）——`VIBRATE` install-time 权限、`performHapticFeedback` 只接受 int 常量，任何机型/调参都改变不了。
- ⚠️ **共存版 versionCode 绝不可与官版脱钩** [V]（继承）——榜单按 version_code 分赛季。
- ⚠️ **新增门控 return 必须排在 `initGameLogger()` 之后** [V]（继承）——否则"拦截时零文件日志"。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（继承）——当前约 40 余项。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——曾致本机 kernel panic。
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]（继承）。
- ⚠️ **用户日志在 Windows 盘** [V]——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`（WSL2 挂载）。日志分三段：`=== 模块进程日志 (Java) ===` / `=== 游戏进程日志 (Java) ===` / `=== 游戏进程日志 (Native) ===`，**按段 grep 而非全文**。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明**。

## 6. 下一步（有序）
1. **修"踏板 OFF 导致振感信号源全灭"**——确定性 bug、与机型无关、改动小。`slip_feedback_tick`/`kerb_haptic_tick` 的采样点需与 `enable_control_replacement` 解耦（`proxy_car_controller` 的安装被它门控着）。
2. **抓地力波形 96 段 → 1 段**——手感不变（本来就是恒定幅度），跨过所有 HAL 量化阈值。**先在本机确认无回归**（魅族 `rampStepDurationMs=5`，96 段与 1 段对它无差别，能直接验"手感不变"）。
3. **抬高抓地力幅度映射下限**——现在 p50=4/255 太低，让默认强度就有可感输出。
4. 上述改完随下个版本发出，**请 VER（HONOR Win，「强度调节无效、一直最大」）复测** —— 他是验证"96 段被 HAL 按 on/off 处理"假说的最直接人选。
5. （可选）评估 API 36 `BasicEnvelopeBuilder` 作为新旗舰的路径。
6. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- 抓地力手感取向：保住"连续绵密"（改段数）vs 换"高频颗粒"（速率编码）——**建议前者**，后者牺牲 12 轮迭代成果。
- 要不要把六位报障用户的机型/系统做成对照表存档？（本次已临时整理，未落盘）
- 本会话研究结论（Android 触觉碎片化 + 段数假说）要不要写进 `docs/`？
