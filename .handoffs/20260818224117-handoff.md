# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T21:20:00+08:00 · Git HEAD: `f9dcb86`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f9dcb86` (2026-08-18)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f9dcb86`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：配置页 UI 重组 + QQ 群卡片直接跳转 QQ App**。两件事已完成并提交。

## 2. 已验证状态 — 工作实际停在哪

- [V] **配置页重组提交** — `c540294`：「功能开关」改名「游戏原生功能控制」，「替换主菜单音乐」移至页面最底部新增「杂项」小标题。已 push。
- [V] **QQ 群跳转提交** — `f9dcb86`：新增 `openQqGroup()` 工具函数，`mqqapi://card/show_pslcard` scheme 直接拉起 QQ 群资料页，失败降级到网页。已 push。
- [V] **构建通过** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL。
- [V] **APK 已安装** — `adb install -r` → Success，设备 `381QYFCN22B9A`。
- [V] **配置页重组真机验证** — 用户确认「好了」。
- [V] **QQ 群跳转真机验证** — 用户确认「可以了」。
- [?] **弹窗返回修复真机验证未完成** — `ac21138` 已提交，SupportDialog/UpdateDialog/NonRootConfirmDialog 按返回关闭弹窗、EULA 按返回退桌面（门控）。
- [?] **M50 游戏版本检测胶囊** — `06b584c` 已提交，真机验证项待用户逐项确认。
- [?] **M49 弹窗退出动画 + 检查更新 + 支持开发** — `c03eae3` 后，真机验证项待确认。
- [?] **M47 EULA 启动门控** — `d115618` 已提交，真机验证未确认。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除「显示悬浮窗」开关** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 5s
adb install -r app-debug.apk → Success
```

## 3. 决策与理由

- **QQ 群跳转用 `mqqapi://card/show_pslcard` 而非 `mqqopensdkapi://bizAgent/qm/qr`** [V]——后者需要官方加群组件生成的 `idkey`，universal-share URL 的 `authKey` 不是 `idkey`，用了 QQ 接住 scheme 但解析失败（转圈后消失）。`show_pslcard` 只需 `groupCode`，打开群资料页让用户手动申请加群。否决方案：① `mqqopensdkapi` + authKey——已证伪，authKey ≠ idkey；② 需要群主在 qun.qq.com 生成 idkey 才能用官方一键加群，成本高。
- **「替换主菜单音乐」移出「游戏原生功能控制」** [V]——音乐替换是模块自带资源替换，不是 hook 游戏原生功能。独立放「杂项」小标题下，语义分层更清晰。

## 4. 失败的尝试 — 不要再试

> 以下全部从旧 HANDOFF 前向搬运，本会话未重新验证，标 [?]。

- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定：每条应只描述自己那条轴。
- [?] 胶囊放在 LazyColumn 首项 — 用户要求放在大标题上方的空白处。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 状态栏高度被算两次。改用 `Box` 叠加。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算 `WindowInsets.statusBars.getTop(density)`。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp` 常量。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — spring 动画不跟随 `fraction` 即时变化。
- [?] `fraction` 阈值分段 — spring 动画完成时机不可从 `fraction` 推断。
- [?] `translationY` 物理移出视区 + alpha 渐隐 — spring 动画滞后导致不同步。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)` 即时隐藏。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到系统返回事件，直接 finish 退桌面。改用 `ComponentActivity` 自带的。
- [X] **`mqqopensdkapi://bizAgent/qm/qr` + universal-share authKey 作为 key** — QQ 接住 scheme 但解析失败，短暂转圈后消失。authKey ≠ 官方加群组件的 idkey。不要再试。

## 5. 已知坑

- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable` 而非线性公式** [V]——不跟随 `collapsedFraction` 即时变化，任何用 `fraction` 线性驱动的联动动画都可能在某个滑动速度下与小标题不同步。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [V]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次。用 `Box` 叠加。
- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [V]——用 `Text` 显示进度百分比替代。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 minSdk 差异，用 `values-vNN` 或 `SDK_INT` 守卫。
- ⚠️ **不能手动 `rememberNavigationEventDispatcherOwner` 覆盖 Activity 自带的 dispatcher owner** [V]——`activity 1.13.0` 的 `ComponentActivity` 已实现 `NavigationEventDispatcherOwner` 并绑定 `OnBackPressedDispatcher`。手动创建独立 dispatcher 未绑定 `OnBackPressedDispatcher`，弹窗 `NavigationBackHandler` 收不到系统返回事件。
- ⚠️ **QQ universal-share URL 的 `authKey` ≠ 官方加群组件的 `idkey`** [V]——`mqqopensdkapi://bizAgent/qm/qr` 的 `k=` 参数需要群主在 `qun.qq.com/join.html` 生成的 idkey，不是 universal-share URL 里的 authKey。用错了 QQ 会接住 scheme 但解析失败。

## 6. 下一步（有序）

1. **真机验证弹窗返回修复** — SupportDialog/UpdateDialog(非下载态)/NonRootConfirmDialog 按返回关闭弹窗、EULA 按返回退桌面（门控）。
2. **真机确认文案修改** — 弹窗标题「向开发者捐赠」、响应曲线 summary。
3. **真机验证 M50 胶囊** — 官版/共存版三态、亮暗色、下滑即时隐藏/上滑延迟渐显。
4. **真机验证 M49 各项** — 弹窗退出动画、检查更新、支持开发卡片。
5. **真机验证 M47 EULA 启动门控**。
6. **验证 position 合并修复** — 切双踏板再切回单踏板，位置/大小是否保持。
7. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- 弹窗返回修复真机表现是否满意？
- 文案修改（弹窗标题、两条响应曲线 summary）真机表现是否满意？
- M49 各项真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 是否计划近期发 stable release？