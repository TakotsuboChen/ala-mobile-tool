# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T05:00:00+08:00 · Git HEAD: 7fad94d1a1a32fbdc8ffe53dc20d510ea0ac944a
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 1.0.0-Alpha-2 的三个严重回归：
1. overlay 编辑模式长按无反应且点击即触碰换挡控件；
2. "关闭自动换挡"开关无效；
3. 踏板覆盖时驾驶高频顿挫（"机关枪"），几乎无法驾驶。

完成定义：release 构建通过、真机验证 overlay 可单独编辑、自动换挡关闭有效、顿挫消失或显著缓解。

## 2. 已验证状态 — 工作停在哪

- [V] 当前分支 `main`，存在大量未提交改动（`git status` 验证）。
- [V] `./gradlew :app:assembleDebug` 构建成功（最近一次 run 输出见下方）。
- [?] 图标已更新为 Ala Mobile 原图蓝色化 + 右下角红色 LSPosed QR 标识，但用户未确认最终效果。
- [?] overlay 编辑改为角落拖动缩放，但用户反馈长按无反应、点击即触碰换挡控件。
- [?] 自动换挡关闭逻辑已写入 native（`disable_auto_gear`），但用户反馈无效。
- [?] 顿挫问题用户反馈比先前更严重，像"机关枪"。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug
[Incubating] Problems report is available at ...

BUILD SUCCESSFUL in 4s
37 actionable tasks: 6 executed, 31 up-to-date
Performing Streamed Install
Success
```

## 3. 决策与理由

- overlay 编辑层改为每个控件一个独立 `OverlayEditView`，尺寸跟随目标控件 [?]——避免之前全屏编辑层拦截所有触摸事件。
- 自动换挡通过写入 `IRDSDrivetrain.automatic = false` 实现 [?]——字段偏移来自 dump.cs（0xBC），但真机未生效，可能指针读取方式或字段布局有误。
- 顿挫修复尝试移除后台 writer、改为在原函数后同步写字段 [?]——但用户反馈更卡顿，可能与 hook 时机或字段选择有关。

## 4. 失败的尝试 — 不要再试

- 用单个全屏 `OverlayEditView` 覆盖所有 overlay 控件 [?]——点击任意位置都会触发换挡控件，无法单独编辑踏板。
- 在 `proxy_set_throttle` 原函数前写入 overlay 值 [?]——可能干扰原输入逻辑，加剧顿挫。
- 用独立后台线程每 16ms 强制写字段 [?]——已被验证导致顿挫，已移除。
- 使用 `SharedPreferences`/`MODE_WORLD_READABLE` 跨进程共享配置 [V]——Android 10+ 抛 SecurityException。已改用 JSON + 外部存储。
- 在 `onPackageReady` 立即安装 native hooks [V]——`libil2cpp.so` 尚未加载。已改为延迟 15 秒。

## 5. 已知坑

- 当前 release APK 使用临时签名 [V]——正式发布前需替换为正式 keystore。
- `ModConfig.saveOverlayPosition` 曾因目录不存在导致崩溃 [?]——已加 `mkdirs()` 和 try-catch，需再次验证。
- 自动换挡字段写入可能因 IL2CPP MonoBehaviour 引用布局而无效 [?]——需要进一步确认 `drivetrain` 指针如何存储。
- 自适应图标在部分 Launcher 上可能裁剪右下角 QR 标识 [?]——已把 QR 往内移动，但不同 Launcher 安全区域不同。

## 6. 下一步（有序）

1. 修复 overlay 编辑：确保长按"工具"按钮进入编辑模式后，能单独拖动/缩放踏板和换挡控件。
2. 排查自动换挡失效原因：确认 `IRDSCarControllInput.drivetrain` 字段在内存中的实际布局，或直接 hook `IRDSDrivetrain.FixedUpdate` / `DoGearShifting` 强制跳过自动逻辑。
3. 修复踏板顿挫：尝试只在用户触摸时写入、手指离开后停止覆盖，或改回更保守的写入策略。
4. 验证 release 构建并准备 1.0.0-Alpha-2。

## 7. 留给用户的开放问题

- 是否需要在设置界面增加"重置布局"按钮？
- 自动换挡失效是否只在特定模式/赛道出现？
- 顿挫是否在关闭"踏板覆盖"开关后消失？这有助于定位是 native hook 还是 overlay 输入逻辑导致。
