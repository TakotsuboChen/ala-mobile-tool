# HANDOFF — 读全文再开始干活

生成时间: 2026-08-08T23:20:00+08:00 · Git HEAD: 待提交（8 文件改动待 commit）
分支: `diagnose/vivo-unlock-logging` → 合并到 `main`
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
修复 vivo/iQOO（OriginOS/Android 16）+ NPatch 本地模式下共存版付费解锁失效。**已完成：vivo 云测真机验证解锁成功**，下一步合并到 main + 发 Beta 3。

## 2. 已验证状态 — 工作实际停在哪

- [V] **vivo 云测解锁成功**——vivo 云测设备（OriginOS/Android 16）+ NPatch 本地模式 + 共存版，装上当前 APK 后直接解锁，无弹窗。`ala_native.log` 完整记录：`BillingManager.Awake() hooked` → `force_unlock_direct: SetUnlocked called successfully` + `force_unlock: OnAlreadyOwned called successfully`。
- [V] **Meizu 20（无谷歌账号）解锁成功**——移除谷歌账号模拟 vivo 场景（共存版包名不对，Billing 连不上），装模块后直接解锁。`hook_awake` 在场景加载时触发，`SetUnlocked(true)` 直调成功。
- [V] **闪退根因已定位并修复**——`App.onCreate` 在游戏进程的 `createOrUpdateClassLoaderLocked` 内部同步调用，`XposedServiceHelper.registerListener` + `ContentResolver.call`（bindNpatchRemoteService）干扰 `LoadedApk.getResources()` 初始化 → `makeApplicationInner` 时 `Resources.getAssets()` NPE。修复：游戏进程里 `App.onCreate` 把 service binding 全部 `Handler.post` 延迟到 next main loop。
- [V] **onPackageLoaded/onPackageReady 也需 deferred**——`BillingHook.install`（Class.forName + xposedInterface.hook）和 `ShadowHook.init + forceLoad + initUnlock` 也干扰 Resources 初始化。全部 `Handler.post` 延迟。
- [V] **native 日志双写 NPatch 日志目录**——`unlock_hook.c` 的 `npatch_log()` 同时打 logcat + 写 `/sdcard/Android/media/<pkg>/npatch/log/ala_native.log`。用户用 NPatch 导出日志时这个文件一起带出来，不需要 adb。
- [V] **forceLoad context=null 从 ClassLoader 反射拿 APK 路径**——NPatch 隔离 ClassLoader 下 `System.loadLibrary` 失败，`forceLoad` 改为 context 可空：context=null 时从 `BaseDexClassLoader.pathList.dexElements` 反射拿模块 APK 路径。vivo 上验证成功。
- [V] **BillingManager.GetInstance hook 作为 Awake 兜底**——新增 `hook_get_instance`（RVA 0x186C958），任何代码访问 `BillingManager.Instance` 时触发。`__atomic_test_and_set` once-guard 避免重复调。
- [V] **forceUnlockNow() one-shot 强制解锁**——新增 JNI `forceUnlockNow`，在 15s 延迟路径调 `get_Instance()` 获取单例 → `SetUnlocked(true)`。不依赖 hook 触发时机。实测在 BillingManager 尚未创建时返回 null（正常），靠 `hook_awake` 兜住。
- [V] **SetUnlocked(true) 直调是解锁主路径**——`OnAlreadyOwned(string)` 需要创建 IL2CPP string，手写 string 的 `klass=NULL` 导致 Unity 侧 `string.Equals` 比较 false。`SetUnlocked(true)` 不需要 string，直接设 `IsUnlocked=true` + `PlayerPrefs.SetInt("AnciTuttu",1)` + `OnUnlockedChanged.Invoke()`。
- [V] **dlopen("libil2cpp.so") 在 NPatch 下失败**——NPatch linker namespace 隔离，`dlopen` 用 basename 查不到。`dl_iterate_phdr` 能找到（拿 base 地址成功，hook 装上）。`il2cpp_string_new` 走手写 IL2CPP string 兜底（klass=NULL，OnAlreadyOwned 辅助路径可能无效但不崩）。
- [V] **NPatch 配置同步修复**——manifest 加 `<queries><package android:name="top.nkbe.npatch" />`；`App.bindNpatchRemoteService` 主动从 NPatch 管理器 `content://top.nkbe.npatch.remote` 拿可写 IXposedService binder；`ModConfig.readFromTargetProcess` 在 `remoteJson==null` 时强制 `enableUnlock=true`；`AlaMobileModule` 在 `settings==null` 时 `enableUnlock ?: true`。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL`。
- [V] **8 文件未提交**：`git diff --stat` 确认。

### 测试/build 输出 tail
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 31s
50 actionable tasks: 11 executed, 39 up-to-date
```

