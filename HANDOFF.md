# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T00:51:17+08:00 · Git HEAD: `437a824`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `437a824`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `437a824`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的振感条目（本会话完全未动）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/util/VersionGate.kt`（版本门控的唯一事实源）

## 1. 当前目标
**停止维护官版 + 版本门控严格相等** —— 三项需求全部实现、lint 绿、已装真机。**未发版**。
振感三项修复仍**完全未动**（§6，优先级待用户定）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **两个工作切片已提交并 push**：`732bc34`（核心逻辑：移除官版 + 门控严格相等）、`741af69`（UI 胶囊）。
- [V] **持久文档已提交并 push**：`437a824`（CLAUDE.md + README.md）。
- [V] **门槛全绿**：`./gradlew :app:lint` → `BUILD SUCCESSFUL in 2s`，`EXIT=0`（65 warnings 全在 baseline）。
- [V] **版本号未动**：`app/build.gradle.kts` + `module.prop` 双处仍 = `1.0.4 Alpha 4` / `104140`。**未打 tag、未发 Release**。
- [V] **release 构建 + 装机成功**：`assembleRelease`（带 `-x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease`）→ 魅族 20（`192.168.50.142:5555`）`install -r` 返回 `Success`，`dumpsys package` 确认 `versionName=1.0.4 Alpha 4` / `versionCode=104140` / `lastUpdateTime=2026-10-18 00:43:39`。
- [V] **代码层官版已清零**：`grep -rn "com\.Vince\.AlamobileFormula" app/src scripts` 仅剩注释里的历史说明；`OFFICIAL_PKG` / `MIN_COEX_FIX` / `GAME_PKG_OFFICIAL` 全部无引用。
- [V] **APK 目录已收敛**：`安装包/Ala Mobile Pro 8.0.6 Fix 2.apk` 现 = NPatch **845**（MD5 `44379426…`，`config.json` 的 `VERSION_CODE` 实测 845）；旧 836/843 与冗余 845 副本已删，只剩标准名 + `原版.apk`。
- **工作区**: 干净（除本 HANDOFF 提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 2s     EXIT=0     （29 tasks，28 up-to-date）

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 35s   （50 tasks）

$ adb -s 192.168.50.142:5555 install -r app/build/outputs/apk/release/app-release.apk
  → Success
```

## 3. 决策与理由
- **官版彻底移除而非"保留但不推荐"** [V]——用户定案。官版 APK 无 `VIBRATE`（抓地力只能走免权限叩击）、且绝大多数用户装 NPatch 共存版；留着分支等于每个包名判断多一条永不验证的死路径。
- **`HapticMixer.Fallback` 保留** [V]——它不是"官版专用代码"，而是"游戏 APK 没 VIBRATE 权限"的降级路径。共存版正常走 `Envelope`/`Duty`，但打包漏权限时 `Fallback` 能避免 `SecurityException` 崩游戏。只把注释去官版化。
- **门控从 `>=` 改 `==`** [V]——旧实现只保证"不太旧"、不保证"不太新"：Fix 3/4/5 只要引擎版本没动就放行，而 Fix 后缀标记的是安装包形态（权限/NPatch 参数/manifest）。代价是发新 Fix 包必须同步改 `SUPPORTED_COEX_FIX` 并随模块发版——这是**故意的**配套契约。
- **常量改名 `MIN_COEX_FIX` → `SUPPORTED_COEX_FIX`** [V]——名字里的 "MIN" 在 `==` 语义下是错的，会误导后来者写回 `>=`。
- **胶囊 label 改 "Ala Mobile Pro"** [V]——用户指定文案，与共存版应用名一致（"共存版"是内部术语，用户侧不该看到）。
- **Toast 文案去掉"游戏"泛指** [V]——现在只有共存版一种游戏，`TOAST_TEXT_GAME` 改为「游戏版本不匹配，功能失效，请到 QQ 群下载最新共存版！」。

## 4. 失败的尝试 — 不要再试
- [X] **「换 Shizuku 实现（含作者 HSSkyBoy fork）能修好诊断导出」** [V]——官方 13.6.0 与 fork 13.6.1.r1192 双实测，诊断包均写 `No`；根因在 NPatch 的 R8 规则。
- [X] **「Shizuku 没授权所以导不出诊断包」** [V]——授权后仍失败。
- [X] **「诊断包对定位没价值，不必上传」** [V]——**我判断错了**。数据的需求方才有资格判断价值。
- [X] **「Shizuku user service 起不来是 Shizuku 13.6.0 + Android 16 的上游 NPE」** [V]——实际是 `NoSuchMethodException`。**同一现象可有多条独立成因，版本匹配只是弱证据**。
- [X] **「NPatch manifest 缺 `ShizukuService` 声明是根因」** [V]——user service 由 server 以 root 直接 `app_process` 启动。
- [X] **「从 `/data/app/.../base.apk` 拉取用户要的 APK」** [V]——用户装/发给别人的包在 `/sdcard/Download/`。
- [X] **「放行未知的更高 Fix 号（`>=`）」** [V]（本会话否决）——Fix 后缀标记安装包形态，未验证形态不放行。
- [X] 归因「系统触感滑条压低振动」/「用户装的是官版」/「primitive 合成能修振感」/「速率编码」/「主流游戏不做持续振动」/「Fallback 叩击压到 13~17ms」 [V]（继承）。
- [X] 继承死路（详 `.handoffs/20261018002237-handoff.md` §4 及更早）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。

## 5. 已知坑
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [V]（本会话新增）——发新 Fix 共存包时若不同步改常量并重发模块，用户装新包会被门控拦（零 hook + 循环 Toast）。反向也成立：模块发版前必须确认当前 Fix 号与分发版一致。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [V]（本会话新增）——共存版补了 VIBRATE，永远走 `Envelope`/`Duty`。保留它只为防御打包漏权限，**不要在它上面继续调参**（官版时代已证伪两条路线）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]（继承）——ColorOS 让 `bindService` 同步返回 false。不影响模块可用性，只影响热重载/作用域实时更新。
- ⚠️ **抓地力波形 96 段是跨机型弱振的首要嫌疑（未验证）** [?]（继承）——96 段幅度完全相同（`buildFlat`），纯属多余；HAL 逐段转跳变指令 + Android 时序精度 ~50ms ⇒ 5ms 段对多数 HAL 是噪声。建议 96 段 → 1 段。**未验证、未动手。**
- ⚠️ **抓地力下发幅度常年是个位数** [?]（继承）——小米 15 Pro p50=4 / p90=23（满 255）。默认强度 50% ⇒ 幅度 ≈20，接近不可感。
- ⚠️ **「踏板模式=OFF 导致振感信号源整个不跑」——确定性 bug（未修）** [?]（继承）——`native/src/pedal_hook.c:1041` 直接 return，一个 hook 都不装；`slip_feedback_tick`/`kerb_haptic_tick` 挂在 `proxy_car_controller` 里 ⇒ 关踏板覆盖 = 连振感传感器一起关。与机型无关。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [V]（继承）——机制差异不是巧合。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [V]（继承）——榜单按 version_code 分赛季。
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
- **振感三项修复现在做还是先搁置？** 本会话全部精力在官版移除 + 门控严格相等，振感调查自上上次交接起未推进。
- 抓地力手感取向：保住「连续绵密」（改段数）vs 换「高频颗粒」（速率编码）——**建议前者**。
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 移除官版后，是否需要给仍在用官版的用户一条**明确的迁移提示**（当前是零 hook + 「游戏版本不匹配」Toast，用户可能不理解为什么突然不能用）？
