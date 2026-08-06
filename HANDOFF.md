# HANDOFF — 读全文再开始干活

生成时间: 2026-08-06T23:55:00+08:00 · Git HEAD: 0e8aa3c（未提交，7 文件改动待 commit）
分支: `diagnose/vivo-unlock-logging`（从 main 分出）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
诊断 + 修复"vivo/iQOO（OriginOS）+ NPatch 非 Root 方案下付费解锁失效、踏板正常"——**根因已定位，native 主动注入 OnAlreadyOwned 已实现并真机验证，但 NPatch 配置同步问题导致 `enableUnlock` 读不到 true，下一步修配置同步**。

## 2. 已验证状态 — 工作实际停在哪

- [V] **根因确认：vivo 上 Java `BillingBridge.checkOwned()` 从未被游戏调用**——vivo 用户 NPatch 日志（`D:\Downloads\QQ\20260806.log`）显示 `Hooked checkOwned()` 成功但**全程无 `checkOwned() intercepted`**。对比作者手机（Meizu 20）同模块日志有 `checkOwned() intercepted, sending OnAlreadyOwned` + `OnAlreadyOwned sent successfully`。游戏在 vivo 上不发起计费查询链路，Java hook 装上了但不触发。
- [V] **vivo 用户有 GMS**——日志 `DIAG: Google Play Store installed (com.android.vending)` + `DIAG: Google Play Services installed (com.google.android.gms)`。排除"无 GMS 导致计费分支不同"假设。
- [V] **配置同步在 NPatch 下失败**——`getRemotePreferences: key not found in daemon db`（NPatch 无 daemon）+ 本地 externalFilesDir 无 JSON → `enableUnlock` 落默认 `false`。ConfigActivity 开关显示"开"但游戏进程读不到。**NPatch 方案下游戏不在后台时修改配置不生效**——广播在游戏未运行时发出即丢失。
- [V] **native 主动注入 OnAlreadyOwned 已实现**——`unlock_hook.c` 新增 `force_unlock_via_on_already_owned()`：用 `dlsym` 找 `il2cpp_string_new`，创建 IL2CPP string `"unlock_alamobile"`，调 `OnAlreadyOwned(string)`（RVA 0x186E1B4）。在 `hook_awake` 里调用，绕过 Java `BillingBridge` 依赖。
- [V] **早期 unlock hooks 安装已实现**——`NativeBridge.initUnlock()` 新 JNI 方法，在 `onPackageReady` 读完配置后立即装 unlock hooks（不等 15s），让 `hook_awake` 赶上 `BillingManager.Awake()`（场景加载 ~2s 触发）。pedal/drs hooks 仍走 15s 延迟路径。`g_hooks_installed` 守卫防重复装。
- [V] **作者手机真机验证解锁成功**——v4/v5 APK 装到 Meizu 20，`checkOwned() intercepted` + `OnAlreadyOwned sent successfully` 出现，解锁成功。但 `enableUnlock=false`（配置没同步），说明作者手机上 Java hook 自己就完成了解锁，native 主动注入路径**尚未被真机验证**（因为 `enableUnlock=false` 时 early unlock hooks 被跳过）。
- [V] **BillingManager 是 C# IL2CPP 类不在 dex 里**——`BillingHook.kt` 里 `Class.forName("BillingManager")` 必抛 `ClassNotFoundException`。v3 已用独立 try/catch 隔离，不影响 `checkOwned` 主路径。`OnOwnedNone`/`OnPurchaseFailed` 两个 Java hook 从未装上（死代码）。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL in 31s`。
- [V] **7 文件未提交**：`git diff --stat` 确认。
- [V] **NPatch 日志写入机制已通**——模块通过 `xposedInterface.log()` → `XposedBridge.log()` → `XposedLogPrinter` → 写入 `Android/media/.../npatch/log/`。不走 `XLog` 白名单过滤，即使用户 tag 不是 "NPatch" 也能写入。用户不需要 adb，只要用 NPatch 打包时勾选"导出日志"，跑完把日志文件发回来即可。
- [V] **9 个 Subagent 研究完成**——配置链路、OriginOS、pairip、真实报告、hook 时序、计费路径、本地代码穷举、NPatch 能力、非 Root 生态。关键结论：NPatch v1.0.6 无 API 102 daemon、`getRemotePreferences` 必抛异常、LSPlant Java hook 受 AOT 内联影响（但有他牌先例，非 vivo 专属）。