## 3. 决策与理由

- **`SetUnlocked(true)` 直调作主路径，`OnAlreadyOwned` 作辅助** [V]——`SetUnlocked` 不需要 IL2CPP string，绕过 `string.Equals` 比较。`OnAlreadyOwned` 的手写 string `klass=NULL` 导致比较失败，但 `SetUnlocked` 已完成解锁。否决：只用 `OnAlreadyOwned`（string 比较失败 = 解锁失败）。
- **`App.onCreate` 在游戏进程 deferred service binding** [V]——`createOrUpdateClassLoaderLocked` 内部同步调 `App.onCreate`，`XposedServiceHelper.registerListener` + `ContentResolver.call` 干扰 Resources 初始化。`Handler.post` 延迟到 next main loop。否决：去掉 `android:name=".App"`（ConfigActivity 进程需要 App 类做 service binding）。
- **`onPackageLoaded` + `onPackageReady` 全部 deferred** [V]——两者都在 `createOrUpdateClassLoaderLocked` 内部同步调用，任何重操作（Class.forName、xposedInterface.hook、ShadowHook.init、JNI）都干扰 Resources。全部 `Handler.post`。否决：只 defer onPackageReady（onPackageLoaded 的 BillingHook.install 也干扰）。
- **native 日志双写文件** [V]——NPatch 日志文件只记 Java 层 `xposedInterface.log()`，不记 native `__android_log_print`。`npatch_log()` 同时打 logcat + 写 `ala_native.log`。否决：让用户抓 adb logcat（小白不可行）。
- **`forceLoad` context 可空 + ClassLoader 反射** [V]——NPatch 隔离 ClassLoader 下 `System.loadLibrary` 失败，context=null 时从 `BaseDexClassLoader.pathList.dexElements` 反射拿模块 APK 路径。否决：硬编码 NPatch 缓存路径（路径随安装变化）。
- **`enableUnlock` 默认 true（配置读不到时）** [V]——NPatch 无 daemon，配置同步断链时 `enableUnlock` 落 false 会跳过 unlock hooks。强制 true 让 native 主动注入在配置断链时仍能跑。否决：让用户手动开开关（NPatch 下配置写不进去 = 永远 false）。

## 4. 失败的尝试 — 不要再试

- **`OnAlreadyOwned` 手写 IL2CPP string（klass=NULL）** [V]——Unity 侧 `string.Equals` 解引用 klass 查方法表，klass=NULL → 比较 false → 不走 `SetUnlocked`。已改用 `SetUnlocked(true)` 直调。
- **`dlopen("libil2cpp.so")` 拿 `il2cpp_string_new`** [V]——NPatch linker namespace 隔离，`dlopen` 用 basename 查不到。`dl_iterate_phdr` 能找到 base 地址（hook 装上），但 `dlopen` 拿不到 handle。手写 string 兜底已实现但 OnAlreadyOwned 路径无效（klass=NULL）。
- **`forceUnlockNow` 在 15s 时调 `get_Instance()`** [V]——BillingManager 在 15s 时还没创建（`Awake()` 在场景加载 ~17s 才触发），`get_Instance()` 返回 null。靠 `hook_awake` 兜住。
- **只 defer `onPackageReady` 不 defer `onPackageLoaded`** [V]——`onPackageLoaded` 里的 `BillingHook.install` 也干扰 Resources 初始化，仍闪退。必须两者都 defer。
- **只 defer `forceLoad+initUnlock` 不 defer `ShadowHook.init`** [V]——`ShadowHook.init` 同步执行也干扰 Resources，仍闪退。必须把 ShadowHook.init 也 deferred。
- **不装模块只装 NPatch 共存版** [V]——不闪退，证明闪退是模块代码导致的，不是共存版/NPatch/Android 16 本身的 bug。
- **（前向搬运）** 假设"vivo 无 GMS 导致计费分支不同"、假设"vivo ART++Turbo AOT 内联杀 Java hook"、假设"Java BillingBridge 类在 vivo 上找不到"、假设"配置 enableUnlock 落 false 是唯一根因"、ShadowHook SHARED 模式、`g_player_controls` 主动读 0x60、`carPilot`(0x68) 作玩家判据、base64 嵌入图标、`decodeResource` 跨进程、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、5 参 call 重载、`by lazy` 只改缓存不够、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、统一公式画两种方向刹车——均不再试。

