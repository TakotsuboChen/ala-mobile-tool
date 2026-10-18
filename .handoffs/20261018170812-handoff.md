# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T16:18:00+08:00 · Git HEAD: `e7f8775`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `e7f8775`（工作切片已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `e7f8775`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 有一条（方向盘视觉链路）
- 先读: `native/src/steer_limit_hook.h`（含完整反汇编依据）；图标规范见 CLAUDE.md 的 `CustomIcons.kt` 条目

## 1. 当前目标
**「解除最大转向角度限制」功能已完整收尾**：物理层 hook 生效 + 配置页图标换成方向盘。
用户 2026-10-18 定案「那就算做完了」，图标验收「完美」。
遗留：车内方向盘模型仍随速度回正（视觉链路，用户未要求继续）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **本会话工作切片已提交并 push**：`e7f8775`（2 文件 +23 行，图标改动）。
- [V] **上一会话工作切片**：`165f79e`（11 文件 +537 行，含 `steer_limit_hook.{c,h}`）。
- [V] **编译 + 构建通过**：`assembleRelease -x lintVital*` → `BUILD SUCCESSFUL in 1m 59s`。
- [V] **lint 全绿**：`:app:lint` → `BUILD SUCCESSFUL in 2m 26s`，66 warnings / 0 error（与基线一致）。
- [V] **产物实证**：APK 16:12:24 晚于源码 16:10:14；dex 命中 `SteeringWheelIcon` ×1
  与 path 起始串 `M50,99A49,49,0,1,0,1,50,49.06` ×1。
- [V] **SVG 等价性**：`rsvg-convert` 原图 vs `translate(-1,-1)` 参考图 `compare -metric AE = 0`。
- [V] **`d` 字符级对拍**：Kotlin 拼接后 535 字符 === SVG 原串 535 字符（`True`）。
- [V] **已装机 MEIZU 20**：`install -r` → `Success`；`lastUpdateTime=2026-10-18 16:16:06`，
  `versionCode=104140` / `versionName=1.0.4 Alpha 4`。
- [V] **用户验收图标**：原话「完美」。
- [V] **版本号未动**：`1.0.4 Alpha 4` / `104140`。未打 tag、未发 Release。
- **工作区**: 干净（HANDOFF 归档前）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 59s    50 tasks: 13 executed, 37 up-to-date

$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 2m 26s    Lint found 66 warnings, 4 hints（0 error）

$ adb -s 192.168.50.142:5555 install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **图标用用户提供的 `Steering Wheel.svg`，不复用 `ForbidIcon`** [V]——原实现用禁止符，
  与「解除转向限制」语义不贴切。用户主动提供素材。
- **`viewportOriginX/Y = 1f`** [V]——源 SVG `viewBox="1 1 98 98"` 原点非零，Compose
  `ImageVector` 不支持非零原点，必须靠 `group(translationX/Y)` 平移回 `0..98`。
  否决方案：改写 `d` 字符串坐标，历史上踩过丢 `z` / 切碎数字的坑。
- **单 path 多子路径靠绕向镂空，不设 `PathFillType.EvenOdd`** [V]——外圆顺时针、
  三个镂空区逆时针，`NonZero`（默认）即正确。AE=0 实测确认。
- **（继承）hook `IRDSWheel.SetMaxSteeringAngle`(0x1A7B49C)** [?]——详见 `steer_limit_hook.h`。
- **（继承）改写 `maxSteeringAngleOld`(0x94) 为 `maxSteeringAngle`(0x90)** [?]。
- **（继承）`base > 0.0f` 守卫** [?]——0x90 在 Awake 前是 0，写入会除零得 NaN。

## 4. 失败的尝试 — 不要再试
- [X] **假设「`maxSteeringAngleOld` 是方向盘视觉的驱动源」** [V]——hook 成功改值、
  物理变灵敏，但车内方向盘模型仍随速度回正 ⇒ 视觉是独立链路。
- [X] **用 `find_field_access.py` 扫浮点字段** [V]——只编码 V=0，浮点走 V=1，
  得到「零命中」，误判成「字段没人写」。已补 `find_field_access_f.py`。
- [X] **假设 `LockSteerAtVelocity` / `LockSteerAtSlipAngle` 是限角实现** [V]——
  前者编译成单条 `ret`（源码即空方法），后者有实体但全 `.so` 零 `bl` 调用者。
- [X] **假设 `SwitchSteerAxisComputation` 是按速度限角** [V]——它读的是
  `AnalogAdvancedSettings` 的 `_steerDeadzone`(0x10)/`_steerLinearity`(0x18)/
  `_steerMax`(0x1C)，是**用户转向轴的死区与行程映射**，与车速无关。
- [X] 继承死路（详 `.handoffs/20261018161710-handoff.md` §4 及更早）：`--rerun-tasks`
  重编 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 /
  `HapticGenerator` / `pm grant VIBRATE` / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」/ `coroutineScope{}`
  内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301。

## 5. 已知坑
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]——新增设置项要配图标、要写用户可见
  文案、要给功能起名字时，**动手前先问**「合不合适、要不要你提供素材」。
  见全局记忆 `ask-before-designing-ui`。用户原话：「不要啥也不说直接整」。
- ⚠️ **物理上限解除 ≠ 视觉/UI 跟着变** [V]——用户报「仍有 X」时先二分手感/显示。
  见 memory `physics-limit-vs-visual-readout`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]——`HasKey` 为假或
  `GetInt` 为 0 时才置 true。读这个字段名的字面义会得出相反结论。
- ⚠️ **IL2CPP 浮点字段必须用 `find_field_access_f.py`** [V]——见 CLAUDE.md。
- ⚠️ **日志 2MB 滚动截断会吃掉安装期证据** [V]——`initSteerLimit` 那行在新一轮
  游戏进程后被滚掉，只剩驾驶期的拦截行。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——任何 adb 动作都算。通知 ≠ 许可。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）——`rampStepDurationMs=5`。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——看日志环境块的
  「构建时间/构建提交」。见 memory `build-fingerprint-in-logs`。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含
  `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）——不要在它上面调参。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [?]（继承）——当前约 41 项。
- ⚠️ **用户日志在 Windows 盘** [?]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验
  设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了
  的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 动用户设备状态前先说明
  9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须先说明 11 对外交流不用内部
  编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛 13 用户要的 APK 先看
  `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（可选，用户未要求）方向盘视觉链路**：若用户回来说「方向盘还是随速度回正、
   想一并解决」，需先加一次诊断采样（在 `SetMaxSteeringAngle` 里顺带打
   `IRDSWheel.steering`(0xF4) 与 `IRDSCarControllInput.inputSteer`(0x5C)），
   用实测数据定位视觉读的是哪个量，**不要**再靠静态搜索盲猜。
   ⚠️ 采样偏移必须经 JNI 下发，不得硬编码。
2. **等远端用户回传带环境块的日志**（上一会话遗留）：判据 = 出现
   `=== 环境信息 ===` 段即新版。若报振感仍弱 → 转向 HAL 对 waveform 的执行。
3. **处理「卡加载」的旧版用户**：查日志有无 `Pushed game logs via chunked broadcast`。
4. （用户发话才做）发版：先问版本号 → tag → CI。

## 7. 留给用户的开放问题
- 方向盘模型随速度回正要不要一并解除？（当前物理已解除，视觉未跟进）
- 「卡加载」要不要在 `docs/` 立一份正式记录？
- 六位报障用户的机型/系统对照表要不要落盘到 `docs/`？
