# Ala Mobile Tool

一个开源的 LSPosed 模块，用于扩展 **Ala Mobile**（包名 `com.Vince.AlamobileFormula`）的操控与 DRS/主动空力体验。

> ⚠️ **仅供个人学习与研究使用**。本模块会修改游戏的运行时行为，在多人/在线模式下使用可能违反服务条款并导致封号。请仅在单机模式下使用。

## 功能

- **踏板覆盖**：在屏幕右侧叠加一个双区线性踏板区域，上半部分控制油门、下半部分控制刹车，并支持死区、过渡点和响应曲线调节。
- **换挡按钮**：在屏幕左侧提供升档/降档按钮，用于手动换挡。
- **自动 DRS / 主动空力**：拦截游戏 DRS 开关，未来会基于 telemetry 自动判断开启时机；当前仅作开关拦截。

## 已测试版本

| 版本 | versionCode | 架构 |
|---|---|---|
| 8.0.0 | 200142 | arm64-v8a |

其他版本请自行测试。IL2CPP 方法地址随版本变化，非 8.0.0 版本可能无法正确加载原生 hook。

## 安装

1. 确保手机已安装并启用 LSPosed（或兼容的 LSPosed 分支）框架。
2. 从 [Releases](https://github.com/TakotsuboChen/ala-mobile-tool/releases) 下载最新 APK。
3. 在 LSPosed 中勾选 **Ala Mobile** 作为作用域。
4. 启动游戏，进入赛道后点击左上角「工具」按钮显示 overlay。

> 首次启动游戏后建议先在模块设置界面中确认「踏板覆盖」等开关已开启。

## 配置说明

在 LSPosed 模块列表中点击 **Ala Mobile Tool** 打开设置界面：

- **踏板覆盖**：是否用 overlay 踏板替代原始输入。
- **自动 DRS**：是否接管 DRS 开关。
- **显示悬浮窗**：是否显示踏板/换挡 overlay（进入游戏后仍可通过「工具」按钮手动开关）。
- **死区**：踏板过渡区域附近的无效范围，数值越小响应越灵敏。
- **过渡点**：油门与刹车区域的分界线位置。
- **响应曲线**：支持线性、二次、指数三种映射。

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
