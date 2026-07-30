# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T22:05:00+08:00 · Git HEAD: 即将 commit
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
本次切片完成：概览页激活状态卡片 UI 重做——照搬 KernelSU HomeMiuix StatusCard 的对称强调底配色（已激活绿色调、未激活红色调）+ 弹窗改用 miuix 官方 OverlayDialog。**真机验证通过**。下一步：修 LSPosed 真激活检测逻辑（当前实现根本检测不到 LSPosed 激活，见"已知坑"）。

## 2. 已验证状态 — 工作实际停在哪

- [V] **激活卡片 UI 真机验证通过**：用户原话"这次对了"。配色对称——已激活深色 `#1A3825`（暗绿）/ 浅色 `#DFFAE4`（浅绿）+ 绿勾 `#36D167`；未激活深色 `#3D1A1A`（暗红）/ 浅色 `#FAE4E4`（浅红）+ 红叉 `#FF5252`。两态都是强调底，文字深色模式白、浅色模式用主题色。
- [V] **弹窗改用 miuix 官方 OverlayDialog**：照搬 `references/miuix/example/shared/.../component/CardSection.kt` 的 `LongPressHoldDownCardDemo` dialog 写法——`OverlayDialog(show, title, onDismissRequest, content)` + 两个 `TextButton`，"是"用 `ButtonDefaults.textButtonColorsPrimary()`。不再自己拼 `androidx.compose.ui.window.Dialog` + Card。
- [V] **build + lint 全绿**：`./gradlew :app:assembleDebug :app:lint` BUILD SUCCESSFUL。
- [V] **references/miuix 已克隆到本地**：浅克隆 `git clone --depth 1`，在 `.gitignore` 加 `references/` 规则，不入库。下次查 miuix 用法直接看 `references/miuix/`。
- [?] **LSPosed 真激活检测未实现**（下一个切片的目标）：当前 `LsposedStatus.evaluate` 靠 `System.getProperty(MODULE_LOADED_FLAG)`——但模块 APK 自己的进程**不会被 LSPosed 注入**，`onModuleLoaded` 只在目标 App（游戏）进程被注入时才调。ConfigActivity 跑在模块进程，永远读不到这个 property → 永远显示未激活。用户已确认这个 bug，说"LSPosed 检测下个会话修"。

### 测试/build 输出 tail（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 1s
50 actionable tasks: 3 executed, 47 up-to-date

$ adb -s 381QYFCN22B9A install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install / Success

# 用户原话："这次对了，LSPosed 检测下个会话修"
```

## 3. 决策与理由

- **配色照搬 KernelSU HomeMiuix StatusCard** [V]——用户明确指向 `https://github.com/tiann/KernelSU/blob/main/manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt`。已激活用 `#1A3825`/`#DFFAE4` + 绿勾 `#36D167`（KernelSU 原值）。否决方案：miuix `primaryVariant`（默认是蓝 `#3482FF`，和绿勾不搭）；miuix `primaryContainer`（Material3 命名，miuix 示例没用）；miuix 默认 surface（深色模式接近纯黑，用户反馈"纯黑底"）。**关键教训**：本项目原代码本来就在照搬 KernelSU，我一度自作主张改成 miuix 语义色 token，破坏了原配色——用户纠正后恢复。
- **未激活也用强调底（红色调）** [V]——用户要求"未激活模仿已激活，深色暗红底，浅色浅红底"。KernelSU 原版只有"已激活"用强调底，"未安装/不支持"用默认 Card；本项目的扩展是让未激活也强调，绿红对称。
- **弹窗用 miuix OverlayDialog** [V]——否决方案：`androidx.compose.ui.window.Dialog` + 自己拼 miuix Card/Text/clickable（我之前这么做，既不标准又风格不统一）。OverlayDialog 是 miuix 0.9.3 自带（`top.yukonga.miuix.kmp.overlay.OverlayDialog`），参数极简（show/title/summary/onDismissRequest/onDismissFinished/content），内部自动处理 scrim/动画/布局。
- **references/ 仓库不入库** [V]——`.gitignore` 加 `references/` 规则，miuix 浅克隆只作本地阅读参考，不污染本仓库。
- **LSPosed 检测的设计盲区**（下个切片要解决） [?]——`onModuleLoaded` 只在目标 App 进程被注入时调，模块 APK 自己的 ConfigActivity 进程不会被注入。所以"当前进程本次启动是否被加载"这个判定基准**对 ConfigActivity 不可用**。用户之前在 AskUserQuestion 里选过"当前进程本次启动是否被加载"，但真机验证证明这个基准无法反映 ConfigActivity 进程的 LSPosed 激活态。下个切片要换判定基准（候选：daemon 持久标记 + 启动时清除让 onModuleLoaded 重写，或读 LSPosed Manager 状态）。

