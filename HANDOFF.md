# HANDOFF — 读全文再开始干活

生成时间: 2026-07-27T23:45:00+08:00 · Git HEAD: 2a205b9
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

推进 M1/M2：IL2CPP dump 已生成并填充 OffsetTable；Gradle 构建已跑通；下一步是引入 ShadowHook 并实现真正的 inline hook。

## 2. 已验证状态 — 工作实际停在哪

- [V] IL2CPP dump 已生成: `il2cpp-dumps/v8.0.0/dump.cs`（689k 行，24 MB）。
- [V] `OffsetTable.kt` 已根据 dump 填充关键方法/字段偏移。
- [V] `NativeBridge.kt` 和 `ala_core.c` 的 JNI 接口已对齐新的 offset 表。
- [V] `AlaMobileModule.kt` 已接入 `OverlayManager`，在目标版本匹配时显示 overlay。
- [V] `PedalOverlayView.kt`、`GearShiftView.kt`、`OverlayManager.kt` 已创建。
- [V] `pedal_hook.h/c`、`drs_hook.h/c` 已创建并带有桩实现。
- [V] **Gradle 构建成功**：`./gradlew :app:assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`（约 10 MB）。

### 构建环境（本机已就位）

- JDK: OpenJDK 21.0.11（Debian 13 系统包）
- Android SDK: `/home/takotsubo/android-sdk/`
  - platforms: android-35, android-36, android-37
  - build-tools: 34.0.0, 36.1.0, 37.0.0
  - ndk: 26.1.10909125
  - cmdline-tools/latest（已接受所有 license）
- `local.properties` 指向 `sdk.dir=/home/takotsubo/android-sdk`（已被 .gitignore 忽略）
- Gradle 仓库：阿里云 Maven 镜像（绕过 Clash TUN 对 dl.google.com 的 TLS 劫持）

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 8s
37 actionable tasks: 10 executed, 27 up-to-date
```

## 3. 决策与理由

- 选择 **轮询调用** 策略实现自动 DRS [?]——`drsToggle()` 是无参切换方法，轮询实现更简单，后续可改为 hook 调用点。
- `OffsetTable.kt` 中使用十六进制长整型字面量存储 method/file offset [V]——与 Il2CppDumper 的 "Offset" 字段一致。
- `ActiveAeroWing` 在 dump 中是 struct 而非 MonoBehaviour，已从 offset 表和 JNI 接口中移除 [V]。
- 升级版本链：AGP 8.6.1→8.9.1, Kotlin 2.0.21→2.4.0, compileSdk 35→37 [V]——libxposed 102 和 miuix 0.9.3 都要求 compileSdk 37 和 Kotlin 2.4。
- 固定 `ndkVersion = "26.1.10909125"` [V]——避免 AGP 自动拉取 NDK 27 导致 license 下载失败。
- 使用阿里云 Maven 镜像 + 腾讯云 AndroidSDK 镜像 [V]——Clash TUN 对 dl.google.com 的 TLS 握手在本环境失败。

## 4. 失败的尝试 — 不要再试

- 直接访问 `dl.google.com` [V]——Clash TUN 劫持到 198.18.0.128 但 TLS 握手失败，浏览器也 ERR_CONNECTION_CLOSED。用镜像绕过。
- 用 `platform-37.1_r01.zip` [V]——`AndroidVersion.ApiLevel=37.1`（带小数），AGP 不认。改用 `platform-37.0_r02.zip` 并 sed 修正为 `37`。
- Kotlin 2.0.21 + miuix 0.9.3 [V]——miuix 用 Kotlin 2.4.0 编译，metadata 不兼容。必须升级 Kotlin 到 2.4.0+。
- `kotlinOptions { jvmTarget = "17" }` [V]——Kotlin 2.4.0 已废弃该 DSL，改用 `kotlin { compilerOptions { } }`。
- libxposed 101 降级 [V]——`interface:101.0.0` 仍要求 compileSdk 36+。最终用 102 + compileSdk 37。

## 5. 已知坑

- Clash TUN 对 `dl.google.com` 域名劫持导致 TLS 失败 [V]——用阿里云 Maven + 腾讯云 AndroidSDK 镜像绕过；如需直连需修 Clash 规则。
- `platform-37.0_r02.zip` 的 `source.properties` 里 `ApiLevel=37.0` 有小数点 [V]——已 sed 修正为 `37`，但重新解压需再次修正。
- 当前 native hook 仍是 stub，ShadowHook 库尚未引入 [V]——`native/CMakeLists.txt` 已支持可选预编译 `libshadowhook.so`。
- `PedalOverlayView.updateValues()` 目前是纯线性的占位实现 [V]。
- `AlaMobileModule` 中 hard-coded 开启了 control replacement 和 auto DRS [V]——后续应从 SharedPreferences 读取开关状态。

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
