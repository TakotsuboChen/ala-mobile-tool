# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T01:50:00+08:00 · Git HEAD: ff5ca81e656f06dd7170749b20571760adab7637
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

推进 M4：让踏板/换挡 overlay 在游戏内正确显示，并验证 ShadowHook hook 后游戏输入是否生效。

完成定义：
- LSPosed 能稳定识别并加载模块；
- `libil2cpp.so` 加载后成功安装 throttle/brake/DRS hooks；
- overlay 默认隐藏，通过左上角开关手动显示/隐藏；
- 点击 overlay 踏板/换挡时，游戏车辆有响应；
- 设置界面按 MIUIX 规范重写，不再用 Button 代替 Slider/Switch。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，与 `origin/main` 同步（`git status` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功，2 秒，BUILD SUCCESSFUL（本次交接刚运行）。
- [V] LSPosed 能识别并加载模块；`module.prop` 最终格式与 libxposed 示例一致。
- [V] `AlaMobileModule` 在目标进程加载，logcat 能打印 `Module loaded` / `Package ready`。
- [V] 延迟 15 秒后 hook 安装成功；`dl_iterate_phdr` 能定位到 `libil2cpp.so`。
- [V] `shadowhook_hook_sym_addr` 成功 hook `setThrottle`、`setBrake`、`drsToggle`。
- [V] overlay 能显示/隐藏，且新增左上角「工具」开关按钮。
- [?] 踏板/换挡 hook 是否真正影响车辆输入，尚未在赛道上验证。
- [?] proxy 函数签名是否完全匹配 IL2CPP 实际 calling convention 仍需真机 logcat 确认。
- [?] 设置界面仍是 Button 拼凑，未使用 miuix 的 Slider/Switch。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE
[Incubating] Problems report is available at: file:///home/takotsubo/projects/ala-mobile-tool/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 1s
37 actionable tasks: 2 executed, 35 up-to-date
```

最近一次真机 hook 成功日志：
```
AlaMobileTool: Hooked setThrottle at 0x77b1d281f4
AlaMobileTool: Hooked setBrake at 0x77b1d281d8
AlaMobileTool: Hooked drsToggle at 0x77b1d2a0ac
```

## 3. 决策与理由

- 改用 `dl_iterate_phdr` 定位 `libil2cpp.so` [V]——`/proc/self/maps` 解析失败（权限/解析问题），`dl_iterate_phdr` 更可靠。
- hook 安装延迟到 `onPackageReady` 后 15 秒 [V]——`libil2cpp.so` 在 `onPackageReady` 时尚未加载，直接 hook 会找不到模块。
- 配置持久化改用 JSON + 外部存储 [V]——Android 10+ 已移除 `MODE_WORLD_READABLE`，`SharedPreferences` 跨进程崩溃。
- overlay 改为默认隐藏，左上角提供开关 [V]——用户反馈 overlay 挡住游戏菜单 UI。

## 4. 失败的尝试 — 不要再试

- `Context.MODE_WORLD_READABLE` [V]——Android 10+ 直接抛 `SecurityException`，模块设置界面都进不去。已改用 JSON 外部存储。
- `assets/xposed_init` 作为入口 [V]——与 `META-INF/xposed/java_init.list` 冲突，导致 LSPosed 列表里模块反复出现/消失。只保留 `META-INF/xposed`。
- `module.prop` 里用 `api=102` 替代 `minApiVersion/targetApiVersion` [V]——用户使用的 LSPosed fork 要求官方格式，`api=102` 导致静态作用域失效。
- 在 `onPackageReady` 立即安装 native hooks [V]——`libil2cpp.so` 尚未加载，基址查找失败。必须延迟到游戏加载 il2cpp 后。
- `/proc/self/maps` 解析 `libil2cpp.so` [V]——解析逻辑有 bug，且目标进程可能没有 `/proc/self/maps` 读取权限。已改用 `dl_iterate_phdr`。

## 5. 已知坑

- `setThrottle` / `setBrake` / `drsToggle` 的 proxy 函数签名假设必须与原 IL2CPP 方法一致 [?]。当前假设：
  - `setThrottle(float)` -> `void proxy(void* this, float value)`
  - `setBrake(float)` -> `void proxy(void* this, float value)`
  - `drsToggle()` -> `void proxy(void* this)`
  若 IL2CPP 编译器优化或 calling convention 不同，hook 会导致崩溃或无效。
- overlay 目前是通过 `Activity.decorView` 的 `content` 添加的 View，可能会被 Unity 的 SurfaceView 覆盖或拦截触摸 [?]。
- 全局 `g_throttle_value` / `g_brake_value` 是 `volatile float`，没有加锁 [?]。叠加更新和 IL2CPP 调用可能在不同线程，但单次 float 写是原子的，可接受轻微抖动。
- DRS 当前只是"开关"逻辑，没有真正的自动判断，后续需要读取 telemetry 字段。
- 设置 UI 仍用 Button 拼凑，未使用 miuix 的 Slider/Switch [?]。

## 6. 下一步（有序）

1. 打开游戏，进赛道，点击「工具」开关显示 overlay，验证点击踏板/换挡是否生效。
2. 如果 hook 无效，在 `pedal_hook.c` 中加日志确认 `proxy_set_throttle` / `proxy_set_brake` 是否被调用。
3. 如果 proxy 被调用但车辆无反应，检查 IL2CPP 方法签名/calling convention，必要时 hook 字段而非 setter。
4. 修复 overlay 显示不全问题（「工具」按钮文字被截断，已临时把按钮调大到 70dp）。
5. 按 miuix 规范重写 `ConfigActivity`：使用 `Switch`、`Slider`、`SegmentedButton` 等标准组件。
6. 实现运行时热重载配置（可选）。

## 7. 留给用户的开放问题

- 是否希望设置界面先按 MIUIX 官方示例重写？
- 踏板/换挡 overlay 的默认大小和位置是否符合操作习惯？
- 是否需要自动检测进赛道后再显示 overlay？
