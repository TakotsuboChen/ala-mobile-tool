# HANDOFF — 读全文再开始干活

生成时间: 2026-10-17T23:00:05+08:00 · Git HEAD: `d47b741`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `d47b741`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `d47b741`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 里标 [?] 的条目（振感相关，本会话未动）
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md` §12（NPatch 三类问题的对号入座表）

## 1. 当前目标
**NPatch「管理器不在后台时模块不加载」调查已闭环** —— 根因定位、上游修复、实机验证、回复上游，全部完成。
本轮**未改模块代码**，只做了版本门控升级（`MIN_COEX_FIX = 2`）+ 版本号 Alpha 4。**未发版**。
上一会话的**跨品牌振感弱**调查仍**完全未动代码**（见 §5/§6，优先级待用户定）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **三切片已提交并 push**：`cd8945b`（版本门控）→ `a56255b`（NPatch 调查文档）→ `d47b741`（CLAUDE.md）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`./gradlew :app:lint` → `EXIT=0`，`BUILD SUCCESSFUL in 1s`。
- [V] **版本号**：`app/build.gradle.kts` + `module.prop` 双处 = `1.0.4 Alpha 4` / `104140`（用户指定）。**未打 tag、未发 Release**。
- [V] **NPatch 843 实机验证通过**（一加平板 2 Pro / ColorOS 16 / Android 16）：管理器杀后冷启动，模块完整加载 —— `Installed native LoadedModule service proxy` + `Module loaded in process`，进程 maps **6 段**，功能初始化日志 **16 条**；管理器活着时同样 16 条，**逐条一致**。对照 836 失败态 = 0 段 / 0 条。
- [V] **共存包已产出发放**：`安装包/Ala Mobile Pro 8.0.6 Fix 2.apk` = 843 修补版（MD5 `b55c7400…`）；旧 836 版改名保留为 `Ala Mobile Pro 8.0.6 Fix 2 (NPatch 836).apk`。平板已装 843（落盘 APK 内 `"VERSION_CODE":843` 已回拉核对）。
- [V] **上游 #154 已回复**：https://github.com/7723mod/NPatch/issues/154#issuecomment-6235120064（6385 字符，含 843 验证表、ColorOS 旁证原始日志、诊断导出观察、Shizuku 通道断链根因、诊断包 gofile 链接）。
- **工作区**: 干净（本 HANDOFF 提交带走 `.handoffs/` 归档）。
- ⚠️ **未做**：§6 的振感三项修复全部未动代码。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1s     EXIT=0     （29 tasks，28 up-to-date）
```

## 3. 决策与理由
- **MIN_COEX_FIX 抬到 2** [V]——用户定案。旧共存包（Fix 0/1）配 NPatch ≤1.0.8 存在「模块经常不生效」（缓存未设只读 → Android 14+ 拒绝可写 dex）。模块读不到 NPatch 版本，只能靠游戏包版本间接逼用户换 836+ 并重装。**否决「模块内读 NPatch 版本判断」**：跨进程读框架版本不可靠且增加耦合。
- **843 修复的正确性核验方式** [V]——不只看「不崩溃」，而是逐条对照两态的功能初始化日志（各 16 条）+ 进程 maps 段数（0→6）。836 失败态**也不崩溃**，只验「能进游戏」会永远漏掉这个 bug。
- **版本号 alpha 4 而非继续 alpha 3** [V]——用户明确要求（`1.0.4 Alpha 4` / `104140`）。
- **旧共存包改名而非覆盖** [V]——标准名让给 843 新包，836 旧包加 `(NPatch 836)` 后缀保留，不擅自删除用户产物。
- **给上游的回复用现象描述而非内部编号** [V]——初稿写了「Bug C 已修复」，那是我自己文档里的编号、作者无从得知；已改为「管理器不在后台时模块已能正常加载」。

