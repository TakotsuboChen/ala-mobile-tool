# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T00:15:00+08:00 · Git HEAD: ba59eb6
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

M1 已完成。下一个切片 M2 起步：引入 inline hook 库实现真正的 native hook。本次会话刚确认 `libxposed/ShadowHook` repo 不存在，需要改用其他 hook 库（候选：`bytedance/android-inline-hook`）。

## 2. 已验证状态 — 工作实际停在哪

- [V] 工作树干净，与 origin/main 同步（`git status` 验证）。
- [V] HEAD = ba59eb6（`git log --oneline -5` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功，1 秒，全 UP-TO-DATE。
- [V] `dl.google.com` 已恢复可访问（换 Clash 订阅后，`curl -I` 返回 HTTP 200）。
- [V] `libxposed/ShadowHook` GitHub repo 不存在（API 返回 404）。
- [V] 搜索 "shadowhook android" 找到候选库：`bytedance/android-inline-hook`。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:assembleDebug UP-TO-DATE
BUILD SUCCESSFUL in 1s
37 actionable tasks: 2 executed, 35 up-to-date
```

### 构建环境（本机已就位，沿用上一 handoff）

- JDK 21.0.11, Android SDK `/home/takotsubo/android-sdk/`, NDK 26.1.10909125
- 阿里云 Maven 镜像已配置在 `settings.gradle.kts`（用户选择保留，方案 1）
- `local.properties` 指向 `sdk.dir=/home/takotsubo/android-sdk`（已 gitignore）

## 3. 决策与理由

- 保留阿里云 Maven 镜像配置（方案 1）[V]——用户明确选择。即使 `dl.google.com` 已恢复，镜像无副作用且作为 fallback 防止未来节点问题。
- ShadowHook 引入策略需重新评估 [?]——原 CLAUDE.md 假设的 `libxposed/ShadowHook` repo 不存在。候选：`bytedance/android-inline-hook`（字节跳动开源，社区广泛使用）。

## 4. 失败的尝试 — 不要再试

- `libxposed/ShadowHook` GitHub repo [V]——API 返回 404 Not Found，repo 不存在。不要再尝试从该地址下载。
- 直接访问 `dl.google.com`（旧订阅）[V]——Clash TUN 劫持导致 TLS 失败。换订阅后已恢复，但保留镜像配置作为 fallback。
- 用 `Il2CppDumper-net8-linux-x64.zip` [V]——该 release 不存在，正确文件名是 `Il2CppDumper-net6-v6.7.46.zip`。
- 用 `platform-37.1_r01.zip` [V]——`AndroidVersion.ApiLevel=37.1`（带小数），AGP 不认。改用 `platform-37.0_r02.zip` 并 sed 修正为 `37`。
- Kotlin 2.0.21 + miuix 0.9.3 [V]——miuix 用 Kotlin 2.4.0 编译，metadata 不兼容。
- `kotlinOptions { jvmTarget = "17" }` [V]——Kotlin 2.4.0 已废弃该 DSL。
- libxposed 101 降级 [V]——`interface:101.0.0` 仍要求 compileSdk 36+。
- Clash 不开 Allow LAN 时从 WSL2 访问 Windows Clash 端口 [V]——端口扫描全 CLOSED。

## 5. 已知坑

- `libxposed/ShadowHook` repo 不存在 [V]——CLAUDE.md 和 `native/CMakeLists.txt` 中的引用需更新为实际可用的 hook 库（如 `bytedance/android-inline-hook`）。
- `platform-37.0_r02.zip` 的 `source.properties` 里 `ApiLevel=37.0` 有小数点 [V]——已 sed 修正，重新解压需再次修正。
- 当前 native hook 仍是 stub [V]——`pedal_hook.c`/`drs_hook.c` 只有桩实现，真正的 inline hook 未实现。
- `PedalOverlayView.updateValues()` 是纯线性占位 [V]——需要调试踏板映射曲线和死区。
- `AlaMobileModule` hard-coded 开启 control replacement 和 auto DRS [V]——后续应从 SharedPreferences 读取。
- Il2CppDumper 在非交互终端最后会抛 `Console.ReadKey` 异常 [V]——dump 已生成，忽略。
- .NET 6 运行 Il2CppDumper 需 `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` [V]。

## 6. 下一步（有序）

1. 调研 `bytedance/android-inline-hook` 的 API 和集成方式（预编译 so vs 源码 submodule）。
2. 更新 CLAUDE.md 和 `native/CMakeLists.txt` 中对 ShadowHook 的引用为实际选用的库。
3. 下载/集成 hook 库到 `native/libs/arm64-v8a/`。
4. 在 `pedal_hook.c` 中实现 `setThrottleInput`/`setBrakeInput` 的 inline hook。
5. 完善 `PedalOverlayView.updateValues()` 踏板映射。
6. 在 `drs_hook.c` 轮询线程中读取 telemetry 并调用 `drsToggle()`。
7. `ConfigActivity` 写入 SharedPreferences，`AlaMobileModule` 读取开关状态。
8. 在 LSPosed 中安装并验证模块。

## 7. 留给用户的开放问题

- 选用哪个 inline hook 库？候选：`bytedance/android-inline-hook`（字节跳动，广泛使用）、`cmbz/ShadowHook`（如果有）、或自研。
- 引入方式：预编译 so、源码 submodule，还是 CI 自动下载？
- DRS 策略是否仍用轮询 + 主动调用 `drsToggle()`？
- 双区踏板尺寸和位置（当前左侧 300x600、右侧 200x400）是否需要调整？
