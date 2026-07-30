# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T23:15:25+08:00 · Git HEAD: 4398609e27034aeebfee44e66cac9846fd6e72e0
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
本次切片完成两件事：(1) 修 LSPosed 真激活检测——把 `LsposedStatus.evaluate` 从 `System.getProperty(MODULE_LOADED_FLAG)`（ConfigActivity 进程不可达，死路）改为 `App.xposedService != null`（daemon 绑定 = Manager 当前启用）；(2) 概览页 LinksCard 图标——GitHub 行用 Octocat、QQ 群行用 QQ 企鹅 logo，均内嵌 SVG path。**Build + lint 全绿，APK 已装设备 381QYFCN22B9A；真机视觉验证待用户确认。**

## 2. 已验证状态 — 工作实际停在哪

- [V] **激活检测改造** (`LsposedStatus.kt`)：`evaluate` 主判定改为 `App.xposedService != null`。daemon binder 只在 LSPosed Manager 启用本模块时由框架经 `XposedProvider.call("SendBinder")` 触发；用户在 Manager 关模块 → 不触发 → service 为 null。语义 = "Manager 当前启用"，用户确认选此方案。删除进程级 property 读取 + 轮询（ConfigActivity 不可达，死路）；删除 daemon `module_loaded` 持久标记读写（被 service 绑定状态取代）。Non-root 手动确认路径保留不变。参数 `awaitModuleLoad` → `awaitService`（轮询对象变成 daemon 异步绑定）。
- [V] **AlaMobileModule.markActivated 瘦身**：删 daemon `module_loaded` 写入（被 service 绑定取代）。保留 `System.setProperty(MODULE_LOADED_FLAG)`（向后兼容，`clearAll` 仍清，无害）。
- [V] **GitHub/QQ 图标内嵌** (`OverviewPage.kt`)：新增 `svgIcon(name, svgPath)` helper——用 `PathParser().parsePathString(svg).toNodes()` 解析 SVG `d` 字符串，在 `path()` DSL 的 `PathBuilder` lambda 里按 `when` 分发到对应方法（支持 M/L/H/V/C/Q/A/Z 全命令及相对/reflective 变体）。`GithubMark` + `QqMark` 顶层 val，path 数据来自 simple-icons（CC0）。`LinksCard` 里"GitHub 源代码"行 `Icons.Rounded.Info`→`GithubMark`，"QQ 群"行 `Icons.Rounded.Phone`→`QqMark`。
- [V] **libxposed 仓库克隆到 references**：`references/libxposed-api`、`references/libxposed-service`、`references/LSPatch`（均 shallow clone，`.gitignore` 的 `references/` 规则已覆盖）。查证 `XposedService` 暴露 `getFrameworkName()`/`getScope()`/`getRunningTargets()`，但当前方案只用 service 绑定状态（最简，足够）。
- [?] **真机视觉验证未做**：APK 已装，但用户未点开 ConfigActivity 确认 GitHub/QQ 图标形状、激活卡片显示。

### 测试/build 输出 tail（本次交接 run 的真实输出）
```
$ git status
modified: app/src/main/kotlin/tools/alamobile/mod/AlaMobileModule.kt
modified: app/src/main/kotlin/tools/alamobile/mod/LsposedStatus.kt
modified: app/src/main/kotlin/tools/alamobile/mod/ui/OverviewPage.kt

$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 1s
50 actionable tasks: 3 executed, 47 up-to-date

$ adb -s 381QYFCN22B9A install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install / Success
```

## 3. 决策与理由

- **激活检测用 "Manager 当前启用" 语义** [V]——用户在 AskUserQuestion 里选此方案。daemon 绑定状态随 Manager 启用态实时变化，不需进程重启，不依赖 onModuleLoaded 时机。否决方案："曾激活过"（daemon 持久标记，关了 Manager 仍残留，语义松）；"启用 + 目标进程正被注入"（getRunningTargets，用户没开游戏时显示未激活，反直觉）。
- **Non-root 不做自动识别，保留手动点击弹窗** [V]——用户原话"Non-root 维持手动点击，不做了"。查证 LSPatch 用 legacy `assets/xposed_init` + `de.robv.android.xposed.XposedInit`（不走 libxposed API 102 的 `onModuleLoaded`），且不绑 daemon → `xposedService == null` → 自动落 Non-root 手动路径，语义分流天然正确，无需手写 isLSPatch 判定。否决方案：用 `getFrameworkName()` 自动区分框架名（功能可行但用户不要）。
- **图标用 PathParser + when 分发，不引 material-icons-extended** [V]——material-icons-extended 数 MB，只为两个图标引它不值当。内嵌 `ImageVector` 常量数百字节，与 `Icons.Rounded.*` 共用 24×24 坐标系。否决方案：手动 moveTo/curveTo 链（QQ 含 arc 相对命令 `a`，`arcToRelative` 参数多且顺序敏感，手写易错）；直接用 mangled `addPath-oIyEayM`（源码层不可读）。
- **PathParser + when 分发是处理含 arc SVG 的最优解** [V]——`path()` DSL 只接 `PathBuilder.() -> Unit`，没有 `pathData: List<PathNode>` 重载（那是 `group()` 才有）。PathParser 解析后 PathNode sealed 子类已把命令语义化，`when` 分发零转译风险。
- **references/libxposed-* 不入库** [V]——`.gitignore` 的 `references/` 规则已覆盖，只作本地阅读参考。

