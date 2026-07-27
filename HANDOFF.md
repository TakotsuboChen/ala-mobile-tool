# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T00:30:15+08:00 · Git HEAD: bf06cd7590fa5b85777f3da1016f8a55f87d4e25
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

完成 M3：让模块配置可持久化，并通过设置 UI 控制踏板覆盖 / 自动 DRS / 踏板映射曲线，为真机验证 ShadowHook hook 签名做准备。

完成定义：
- ConfigActivity 写入 SharedPreferences，`AlaMobileModule` 在目标进程中读取并应用配置；
- `PedalOverlayView` 支持过渡点、死区、线性/二次/指数曲线；
- 提供 `scripts/install-and-logcat.sh` 一键安装 + 观察 logcat；
- `./gradlew :app:assembleDebug` 构建成功。

## 2. 已验证状态 — 工作实际停在哪

- [V] 工作树有 4 个文件修改 + 2 个新增目录（`config/`、`scripts/`），未提交（`git status` 验证）。
- [V] 当前分支 `main`，与 `origin/main` 同步（`git status` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功，11 秒，BUILD SUCCESSFUL（本次交接刚运行）。
- [V] `ModConfig` 使用 `Context.MODE_WORLD_READABLE` 实现模块进程与目标进程共享配置。
- [V] `AlaMobileModule` 在 `onPackageReady()` 中通过 `createPackageContext` 读取模块配置并传递给 `NativeBridge.initWithOffsets()`。
- [V] `PedalOverlayView` 已按配置参数绘制过渡点、死区，并应用曲线映射。
- [?] 真机上 LSPosed 模块是否被加载、SharedPreferences 是否能被游戏进程读取，尚未验证。
- [?] `setThrottle` / `setBrake` / `drsToggle` 的 proxy 函数签名假设是否匹配 IL2CPP 实际 calling convention，仍需真机 logcat 验证。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug
[Incubating] Problems report is available at: file:///home/takotsubo/projects/ala-mobile-tool/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 11s
37 actionable tasks: 8 executed, 29 up-to-date
```

## 3. 决策与理由

- 使用 `Context.MODE_WORLD_READABLE` 共享配置 [V]——LSPosed 模块的标准做法，ConfigActivity 写入模块自己的 `SharedPreferences`，目标游戏进程通过 `createPackageContext` 读取。否决方案：XSharedPreferences（libxposed API 102 未提供）、ContentProvider（过度设计）。
- `PedalOverlayView` 保留默认配置兜底 [?]——即使读取配置失败也能工作。默认启用踏板覆盖，过渡点 50%，死区 5%，线性曲线。
- `SliderRow` 使用 +/- 按钮而非 Compose Slider [V]——当前 miuix-ui 版本没有稳定的公开 Slider API，使用 Button 步进实现。
- 配置保存后需要重启目标游戏才能生效 [?]——`AlaMobileModule` 只在 `onPackageReady` 读取一次配置；运行时动态重载可在后续切片实现。

## 4. 失败的尝试 — 不要再试

- `libxposed/ShadowHook` GitHub repo [V]——API 返回 404，repo 不存在。不要再尝试从该地址下载。
- `com.bytedance.android:shadowhook:1.2.6` [V]——Maven Central 不存在该版本，构建时报 `Could not find com.bytedance.android:shadowhook:1.2.6`。已改用 2.0.1。
- 手工下载并放置 `libshadowhook.so` [V]——prefab 已自动处理 so 和 header，无需放到 `native/libs/`。
- 直接访问 `dl.google.com`（旧订阅）[V]——Clash TUN 劫持导致 TLS 失败。换订阅后已恢复，但保留镜像配置作为 fallback。

## 5. 已知坑

- `setThrottle` / `setBrake` / `drsToggle` 的 proxy 函数签名假设必须与原 IL2CPP 方法一致 [?]。当前假设：
  - `setThrottle(float)` → `void proxy(void* this, float value)`
  - `setBrake(float)` → `void proxy(void* this, float value)`
  - `drsToggle()` → `void proxy(void* this)`
  若 IL2CPP 编译器优化或 calling convention 不同，hook 会导致崩溃。
- `/proc/self/maps` 解析基址只取第一个匹配 `libil2cpp.so` 的映射 [?]。如果游戏以后加载多个 il2cpp 映射，需要更精细的解析。
- 全局 `g_throttle_value` / `g_brake_value` 是 `volatile float`，没有加锁 [?]。叠加更新和 IL2CPP 调用可能在不同线程，但单次 float 写是原子的，可接受轻微抖动。
- DRS 当前只是“开关”逻辑，没有真正的自动判断，后续需要读取 telemetry 字段。
- Android 10+ 可能限制 `MODE_WORLD_READABLE` [?]。如果目标游戏无法读取模块配置，需迁移到 `ContentProvider` 或文件共享。
- 阿里云 Maven 镜像没有 ShadowHook，依赖解析会回退到 Maven Central [V]——构建成功，无需改动。
- `platform-37.0_r02.zip` 的 `source.properties` 里 `ApiLevel=37.0` 有小数点 [V]——已 sed 修正，重新解压需再次修正。
- Il2CppDumper 在非交互终端最后会抛 `Console.ReadKey` 异常 [V]——dump 已生成，忽略。
- .NET 6 运行 Il2CppDumper 需 `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` [V]。

## 6. 下一步（有序）

1. 在真机运行 `./scripts/install-and-logcat.sh` 安装模块并观察 `AlaMobileTool` tag。
2. 在 LSPosed 管理器中确认模块已启用，并启动游戏验证 hook 是否命中、是否崩溃。
3. 若 hook 崩溃或日志显示签名不匹配，用 Il2CppDumper + IDA/Ghidra 修正 `pedal_hook.c` / `drs_hook.c` 中的 proxy 函数签名。
4. 验证 SharedPreferences 跨进程读取是否正常；如失败，迁移到 ContentProvider 或文件共享。
5. 根据真机手感微调默认死区、过渡点和曲线。
6. 实现换挡 hook（`shiftUp` / `shiftDown` / `setGear`），并给 overlay 按钮接入 native 调用。
7. DRS：读取 `inDRSZone`、车速、油门等 telemetry 字段，实现自动开启/关闭逻辑。

## 7. 留给用户的开放问题

- 是否现在提交并推送这些改动？（建议真机验证后再提交）
- 真机测试时是否需要在 LSPosed 中手动启用模块？
- 踏板默认死区 5%、过渡点 50% 是否符合你的操作习惯？
- 是否希望配置支持运行时热重载，而非每次保存后重启游戏？
