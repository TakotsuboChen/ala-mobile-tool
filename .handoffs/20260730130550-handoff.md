# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T16:30:00+08:00 · Git HEAD: 即将 commit
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

本次切片完成：DUAL 模式新增"刹车踏板方向反转"开关，红色填充方向与游戏内刹车输入同步反转，真机验证通过。**下一步仍是 M14 遗留核心**：配置同步 M11 首次滞后 bug 的 openRemoteFile/XSharedPreferences 迁移。

## 2. 已验证状态 — 工作实际停在哪

- [V] **刹车方向反转开关真机验证通过**：用户确认"可以了，功能正常"。改动 4 文件：`ModConfig.kt`（新增 `KEY_BRAKE_INVERT`/`Defaults.BRAKE_INVERT=false`/`Settings.brakeInvert` 字段，read/write/fromJson/defaultSettings 全补）、`ConfigMainScreen.kt`（`ConfigUiState.brakeInvert` + saveNow 构造补字段）、`ConfigurePage.kt`（DUAL 的 `AnimatedVisibility` 改 Column 包 SliderRow + 新 SwitchRow，summary 文字修正过一次）、`PedalOverlayView.kt`（默认参数加 brakeInvert=false；`updateDedicatedBrake` `raw = if (brakeInvert) t else 1-t`；`onDraw` BRAKE 分支按 invert 切 `drawRect(0,0,w,height*raw)` vs `drawRect(top,w,h)`；类头注释补 invert 说明）。
- [V] **build + lint 全绿**：`./gradlew :app:assembleDebug :app:lint` BUILD SUCCESSFUL。
- [V] **方向键修复（M14-A）真机验证通过**（前向搬运）：用户确认"方向键和摇杆都正常了"。`pedal_hook.c` writer 在 `!active` 早返回不清零，active→inactive 主动 clear 一次。
- [V] **配置同步 M11 首次滞后 bug 根因已定位**（前向搬运）：游戏没运行时定向广播被系统丢弃，下次启动读到旧值。所有常规跨进程拉取路径（公共目录/ContentProvider/createPackageContext）在 Android 11+ scoped storage + 包可见性下全部实测失效。方案研究完成（XSharedPreferences / openRemoteFile），待落地。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 17s

