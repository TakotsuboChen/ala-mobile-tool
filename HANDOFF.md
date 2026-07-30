# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T13:45:00+08:00 · Git HEAD: 94c6e71
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 M11/M12/M13 三次 CI 失败。**已完成并 CI 验证通过**：根因是 M11 引入的 `ConfigReceiver` 注册调用 `context.registerReceiver(receiver, filter)` 旧式 API（else 分支），lint 静态分析命中 `UnspecifiedRegisterReceiverFlag`，CI lintDebug 直接 fail。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `94c6e71`，工作树 clean，已 push。
- [V] `./gradlew :app:lint` 本地 BUILD SUCCESSFUL in 46s（compileDebugKotlin + lintDebug 全过）。
- [V] CI run 30514831609 全绿：所有非 tag 步骤 success（Set up job / Checkout / JDK 21 / platforms;android-37 / NDK 26.1 / Decode keystore / chmod / Run lint ✅ / Build release APK ✅ / Rename ✅ / Upload artifact ✅），Upload to Release skipped（非 tag push，符合策略）。
- [V] 核心修复（`AlaMobileModule.kt`，1 处改动 + 1 行 import）：
  1. 加 `import androidx.core.content.ContextCompat`。
  2. 删除 `if (SDK_INT >= TIRAMISU) { context.registerReceiver(receiver, filter, RECEIVER_EXPORTED) } else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }` 手写分支，改为单行 `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)`。
- [V] `androidx.core` 经 `androidx.activity:activity-compose` 传递依赖已在运行时类路径上，**无需新增依赖**（grep `androidx.core` 在 build.gradle.kts / libs.versions.toml 无显式条目，但 ContextCompat 编译+lint 通过）。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:lint --no-daemon
> Task :app:lintAnalyzeDebug
> Task :app:lintReportDebug
Wrote HTML report to file:///.../app/build/reports/lint-results-debug.html
> Task :app:lintDebug
> Task :app:lint
BUILD SUCCESSFUL in 46s
28 actionable tasks: 10 executed, 18 up-to-date

$ gh run view 30514831609 --json status,conclusion
{"conclusion":"success","status":"completed"}
```

## 3. 决策与理由

- **改用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)` 而非给 lint 加 baseline 或加 `@Suppress`** [V]——baseline 是把错误藏进黑名单，`@Suppress("UnspecifiedRegisterReceiverFlag")` 会抑制真正的安全问题；ContextCompat 重载是 AndroidX 为"跨 API 一份代码 + 过 lint"设计的官方 API，内部按 SDK_INT 自动分发旧/新重载，代码反而更短（删掉手写分支 + `@Suppress("DEPRECATION")`）。否决方案：①加 lint-baseline.xml 条目——藏问题，且每次新 receiver 都要补；②加 `@Suppress`——同上，且不解决实际运行时 flag 缺失。
- **保留 RECEIVER_EXPORTED（不是 NOT_EXPORTED）** [V]——广播来自模块进程（不同应用，跨应用派发），必须 EXPORTED。这与 M11 既有设计一致（广播 setPackage 定向派发，绕包可见性）。
- **不改 lint-baseline.xml** [V]——baseline 里那 1 处 AlaMobileModule 条目是 `PrivateApi`（反射 ActivityThread），不是本次错误；本次错误不在 baseline，修复后直接消失，不需要碰 baseline。

## 4. 失败的尝试 — 不要再试

- **M11 手写 `if (SDK_INT >= TIRAMISU)` 分支注册 receiver** [V]——lint 是**静态分析**，对 else 分支的旧式 `registerReceiver(receiver, filter)`（无 flag）照样报 `UnspecifiedRegisterReceiverFlag`，即便运行时走不到。从 M11 起每次 CI 在 lintDebug 直接 fail（M11/M12/M13 三次）。不要再手写 SDK 分支，用 ContextCompat。
- （前向搬运）M11 ConfigReceiver 直接 writeText 覆盖 [V]、root 保持 val 不刷新 [V]、文件直读跨进程 [V]、ContentProvider 跨进程 [V]、createPackageContext [V]、5 参 call 重载 [V]、by lazy 只改缓存不够 [V]、applyCurve 作用单字段 [V]、BRAKE 从底向上画水位式 [V]、M12 OverlayEditView 传 settings.*Position 作 defaultPosition [V]、SINGLE/DUAL 共用 pedal_position 字段 [V]——均不再试。

## 5. 已知坑

- **Android 13+ registerReceiver 需 flag** [V]——targetSdk 35 强制。**必须用 `ContextCompat.registerReceiver(context, receiver, filter, flag)`**，不要手写 SDK_INT 分支（lint 静态分析会命中旧式重载）。跨应用广播用 RECEIVER_EXPORTED。
- **`androidx.core` 经传递依赖可用，无显式依赖** [V]——build.gradle.kts / libs.versions.toml 都没有 androidx.core 条目，但 activity-compose 传递性拉入 `androidx.core:core-ktx`，ContextCompat 编译 + lint 通过。未来若依赖图变化需复核。
- **lint baseline 不覆盖新错误** [V]——baseline 只过滤已记录的旧 issue；新引入的 lint error（如本次 UnspecifiedRegisterReceiverFlag）不在 baseline，CI 直接 fail。加新代码后必须本地 `./gradlew :app:lint` 验证。
- **原版/共存版布局存档不共用** [V]——Android 沙箱按包名隔离 externalFilesDir，无法绕过。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播 Intent.setPackage 是唯一可靠跨进程 IPC。
- **广播首次启动滞后** [V]——首次安装后首次进游戏读默认值；用户改一次配置后 receiver 接收并写入，之后即时生效。
- **PedalOverlayView 构造拷 settings 快照** [V]——加/改 Settings 字段必须同步默认参数构造；配置变更必须重建 view（rebuildFromConfigChange 或 toggle）。
- **applyCurve exponent 方向** [V]——<1 是 ease-out（先快后慢），≥1 是 ease-in。拟真用 0.66（30%→45%）。仅作用于 mapped（送 native），不影响 raw（绘制）。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [V]——广播方案落地后未使用，可删。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。notifyConfigChanged 在第二个 ClassLoader 是 no-op（instance 为 null）。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。

## 6. 下一步（有序）

1. **发 Beta**——M13 五项修复 + 本次 CI 修复闭环，可发 Beta（versionCode 按命名规则，versionName `1.0.0 Beta 3`）。流程：改 versionName/versionCode → commit → tag `v1.0.0 Beta 3` push → CI 自动产 Pre-release APK。
2. **清理 ConfigProvider.kt**（可选）——广播方案落地后未使用，manifest provider 声明一并删。
3. （后续）实现"手动换挡""自动 DRS"——换挡开关当前 UI 禁用，启用时加 DUAL 互斥 UI 联动。自动 DRS 默认读 false，待 IL2CPP telemetry 字段接入。

## 7. 留给用户的开放问题

- 何时发 Beta 3？M13 已真机全过 + CI 全绿，可发。
- 原版/共存版布局不共用：用户已"暂时接受"，保留。
- 双踏板刹车过渡点默认 10% 是否合适？用户可在配置页调 0–20%，本轮默认值未反馈是否需改。
