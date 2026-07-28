# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T09:30:00+08:00 · Git HEAD: e366b88
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

发布 v1.0.0-Alpha-2 到 Pre-release，完成 UI 重构工作。
完成定义：`./gradlew :app:assembleRelease` 构建成功，GitHub Release 已创建并上传 APK。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，工作区干净（`git status` 验证）。
- [V] 最新版本：v1.0.0-Alpha-2 (100120)，已推送到 GitHub 并创建 Pre-release。
- [V] `./gradlew :app:assembleRelease` 构建成功，APK 大小 2.0M。
- [V] UI 重构为 KernelSU 风格的三页布局（概览/配置/设置）。
- [V] 深色模式正常工作，激活卡片自适应亮/暗主题。
- [V] 开关和滑条即时反馈正常。
- [V] 下拉菜单（响应曲线选择）正常工作。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:assembleRelease
[Incubating] Problems report is available at: file:///home/takotsubo/projects/ala-mobile-tool/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 1m 20s
54 actionable tasks: 50 executed, 4 from cache
```

## 3. 决策与理由

- 使用 KernelSU 风格的 UI 布局 [V]——用户要求参考 KernelSU/InstallerX 的设计。否决方案：继续使用原生 Compose 组件，因为缺乏 miuix 的统一视觉风格。
- 激活卡片使用大尺寸背景图标 [V]——参考 KernelSU 的 StatusCard 实现，视觉效果更突出。否决方案：简单文本显示，因为缺乏视觉层次。
- 禁用开发中功能（自动 DRS、关闭自动换挡）[V]——这些功能尚未实现，避免用户误操作。否决方案：隐藏功能，因为需要在 UI 中展示开发路线图。

## 4. 失败的尝试 — 不要再试

- 用 `by mutableStateOf(...)` 声明属性 [V]——导致 "Property delegate must have a 'getValue' method" 错误。原因：Compose 的 `getValue`/`setValue` 扩展函数需要正确导入 `androidx.compose.runtime.getValue` 和 `setValue`。不要再试，改用显式 `.value` 访问或确保导入。
- 用 miuix `SliderPreference(minValue=..., maxValue=..., valueFormatter=...)` [V]——编译失败，参数名不对（miuix 0.9.3 的 SliderPreference 签名是 `SliderPreference-N3THUTQ(float, Function1<Float, Unit>, ...)`，没有 minValue/maxValue/valueFormatter 参数）。不要再试，改用原生 Slider 或查看 miuix 源码确认正确参数。
- 用 `MiuixIcons.Basic.*` 内置图标 [V]——编译失败，`MiuixIcons` 在 0.9.3 中不存在或不可访问。不要再试，改用 material icons。
- 用 `by mutableIntStateOf(...)` 声明 selectedPage [V]——导致 delegate 错误，原因同上。不要再试，改用 `var selectedPage = mutableStateOf(...).value` 或显式导入。
- 用 `OverlayDropdownPreference(items=..., onSelectedIndexChange=..., navigationIcon=...)` [V]——参数名不对。正确参数名是 `items`、`onSelectedIndexChange`、`startAction`。不要再试错误参数名。

## 5. 已知坑

- miuix 0.9.3 的 preference API 签名与文档/KernelSU 代码不符 [V]——javap 显示 SliderPreference 没有 minValue/maxValue/valueFormatter 参数，可能版本差异或 KernelSU 用了自定义封装。
- Compose property delegate 需要显式导入 `getValue`/`setValue` [V]——否则会出现 "no method 'getValue'" 错误。
- material-icons-core 只包含基础图标 [V]——Rounded.Animation、Rounded.Gesture 等在 extended 包中。
- NavigationRailItem 是 ColumnScope 的扩展函数 [?]——不能在 NavigationRail 的 lambda 中直接调用，需要用 `this.NavigationRailItem` 或确保在正确 scope 中。
- WindowInsets.displayCutout 在某些 API 级别不可用 [V]——已移除相关代码。
- `OverlayDropdownPreference` 需要 `NavigationEventDispatcherOwner` [V]——否则会抛出 `IllegalStateException`，需要在 Activity 的 `setContent` 中提供。

## 6. 下一步（有序）

1. 测试 v1.0.0-Alpha-2 在真实设备上的表现，收集用户反馈。
2. 根据用户反馈修复 UI 问题或调整样式。
3. 继续开发自动 DRS 功能（M5 后续工作）。
4. 实现关闭自动换挡功能。
5. 优化性能和稳定性。

## 7. 留给用户的开放问题

- 自动 DRS 的实现策略：是基于 telemetry 判断还是继续用开关拦截？
- 是否需要支持更多游戏版本（非 8.0.0）？
- UI 细节是否需要进一步调整（字体大小、间距、颜色等）？
- 是否需要添加更多配置选项（如踏板透明度、位置记忆等）？
