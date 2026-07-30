# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T21:05:50+08:00 · Git HEAD: 即将 commit
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
本次切片完成：M14-B 配置同步迁移改用 **Remote Preferences**（libxposed API 102 的 `getRemotePreferences`），根治"游戏没运行→广播丢失→下次启动读旧值"的 M11 首次滞后 bug，真机验证通过。**下一步**：清理废弃的 ConfigProvider + manifest provider 声明，然后发 Beta 3（versionName `1.0.0 Beta 3`，versionCode `100230`）。

## 2. 已验证状态 — 工作实际停在哪

- [V] **Remote Preferences 路线真机验证通过**：用户从单踏板改双踏板，启动游戏直接读到 DUAL。logcat 证据：`getRemotePreferences ok, len=393` + `Config via remote prefs (merged local position): pedalMode=DUAL`（21:01:41）。对比上次 openRemoteFile 路线全是 `failed: must not be null` + `Config via local fallback`，这次读取端完全走通 daemon SQLite。
- [V] **build + lint 全绿**：`./gradlew :app:assembleDebug :app:lint` BUILD SUCCESSFUL in 13s。
- [V] **openRemoteFile 路线已证伪**（前向搬运）：logcat `openRemoteFile failed: openRemoteFile(...) must not be null` + `listRemoteFiles:` 空——ConfigActivity 写 `context.filesDir`（`/data/data/<pkg>/files/`），但 openRemoteFile 读 daemon 目录（`/data/adb/lspd/modules/<userId>/<pkg>/files/`），两个完全独立的存储。写入端没改，读取端必然读不到。已删除 reader 注入里的 openRemoteFile 调用，改成 getRemotePreferences。
- [V] **方向键修复（M14-A）真机验证通过**（前向搬运）。
- [V] **刹车方向反转开关真机验证通过**（前向搬运）。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 13s

# logcat（真机，21:01:41）：
# 21:01:41.336  getRemotePreferences ok, len=393
# 21:01:41.336  readFromTargetProcess: remote prefs ok len=393
# 21:01:41.348  Config via remote prefs (merged local position): pedalMode=DUAL
# 21:01:41.362  Overlay shown
# 21:01:41.374  Native hooks installed (isAvailable=true)

