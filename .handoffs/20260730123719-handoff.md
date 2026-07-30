# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T03:58:29+08:00 · Git HEAD: a772be2（本次改动尚未 commit）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 M11 遗留的"踏板配置不即时生效 + 坐标系不一致 + 布局不持久化"三问题。**已完成**：ConfigReceiver 合并写 + OverlayManager 回调重建实现即时生效；左下角原点百分比坐标系默认值；广播不含 position + 游戏进程局部化 position 状态实现持久化。真机已装 APK，待用户实测确认。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `a772be2`，4 个文件改动未 commit。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 3s（compileDebugKotlin 通过，含 smart cast 修复后）。
- [V] `adb install -r app/build/outputs/apk/debug/app-debug.apk` Success（设备 381QYFCN22B9A）。
- [V] 四个文件改动闭环验证（代码审查，非真机）：
  1. `OverlayPosition.kt`：DEFAULT_PEDAL=(0.80,0.45,0.15,0.50)、DEFAULT_BRAKE=(0.05,0.45,0.15,0.50)、DEFAULT_GEAR=与刹车同。用户左下原点坐标 (80,55),(95,55),(80,5),(95,5) 转 Android 原生（左上原点 y 向下）：top=1-0.55=0.45, height=0.55-0.05=0.50。
  2. `ModConfig.kt` write：去掉 put(KEY_PEDAL_POSITION/GEAR/BRAKE) 三行，广播和模块 filesDir 备份都不含 position。
  3. `ConfigReceiver.kt`：onReceive 改合并写（读已有 JSON，incoming 覆盖非 position 字段，position 保留游戏进程已有）+ 写完调 OverlayManager.notifyConfigChanged()。
  4. `OverlayManager.kt`：companion `@Volatile instance` + notifyConfigChanged()（post 主线程）+ rebuildFromConfigChange()（refreshRoot → 重读 settings → removeGamingOverlays → addGamingOverlays → 按 overlaysVisible 重设可见性 → 若 editMode 则 updateEditModeVisibility）。root 从 val 改 var + refreshRoot()（防 Activity 重建后旧 decorView 失效）。addGamingOverlays DUAL 守卫：enableManualShift && pedalMode != DUAL 才创建 gearView。
- [V] Plan agent 验证报告确认方案正确：双 ClassLoader 安全（第二个 instance 为 null 自动 no-op）、线程安全（post 主线程）、可见性保持、合并逻辑边界、ConfigActivity 不读 position 无影响。
- [?] 真机实测：待用户验证即时生效 + 默认坐标 + 拖拽持久化 + 重启恢复。