## 4. 失败的尝试 — 不要再试
- [X] **「Shizuku 没授权所以导不出诊断包」** [V]——授权后仍失败，真实根因是 NPatch 的 R8 混淆 bug（见 §5）。
- [X] **「诊断包对定位没价值，不必上传」** [V]——**我判断错了**。作者要包是为了验证「导出流程本身是否正常」，不是定位 bug；数据的需求方才有资格判断价值。
- [X] **「Shizuku user service 起不来是 Shizuku 13.6.0 + Android 16 的上游 NPE（issue #2561）」** [V]——版本号完全对得上，但实际抓到的崩溃是 `InstantiationException` 而非 `NPE`。**同一现象可有多条独立成因，版本匹配只是弱证据**；必须抓到真实崩溃栈。
- [X] **「NPatch manifest 缺 `ShizukuService` 声明是根因」** [V]——Shizuku 的 user service 由 server 以 root 直接 `app_process` 启动，不依赖 manifest。
- [X] **归因「系统触感滑条压低振动」** [V]（继承）——`UNKNOWN = MEDIUM` ⇒ `scaleLevel=0` 不缩放。
- [X] **归因「用户装的是官版 ⇒ Fallback 滑条空转」** [V]（继承）——终端用户必是共存版。
- [X] **「改成 primitive 合成路径就能修振感」** [V]（继承）——本机 HAL 把 primitive 全压成 4ms。
- [X] **「把抓地力改成速率编码（脉冲密度）」** [V]（继承）——牺牲 12 轮迭代的连续手感，病根是段数不是连续。
- [X] **「主流游戏不做持续振动」** [V]（继承）——按摩器 App 用 `createOneShot(长时长)` 单段恒定幅度即可持续。
- [X] **「Fallback 叩击间隔压到 13~17ms」/「全程只用 CLOCK_TICK」** [V]（继承）——用户报「更快的哒哒哒」「判断不了强弱」；官版绵密是平台级限制。
- [X] 继承死路（详 `.handoffs/20261017230004-handoff.md` §4 及更早）：四个二级页各自 `viewModel()` / hook `OnLapChange` / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」 / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 / 清游戏缓存救 Android 16 白屏。

## 5. 已知坑
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [V]——ColorOS 让 `bindService` **同步返回 false**（拦截行与 `Manager not ready in time` 只隔 20ms，远小于代码里 2s 超时）⇒ `bindInitiated=false` ⇒ `scheduleGiveUp()` 重连机制**根本没注册**。不影响模块可用性（模块已加载），只影响热重载/作用域实时更新。作者已在 #154 说明此限制。
- ⚠️ **NPatch 诊断导出的 Shizuku logcat 通道是断的（R8 混淆 bug）** [V]——`ShizukuService` 被 R8 折叠成 AIDL 抽象基类 `mm0`（`dexdump`：`PUBLIC ABSTRACT` + 零构造器），`ServiceStarter` 对它 `Class.newInstance()` 必抛 `InstantiationException`。`manager/proguard-rules.pro` 无任何 shizuku keep 规则。已把 `-keep` 建议给上游。**用户侧无感**（`getOrNull()` 静默降级）。
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
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明** 11 **对外交流不用内部编号，用现象描述** 12 **数据还有没有用由需求方判断，不替对方筛**。

## 6. 下一步（有序）
1. **（待用户定优先级）振感三项修复**，按确定性从高到低：
   a. **修「踏板 OFF 导致振感信号源全灭」**——确定性 bug、与机型无关、改动小。`slip_feedback_tick`/`kerb_haptic_tick` 采样点需与 `enable_control_replacement` 解耦。
   b. **抓地力波形 96 段 → 1 段**——手感不变（本来就恒定幅度），跨过 HAL 量化阈值。先在本机确认无回归（魅族 `rampStepDurationMs=5`，96 段与 1 段无差别）。
   c. **抬高抓地力幅度映射下限**——p50=4/255 太低。
2. 上述改完随下个版本发出，**请 VER（HONOR Win，「强度调节无效、一直最大」）复测**——验证「96 段被 HAL 按 on/off 处理」假说的最直接人选。
3. （可选）评估 API 36 `BasicEnvelopeBuilder`。
4. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- **振感三项修复现在做还是先搁置？** 本会话全部精力在 NPatch，振感调查自上次交接起未推进。
- 抓地力手感取向：保住「连续绵密」（改段数）vs 换「高频颗粒」（速率编码）——**建议前者**。
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
- 旧 836 共存包（`Ala Mobile Pro 8.0.6 Fix 2 (NPatch 836).apk`）要保留还是删除？
