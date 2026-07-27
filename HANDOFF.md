# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T00:00:00+08:00 · Git HEAD: 7c057b1
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

M1 已完成（IL2CPP dump + OffsetTable + overlay 骨架 + 构建跑通）。下一切片是 M2：引入 ShadowHook 实现真正的 inline hook，并完善踏板映射和 DRS 轮询逻辑。

## 2. 已验证状态 — 工作实际停在哪

- [V] 工作树干净，本地领先 origin/main 3 个 commit（`git status` + `git log` 验证）。
- [V] HEAD = 7c057b1（`git log --oneline -5` 验证）。
- [V] IL2CPP dump 已生成：`il2cpp-dumps/v8.0.0/dump.cs`（689k 行，24 MB）。
- [V] `OffsetTable.kt` 已填充 `IRDSCarControllInput` 和 `IRDSDrivetrain` 的方法/字段偏移。
- [V] `NativeBridge.kt` 和 `ala_core.c` 的 JNI 接口已对齐 offset 表。
- [V] overlay 层已创建：`PedalOverlayView`、`GearShiftView`、`OverlayManager`。
- [V] native hook 骨架已建：`pedal_hook.h/c`、`drs_hook.h/c`（stub）。
- [V] **`./gradlew :app:assembleDebug` 构建成功**，产出 `app-debug.apk`（约 10 MB）。

### 构建环境（本机已就位）

- JDK: OpenJDK 21.0.11（Debian 13 系统包）
- Android SDK: `/home/takotsubo/android-sdk/`
  - platforms: android-35, android-36, android-37（android-37 的 source.properties 已 sed 修正 ApiLevel=37）
  - build-tools: 34.0.0, 36.1.0, 37.0.0
  - ndk: 26.1.10909125
  - cmdline-tools/latest（所有 license 已接受）
- `local.properties` 指向 `sdk.dir=/home/takotsubo/android-sdk`（已被 .gitignore 忽略）
- Gradle 仓库：阿里云 Maven 镜像（绕过 Clash TUN 对 dl.google.com 的 TLS 劫持）
- .NET 6 已安装到 `~/.dotnet`（用于运行 Il2CppDumper）

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:assembleDebug UP-TO-DATE
BUILD SUCCESSFUL in 2s
37 actionable tasks: 2 executed, 35 up-to-date
```

## 3. 决策与理由

- DRS 策略选 **轮询调用** [?]——`drsToggle()` 是无参切换方法，轮询实现更简单。否决方案：hook 调用点，需要先定位调用方代码位置，工程量更大。
- OffsetTable 用十六进制长整型字面量 [V]——与 Il2CppDumper 的 "Offset" 字段一致，可直接传给 native hook。
- 移除 `ActiveAeroWing` setter/getter [V]——dump 中 `ActiveAeroWing` 是 struct 而非 MonoBehaviour，没有方法。
- 版本链：AGP 8.6.1→8.9.1, Kotlin 2.0.21→2.4.0, compileSdk 35→37 [V]——libxposed 102 和 miuix 0.9.3 都要求 compileSdk 37 和 Kotlin 2.4。
- 固定 `ndkVersion = "26.1.10909125"` [V]——避免 AGP 自动拉取 NDK 27 导致 license 下载失败。
- 用阿里云 Maven 镜像 + 腾讯云 AndroidSDK 镜像 [V]——Clash TUN 对 dl.google.com 的 TLS 握手在本环境失败。
- DRS 用 `dotnet Il2CppDumper.dll` 运行 [V]——Linux release 只提供 .dll，没有无后缀可执行文件。

## 4. 失败的尝试 — 不要再试

- 直接访问 `dl.google.com` [V]——Clash TUN 劫持到 198.18.0.128 但 TLS 握手失败，Windows 浏览器也 ERR_CONNECTION_CLOSED。用镜像绕过，不要再试直连。
- 用 `Il2CppDumper-net8-linux-x64.zip` [V]——该 release 不存在，正确文件名是 `Il2CppDumper-net6-v6.7.46.zip`。
- 用 `platform-37.1_r01.zip` [V]——`AndroidVersion.ApiLevel=37.1`（带小数），AGP 不认。改用 `platform-37.0_r02.zip` 并 sed 修正为 `37`。
- Kotlin 2.0.21 + miuix 0.9.3 [V]——miuix 用 Kotlin 2.4.0 编译，metadata 不兼容。必须升级 Kotlin 到 2.4.0+。
- `kotlinOptions { jvmTarget = "17" }` [V]——Kotlin 2.4.0 已废弃该 DSL，改用 `kotlin { compilerOptions { } }`。
- libxposed 101 降级 [V]——`interface:101.0.0` 仍要求 compileSdk 36+。最终用 102 + compileSdk 37。
- 在 Clash 不开 Allow LAN 时从 WSL2 访问 Windows Clash 端口 [V]——端口扫描全部 CLOSED，无法连通。

## 5. 已知坑

- Clash TUN 对 `dl.google.com` 域名劫持导致 TLS 失败 [V]——已用阿里云 Maven + 腾讯云 AndroidSDK 镜像绕过；如需直连需修 Clash 规则。
- `platform-37.0_r02.zip` 的 `source.properties` 里 `ApiLevel=37.0` 有小数点 [V]——已 sed 修正为 `37`，但重新解压需再次修正。
- 当前 native hook 仍是 stub，ShadowHook 库尚未引入 [V]——`native/CMakeLists.txt` 已支持可选预编译 `libshadowhook.so`。
- `PedalOverlayView.updateValues()` 目前是纯线性的占位实现 [V]——需要调试踏板映射曲线和死区。
- `AlaMobileModule` 中 hard-coded 开启了 control replacement 和 auto DRS [V]——后续应从 SharedPreferences 读取开关状态。
- Il2CppDumper 在非交互终端最后会抛 `Console.ReadKey` 异常 [V]——但 dump 文件已成功生成，忽略即可。
- .NET 6 运行 Il2CppDumper 需设置 `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` [V]——否则 libicu 缺失导致崩溃。

## 6. 下一步（有序）

1. 引入 ShadowHook（预编译 so 或 submodule），在 `pedal_hook.c` 中实现 inline hook。
2. 完善 `PedalOverlayView.updateValues()` 的踏板映射与死区逻辑。
3. 在 `drs_hook.c` 轮询线程中读取 telemetry 并调用 `drsToggle()`。
4. `ConfigActivity` 写入 SharedPreferences，`AlaMobileModule` 读取实际开关状态。
5. 在 LSPosed 中安装并验证模块。

## 7. 留给用户的开放问题

- 你是否接受当前"轮询 + 主动调用 drsToggle()"的 DRS 策略？
- 双区踏板尺寸和位置（当前为左侧 300x600、右侧 200x400，屏幕纵向居中）是否需要调整？
- ShadowHook 引入方式：预编译 so、源码 submodule，还是 CI 自动下载？