### 测试/build 输出 tail

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:assembleDebug
BUILD SUCCESSFUL in 3s
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success
```

## 3. 决策与理由

- **配置即时生效用 ConfigReceiver→OverlayManager 静态回调** [V]——ConfigReceiver 写完 JSON 后调 OverlayManager.notifyConfigChanged()，post 主线程触发 rebuildFromConfigChange 重建 view。根因：PedalOverlayView 构造拷 ModConfig.Settings 快照（data class 值语义），光写文件不够必须重建 view。否决方案：让 PedalOverlayView 不拷快照改读运行时 settings——破坏现有值语义设计，改动大。
- **左下角原点百分比坐标系只改默认值，内部存储保持 Android 原生语义** [V]——OverlayPosition 内部已是百分比分数（左上原点 y 向下），所有转换函数（topPx/leftPx/widthPx/heightPx/fromPixels）不动，只改 DEFAULT_* 数值。用户坐标转换：top_android=1-top_user, height=top_user-bottom_user。否决方案：改 OverlayPosition 内部语义为左下原点——要改所有转换函数和 onTouchEvent 的 rawY 计算，改动大且易错。
- **布局持久化用"广播不含 position + ConfigReceiver 合并写"** [V]——ModConfig.write 去掉 position 三行，ConfigReceiver 收到后合并（保留游戏进程已有的 position，只更新非 position 字段）。position 状态局部化在游戏进程（ConfigReceiver 合并 + saveOverlayPosition 拖拽写 + readFromTargetProcess 读）。根因（最严重 bug）：ConfigMainScreen.saveNow 创建 Settings 时不传 position → 用默认值 → 广播带默认 position → ConfigReceiver 直接 writeText 覆盖游戏进程拖拽后的 position。否决方案：① ConfigMainScreen 读 position 传入——配置页无 position UI，要加状态管理复杂；② saveOverlayPosition 也写模块 filesDir——跨进程同步 position 复杂，且模块进程读不到游戏 externalFilesDir。
- **DUAL + 手动换挡运行时守卫** [V]——addGamingOverlays 里 `enableManualShift && pedalMode != DUAL` 才创建 gearView。用户明确要求两者不能同时开，当前换挡 UI 禁用（enabled=false），守卫防 JSON 手动编辑或未来 UI bug。否决方案：UI 联动（开 DUAL 关换挡）——换挡还禁用着，等启用时再加。
- **root 改 var + refreshRoot()** [V]——Plan agent 发现的 bug：OverlayManager 构造时 root 一次性赋值，Activity 重建后旧 decorView 失效。rebuildFromConfigChange 在广播到达时触发（不在 Activity 生命周期同步点），必须重新获取。removeGamingOverlays/removeExisting 用局部 val parent=root?:return 快照规避 smart cast 限制。

## 4. 失败的尝试 — 不要再试

- **ConfigReceiver 直接 writeText 覆盖** [V]——会让游戏进程 JSON 的 position 字段消失（广播不含 position），下次读取读到默认值，拖拽位置丢失。必须合并写。
- **root 保持 val 不刷新** [V]——Activity 重建后旧 decorView 失效，rebuildFromConfigChange 的 addView/removeView 作用在旧 view 上无效。必须改 var + refreshRoot。
- **removeGamingOverlays/removeExisting 用 `root?.findViewWithTag()?.let { root.removeView(it) }`** [V]——root 改 var 后 Kotlin smart cast 失败（"mutable property could be mutated concurrently"），编译报错。必须用局部 val 快照。
- （前向搬运自 M11）文件直读跨进程 [V]、ContentProvider 跨进程 [V]、createPackageContext [V]、5 参 call 重载 [V]、by lazy 只改缓存不够 [V]、applyCurve 作用单字段 [V]、BRAKE 从底向上画水位式 [V]——均不再试。

## 5. 已知坑

- **原版/共存版布局存档不共用** [V]——原版 `com.Vince.AlamobileFormula` 和共存版 `com.Takotsubo.AlamobileFormula` 各自 externalFilesDir 路径按包名隔离（/storage/emulated/0/Android/data/<包名>/files），两版读写不同文件。ConfigReceiver 合并写机制在两版各自内部都正确，但跨版不共享 position。Android 沙箱设计，无法绕过（外部存储根被 targetSdk 35 scoped storage 拦截）。属预期限制，非 bug。若用户要两版同步布局，需手动各调一次或接受分别配置。
- **Android 11+ scoped storage** [V]——外部存储根 EACCES，Android/data/<pkg> uid 隔离。模块进程和游戏进程各写自己 filesDir/externalFilesDir，跨进程靠定向广播。
- **Android 11+ 包可见性** [V]——targetSdk≥30 默认看不到未在 <queries> 声明的包。Intent.setPackage 定向广播是唯一绕过方式。
- **Android 13+ registerReceiver 需 flag** [V]——RECEIVER_EXPORTED（跨应用）或 RECEIVER_NOT_EXPORTED（同应用），targetSdk 35 强制。
- **广播首次启动滞后** [V]——游戏进程 onPackageReady 注册 receiver 前，ConfigActivity 发的广播丢。首次安装后首次进游戏读默认值；用户改一次配置后 receiver 接收并写入，之后即时生效。
- **PedalOverlayView 构造拷 settings 快照** [V]——加/改 ModConfig.Settings 字段必须同步默认参数构造；配置变更必须重建 view（rebuildFromConfigChange 或 toggle）。
- **applyCurve exponent 方向** [V]——<1 是 ease-out（先快后慢），≥1 是 ease-in。拟真用 0.42（30%→60%）。仅作用于 mapped（送 native），不影响 raw（绘制）。
- **ConfigProvider.kt 已废弃** [V]——广播方案落地后未使用，代码仍在，可删。
- **OverlayManager.settings 不再 by lazy** [V]——改可重读 var，show/toggle/rebuild 时重读 JSON。
- **removeGamingOverlays vs removeExisting** [V]——前者只清踏板/换挡 view 保留 toggle 按钮，后者连按钮清。
- **ModConfig.read() 对 enableAutoDrs 强制 false** [V]——功能未实现，避免老用户升级后开关显示"开"但无效果。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。notifyConfigChanged 在第二个 ClassLoader 是 no-op（instance 为 null）。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。
- **OverlayEditView.resetPosition 重置到运行时值非出厂默认** [?]——defaultPosition 参数传的是 settings.pedalPosition 不是 OverlayPosition.DEFAULT_*。长按重置是重置到当前已保存位置，不是出厂默认。预先存在，与本轮无关；若要重置到出厂默认需改构造参数。

## 6. 下一步（有序）

1. **真机实测确认**——用户验证：① ConfigActivity 改踏板模式切回游戏 overlay 自动变（不用 toggle）；② 默认位置油门右侧 80-95%/刹车左侧 5-20%；③ 拖拽后重启游戏位置恢复；④ 拖拽后改曲线位置不丢。
2. **commit + 发 Beta**——本轮修复闭环，可 commit 后发 Beta（versionCode 按命名规则，versionName `1.0.0 Beta 3` 或按实际版本）。
3. **清理 ConfigProvider.kt**（可选）——广播方案落地后未使用，可删避免混淆。manifest 里 provider 声明一并删。
4. （后续）实现"手动换挡""自动 DRS"——换挡开关当前 UI 禁用，启用时加 DUAL 互斥 UI 联动。

## 7. 留给用户的开放问题

- 原版/共存版布局不共用是否可接受？还是要做同步机制（如 ConfigActivity 写 position 到两版都能读的位置）？当前判定为 Android 沙箱限制，建议接受。
- OverlayEditView 长按重置当前重置到"已保存位置"而非"出厂默认"，是否要改成重置到出厂默认？
- curve exponent=0.42（30%→60%）手感是否合适？需调参吗？