## 4. 失败的尝试 — 不要再试

- **`System.getProperty(MODULE_LOADED_FLAG)` 作 ConfigActivity 激活判定** [V]——`onModuleLoaded` 只在目标 App（游戏）进程被注入时调，模块 APK 自己的 ConfigActivity 进程不被注入，property 永不设上 → 永远显示未激活。已废弃。不要再试。
- **daemon `module_loaded` 持久标记作主判定** [V]——ConfigActivity 不读它了（被 service 绑定状态取代）。语义松（关了 Manager 仍残留）。`clearAll` 仍清它作向后兼容，但不再写入。
- **`PathBuilder` 手动转译 SVG path 含 arc** [V]——QQ path 含相对 `a` 命令，`arcToRelative(rx,ry,theta,isMoreThanHalf,isPositiveArc,dx,dy)` 参数顺序敏感，手写易错位。改用 PathParser。
- **`path()` DSL 的 `pathData: List<PathNode>` 参数** [V]（前向搬运，已证伪）——`path()` 只接 `PathBuilder.() -> Unit`，没有 `pathData` 重载。`group()` 才有 `pathData: List<PathNode>`。
- **PathNode 子类属性名从兄弟类推断** [V]——不同子类命名不统一：`CurveTo` 用 `x1/y1/x2/y2/x3/y3`，`ReflectiveCurveTo` 只有 `x1/y1/x2/y2`，`ReflectiveQuadTo` 是 `x/y`（单数），`ArcTo.isMoreThanHalf`（不是 `isArcGreaterThanSemi`）。必须逐类 `javap` 查。
- **miuix `primaryContainer`/`primaryVariant` 作已激活底色** [V]、**miuix 0.9.3 有 SuperDialog** [V]、**openRemoteFile 读模块 filesDir** [V]、**legacy `de.robv.android.xposed.XSharedPreferences`** [V]、**模块进程写公共 `/sdcard/`** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]、**统一公式画两种方向刹车** [V]——均不再试（前向搬运）。

## 5. 已知坑

- **ConfigActivity 进程不被 LSPosed 注入** [V]——`onModuleLoaded` 只在目标 App 进程调。新方案用 `App.xposedService` 绑定状态绕开，不再依赖 property。
- **LSPatch 用 legacy `assets/xposed_init`** [V]——不走 libxposed API 102 的 `onModuleLoaded`，不绑 daemon。`xposedService == null` 自动落 Non-root 手动路径。
- **XposedServiceHelper 异步绑定** [V]——ConfigActivity.onCreate 时 `App.xposedService` 可能仍 null，`evaluate` 的 `awaitService` 轮询兜 3s。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。配色跟 KernelSU（绿调），不要用 miuix 语义色 token 作已激活底，要硬编码 KernelSU 的绿值。
- **miuix 0.9.3 没有 SuperDialog** [V]——用 `OverlayDialog`（`top.yukonga.miuix.kmp.overlay.OverlayDialog`）。
- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- **lint baseline 不覆盖新错误** [V]——加新代码必须本地 `./gradlew :app:lint`。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播 + Remote Preferences 是可靠跨进程 IPC。
- **广播首次启动滞后（M11）** [V]——已由 Remote Preferences 根治。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [?]——广播方案落地后未使用，Remote Preferences 方案下更无用，待清理。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。
- **libxposed-service 依赖已就位** [V]——`implementation(libs.libxposed.service)` version 102.0.0。

## 6. 下一步（有序）

1. **真机视觉验证**——打开 ConfigActivity 概览页：(a) 激活卡片在 LSPosed Manager 启用模块时应显示"已激活/模块已通过 LSPosed 加载"（绿色调）；在 Manager 禁用模块后应显示"未激活"（红色调）。(b) "GitHub 源代码"行显示 Octocat 图标，"QQ 群"行显示 QQ 企鹅。若形状错（path 渲染问题），调 SVG path。
2. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明。
3. **发 Beta 3**——versionName `1.0.0 Beta 3`，versionCode `100230`。M14 四件事（方向键修复 + 刹车方向反转 + 配置同步迁移 + 激活检测修复）全闭环后发。

## 7. 留给用户的开放问题

- 是否现在清理废弃的 ConfigProvider.kt + manifest provider 声明？
- 真机视觉验证后，GitHub/QQ 图标形状对不对？
