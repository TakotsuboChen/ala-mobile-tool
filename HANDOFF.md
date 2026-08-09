# HANDOFF — 读全文再开始干活

生成时间: 2026-08-09T23:57:00+08:00 · Git HEAD: 最近提交 `42a8810`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `42a8810` (feat: M19 vivo/OriginOS/Android 16 + NPatch 本地模式解锁修复)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `42a8810`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。若 `git status` 有未推送提交，以 git 实际输出为准。
- 先读: `CLAUDE.md` M19 条目 + 本文件。

## 1. 当前目标
M19（vivo/OriginOS/Android 16 + NPatch 本地模式付费解锁修复）已完成、真机验证通过、并已合并到 `main`。**下一目标：发 Beta 3（versionCode `100230`，versionName `1.0.0 Beta 3`）。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **M19 全部工作已 commit 并合并到 main**——`git rev-parse main diagnose/vivo-unlock-logging origin/main origin/diagnose/vivo-unlock-logging` 输出同 SHA `42a8810`（1 个唯一值）。当前分支 `main`，与 `origin/main` 同步，工作区 clean。
- [V] **vivo 云测解锁成功**——vivo 云测设备（OriginOS/Android 16）+ NPatch 本地模式 + 共存版，装当前 APK 直接解锁。`ala_native.log`：`BillingManager.Awake() hooked` → `force_unlock_direct: SetUnlocked called successfully` + `force_unlock: OnAlreadyOwned called successfully`。
- [V] **Meizu 20（无谷歌账号）解锁成功**——移除谷歌账号模拟 vivo 场景，装模块直接解锁；`hook_awake` 场景加载触发，`SetUnlocked(true)` 直调成功。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `EXIT=0`，`BUILD SUCCESSFUL`（50 tasks: 3 executed, 47 up-to-date）。
- [V] **`SetUnlocked(true)` 直调是解锁主路径**——`OnAlreadyOwned(string)` 需创建 IL2CPP string，手写 string `klass=NULL` → `string.Equals` 比较 false。`SetUnlocked(true)`（RVA 0x186E440）不需要 string，直接 `IsUnlocked=true` + `PlayerPrefs.SetInt("AnciTuttu",1)` + `OnUnlockedChanged.Invoke()`。
- [V] **`App.onCreate` 游戏进程 service binding 已 deferred**——在 `createOrUpdateClassLoaderLocked` 内部同步调用时，`XposedServiceHelper.registerListener` + `ContentResolver.call`（bindNpatchRemoteService）干扰 `LoadedApk.getResources()` → `makeApplicationInner` NPE 闪退。`Handler.post` 延迟到 next main loop。`onPackageLoaded`（BillingHook.install）+ `onPackageReady`（ShadowHook.init + forceLoad + initUnlock）也全 deferred。
- [V] **NPatch 配置同步已修**——manifest 加 `<queries>` 声明 `top.nkbe.npatch`；`App.bindNpatchRemoteService` 主动从 NPatch 管理器 `content://top.nkbe.npatch.remote` 拿 binder；`ModConfig.readFromTargetProcess` 在 `remoteJson==null` 时强制 `enableUnlock=true`；`AlaMobileModule` 在 `settings==null` 时 `enableUnlock ?: true`。

