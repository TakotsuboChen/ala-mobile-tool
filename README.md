# Ala Mobile Tool

一个开源的 LSPosed 模块，用于扩展 **Ala Mobile**（包名 `com.Vince.AlamobileFormula`）的操控与 DRS/主动空力体验。

> ⚠️ **仅供个人学习与研究使用**。本模块会修改游戏的运行时行为，在多人/在线模式下使用可能违反服务条款并导致封号。请仅在单机模式下使用。

## 功能

- **控件替换**：将原始刹车/油门按钮替换为双区线性踏板（上半油门、下半刹车），并提供升/降档按钮。
- **自动 DRS / 主动空力**：过线或在 DRS 区时自动开启 DRS/主动空力，无需手动操作。
- **miuix 配置界面**：基于 Jetpack Compose + miuix 的现代设置页。

## 已测试版本

| 版本 | versionCode | 架构 |
|---|---|---|
| 8.0.0 | 200142 | arm64-v8a |

其他版本请自行测试。IL2CPP 方法地址随版本变化，非 8.0.0 版本可能无法正确加载原生 hook。

## 技术栈

- 现代 libxposed API 102
- Kotlin + Jetpack Compose + miuix
- ShadowHook（libxposed 生态）+ IL2CPP inline hook

## 构建

需要 Android SDK 和 NDK。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 逆向工具

```bash
tools/run-il2cpp-dumper.sh
```

该脚本会调用 [Il2CppDumper](https://github.com/Perfare/Il2CppDumper) 生成 `il2cpp-dumps/v8.0.0/dump.cs`，随后需要手动提取目标方法/字段偏移并更新 `OffsetTable.kt`。

## 许可证

Apache-2.0
