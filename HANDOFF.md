# HANDOFF — 读全文再开始干活

生成时间: 2026-07-31T17:57:00+08:00 · Git HEAD: c0942e7（未提交，HEAD 仍是 Beta 2）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
本次会话把"工具"Overlay 控件从 70dp 黑底白字 Button 改造成 **96px（后改为屏高 10%）圆角矩形 + 居中 App 图标 + 可拖动 + 长按进编辑**——**完成，真机手势验证通过**。

## 2. 已验证状态 — 工作实际停在哪

- [V] **工具按钮改造完成**：`ToolButtonView.kt`（自定义 View）+ `ToolButtonIcon.kt`（base64 嵌入图标）+ `OverlayManager.kt`（接入）+ `ModConfig.kt`（加 `KEY_TOOL_POSITION` / `toolButtonPosition` 字段）。`git diff --stat` 确认。
- [V] **视觉**：96px→屏高 10%（Meizu 20 横屏 = 108px）圆角矩形 `#000814` 底色，前景 PNG 放大 1.5x + `clipPath` 裁掉 adaptive icon 34% 透明 padding，图标内容铺满圆角矩形。真机确认"显示了，填满"。
- [V] **手势三连**：单击切展开/折叠、拖动移自身（不持久化，每次 showOverlays 归默认）、长按 500ms 进其他 overlay 编辑模式。用户原话"手势都正常"。
- [V] **大小动态**：`sizePx = screenHeight * 0.10f`——横屏 `heightPixels` 是短边（1080）→ 108px。用户确认大小对。
- [V] **图标加载跨进程**：`BitmapFactory.decodeResource` 在游戏进程返回 null（游戏 Resources 不认识模块 R 值）；`PackageManager.getResourcesForApplication` 抛 `NameNotFoundException`（Android 11+ 包可见性）。最终方案：**把 ldpi 144×144 PNG base64 嵌入 Kotlin 源码**（`ToolButtonIcon.kt`，~16KB），`Base64.decode` + `BitmapFactory.decodeByteArray` 绕开资源系统。logcat `result=144x144` 确认。
- [V] **build + lint 全绿**：`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL in 24s`。
- [V] **未提交**：4 个文件改动（2 modified + 2 new）未 commit，按 CLAUDE.md 规则留提交决定权给用户。
- [V] **真机验证**：Meizu 20（`381QYFCN22B9A`，Android 16，450dpi，1080×2400）。手势全过。