# 用户原话："可以了，我从单踏板改到双踏板，进去就是双踏板"
```

## 3. 决策与理由

- **Remote Preferences 而非 openRemoteFile** [V]——openRemoteFile 路线证伪后，Agent 查证 libxposed API 102 禁止调用 legacy `de.robv.android.xposed.XSharedPreferences`（XposedInterface.java 第 42 行注释明确），且 LSPosed v2.1.0 已移除 "New XSharedPreferences" 兼容层。唯一可行的是 libxposed 102 原生 `getRemotePreferences`（daemon SQLite 中介）或 `openRemoteFile`（daemon 文件）。选 Remote Preferences：支持 OnSharedPreferenceChangeListener（配置变更自动推送，连广播都能删）、复合对象用 `putString("config_json", JSON)` 一行存、改动量小。否决方案：openRemoteFile——不支持 change listener、需手动管理文件 I/O，且写入端要额外经 `XposedService.openRemoteFile` 写 daemon 目录（比 Remote Preferences 多一层 PFD 操作）。
- **写入端经 App.xposedService** [V]——ConfigActivity（模块进程）不是 XposedModule，调不到 `getRemotePreferences`。新增 `App : Application, XposedServiceHelper.OnServiceListener`，在 onCreate 注册 listener，onServiceBind 拿到 `XposedService` 存静态变量。ModConfig.write 优先用 `service.getRemotePreferences(PREF_GROUP).edit().putString(KEY_CONFIG_JSON, json).apply()`。否决方案：让 ConfigActivity 自己连 service——Application 是更早的注入点，保证 service 尽早绑定。
- **异步绑定 fallback 保留广播** [V]——XposedServiceHelper.registerListener 异步，ConfigActivity.onCreate 时 `App.xposedService` 可能仍 null。ModConfig.write 检测 service 为 null 时走原 filesDir + 广播兜底（零回归）。运行时配置变更即时推送仍靠广播（ConfigReceiver + notifyConfigChanged），service 只解决"启动时读陈旧值"。
- **position 仍走本地 externalFilesDir** [V]——daemon 的 JSON 不含 position（ConfigActivity 不管 position，position 是游戏进程拖拽时 saveOverlayPosition 写本地）。readFromTargetProcess 读到 remote JSON 后用 `mergePositionFromLocal` 合并本地 externalFilesDir 的 4 个 position 字段。否决方案：position 也写 daemon——拖拽频繁写经 Binder 到 daemon 开销大，且 daemon 要回写模块侧不自然。
- **reader 注入复用 remoteConfigReader 静态回调** [V]——OverlayManager/ModConfig 不继承 XposedModule 拿不到 getRemotePreferences。AlaMobileModule.onPackageReady 设置 `ModConfig.remoteConfigReader = { getRemotePreferences(PREF_GROUP).getString(KEY_CONFIG_JSON, null) }`，ModConfig 不必继承 XposedModule 也能间接受益。贴合现有 notifyConfigChanged 静态回调模式。reader 签名从 `(String)->String?`（openRemoteFile 带 name）改成 `()->String?`（Remote Preferences 不需要外部传 name，reader 内部知道 PREF_GROUP/KEY）。
- **刹车反转 summary 文案修正** [V]——ConfigurePage 第 216 行 summary 从"开启后红色改为从上往下生长（默认从下往上）"改成"开启后刹车行程变为由上往下"，用户反馈原文案模糊。

## 4. 失败的尝试 — 不要再试

- **openRemoteFile 读模块 filesDir** [V]（前向搬运+本次确认）——ConfigActivity 写 `context.filesDir`（`/data/data/<pkg>/files/`），openRemoteFile 读 daemon 目录（`/data/adb/lspd/modules/<userId>/<pkg>/files/`），两个独立存储。logcat `must not be null` + `listRemoteFiles:` 空。openRemoteFile 写入端必须也经 `XposedService.openRemoteFile(name)` 写 daemon 目录——但 Remote Preferences 更优，不再走 openRemoteFile。
- **legacy `de.robv.android.xposed.XSharedPreferences`** [V]（本次新增）——libxposed API 102 明确禁止调用 legacy API（XposedInterface.java 第 42 行注释），LSPosed v2.1.0 已移除 "New XSharedPreferences" 兼容层。HANDOFF 之前说的"回退 XSharedPreferences"实际上不可行，这是之前研究的盲点。
- **模块进程写公共 `/sdcard/AlaMobileTool/`** [V]、**游戏进程读公共 `/sdcard/`** [V]、**ContentProvider.call 跨进程** [V]、**createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY)** [V]、**游戏进程直读模块私有文件** [V]、**M11 手写 SDK_INT 分支注册 receiver** [V]、**ConfigReceiver 直接 writeText 覆盖** [V]、**root 保持 val 不刷新** [V]、**文件直读跨进程** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]、**统一公式画两种方向刹车** [V]——均不再试。

## 5. 已知坑

- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_EXPORTED)`。
- **`androidx.core` 经传递依赖可用** [V]——activity-compose 传递拉入。
- **lint baseline 不覆盖新错误** [V]——加新代码必须本地 `./gradlew :app:lint` 验证。
- **原版/共存版布局存档不共用** [V]——Android 沙箱按包名隔离 externalFilesDir。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播是唯一可靠跨进程 IPC（但游戏没运行时丢弃，现已由 Remote Preferences 替代根治）。
- **广播首次启动滞后（M11 精确机制）** [V]——已修复：Remote Preferences 走 daemon SQLite，不依赖广播时机或游戏进程存活。复现步骤（杀游戏→改 DUAL→杀游戏→改 SINGLE→启动游戏→读 SINGLE）真机通过。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view（rebuildFromConfigChange 或 toggle）。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。仅作用于 mapped，不影响 raw。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [?]（前向搬运）——广播方案落地后未使用，Remote Preferences 方案下更无用，manifest provider 声明保留，待清理。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。Remote Preferences 经 Binder 到 daemon 读同一份数据，无冲突。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。
- **XposedServiceHelper 异步绑定** [V]（本次新增）——ConfigActivity.onCreate 时 `App.xposedService` 可能仍 null，ModConfig.write 走 fallback（filesDir + 广播）。首次进 ConfigActivity 太快可能 service 没绑上，但用户改配置通常在 service 绑上之后。
- **libxposed-service 依赖已就位** [V]——`implementation(libs.libxposed.service)` version 102.0.0，无需加依赖。XposedProvider 在 service AAR 自带 manifest，AGP 自动合并，模块 manifest 不用单独声明 provider。

## 6. 下一步（有序）

1. **清理废弃 IPC 层**（可选收尾）——删 `ConfigProvider.kt` + manifest 里它的 `<provider>` 声明。广播 + ConfigReceiver 暂保留（运行时即时更新 + service 异步绑定兜底）。也可考虑用 `prefs.registerOnSharedPreferenceChangeListener` 替代广播（Remote Preferences 原生支持 change listener），但广播已验证工作，不急改。
2. **发 Beta 3**——versionName `1.0.0 Beta 3`，versionCode `100230`。更新 `app/build.gradle.kts` 的 versionName/versionCode + `module.prop` 的 version/versionCode。CI workflow 的 `prerelease: true` 保持（Beta 阶段）。M14 三件事（方向键修复 + 刹车方向反转 + 配置同步迁移）全闭环后发。
3. **真机回归测试**——发版前跑一遍：pedal/dual/curve/invert/方向键/摇杆/配置即时生效（运行时改→overlay 重建）。

## 7. 留给用户的开放问题

- 是否现在清理废弃的 ConfigProvider.kt + manifest provider 声明？小改动不阻塞 Beta 3，但留着是死代码。
- 是否用 Remote Preferences 的 `OnSharedPreferenceChangeListener` 替代广播？原生支持、能删整层 ConfigReceiver，但广播已验证工作，改它属优化非必需。
