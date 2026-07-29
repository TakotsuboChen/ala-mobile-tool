# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T03:50:00+08:00 · Git HEAD: 6c5b65b（本次改动尚未 commit）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 M10 遗留的"踏板模式切换 + 响应曲线切换运行时不生效"问题。**已完成**：根因定位为 Android 11+ scoped storage + 包可见性双重隔离，落地广播方案 + raw/mapped 分离 + 刹车视觉方向修复，真机全过。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `6c5b65b`，8 个文件改动（6 modified + 2 新增）未 commit。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 3s。
- [V] 装机成功，真机全功能验证通过（用户确认）：
  - 配置页改双踏板 → 广播送达游戏进程 → toggle 后控件正确变化。
  - 拟真曲线：视觉跟手（手指30%→填充30%），游戏油门非线性（30%→60%）。
  - 刹车视觉：从手指位置往下填到底，与油门镜像对称。
- [V] 根因链（每层都验证）：
  1. `OverlayManager.settings by lazy` 缓存 → 改可重读 `var` + toggle 重建。
  2. scoped storage：模块进程写模块 `filesDir`，游戏进程读不到（`EACCES` 外部存储根 + uid 隔离游戏 `Android/data`）。
  3. ContentProvider 被包可见性拦：`createPackageContext` 抛 `NameNotFoundException`，`Failed to find provider info`。
  4. **广播方案**：ConfigActivity 发定向广播（`Intent.setPackage` 不查可见性），游戏进程 `ConfigReceiver` 写自己 `externalFilesDir`，OverlayManager 读同一路径。
- [V] ConfigProvider.kt 实际未使用（广播方案落地后废弃），但代码仍在，可删或留作备份。
- [V] 首次启动限制：游戏进程首次启动时 `ConfigReceiver` 还没收到广播，读自己目录无文件 → 默认值。用户改一次配置后 toggle 即生效；后续启动因游戏目录已有配置文件，直接读到正确值。

