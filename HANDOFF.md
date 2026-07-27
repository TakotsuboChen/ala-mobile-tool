# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T02:20:00+08:00 · Git HEAD: 工作树有未提交改动（M2 完成）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

M2 完成：用字节跳动 ShadowHook 替换原 `libxposed/ShadowHook` 占位，实现真正的 native inline hook。
完成定义：
- ShadowHook 2.0.1 通过 Maven Central + prefab 集成；
- `setThrottle` / `setBrake` / `drsToggle` 已用 `shadowhook_hook_sym_addr` 安装 hook；
- `./gradlew :app:assembleDebug` 构建成功。

## 2. 已验证状态 — 工作实际停在哪

- [V] 工作树有 10 个文件未提交（`git status` 验证）。
- [V] 当前分支 `main`，与 `origin/main` 同步（`git status` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功，12 秒，BUILD SUCCESSFUL（本次交接刚运行）。
- [V] ShadowHook 依赖 `com.bytedance.android:shadowhook:2.0.1` 从 Maven Central 解析成功。
- [V] CMake 通过 prefab `find_package(shadowhook REQUIRED CONFIG)` 链接 `shadowhook::shadowhook`。
- [V] `libshadowhook.so` 重复文件警告已通过 `packaging.jniLibs.pickFirsts` 消除。
- [?] 运行时 ShadowHook 初始化代码已加入 `AlaMobileModule.onPackageReady()`，未在真机/模拟器验证。
- [?] `setThrottle` / `setBrake` / `drsToggle` 的 proxy 函数按实例方法签名假设，需在运行时验证是否匹配 IL2CPP 实际 calling convention。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE
[Incubating] Problems report is available at: file:///home/takotsubo/projects/ala-mobile-tool/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 12s
37 actionable tasks: 2 executed, 35 up-to-date
```

## 3. 决策与理由

- 选用字节跳动 `com.bytedance.android:shadowhook:2.0.1` [V]——`libxposed/ShadowHook` repo 404 不存在；字节跳动 ShadowHook 活跃维护，Maven Central + prefab 集成最干净。否决方案：源码 submodule / 预编译 so；否决原因：增加构建复杂度和 ABI 管理。
- 使用 `ShadowHook.Mode.SHARED` [?]——适合多 hook 共享 proxy。如运行时遇到 TLS 问题可换 `UNIQUE`。
- 踏板覆盖策略 [?]：Java overlay 写入目标油门/刹车值，native proxy 用全局变量替换原函数参数。理由：最直接，不需要反向调用 Java。
- DRS 策略 [?]：当前实现是“用户请求 DRS 时才允许 drsToggle 调用”，尚未读取 telemetry 字段。理由：先把 hook 链路跑通，telemetry 自动策略留给后续切片。
- 换挡 hook [?]：offset 已传递到 native，但当前未安装 hook。理由：踏板覆盖稳定后再实现换挡，避免一次性改动过多难以调试。

## 4. 失败的尝试 — 不要再试

- `libxposed/ShadowHook` GitHub repo [V]——API 返回 404，repo 不存在。不要再尝试从该地址下载。
- `com.bytedance.android:shadowhook:1.2.6` [V]——Maven Central 不存在该版本，构建时报 `Could not find com.bytedance.android:shadowhook:1.2.6`。已改用 2.0.1。
- 手工下载并放置 `libshadowhook.so` [V]——prefab 已自动处理 so 和 header，无需放到 `native/libs/`。
- 直接访问 `dl.google.com`（旧订阅）[V]——Clash TUN 劫持导致 TLS 失败。换订阅后已恢复，但保留镜像配置作为 fallback。

## 5. 已知坑

- `libxposed/ShadowHook` repo 不存在 [V]——已改用字节跳动 ShadowHook。
- `setThrottle` / `setBrake` / `drsToggle` 的 proxy 函数签名假设必须与原 IL2CPP 方法一致 [?]。当前假设：
  - `setThrottle(float)` → `void proxy(void* this, float value)`
  - `setBrake(float)` → `void proxy(void* this, float value)`
  - `drsToggle()` → `void proxy(void* this)`
  若 IL2CPP 编译器优化或 calling convention 不同，hook 会导致崩溃。
- `/proc/self/maps` 解析基址只取第一个匹配 `libil2cpp.so` 的映射 [?]。如果游戏以后加载多个 il2cpp 映射，需要更精细的解析。
- 全局 `g_throttle_value` / `g_brake_value` 是 `volatile float`，没有加锁 [?]。overlay 更新和 IL2CPP 调用可能在不同线程，但单次 float 写是原子的，可接受轻微抖动。
- DRS 当前只是“开关”逻辑，没有真正的自动判断，后续需要读取 telemetry 字段。
- 阿里云 Maven 镜像没有 ShadowHook，依赖解析会回退到 Maven Central [V]——构建成功，无需改动。
- `platform-37.0_r02.zip` 的 `source.properties` 里 `ApiLevel=37.0` 有小数点 [V]——已 sed 修正，重新解压需再次修正。
- Il2CppDumper 在非交互终端最后会抛 `Console.ReadKey` 异常 [V]——dump 已生成，忽略。
- .NET 6 运行 Il2CppDumper 需 `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` [V]。

## 6. 下一步（有序）

1. 在真机/模拟器（已 root + LSPosed）安装模块，验证 ShadowHook 初始化不崩溃、踏板/DRS hook 能工作。
2. 用 `adb logcat | grep AlaMobileTool` 观察 hook 是否命中，确认函数签名假设正确。
3. 如签名不匹配，用 Il2CppDumper + 反汇编修正 proxy 函数签名。
4. 完善 `PedalOverlayView.updateValues()` 的踏板映射曲线和死区（仍 TODO）。
5. 实现换挡 hook（`shiftUp` / `shiftDown` / `setGear`），并给 overlay 按钮接入 native 调用。
6. DRS：读取 `inDRSZone`、车速、油门等 telemetry 字段，实现自动开启/关闭逻辑。
7. `ConfigActivity` 写入 SharedPreferences，`AlaMobileModule` 读取开关状态，替换 hard-coded true。

## 7. 留给用户的开放问题

- 是否现在提交并推送这些改动？（建议先测试再提交）
- 踏板映射曲线：线性、指数、还是分段？死区范围多大？
- DRS 自动策略具体阈值（车速、油门百分比、是否在 DRS 区域）？
- 换挡按钮行为：用户点击时直接调用原 `shiftUp`/`shiftDown`，还是让 hook 代理游戏调用？