### build 输出（本次交接 run 真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
EXIT=0
BUILD SUCCESSFUL
50 actionable tasks: 3 executed, 47 up-to-date
```

## 3. 决策与理由
- **`SetUnlocked(true)` 直调作主路径，`OnAlreadyOwned` 作辅助** [V]——不需要 IL2CPP string，绕过 `string.Equals`。否决：只用 `OnAlreadyOwned`（string 比较失败 = 解锁失败）。
- **`App.onCreate` 游戏进程 deferred service binding** [V]——干扰 Resources 初始化。`Handler.post` 延迟。否决：去掉 `android:name=".App"`（ConfigActivity 进程需要）。
- **`onPackageLoaded` + `onPackageReady` 全部 deferred** [V]——都在 `createOrUpdateClassLoaderLocked` 内部，任何重操作都干扰 Resources。否决：只 defer onPackageReady。
- **`forceLoad` context 可空 + ClassLoader 反射** [V]——NPatch 隔离 ClassLoader 下 `System.loadLibrary` 失败，context=null 时从 `BaseDexClassLoader.pathList.dexElements` 反射拿模块 APK 路径。否决：硬编码 NPatch 缓存路径。
- **`enableUnlock` 默认 true（配置读不到时）** [V]——NPatch 无 daemon 配置断链，强制 true 让 native 主动注入仍能跑。否决：让用户手动开开关。
- **native 日志双写 `ala_native.log`** [V]——NPatch 日志文件只记 Java 层，不记 native。`npatch_log()` 同时打 logcat + 写 `/sdcard/Android/media/<pkg>/npatch/log/ala_native.log`。否决：让用户抓 adb logcat。

## 4. 失败的尝试 — 不要再试
- **`OnAlreadyOwned` 手写 IL2CPP string（klass=NULL）** [V]——`string.Equals` 解引用 klass，比较 false。已改用 `SetUnlocked(true)` 直调。
- **`dlopen("libil2cpp.so")` 拿 `il2cpp_string_new`** [V]——NPatch linker namespace 隔离查不到。`dl_iterate_phdr` 能找到 base（hook 装上），但 dlopen 拿不到 handle。
- **`forceUnlockNow` 在 15s 时调 `get_Instance()`** [V]——BillingManager 场景加载 ~17s 才创建，返回 null。靠 `hook_awake` 兜住。
- **只 defer `onPackageReady` 不 defer `onPackageLoaded`** [V]——`BillingHook.install` 也干扰 Resources，仍闪退。
- **只 defer `forceLoad+initUnlock` 不 defer `ShadowHook.init`** [V]——ShadowHook.init 同步也干扰，仍闪退。
- **不装模块只装 NPatch 共存版** [V]——不闪退，证明闪退是模块代码导致。
- **（前向搬运）** 假设"vivo 无 GMS 计费分支不同"、假设"vivo ART++Turbo AOT 内联杀 Java hook"、假设"Java BillingBridge 类 vivo 上找不到"、假设"配置 enableUnlock 落 false 是唯一根因"、ShadowHook SHARED 模式、`g_player_controls` 主动读 0x60、`carPilot`(0x68) 作玩家判据、base64 嵌入图标、`decodeResource` 跨进程、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、5 参 call 重载、`by lazy` 只改缓存、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、统一公式画两种方向刹车——均不再试。

## 5. 已知坑
- **⚠️ NPatch `App.onCreate` 游戏进程同步执行** [V]——模块 manifest `android:name=".App"`，任何重操作（Binder/ContentResolver/JNI）干扰 Resources 初始化，必须 `Handler.post` 延迟。
- **⚠️ NPatch `onPackageLoaded`/`onPackageReady` 也在 `createOrUpdateClassLoaderLocked` 内部** [V]——必须全 deferred。`Thread.sleep(500)` 也在 main thread 阻塞 handleBindApplication，移到 deferred block。
- **NPatch linker namespace 隔离 `dlopen`** [V]——`dlopen` 失败、`dl_iterate_phdr` 能找到。`il2cpp_string_new` 不可用。
- **NPatch 无 daemon，配置同步断链** [V]——embedded/local 模式仍断链 → `enableUnlock` 强制 true 兜底。
- **共存版包名改了，Google Play Billing 校验不通过** [V]——有谷歌账号走"计费不可用 → 自动解锁"；无谷歌账号锁着，模块 `SetUnlocked(true)` 有效。
- **`BillingManager` 是 C# IL2CPP 类不在 dex** [V]——`Class.forName("BillingManager")` 必失败，独立 try/catch 隔离。
- **ShadowHook SHARED 模式在 pairip 壳下不可用** [V]——必须用 UNIQUE。
- **油门＞0 时 AI 车被误控** [V]——M18 遗留 bug，本次未修。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 2=`100220`→Beta 3=`100230`，Stable 1.0.0=`100300`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。
- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **ConfigProvider.kt 已废弃** [?]——待清理。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **NPatch `references/` 克隆的 Gradle 文件干扰主项目 build** [V]——已删 settings.gradle.kts + build.gradle.kts + gradle/。

## 6. 下一步（有序）
1. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史，README 需补 M17/M18/M19 版本说明）。CI workflow `prerelease: true`（Beta 阶段），tag `v1.0.0-Beta-3` 触发。
2. **修复 AI 车误控 bug**（M18 遗留）——油门＞0 时所有 AI 车被模块踏板控制。怀疑 `is_player_controller`（读 `IRDSCarControllInput.playerControls` 0x108）对 AI 车也返回 true，或 `proxy_fixed_update`/writer 写到 AI controller。
3. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明。
4. **后续 Stable 1.0.0**——versionCode `100300`，workflow `prerelease: false`。

## 7. 留给用户的开放问题
- vivo 上 `checkOwned` 不触发的深层原因（`BillingClient.startConnection` 是否在 vivo 上失败？）——不修也行，`SetUnlocked(true)` 直调已绕过。
- 诊断日志（`xposedInterface.log()` + `logX` + `npatch_log`）保留还是发 release 前去掉？
- `references/NPatch` 已 clone（`.gitignore` `references/` 规则覆盖），保留供查阅 NPatch 源码。
- native 日志 `ala_native.log` 游戏退出时自动清理，还是一直追加？
