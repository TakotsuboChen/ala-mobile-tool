# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T07:05:00+08:00 · Git HEAD: 51fee08fe0d7c3a8b0d0e0a0a0f0c0d0e0f0a0b0c
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

继续修复 1.0.0-Alpha-2 的回归：踏板顿挫、自动换挡无效，并按用户要求完成 ConfigActivity 的 MIUIX UI 重构。
完成定义：踏板持续加油/刹车稳定；自动换挡关闭后不再自动升档；ConfigActivity 三页 UI 符合 MIUIX 风格。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，工作区有未提交改动（`git status` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功（输出见下方 tail）。
- [V] overlay 编辑层问题已修复：拖动、缩放、长按重置均正常。
- [?] 踏板顿挫问题已尝试修复：native `pedal_hook.c` 中 setter 改为 active 时直接 return，writer 频率提升到 2ms，FixedUpdate 前后都写入。用户反馈"已经正常，且没有性能损失"。
- [?] ConfigActivity 已重构为三页底栏导航，但用户反馈仍需继续调整 UI 细节（小标题对齐、响应曲线 MIUIX 组件、图标、激活状态检测等）。
- [?] 自动换挡关闭（disable_auto_gear）仍待排查，未在本次会话中验证。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:assembleDebug UP-TO-DATE
[Incubating] Problems report is available at .../problems-report.html

BUILD SUCCESSFUL in 1s
39 actionable tasks: 2 executed, 37 up-to-date
```

## 3. 决策与理由

- overlay 编辑层直接同步目标 view 和编辑层 LayoutParams [?]——避免编辑层与目标控件解耦、闪烁、突然放大。
- 用 `event.rawX/rawY` 计算移动/缩放 delta [?]——避免视图坐标跳变导致尺寸突变。
- setter active 时直接 return，由 writer 线程负责字段写入 [?]——减少 setter 和 writer 竞争导致的高频顿挫。
- writer 频率从 16ms 提升到 2ms [?]——扩大输入覆盖窗口。
- 配置即时保存 + 300ms debounce [?]——用户要求去掉保存按钮，调整即保存。

## 4. 失败的尝试 — 不要再试

- 用 `translationX/Y` 移动编辑层而目标 view 不动 [?]——导致编辑层与目标控件完全解耦、位置错乱。
- 在 `setThrottleInput` 中拦截并返回不调用原函数 [?]——Alpha-1 曾导致踏板完全无响应；本次改为 active 时直接 return，依赖 writer 写入，初步验证正常。
- 只在 `FixedUpdate` 后写入字段、跳过相同值 [?]——输入被游戏每帧覆盖，出现"机关枪"顿挫和快速归零。
- 用 `/storage/emulated/0/AlaMobileTool/ala_tool_config.json` 保存 overlay 位置且无权限保护 [?]——在目标游戏进程写入时崩溃，已加 try-catch。
- 用 miuix `MiuixIcons.Basic.*` 内置图标 [V]——编译失败，`MiuixIcons` 在 0.9.3 中不存在或不可访问，改用 material icons。

## 5. 已知坑

- 当前 release APK 使用临时签名 [V]——正式发布前需替换为正式 keystore。
- `ModConfig.saveOverlayPosition` 在目标进程写入外部存储可能失败 [?]——已加 try-catch 忽略失败，但位置不会持久化。
- 自动换挡字段写入可能因 IL2CPP MonoBehaviour 引用布局而无效 [?]——需要进一步确认 `drivetrain` 指针和 `automatic` 字段的实际布局。
- 新添加的 material3 和 material-icons-core 依赖可能增加 APK 体积 [?]——当前 debug 构建无影响。
- LSPosed 激活状态检测不可靠 [?]——当前通过 flag 文件 + 检查 LSPosed Manager 包名实现，无法 100% 确认模块是否已启用。

## 6. 下一步（有序）

1. 继续按用户反馈精修 ConfigActivity UI：
   - 确保只有概览页显示"Ala Mobile Tool"大标题。
   - 激活状态卡片用 MIUIX 风格（绿色/红色图标）。
   - 链接卡片加图标，第二行小字淡色。
   - 配置页小标题和卡片左对齐，字调大。
   - 响应曲线改用 MIUIX 组件（非 material3 DropdownMenu）。
   - 设置页"日志"小标题和卡片对齐。
2. 测试踏板顿挫修复在真实游戏中的长期稳定性。
3. 排查自动换挡失效原因：确认 `IRDSCarControllInput` 中 `drivetrain` 字段偏移（0x98）和 `IRDSDrivetrain.automatic` 字段偏移（0xBC）是否仍正确，或改用 hook `IRDSDrivetrain.FixedUpdate` 写入。
4. 验证 release 构建并准备 1.0.0-Alpha-2。

## 7. 留给用户的开放问题

- UI 当前版本是否符合预期？还有哪些细节需要继续调整？
- 踏板顿挫修复后，长时间游戏是否仍然稳定？
- 关闭自动换挡后，车辆是否会在某个转速自动升档？需要具体的档位/速度观察。