### 测试/build 输出 tail（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 24s
50 actionable tasks: 11 executed, 39 up-to-date
```

## 3. 决策与理由

- **base64 嵌入图标而非运行时读资源** [V]——`decodeResource` 在游戏进程返回 null（游戏 Resources 不认识模块 R 值，R 是编译时模块资源表）；`getResourcesForApplication` 抛 NameNotFoundException（Android 11+ 包可见性 + scoped storage，M11 验证过）。base64 嵌入是唯一可靠跨进程方案。否决：从模块 APK `/data/app/.../base.apk` 直接读（路径需先 `getApplicationInfo` 拿，又回包可见性）；`openRemoteFile`（M14 验证读 daemon 目录不是模块 filesDir）。
- **大小用屏高 10% 而非固定 96px** [V]——用户原话"96×96 像素"，初版按固定 96px 实现后用户改要求"改成 10% 屏幕高度（如 1080 就是 108，动态的）"。横屏 `heightPixels` 是短边。否决：固定 96px（跨设备大小不一致，小屏太小）。
- **图标放大 1.5x + clipPath** [V]——adaptive icon 前景 PNG 自带 34% 透明 padding（系统 mask 裁切冗余），inset=0 直接铺满仍有"一圈黑"。放大 1.5x 让中心 66% 内容铺满圆角矩形，外围 34% 透明区用 `Path.addRoundRect` + `canvas.clipPath` 裁掉。否决：inset=0 不放大（黑边大）；inset=10%（图标只占 80%，更小）。
- **位置不持久化但加字段** [V]——用户要求"每次打开游戏重置到默认位置"。`Settings.toolButtonPosition` 字段保留（架构统一，未来加"记忆位置"开关零改动），`write()` 不写、`read()` 读但不依赖。`showOverlays()` 末尾调 `resetToDefault()` 归位。否决：不写字段（未来要持久化时散落 if-else）。
- **描边尝试后撤回** [V]——加 0x44FFFFFF 半透明白描边增强可见性，用户反馈"太窄了，基本没有，暂时不需要"，撤回 `borderPaint` 和 onDraw 描边调用。

## 4. 失败的尝试 — 不要再试

- **`BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)` 在游戏进程** [V]——返回 null，无异常。`context.resources` 是游戏进程的 Resources，不认识模块 R 值（R 是编译时模块资源表）。不要再试。
- **`PackageManager.getResourcesForApplication("tools.alamobile.mod")` 在游戏进程** [V]——抛 `PackageManager$NameNotFoundException`。Android 11+ 包可见性限制，游戏进程（targetSdk 35）看不到模块包。不要再试（M11 已验证过 ContentProvider / createPackageContext 同样失败）。
- **`setShadowLayer` 画阴影** [V]——`drawRoundRect` 实心填充覆盖阴影（阴影画在填充下面），用户看不到。不要再试。
- **手画偏移阴影层（shadowPaint + drawRect 向下偏移）** [?]——View bounds 裁切超出部分，阴影被裁掉。未深入试就撤回。
- **（前向搬运）** `System.getProperty(MODULE_LOADED_FLAG)` 作 ConfigActivity 激活判定 [V]、daemon `module_loaded` 持久标记 [V]、`PathBuilder` 手动转译 SVG path 含 arc [V]、`path()` DSL 的 `pathData: List<PathNode>` [V]、miuix `primaryContainer`/`primaryVariant` 作已激活底色 [V]、miuix 0.9.3 有 SuperDialog [V]、openRemoteFile 读模块 filesDir [V]、legacy `de.robv.android.xposed.XSharedPreferences` [V]、模块进程写公共 `/sdcard/` [V]、ContentProvider 跨进程 [V]、createPackageContext [V]、5 参 call 重载 [V]、by lazy 只改缓存不够 [V]、applyCurve 作用单字段 [V]、BRAKE 从底向上画水位式 [V]、M12 OverlayEditView 传 settings.*Position 作 defaultPosition [V]、SINGLE/DUAL 共用 pedal_position 字段 [V]、统一公式画两种方向刹车 [V]——均不再试。

## 5. 已知坑

- **LSPosed 注入进程的资源访问** [V]——游戏进程的 Resources / PackageManager 不认识模块包（Android 11+ 包可见性）。读模块资源只能用跨进程方案：base64 嵌入源码（本次）或 Remote Preferences（M14 配置同步）。`R.mipmap.xxx` 在模块进程可用，在游戏进程不可用。
- **adaptive icon 前景 PNG 的 66% 安全区** [V]——`ic_launcher_foreground.png` 整张 144×144，实际内容只占中心 66%，外围 34% 透明 padding。直接铺满圆角矩形会有"一圈黑"。解法：放大 1.5x + clipPath 裁掉外围透明区。
- **横屏 `displayMetrics.heightPixels`** [V]——横屏时返回短边（如 1080×2400 横屏 → heightPixels=1080），不是长边。
- **versionCode 必须用 CLAUDE.md M8 表格锚点反推** [V]——Beta 1=`100210`→Beta 2=`100220`→Beta 3=`100230`。不能凭空算，不能拿 HANDOFF 未来计划当当前号。
- **抄旧内容要独立验证每条事实** [V]——旧 README 日期错（2025→应为 2026），不验证就抄会传播错误。
- **GitHub Release asset name vs label** [V]——`name` 是 URL 文件名（不能含空格，softprops 自动替换成点），`label` 是 UI 显示名。M8 "space-preserving" 指 label。
- **ConfigActivity 进程不被 LSPosed 注入** [V]——`onModuleLoaded` 只在目标 App 进程调。新方案用 `App.xposedService` 绑定状态绕开。
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
- **Canvas 无 `clipRoundRect` 方法** [V]——只有 `clipRect` / `clipPath`。裁圆角矩形用 `Path.addRoundRect` + `canvas.clipPath`。
- **`setShadowLayer` 在实心填充上不可见** [V]——阴影画在填充下面，不透明填充覆盖。画可见阴影需手画偏移层 + 父 ViewGroup `setClipChildren(false)` 让超出 bounds 部分可见。

## 6. 下一步（有序）

1. **提交本次改动**——4 个文件（2 modified + 2 new）未 commit。建议 commit message：`feat: M17 工具按钮改造成圆角矩形 App 图标 + 可拖动`。用户决定是否 commit。
2. **清理废弃 IPC 层**（可选，Beta 2 遗留）——删 `ConfigProvider.kt` + manifest provider 声明。
3. **M16 真机视觉验证**（Beta 2 遗留）——打开 ConfigActivity 概览页确认 GitHub/QQ 图标形状 + 激活卡片两态配色。
4. **后续 Stable 1.0.0**——所有 Beta 闭环 + 真机全过后，versionCode `100300`，versionName `1.0.0`，workflow `prerelease: false`。

## 7. 留给用户的开放问题
- 是否现在 commit 本次工具按钮改动？（4 文件，含 2 个新文件）
- 是否清理废弃的 ConfigProvider.kt + manifest provider 声明？
- 是否把工具按钮改造记为 M17 进入 CLAUDE.md 进度？
- M16 真机视觉验证：GitHub/QQ 图标形状对不对？激活卡片两态配色对不对？