## 5. 已知坑

- **⚠️ NPatch `App.onCreate` 在游戏进程同步执行** [V]——模块 manifest 声明 `android:name=".App"`，NPatch 在游戏进程实例化模块 Application。`App.onCreate` 在 `createOrUpdateClassLoaderLocked` 内部同步调用，任何重操作（Binder、ContentResolver、JNI）都干扰 Resources 初始化。必须 `Handler.post` 延迟。
- **⚠️ NPatch `onPackageLoaded`/`onPackageReady` 也在 `createOrUpdateClassLoaderLocked` 内部** [V]——同上，必须全部 deferred。`Thread.sleep(500)` 也在 main thread 阻塞 handleBindApplication，移到 deferred block 里。
- **NPatch linker namespace 隔离 `dlopen`** [V]——`dlopen("libil2cpp.so")` 失败，`dl_iterate_phdr` 能找到。`il2cpp_string_new` 不可用，手写 IL2CPP string 兜底（klass=NULL，OnAlreadyOwned 辅助路径无效但不崩）。
- **NPatch 无 daemon，配置同步断链** [V]——`getRemotePreferences: key not found in daemon db`。`App.bindNpatchRemoteService` 主动从 NPatch 管理器 ContentProvider 拿 binder（管理器模式下），embedded/local 模式仍断链 → `enableUnlock` 强制 true 兜底。
- **共存版包名改了，Google Play Billing 校验不通过** [V]——有谷歌账号时游戏走"计费不可用 → 自动解锁"兜底；无谷歌账号时锁着。模块的 `SetUnlocked(true)` 对无谷歌账号场景有效。
- **`BillingManager` 是 C# IL2CPP 类不在 dex** [V]——`Class.forName("BillingManager")` 必失败。已用独立 try/catch 隔离。
- **ShadowHook SHARED 模式在 pairip 壳下不可用** [V]——必须用 UNIQUE。
- **油门＞0 时 AI 车被误控** [V]——M18 遗留 bug，本次未修。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 必须用 CLAUDE.md M8 表格锚点反推** [V]——Beta 2=`100220`→Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。
- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **ConfigProvider.kt 已废弃** [?]——待清理。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **NPatch `references/` 克隆的 Gradle 文件会干扰主项目 build** [V]——已删除 settings.gradle.kts + build.gradle.kts + gradle/ 目录。

## 6. 下一步（有序）

1. **合并 `diagnose/vivo-unlock-logging` 到 `main`**——当前分支 8 文件改动，commit + push + merge to main。
2. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）。CI workflow `prerelease: true`（Beta 阶段）。
3. **修复 AI 车误控 bug**（M18 遗留）——油门＞0 时所有 AI 车被模块踏板控制。
4. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明。
5. **后续 Stable 1.0.0**——versionCode `100300`，workflow `prerelease: false`。

## 7. 留给用户的开放问题
- vivo 上 `checkOwned` 不触发的深层原因（`BillingClient.startConnection` 是否在 vivo 上失败？）——不修也行，`SetUnlocked(true)` 直调已绕过。
- 是否把诊断日志（`xposedInterface.log()` + `logX` + `npatch_log`）保留在正式版？还是发 release 前去掉？
- `references/NPatch` 已 clone 到本地（`.gitignore` 的 `references/` 规则覆盖），需要保留供后续查阅 NPatch 源码。
- native 日志 `ala_native.log` 是否需要在游戏退出时自动清理？还是一直追加？