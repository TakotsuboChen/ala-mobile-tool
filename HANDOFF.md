# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T00:22:41+08:00 · Git HEAD: `cdf6e87`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `cdf6e87`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `cdf6e87`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的条目（振感三项，本会话完全未动）
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md` §13（Bug D 的完整证据链与 845 修复验证）

## 1. 当前目标
**NPatch Bug D（诊断导出 Shizuku logcat 通道断裂）调查闭环** —— 从"用户报告导不出 logcat"到"上游修复 + 双设备实测验证"，全部完成。
本轮**未改模块代码**，只更新调查文档。**未发版**。
上一会话遗留的**振感三项修复**仍**完全未动**（§6，优先级待用户定）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **两切片已提交并 push**：`b1e7bb5`（Bug D 调查文档）→ `cdf6e87`（CLAUDE.md）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`./gradlew :app:lint` → `EXIT=0`，`BUILD SUCCESSFUL in 2s`。
- [V] **版本号未动**：`app/build.gradle.kts` + `module.prop` 双处仍 = `1.0.4 Alpha 4` / `104140`。**未打 tag、未发 Release**。
- [V] **845 三项修复全部实测通过**（一加平板 2 Pro / ColorOS 16 / Android 16 / NPatch 845 + 共存版 Fix 2）：
  - **① keep 规则**：诊断包 `Included system logcat via Shizuku: **Yes**`，`logcat.log` 入包（6 文件 / 30159 B）。logcat 实证类名不再被混淆：`starting service top.nkbe.npatch/top.nkbe.npatch.ShizukuService...` → `Received binder` → `send binder to moe.shizuku.privileged.api`（843 对照 = `mm0` → `NoSuchMethodException: mm0.<init> []`）。
  - **② dumpLogcat 窗口**：`logcat.log` 56 行，时间跨度 `00:13:28`→`00:15:56`（10 分钟窗口），含 19 条 Shizuku 记录。
  - **③ 断线保护 + 恢复**：`kill -9 shizuku_server` 后触发操作 → Toast「Shizuku 未连接」、**NPatch 进程存活（PID 6033 未变）、零 `FATAL EXCEPTION`**；手动重启 Shizuku 后再导出 → **重新正常**（`Yes` + `logcat.log`），证明 `runCatching` 未缓存"不可用"状态。
- [V] **模块无回归**：845 + Fix 2 冷启动游戏，`Native hooks installed (isAvailable=true)`、`lap hooks initialized`、`MusicPlayer initialized`、`PaddockUploader started`、配置仲裁 `winner=remote prefs ... pedalMode=DUAL`；包名白名单生效（gms/webview 均 `not a game package, skipping`）。
- [V] **APK 已归档**：`安装包/Ala Mobile Pro 8.0.6 Fix 2 (NPatch 845).apk`（MD5 `44379426b5d13daaea52ffde8e1a4043`，签名 `b3720382…`），源 = 手机 `/sdcard/Download/Ala Mobile Pro 8.0.6 Fix 2 原版-845-npatched_sign.apk`。平板已覆盖安装。
- **工作区**: 干净（本 HANDOFF 提交带走 `.handoffs/` 归档）。
- ⚠️ **未做**：§6 的振感三项修复全部未动代码。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 2s     EXIT=0     （29 tasks，28 up-to-date）
```

## 3. 决策与理由
- **Bug D 定性为独立问题而非 Bug C 变体** [V]——两者症状都是"某功能不工作"，但根因层完全不同（C = loader 服务回落，D = R8 混淆）。混在一起排查会互相误导，故在文档头部表格里单列第 4 列。
- **换 Shizuku 实现被实测证伪为解法** [V]——作者建议换其 fork，实测（官方 13.6.0 vs fork 13.6.1.r1192）结果不变。根因在 NPatch 的 R8 规则，fork 改的是 server 侧，`ServiceStarter` 反射路径与上游一致。**否决"换框架"路线**。
- **验证采用三层闭环而非单点** [V]——进程层（`Received binder`）+ 数据层（`logcat.log` 入包）+ 声明层（`collection-report.txt: Yes`）。只验一层会被"假成功"骗过（进程起来了但 logcat 为空）。
- **断线恢复必须单独验证** [V]——`runCatching` 类修复的常见错误是"失败一次就记不可用"，那样恢复后永远好不了。实测证明 NPatch 无此缓存。
- **`-keep ... { <init>(); }` 而非 `{ *; }`** [V]——作者采纳的精确形式，只保反射唯一需要的成员，混淆收益保留最大。

