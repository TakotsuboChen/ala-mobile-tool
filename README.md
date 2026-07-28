# Ala Mobile Tool

一个开源的 LSPosed 模块，用于增强 **Ala Mobile** 的操控体验。

> ⚠️ **仅供个人学习与研究使用**。本模块会修改游戏的运行时行为，在多人/在线模式下使用可能违反服务条款并导致封号。请仅在单机模式下使用。

## 演示

### 使用踏板覆盖

单击游戏内「工具」按钮打开 Overlay，使用线性踏板进行油门和刹车控制。

<video src="https://github.com/TakotsuboChen/ala-mobile-tool/raw/main/assets/videos/click-tool-button-linear-pedal.mp4" controls width="100%"></video>

### 编辑 Overlay 布局

长按游戏内「工具」按钮进入编辑模式，拖动调整 Overlay 位置。

<video src="https://github.com/TakotsuboChen/ala-mobile-tool/raw/main/assets/videos/long-press-edit-overlay-layout.mp4" controls width="100%"></video>

## 功能

### ✅ 已实现

- **踏板覆盖**：在屏幕右侧叠加双区线性踏板区域，上半部分控制油门、下半部分控制刹车。支持死区、过渡点和响应曲线（线性/二次/指数）调节。
- **换挡按钮**：在屏幕左侧提供升档/降档按钮，用于手动换挡。
- **现代 UI**：采用 KernelSU 风格的三页布局（概览/配置/设置），支持深色模式。

### 🚧 开发中

- **自动 DRS**：基于赛道 telemetry 自动判断 DRS 开启时机（当前仅拦截开关）。
- **关闭自动换挡**：禁用车载自动换挡逻辑，让手动换挡更可靠。

## 已测试版本

| 版本 | versionCode | 架构 |
|---|---|---|
| 8.0.0 | 200142 | arm64-v8a |

其他版本请自行测试。IL2CPP 方法地址随版本变化，非 8.0.0 版本可能无法正确加载原生 hook。

## 安装

1. 确保手机已安装并启用 LSPosed（或兼容的 LSPosed 分支）框架。
2. 从 [Releases](https://github.com/TakotsuboChen/ala-mobile-tool/releases) 下载最新 APK。
3. 在 LSPosed 中勾选 **Ala Mobile** 作为作用域。
4. 启动游戏，进入游戏后点击左上角「工具」按钮显示 overlay。

> 首次启动游戏后建议先在模块设置界面中确认配置。

## 配置

在 LSPosed 模块列表中点击 **Ala Mobile Tool** 打开设置界面：

### 配置页

- **踏板覆盖**：是否用 overlay 踏板替代原始输入。
- **显示悬浮窗**：是否显示踏板/换挡 overlay。
- **死区**：踏板过渡区域附近的无效范围（0-20%），数值越小响应越灵敏。
- **过渡点**：油门与刹车区域的分界线位置（20-80%）。
- **响应曲线**：支持线性、二次、指数三种映射。

### 设置页

- **启用日志**：记录模块运行日志以便排查问题。
- **导出并分享日志**：导出当前日志文件。
- **清除激活标记**：删除 LSPosed 激活状态缓存。

## 已知问题

这是早期 Alpha 版本，以下问题已知并在后续版本中修复：

- 换挡按钮会调用游戏内换挡方法，但当前不会关闭游戏的自动换挡，因此手动换挡可能与自动换挡产生冲突。
- 自动 DRS 仅拦截开关，尚未实现基于赛道 telemetry 的自动判断。
- 使用踏板覆盖时，行驶过程中可能出现高频顿挫，具体原因待排查。
- 当前 release APK 使用临时签名，正式发布前会替换为正式签名。

## 技术栈

- 现代 libxposed API 102
- Kotlin + Jetpack Compose + miuix
- ByteDance ShadowHook + IL2CPP inline hook
- Kotlin Multiplatform 支持

## 构建

需要 Android SDK（API 37）和 NDK r26c。

```bash
# 调试构建
./gradlew :app:assembleDebug

# 发布构建（需要配置签名）
./gradlew :app:assembleRelease
```

## 逆向工具

```bash
tools/run-il2cpp-dumper.sh
```

该脚本会调用 [Il2CppDumper](https://github.com/Perfare/Il2CppDumper) 生成 `il2cpp-dumps/v8.0.0/dump.cs`，随后需要手动提取目标方法/字段偏移并更新 `OffsetTable.kt`。

## 许可证

Apache-2.0

## 版本历史

- **v1.0.0-Alpha-2** (2025-01-28)：UI 重构为 KernelSU 风格，修复多个编译错误，改进深色模式支持。
- **v1.0.0-Alpha-1** (2025-01-27)：初始发布，包含踏板覆盖、换挡按钮和基础 DRS 拦截功能。
