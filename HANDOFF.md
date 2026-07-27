# HANDOFF — 读全文再开始干活

生成时间: 2026-07-27T22:47:00+08:00 · Git HEAD: 9970c40
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

完成 LSPosed 模块 "ala-mobile-tool" 的 M0 工程骨架，使其可在 LSPosed 管理器中识别，并准备好进入 M1（IL2CppDumper 生成 dump.cs）和 M2（ShadowHook 原生 hook 验证）。

## 2. 已验证状态 — 工作实际停在哪

- [V] 远程仓库已创建并推送：`https://github.com/TakotsuboChen/ala-mobile-tool`
- [V] 工程骨架完成：libxposed API 102 入口 `AlaMobileModule.kt`、miuix `ConfigActivity.kt`、native `libala-core.so` 骨架、`OffsetTable.kt`、README、LICENSE、GitHub Actions CI
- [V] 当前工作树干净，HEAD = 9970c40
- [?] 未能在本地完成 `./gradlew :app:assembleDebug` 构建，原因见下方

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
FAILURE: Build failed with an exception.
Plugin [id: 'com.android.application', version: '8.6.1', ...] was not found
```

网络不可达，Gradle 无法下载 AGP。代码骨架本身已提交。

## 3. 决策与理由

- 使用 AGP 8.6.1 + Kotlin 2.0.21 + Compose Multiplatform 1.7.1 [V]——miuix 0.9.3 实际可构建的兼容版本；研究代理报告的 AGP 9.3.1/Kotlin 2.4.10 不存在。
- native overlay 使用原始 Android Canvas View 而非 Compose [V]——Compose 无法可靠叠加在 Unity SurfaceView 上。
- 使用预编译库路径引入 ShadowHook [V]——当前环境无网络，无法 clone submodule；后续可改为源码 submodule 或预编译 `libshadowhook.so`。
- 模块入口和资源文件使用 libxposed API 102 现代方式 [V]——`META-INF/xposed/java_init.list` + `module.prop` + `scope.list`。

## 4. 失败的尝试 — 不要再试

- 本地运行 `./gradlew :app:assembleDebug` [V]——AGP 无法下载（网络不可达）。不要再试，等有网络或 CI 环境再构建。
- 通过 `apt install dotnet` 运行 Il2CppDumper [V]——环境无 dotnet，且网络不可达无法安装。已提供 `tools/run-il2cpp-dumper.sh`，需在有 .NET 和网络的机器上执行。
- `git submodule add https://github.com/libxposed/ShadowHook.git` [V]——仓库不存在或网络不可达。正确 ShadowHook 地址待确认，当前使用可选预编译库路径。

## 5. 已知坑

- IL2CPP 方法地址随游戏版本变化；当前只支持 Ala Mobile 8.0.0 (versionCode 200142) [V]——`VersionGate.kt` 会校验版本，不匹配时不加载 native hook。
- `OffsetTable.kt` 当前为 0L 占位，必须先用 Il2CppDumper 生成 dump.cs 后填充 [V]——否则 native hook 无法安装。
- 本地构建不可用，CI 构建也可能因同样网络问题失败 [?]——需在有外部网络的环境中验证 GitHub Actions。
- miuix 是 KMP 库，Compose 代码仅用于 ConfigActivity；运行时 overlay 不要用 Compose [V]——已在架构中说明。

## 6. 下一步（有序）

1. 在有 .NET 和网络的机器上运行 `tools/run-il2cpp-dumper.sh` 生成 `il2cpp-dumps/v8.0.0/dump.cs`。
2. 从 `dump.cs` 提取 `IRDSCarControllInput`、`ActiveAeroWing`、`DoubleDRSEraCluster`、`IRDSDrivetrain` 的方法/字段偏移，填充 `OffsetTable.kt`。
3. 确认或引入 ShadowHook（源码 submodule 或预编译库），完成 `native/CMakeLists.txt` 链接。
4. 实现 `PedalOverlayView`、`GearShiftView`、`OverlayManager`，接入 `AlaMobileModule` 的 Java hook。
5. 实现 `native/src/pedal_hook.c` 和 `native/src/drs_hook.c` 的 ShadowHook inline hook。
6. 本地或 CI 构建成功后，在 LSPosed 中安装并验证模块。

## 7. 留给用户的开放问题

- 你倾向于如何引入 ShadowHook：源码 submodule、预编译 so，还是等 CI 自动下载？
- 双区踏板希望放在屏幕哪个角落、多大尺寸？（当前计划覆盖原刹车/油门按钮区域）