### 测试/build 输出 tail

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:assembleDebug
BUILD SUCCESSFUL in 3s
$ adb install -r → Success
真机：双踏板+拟真生效，视觉跟手，刹车方向正确（用户确认"现在对了"）
```

## 3. 决策与理由

- **广播方案作跨进程配置 IPC** [V]——ConfigActivity（模块进程）写模块 `filesDir` + 发定向广播给游戏包；游戏进程 `ConfigReceiver` 收到后写自己 `externalFilesDir`；OverlayManager 读同一路径。`Intent.setPackage` 直接派发不查 PackageManager 可见性，绕过 Android 11+ 双重限制。否决方案：① 文件直读（scoped storage 拦）；② ContentProvider（包可见性拦 `NameNotFoundException`）；③ `createPackageContext`（同样被可见性拦）。三者全实测失败。
- **raw/mapped 值分离** [V]——`rawThrottle`/`rawBrake` 是手指位移（绘制，跟手），`mappedThrottle`/`mappedBrake` 是曲线变换后送 native 的值（非线性）。否决：原设计 `applyCurve` 直接作用在 `throttle` 字段，导致视觉填充也非线性，"触摸30%填充60%"不跟手。
- **刹车视觉从手指位置往下填到底** [V]——`drawRect(0, top, width, height)`，`top=height*(1-rawBrake)`。与油门（从底向上填到手指）镜像对称。否决：原从底向上画水位式，方向反。
- **ConfigReceiver 用 RECEIVER_EXPORTED 注册** [V]——Android 13+ 强制要求，广播跨应用派发必须 EXPORTED。

## 4. 失败的尝试 — 不要再试

- **文件直读跨进程** [V]——`Environment.getExternalStorageDirectory()/AlaMobileTool/` 在 targetSdk 35 下模块进程写 `EACCES`、游戏进程读 `EACCES`（scoped storage）。`getExternalFilesDir` 各进程是自己包私有目录，uid 隔离互相读不到。不要再试文件 IPC。
- **ContentProvider 跨进程** [V]——`resolver.call(uri,...)` 报 `Failed to find provider info`（游戏进程看不到模块 Provider，包可见性）。`createPackageContext(MODULE_PACKAGE, CONTEXT_IGNORE_SECURITY)` 抛 `NameNotFoundException`——`CONTEXT_IGNORE_SECURITY` 不绕过包可见性过滤。不要再试 Provider/createPackageContext。
- **`ContentResolver.call(String, String, String, String, Bundle)` 显式包名重载** [V]——Kotlin SDK 在 minSdk 26 下编译期解析为旧 4 参 `call(Uri,...)`，5 参重载（API 30+）不可用。要反射或 `@RequiresApi(30)`，未试（广播方案已落地）。
- **`OverlayManager.settings by lazy` 只改缓存不够** [V]——即使改可重读 `var`，`PedalOverlayView` 构造时拷 settings 快照（data class 值语义），必须重建 view 才能让新配置生效。toggle 时要 `removeGamingOverlays`+重建。
- **`applyCurve` 作用在 throttle 单字段** [V]——导致视觉和 native 都非线性，不跟手。必须 raw/mapped 分离。
- **BRAKE 填充从底向上画水位式** [V]——与油门方向不一致，用户感觉反。要镜像对称。

## 5. 已知坑

- **Android 11+ scoped storage** [V]——外部存储根 `EACCES`，`Android/data/<pkg>` uid 隔离。模块进程和游戏进程只能各写自己 `filesDir`/`externalFilesDir`，跨进程靠广播。
- **Android 11+ 包可见性** [V]——targetSdk≥30 默认看不到未在 `<queries>` 声明的包。游戏 manifest 改不了，`createPackageContext` 和 ContentProvider 都被拦。`Intent.setPackage` 定向广播是唯一绕过方式。
- **`CONTEXT_IGNORE_SECURITY` 不绕过包可见性** [V]——只忽略 security context，`PackageManager.NameNotFoundException` 仍抛。
- **Android 13+ `registerReceiver` 需 flag** [V]——`RECEIVER_EXPORTED`（跨应用）或 `RECEIVER_NOT_EXPORTED`（同应用）。targetSdk 35 强制。
- **广播首次启动滞后** [V]——游戏进程 `onPackageReady` 注册 receiver 前，ConfigActivity 发的广播丢。首次安装后首次进游戏读默认值。用户改一次配置后 toggle 生效；之后游戏目录有文件，直接读对。
- **Meizu AppsFilter BLOCKED** [?]——系统层额外包交互过滤，可能加剧可见性问题。非根因（广播已绕过）。
- **`PedalOverlayView` 构造拷 settings 快照** [V]——加/改 `ModConfig.Settings` 字段必须同步默认参数构造；配置变更必须重建 view（toggle 时 removeGamingOverlays+addGamingOverlays）。
- **`applyCurve` exponent 方向** [V]——<1 是 ease-out（先快后慢），≥1 是 ease-in。拟真用 0.42（30%→60%）。仅作用于 mapped（送 native），不影响 raw（绘制）。
- **`ConfigProvider.kt` 已废弃** [V]——广播方案落地后未使用，代码仍在，可删。
- **`OverlayManager.settings` 不再 by lazy** [V]——改可重读 `var`，show/toggle 时重读 JSON。
- **`removeGamingOverlays` vs `removeExisting`** [V]——前者只清踏板/换挡 view 保留 toggle 按钮（toggle 操作用），后者连按钮清（showOverlays 全量初始化用）。
- **ModConfig.read() 对 enableAutoDrs 强制 false** [V]——功能未实现，避免老用户升级后开关显示"开"但无效果。未来实现时改回读真实值。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，`markNativeInstalled()` 守卫拦第二个。日志常见"Native already installed by another ClassLoader"是正常态。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 `rawY - settings.pedalPosition.topPx()` 绕开。

## 6. 下一步（有序）

1. **清理 ConfigProvider.kt**（可选）——广播方案落地后未使用，可删避免混淆。manifest 里 provider 声明也可一并删。
2. **首次启动体验优化**（可选）——当前首次安装后首次进游戏读默认值。若要改善：ConfigActivity 启动时检测游戏是否在跑（`ActivityManager.getRunningAppProcesses`，需权限），在跑则立即发一次当前配置广播。或文档说明"改配置需 toggle 一次"。
3. **curve exponent 真机调参**（可选）——0.42（30%→60%）是初始值，用户可反馈手感是否合适，调整 `PedalOverlayView.applyCurve` 的 exponent。
4. **commit + 发 Beta**——本次修复闭环，可 commit 后发 Beta 2（versionCode 100221, versionName `1.0.0 Beta 2`）。
5. （后续）实现"手动换挡""自动 DRS"。

## 7. 留给用户的开放问题

- 拟真曲线 exponent=0.42（30%→60%）手感是否合适？需调参吗？
- 首次安装后首次进游戏读默认值（需改一次配置 toggle 才生效）是否可接受？还是要做启动时主动广播？
- ConfigProvider.kt 是删还是留作备份？