# 用户原话："可以了，功能正常"
```

## 3. 决策与理由

- **刹车反转：raw 与 mapped 同步反转** [V]——M11 拆分 raw（绘制用）/mapped（送 native）的设计在此兑现红利：只改 `updateDedicatedBrake` 里 `raw` 的取法（`1-t`↔`t`），mapped 经曲线变换自动跟着反转，仲裁用的 raw 也同步，无需改仲裁逻辑或 native 层。否决方案：在 native 层反转——改动面大、与绘制不同源、维护成本高，不用。
- **绘制方向必须分支** [V]——默认红色锚底部（`top..bottom`，raw=1-t），反转红色锚顶部（`0..bottom`，raw=t），是两个不同的 drawRect，不能统一公式（raw 语义变了）。第一次想用统一公式偷懒，复核时发现方向不对应。
- **开关卡片放在刹车过渡点 AnimatedVisibility 块内** [V]——同属 DUAL 刹车相关配置，与过渡点滑块同展开/收起动画，语义聚合。否决方案：单独 AnimatedVisibility 块——重复动画代码、视觉割裂，不用。
- **配置同步迁移选 openRemoteFile 或 XSharedPreferences** [?]（前向搬运）——先试 `openRemoteFile()`（API 102 原生，Binder 到 LSPosed daemon 读模块 filesDir，改动最小）；失败回退 XSharedPreferences（生态最成熟，删整层 IPC）。两者都读磁盘文件无视游戏进程状态，都能根治"广播丢→文件陈旧"。

## 4. 失败的尝试 — 不要再试

- **模块进程写公共 `/sdcard/AlaMobileTool/`** [V]——`open failed: EACCES`，scoped storage 禁写外部存储根，WRITE_EXTERNAL_STORAGE 对 targetSdk 30+ 无效。
- **游戏进程读公共 `/sdcard/`** [V]——模块写不进去，文件停旧值。
- **ContentProvider.call 跨进程** [V]——`Unknown authority`，包可见性挡死。
- **createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY)** [V]——`NameNotFoundException`，包可见性挡死。
- **游戏进程直读模块私有文件** [V]——`Permission denied`，uid 隔离。
- **M11 手写 SDK_INT 分支注册 receiver** [V]——lint `UnspecifiedRegisterReceiverFlag`，用 `ContextCompat.registerReceiver`。
- **ConfigReceiver 直接 writeText 覆盖** [V]、**root 保持 val 不刷新** [V]、**文件直读跨进程** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]——均不再试。
- **统一公式画两种方向刹车** [V]（本次新增）——raw 语义随 invert 变（1-t→t），同一公式导致绘制方向与 raw 不对应，必须 drawRect 分支。

## 5. 已知坑

- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_EXPORTED)`。
- **`androidx.core` 经传递依赖可用** [V]——activity-compose 传递拉入。
- **lint baseline 不覆盖新错误** [V]——加新代码必须本地 `./gradlew :app:lint` 验证。
- **原版/共存版布局存档不共用** [V]——Android 沙箱按包名隔离 externalFilesDir。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播是唯一可靠跨进程 IPC（但游戏没运行时丢弃，M11 遗留 bug 根因，待 openRemoteFile/XSharedPreferences 修复）。
- **广播首次启动滞后（M11 精确机制）** [V]——游戏没运行时定向广播被系统丢弃（非持久挂起），游戏 externalFilesDir 文件停旧值。复现：杀游戏→改 DUAL（运行时收）→杀游戏→改 SINGLE（没运行广播丢）→启动游戏→读旧 DUAL=bug。修法：改用读磁盘文件的 XSharedPreferences/openRemoteFile，不依赖广播时机。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view（rebuildFromConfigChange 或 toggle）；本次刹车反转开关生效依赖此重建链路。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。仅作用于 mapped，不影响 raw。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [?]——广播方案落地后未使用，manifest provider 声明保留，待 XSharedPreferences 迁移后删。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。

## 6. 下一步（有序）

1. **落地配置同步迁移**（核心，M14-B）——先试 `openRemoteFile()`（`XposedModule` 基类方法，Binder 到 LSPosed daemon 读模块 filesDir，改动最小：模块写 filesDir 不变，只改 `readFromTargetProcess` 走 openRemoteFile）；不可用回退 XSharedPreferences（manifest 加 `xposedsharedprefs=true`，ConfigActivity 写改 `MODE_WORLD_READABLE`，游戏进程读改 `XSharedPreferences(pkg,name).reload()`，删 ConfigReceiver/ConfigProvider/广播/JSON 整层）。
2. **验证修复**——按第 5 节"广播首次启动滞后"复现步骤跑：杀游戏→改 DUAL→杀游戏→改 SINGLE→启动游戏→应读 SINGLE。
3. **清理 IPC 层**（XSharedPreferences 路线才做）——删 `ConfigReceiver.kt`、`ConfigProvider.kt`、manifest 声明、`ModConfig.write` 广播段、`readFromTargetProcess` JSON 段。openRemoteFile 路线则保留广播作运行时即时更新，只改启动读取。
4. **发 Beta 3**——方向键修复 + 配置同步修复闭环后发（versionName `1.0.0 Beta 3`，versionCode `100230`）。

## 7. 留给用户的开放问题

- 配置同步迁移选 openRemoteFile 还是 XSharedPreferences？研究 agent 倾向 openRemoteFile（API 102 原生，改动小），但 XSharedPreferences 生态更成熟。下个会话可先试 openRemoteFile，失败再回退。