## 4. 失败的尝试 — 不要再试

- **改用 miuix `primaryContainer` 作已激活底色** [V]——miuix 色板默认 `primary=#3482FF`（蓝），`primaryContainer=#5D9BFF`（蓝），和绿勾 `#36D167` 不搭。用户反馈"还是纯黑底"。
- **改用 miuix `primaryVariant` 作已激活底色** [V]——同样蓝色系，不搭绿勾。`primaryVariant` 在浅色模式 `#3482FF`，深色模式大概率深蓝。
- **未激活用默认 Card（不传 colors，走 surface）** [V]——miuix `surface` 浅色 `#F7F7F7`、深色模式接近纯黑。深色模式下未激活卡片显得"纯黑底"，用户反馈"还是纯黑底"。已改：未激活也传 `CardDefaults.defaultColors(color = 红色调硬编码)`。
- **自己用 `androidx.compose.ui.window.Dialog` + miuix Card 拼 NonRootConfirmDialog** [V]——非 miuix 标准做法，已换成官方 `OverlayDialog` + `TextButton`。
- **miuix 0.9.3 有 SuperDialog** [?]（前向搬运，已证伪）——AAR 里 `unzip -l` 查无 `SuperDialog` 类，只有底层 `WindowDialog`/`OverlayDialog`。SuperDialog 是 miuix 1.0+ API。
- **openRemoteFile 读模块 filesDir** [V]（前向搬运）——ConfigActivity 写 `context.filesDir`，openRemoteFile 读 daemon 目录，两个独立存储。已用 Remote Preferences 替代。
- **legacy `de.robv.android.xposed.XSharedPreferences`** [V]（前向搬运）——libxposed API 102 禁止，LSPosed v2.1.0 移除兼容层。
- **模块进程写公共 `/sdcard/AlaMobileTool/`** [V]、**游戏进程读公共 `/sdcard/`** [V]、**ContentProvider.call 跨进程** [V]、**createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY)** [V]、**游戏进程直读模块私有文件** [V]、**M11 手写 SDK_INT 分支注册 receiver** [V]、**ConfigReceiver 直接 writeText 覆盖** [V]、**root 保持 val 不刷新** [V]、**文件直读跨进程** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]、**统一公式画两种方向刹车** [V]——均不再试。

## 5. 已知坑

- **ConfigActivity 进程不被 LSPosed 注入** [V]（本次新增，最关键）——`AlaMobileModule.onModuleLoaded` 由 libxposed 框架在目标 App 进程被注入时调用，模块 APK 自己启动的 ConfigActivity 进程不会被注入。所以 `System.getProperty(MODULE_LOADED_FLAG)` 在 ConfigActivity 里永远读不到。下个切片必须换检测策略。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。本项目配色跟 KernelSU（绿调），不要用 miuix 语义色 token 作已激活底，要硬编码 KernelSU 的绿值。
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
- **XposedServiceHelper 异步绑定** [V]——ConfigActivity.onCreate 时 `App.xposedService` 可能仍 null，ModConfig.write 走 fallback。
- **libxposed-service 依赖已就位** [V]——`implementation(libs.libxposed.service)` version 102.0.0。

## 6. 下一步（有序）

1. **修 LSPosed 真激活检测**（本次切片的遗留目标）——当前 `System.getProperty(MODULE_LOADED_FLAG)` 方案对 ConfigActivity 进程不可用。候选方案（按优先级）：
   (a) **daemon 持久标记 + 启动清除**：`onModuleLoaded` 写 daemon `module_loaded="1"`；ConfigActivity 启动时**先清** daemon `module_loaded`，然后短轮询等几秒看是否被重写（若 LSPosed 仍启用模块，框架会在注入 ConfigActivity 进程时调 onModuleLoaded 重写）。但时序坑：onModuleLoaded 可能晚于读检测，需轮询窗口。
   (b) **读 LSPosed Manager 状态**：查 LSPosed Manager 是否启用本模块（需 LSPosed Manager 暴露的 API/Provider，可能不存在）。
   (c) **回退到"曾在目标进程跑过"语义**：daemon `module_loaded` 持久化，ConfigActivity 读它，用户在 LSPosed Manager 关模块后手动"清除激活标记"。最不严格但最简单。
   下次会话先试 (a)。
2. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明。
3. **发 Beta 3**——versionName `1.0.0 Beta 3`，versionCode `100230`。M14 四件事（方向键修复 + 刹车方向反转 + 配置同步迁移 + 激活检测修复）全闭环后发。

## 7. 留给用户的开放问题

- LSPosed 真激活检测用哪个方案（见下一步候选）？需要用户确认语义偏好（"当前激活"严格 vs "曾激活过"宽松）。
- 是否现在清理废弃的 ConfigProvider.kt + manifest provider 声明？
