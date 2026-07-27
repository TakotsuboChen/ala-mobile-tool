# HANDOFF — 读全文再开始干活

生成时间: 2026-07-27T23:25:00+08:00 · Git HEAD: 105b8d0 之后未提交
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

推进 M1：生成 IL2CPP dump 并填充 `OffsetTable.kt`；补齐 overlay 层和 native hook 骨架。

## 2. 已验证状态 — 工作实际停在哪

- [V] IL2CPP dump 已生成: `il2cpp-dumps/v8.0.0/dump.cs`（689k 行，24 MB）。
- [V] `.gitignore` 已更新，排除 `il2cpp-dumps/` 和 `.tools/`。
- [V] `OffsetTable.kt` 已根据 dump 填充关键方法/字段偏移。
- [V] `NativeBridge.kt` 和 `ala_core.c` 的 JNI 接口已对齐新的 offset 表。
- [V] `AlaMobileModule.kt` 已接入 `OverlayManager`，在目标版本匹配时显示 overlay。
- [V] `PedalOverlayView.kt`、`GearShiftView.kt`、`OverlayManager.kt` 已创建。
- [V] `pedal_hook.h/c`、`drs_hook.h/c` 已创建并带有桩实现。
- [?] 本地 Gradle 构建仍失败，原因见下方。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
FAILURE: Build failed with an exception.

* Where:
Build file '/home/takotsubo/projects/ala-mobile-tool/build.gradle.kts' line: 1

* What went wrong:
Plugin [id: 'com.android.application', version: '8.6.1', apply: false] was not found in any of the following sources:
...
```

进一步诊断：

```
curl: (35) TLS connect error: error:0A000126:SSL routines::unexpected eof while reading
```

`dl.google.com` 在本环境无法完成 TLS 握手，导致无法下载 Android Gradle Plugin。Maven Central 可正常访问。

## 3. 决策与理由

- 选择 **轮询调用** 策略实现自动 DRS [?]——`drsToggle()` 是无参切换方法，轮询实现更简单，后续可改为 hook 调用点。
- `OffsetTable.kt` 中使用十六进制长整型字面量（如 `0x1A511F4L`）存储 method/file offset [V]——与 Il2CppDumper 的 "Offset" 字段一致，可直接传给 native hook。
- `ActiveAeroWing` 在 dump 中是 struct 而非 MonoBehaviour，原 `ACTIVE_AERO_WING_SET_ACTIVE` 等偏移不存在；已从 offset 表和 JNI 接口中移除 [V]。
- Overlay 使用 raw Android Canvas View [V]——避免 Compose 与 Unity SurfaceView 叠加问题。

## 4. 失败的尝试 — 不要再试

- 本地运行 `./gradlew :app:assembleDebug` [V]——因 `dl.google.com` TLS 握手失败，AGP 无法下载。在环境修复前不要再试。
- 使用 `Il2CppDumper-net8-linux-x64.zip` [V]——该 release 不存在，正确文件名是 `Il2CppDumper-net6-v6.7.46.zip`。

## 5. 已知坑

- `dl.google.com` 在本环境 TLS 握手失败，Gradle 无法下载 AGP [V]——需要检查本机 TLS/代理/DNS 配置，或换用能访问 Google Maven 的机器构建。
- 当前 native hook 仍是 stub，ShadowHook 库尚未引入 [V]——`native/CMakeLists.txt` 已支持可选预编译 `libshadowhook.so`。
- `PedalOverlayView.updateValues()` 目前是纯占位实现，踏板映射曲线需要调试 [V]。
- `AlaMobileModule` 中 hard-coded 开启了 control replacement 和 auto DRS [V]——后续应从 SharedPreferences 读取开关状态。

## 6. 下一步（有序）

1. 修复 `dl.google.com` TLS 问题或换环境验证 Gradle 构建。
2. 引入 ShadowHook（源码/submodule/预编译 so），在 `pedal_hook.c` 中实现 inline hook。
3. 完善 `PedalOverlayView.updateValues()` 的踏板映射与死区逻辑。
4. 在 `drs_hook.c` 轮询线程中读取 telemetry 并调用 `drsToggle()`。
5. `ConfigActivity` 写入 SharedPreferences，`AlaMobileModule` 读取实际开关状态。
6. 在 LSPosed 中安装并验证模块。

## 7. 留给用户的开放问题

- 你是否接受当前“轮询 + 主动调用 drsToggle()”的 DRS 策略？如果希望改为 hook `drsToggle()` 调用点，需要进一步分析 dump 中调用该方法的代码位置。
- 双区踏板尺寸和位置（当前为左侧 300x600、右侧 200x400，屏幕纵向居中）是否需要调整？