## 4. 失败的尝试 — 不要再试
- [X] **「换 Shizuku 实现（含作者 HSSkyBoy fork）能修好诊断导出」** [V]——官方 13.6.0 与 fork 13.6.1.r1192 双实测，诊断包均写 `No`；真实根因在 NPatch 的 R8 规则。
- [X] **「Shizuku 没授权所以导不出诊断包」** [V]（继承）——授权后仍失败。
- [X] **「诊断包对定位没价值，不必上传」** [V]（继承）——**我判断错了**。数据的需求方才有资格判断价值。
- [X] **「Shizuku user service 起不来是 Shizuku 13.6.0 + Android 16 的上游 NPE」** [V]（继承）——实际是 `NoSuchMethodException`。**同一现象可有多条独立成因，版本匹配只是弱证据**。
- [X] **「NPatch manifest 缺 `ShizukuService` 声明是根因」** [V]（继承）——user service 由 server 以 root 直接 `app_process` 启动，不依赖 manifest。
- [X] **「从 `/data/app/.../base.apk` 拉取用户要的 APK」** [V]（本会话被用户当场纠正）——**用户装/发给别人的包在 `/sdcard/Download/`**。这是重复犯的错：拉安装包先看 Download 目录。
- [X] **归因「系统触感滑条压低振动」/「用户装的是官版」/「primitive 合成能修振感」/「速率编码」/「主流游戏不做持续振动」/「Fallback 叩击压到 13~17ms」** [V]（继承，详见 `.handoffs/20261018002237-handoff.md` §4 及更早）。
- [X] 继承死路（详 `.handoffs/20261017230004-handoff.md` §4）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。

## 5. 已知坑
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]（继承）——ColorOS 让 `bindService` **同步返回 false** ⇒ `bindInitiated=false` ⇒ `scheduleGiveUp()` 根本没注册。不影响模块可用性，只影响热重载/作用域实时更新。
- ⚠️ **抓地力波形 96 段是跨机型弱振的首要嫌疑（未验证）** [?]（继承）——96 段幅度完全相同（`buildFlat`），纯属多余；HAL 逐段转跳变指令 + Android 时序精度 ~50ms ⇒ 5ms 段对多数 HAL 是噪声。建议 96 段 → 1 段。**未验证、未动手。**
- ⚠️ **抓地力下发幅度常年是个位数** [?]（继承）——小米 15 Pro p50=4 / p90=23（满 255）。默认强度 50% ⇒ 幅度 ≈20，接近不可感。
- ⚠️ **「踏板模式=OFF 导致振感信号源整个不跑」——确定性 bug（未修）** [?]（继承）——`native/src/pedal_hook.c:1041` 直接 return，一个 hook 都不装；`slip_feedback_tick`/`kerb_haptic_tick` 挂在 `proxy_car_controller` 里 ⇒ 关踏板覆盖 = 连振感传感器一起关。与机型无关。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [V]（继承）——机制差异不是巧合。
- ⚠️ **官版振感是平台级限制** [V]（继承）——`VIBRATE` install-time 权限，`performHapticFeedback` 只收 int 常量。
- ⚠️ **共存版 versionCode 绝不可与官版脱钩** [V]（继承）——榜单按 version_code 分赛季。
- ⚠️ **新增门控 return 必须排在 `initGameLogger()` 之后** [V]（继承）——否则拦截时零文件日志。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（继承）——当前约 40 余项。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——曾致 kernel panic。
- ⚠️ **用户日志在 Windows 盘** [V]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`，按段 grep 而非全文。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明** 11 **对外交流不用内部编号，用现象描述** 12 **数据还有没有用由需求方判断，不替对方筛** 13 **用户要的 APK 先看 `/sdcard/Download/`，不要拉 `/data/app/`**。

## 6. 下一步（有序）
1. **（待用户定优先级）振感三项修复**，按确定性从高到低：
   a. **修「踏板 OFF 导致振感信号源全灭」**——确定性 bug、与机型无关、改动小。`slip_feedback_tick`/`kerb_haptic_tick` 采样点需与 `enable_control_replacement` 解耦。
   b. **抓地力波形 96 段 → 1 段**——手感不变（本来就恒定幅度），跨过 HAL 量化阈值。先在本机确认无回归（魅族 `rampStepDurationMs=5`，96 段与 1 段无差别）。
   c. **抬高抓地力幅度映射下限**——p50=4/255 太低。
2. 上述改完随下个版本发出，**请 VER（HONOR Win，「强度调节无效、一直最大」）复测**——验证「96 段被 HAL 按 on/off 处理」假说的最直接人选。
3. （可选）评估 API 36 `BasicEnvelopeBuilder`。
4. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- **振感三项修复现在做还是先搁置？** 本会话全部精力在 NPatch Bug D，振感调查自上上次交接起未推进。
- 抓地力手感取向：保住「连续绵密」（改段数）vs 换「高频颗粒」（速率编码）——**建议前者**。
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 旧 836 共存包（`Ala Mobile Pro 8.0.6 Fix 2 (NPatch 836).apk`）要保留还是删除？
- NPatch 845 的共存包要不要替换分发版（当前标准名 `Ala Mobile Pro 8.0.6 Fix 2.apk` 仍是 843 版）？