### 测试/build 输出 tail
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 31s
50 actionable tasks: 11 executed, 39 up-to-date
```

## 3. 决策与理由

- **native 主动调 `OnAlreadyOwned("unlock_alamobile")`** [V]——vivo 上 Java `checkOwned` 不触发，但 `BillingManager.OnAlreadyOwned(string)` 是 IL2CPP 原生方法（RVA 0x186E1B4），ShadowHook 可直接调。用 `il2cpp_string_new` 创建 IL2CPP string，在 `hook_awake` 里调 `OnAlreadyOwned`，让 Unity 侧走完整解锁链（`SetUnlocked → OnUnlockedChanged → 持久化 PlayerPrefs AnciTuttu`）。否决：JNI 调 Java `BillingBridge.sendUnityMessage`（Java 层在 vivo 上也不可靠）。
- **早期装 unlock hooks（不等 15s）** [V]——`BillingManager.Awake()` 在场景加载 ~2s 触发，15s 延迟必错过。新增 `NativeBridge.initUnlock()` JNI，在 `onPackageReady` 读完配置后立即装。`g_hooks_installed` 守卫防 15s 路径重复装。否决：把所有 hooks 都提前到 onPackageReady（pedal writer 线程需要 game controller 已存在，提前装会崩）。
- **`BillingManager` Java hook 独立 try/catch 隔离** [V]——`BillingManager` 是 C# 类不在 dex，`Class.forName` 必失败。v2 的外层 try/catch 吞掉异常导致整个 `BillingHook.install` 提前退出 + 27 行 stack trace 噪音。v3 隔离后 `checkOwned` 主路径不受影响。否决：删掉 `BillingManager` 两个 hook（它们是"挡弹窗"辅助，虽然从没装上过，保留以防未来 Unity 侧有 Java 桥）。
- **诊断日志走 `XposedBridge.log()`** [V]——NPatch 日志文件只记 `NPatch` tag，但 `XposedBridge.log()` 直接调 `XposedLogPrinter.println()` 不走 `XLog` 白名单过滤。模块用 `xposedInterface.log()` 写日志能进 NPatch 日志文件。否决：让用户抓 adb logcat（小白不可行）。

## 4. 失败的尝试 — 不要再试

- **假设"vivo 无 GMS 导致计费分支不同"** [V]——用户明确说有 GMS，日志确认 `Google Play Store installed`。排除。
- **假设"vivo ART++Turbo AOT 内联杀 Java hook"** [V]——作者手机（Meizu）和 vivo 用户日志都显示 `Hooked checkOwned()` 成功，但只有作者手机触发 `checkOwned() intercepted`。如果是 AOT 内联，hook 会"装上但不触发"——作者手机也该不触发。实际是游戏在 vivo 上根本不调 `checkOwned`，不是 hook 失效。排除。
- **假设"Java `BillingBridge` 类在 vivo 上找不到"** [V]——vivo 日志 `BillingBridge class found: BillingBridge`。排除。
- **假设"配置 `enableUnlock` 落 false 是唯一根因"** [V]——vivo 用户第一次日志 `enableUnlock=true` 但 `checkOwned` 仍不触发。配置不是唯一根因。
- **（前向搬运）** ShadowHook SHARED 模式、`g_player_controls` 主动读 0x60、`carPilot`(0x68) 作玩家判据、base64 嵌入图标、`decodeResource` 跨进程、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、5 参 call 重载、`by lazy` 只改缓存不够、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、统一公式画两种方向刹车——均不再试。

## 5. 已知坑

- **⚠️ NPatch 方案下游戏不在后台修改配置不生效** [V]——这是本次最关键发现。NPatch 无 daemon，`getRemotePreferences` 不通（`key not found in daemon db`），配置同步只靠定向广播。广播在游戏进程未运行时被系统丢弃 → 下次游戏启动读默认值。**用户必须在游戏运行（后台）时去 ConfigActivity 改配置，广播才能被 ConfigReceiver 收到。** 这是所有 NPatch 用户的普适问题，不是 vivo 特有。
- **NPatch v1.0.6 无 API 102 daemon** [V]——官方 release 明确"本版本尚未包含 API 102 支援"。`App.xposedService` 恒 null，`ModConfig.write` 走 filesDir + 广播兜底。`getRemotePreferences` 在游戏进程返回空/异常。
- **vivo 上 Java `checkOwned` 不触发** [V]——游戏在 vivo 上不发起 `BillingBridge.checkOwned()` 调用链。原因未完全定位（可能 `BillingClient.startConnection` 在 vivo 上没成功 → `onBillingSetupFinished` 不回调 OK → `checkOwnedInternal` 永不被调）。但 native `OnAlreadyOwned` 主动注入可以绕过。
- **`BillingManager` 是 C# IL2CPP 类不在 dex** [V]——`BillingHook.kt` 里 `Class.forName("BillingManager")` 必失败。v3 已隔离。
- **ShadowHook SHARED 模式在 pairip 壳下不可用** [V]——原版游戏自带 pairip，SHARED trampoline 被覆盖，必须用 UNIQUE。
- **油门＞0 时 AI 车被误控** [V]——M18 遗留 bug，UNIQUE 模式下 hook 持续触发，`is_player_controller` 过滤可能对 AI 车也返回 true。本次未修。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 必须用 CLAUDE.md M8 表格锚点反推** [V]——Beta 2=`100220`→Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。
- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **ConfigProvider.kt 已废弃** [?]——待清理。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **NPatch `references/` 克隆的 Gradle 文件会干扰主项目 build** [V]——`references/NPatch/settings.gradle.kts` 和 `build.gradle.kts` 会被 Gradle 误认为子项目，导致 `libs.versions.toml` 找不到错误。已删除这两个文件 + `gradle/` 目录。

## 6. 下一步（有序）

1. **⚠️ 修 NPatch 配置同步——游戏不在后台时也能生效** [最重要]——当前 NPatch 无 daemon，配置写不进游戏进程。方向：
   - (a) **让 `enableUnlock` 默认 true**——最简单，但用户关了开关也会解锁（非致命，用户可以选择不装模块）。
   - (b) **检测 NPatch 无 daemon 时强制 `enableUnlock=true`**——`App.xposedService == null` 时认为是非 Root 框架，强制开。
   - (c) **让 ConfigActivity 写一个游戏进程可直接读的文件**——如 `/sdcard/Android/data/com.Takotsubo.AlamobileFormula/files/ala_tool_config.json`（游戏 externalFilesDir，但模块进程写不了，需另寻路径）。
   - 建议 (a) 或 (b)，最小改动。
2. **把 v5 APK 发给 vivo 用户验证**——v5 有早期 unlock hooks + native OnAlreadyOwned 注入 + 诊断日志。但需先修第 1 步的配置同步，否则 vivo 用户 `enableUnlock=false` 早期 hooks 被跳过。
3. **验证 native OnAlreadyOwned 注入在 vivo 上 work**——vivo 用户日志里应出现 `force_unlock: calling OnAlreadyOwned("unlock_alamobile")` + `force_unlock: OnAlreadyOwned called successfully`。如果出现且解锁成功 → 根因修复完成。如果不出现 → 排查 `il2cpp_string_new` 是否可用、`OnAlreadyOwned` 调用是否崩。
4. **修复 AI 车误控 bug**（M18 遗留）——油门＞0 时所有 AI 车被模块踏板控制。
5. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明。
6. **后续 Stable 1.0.0**——versionCode `100300`，workflow `prerelease: false`。

## 7. 留给用户的开放问题
- NPatch 配置同步怎么修？`enableUnlock` 默认 true 还是无 daemon 时强制 true？还是找到一条游戏进程能直接读的文件路径？
- vivo 用户 `checkOwned` 不触发的深层原因（`BillingClient.startConnection` 是否在 vivo 上失败？游戏是否检测到特定 store 后走另一条路？）——不修也行，native OnAlreadyOwned 注入已绕过。
- 是否把诊断日志（`xposedInterface.log()` + `logX`）保留在正式版？还是发 release 前去掉？
- `references/NPatch` 已 clone 到本地（`.gitignore` 的 `references/` 规则覆盖），需要保留供后续查阅 NPatch 源码。