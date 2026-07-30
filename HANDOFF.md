# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T15:05:00+08:00 · Git HEAD: 80cecab（即将提交新改动）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

两个独立任务：(A) 修复游戏自带方向键被屏蔽 — **已完成真机验证**；(B) 修复"游戏没运行时改配置，下次启动读到旧值"的 M11 首次滞后 bug — **根因已定位，方案已研究，待下个会话落地 XSharedPreferences/openRemoteFile 迁移**。

## 2. 已验证状态 — 工作实际停在哪

- [V] **方向键修复真机验证通过**：用户确认"方向键和摇杆都正常了"。改动在 `native/src/pedal_hook.c`：`input_writer_thread` 在 `!g_throttle_active && !g_brake_active` 时整体早返回不写任何字段；`apply_inputs_to_controller` 去掉 `else if (value<=0) 清零` 分支，只在 active 时写字段；新增 `clear_throttle_field`/`clear_brake_field`，`pedal_set_throttle_value`/`pedal_set_brake_value` 在 active→inactive 转变时主动调 clear 一次清零（writer 不再轮询清零，所以松开瞬间必须主动清一次，否则值卡住）。
- [V] **配置同步 bug 根因定位**：实机 logcat 抓到完整证据链——游戏运行时改 DUAL，广播收到、文件写 DUAL（`ConfigReceiver: merged`）；杀游戏后改 SINGLE，广播发出但**无 `ConfigReceiver: merged` 日志**（游戏没运行，定向广播被系统丢弃）；游戏下次启动 `readFromTargetProcess` 读到 lastModified=14:44:05 的旧 DUAL 文件。机制确认：广播是瞬时派发，目标进程没运行就丢弃，游戏 externalFilesDir 文件停留旧值。
- [V] **所有常规跨进程拉取路径实测失效**（在这台 MeiZu 20 / Android 16 / 游戏 targetSdk 35 上）：
  - 模块 filesDir `/data/data/tools.alamobile.mod/files/` → 游戏进程 `cat` EACCES（uid 隔离）[V]（adb shell 实测）
  - 公共 `/sdcard/AlaMobileTool/` → 模块进程 `writeText` EACCES（scoped storage 禁写外部存储根，WRITE_EXTERNAL_STORAGE 权限对 targetSdk 30+ 无效）[V]（logcat: `open failed: EACCES (Permission denied)`）
  - ContentProvider `content://tools.alamobile.mod.config` → `Unknown authority`（包可见性，游戏进程查不到模块 provider，即使 manifest exported=true）[V]
  - `createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY)` → `Application package tools.alamobile.mod not found`（包可见性）[V]
  - 模块 APK `/data/app/.../base.apk` → 游戏进程**可读**（`-rw-r--r--` 全局可读）[V]，但 APK 只读，无法运行时写配置进去
- [V] **方案研究完成（两个 subagent 网络搜索）**：LSPosed 有两个官方跨进程配置同步机制可用（见决策段）。
- [V] **当前工作区状态**：`./gradlew :app:assembleDebug` + `:app:lint` 全绿。工作区改动 5 文件：`pedal_hook.c`（方向键修复，保留）、`AlaMobileModule.kt`（诊断日志，保留）、`ConfigReceiver.kt`（POSITION_KEYS 改引用 ModConfig 的，重构）、`ModConfig.kt`（诊断日志 + POSITION_KEYS 提为 public，失败实验已回退干净）、`CLAUDE.md`（handoff 段）。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 3s
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 31s

# 方向键修复真机验证（用户原话）：
# "方向键和摇杆都正常了"
# 配置同步 bug 复现（用户原话）："完全复现"——游戏没运行时改配置，下次启动读旧值
```

## 3. 决策与理由

- **方向键修复：writer 仅在 active 时写字段** [V]——writer 原本以 ~500Hz 持续往 throttle/brake 字段写 0（用户没踩时），覆盖游戏自带输入；`ButtonsSteering` 方向键模式的转向辅助（steerHelp/LockSteerAtVelocity/TractionFilter）依赖油门/刹车值决定辅助力度，被持续清零后转向辅助失效。陀螺仪模式不依赖油门/刹车做转向，所以不受影响（与用户反映"重力感应/陀螺仪用户不受影响"吻合）。否决方案：hook setSteerInput——模块没碰 steer 字段也没 hook 它，方向键失效不是 steer 被覆盖，是转向辅助依赖的油门/刹车被清零。
- **配置同步：选 XSharedPreferences 或 openRemoteFile() 落地** [?]——两个 subagent 研究结论：
  - **Agent 1 推 XSharedPreferences**：LSPosed 官方 wiki "New XSharedPreferences"（API 93+），所有主流开源模块都用它（Pixelify-Google-Photos/SimpleHook/StarVoyager/NexAlloy 等全部）。写：`getSharedPreferences(name, MODE_WORLD_READABLE).edit().apply()`；读：`XSharedPreferences(pkg, name).reload().getString(...)`。读磁盘 XML 文件，无视目标进程是否运行。需 manifest 加 `xposedsharedprefs=true` meta-data。可删除整个广播+JSON+ConfigReceiver+ConfigProvider IPC 层。
  - **Agent 2 推 openRemoteFile()/getRemotePreferences()**：libxposed API 102 `XposedModule` 基类自带方法（继承自 `XposedInterfaceWrapper`）：`openRemoteFile(String)→ParcelFileDescriptor`、`getRemotePreferences(String)→SharedPreferences`、`listRemoteFiles()→String[]`。Binder 到 LSPosed daemon（system uid，有权限读任意 filesDir），绕过 uid 隔离。模块写 filesDir 不变，只改游戏进程读取走 openRemoteFile。
  - **下个会话决策点**：先试 `openRemoteFile()`（API 102 原生方法，改动最小，agent 2 给了完整调用链）。若不可用再回退 XSharedPreferences（agent 1 方案，生态最成熟）。两者都读磁盘文件无视游戏进程状态，都能根治"广播丢→文件陈旧"。
- **失败实验已回退** [V]——`ModConfig.write` 写公共 `/sdcard/AlaMobileTool/` 段已删（EACCES 刷日志）；`readFromTargetProcess` 公共文件拉取段已删（游戏进程读不到）；`pullLatestViaProvider`（ContentProvider+createPackageContext 双路径探测）已删。保留诊断日志（`readFromTargetProcess: path=... lastModified=...`）供下个会话排查。`ConfigReceiver.POSITION_KEYS` 改引用 `ModConfig.POSITION_KEYS`（提为 public val）避免重复维护，保留。

## 4. 失败的尝试 — 不要再试

- **模块进程写公共 `/sdcard/AlaMobileTool/` 作配置镜像** [V]——`ModConfig.write` 加写公共文件，`open failed: EACCES (Permission denied)`。模块进程 targetSdk 35，scoped storage 禁写外部存储根，WRITE_EXTERNAL_STORAGE 权限对 targetSdk 30+ 无效。不要再试写 `/sdcard/` 任意目录。
- **游戏进程读公共 `/sdcard/AlaMobileTool/` 拉取** [V]——虽然 adb shell `cat` 能读（legacy 读路径放宽），但模块进程写不进去，文件停在 02:25 旧值，拉不到最新。且这是"模块写不了、游戏能读"的非对称，不可用。
- **ContentProvider.call 跨进程拉取** [V]——`Unknown authority tools.alamobile.mod.config`。游戏进程 targetSdk 35，包可见性让它查不到模块 provider，即使 manifest `exported=true`。M11 记录的失效在这台 MeiZu 复现确认。
- **createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY) 读模块 filesDir** [V]——`Application package tools.alamobile.mod not found`。包可见性挡死。CONTEXT_IGNORE_SECURITY flag 无效。
- **游戏进程直接读模块私有文件** [V]——`cat /data/data/tools.alamobile.mod/files/ala_tool_config.json` → `Permission denied`。uid 隔离，文件 `-rw------- u0_a159`。
- **（前向搬运）M11 手写 SDK_INT 分支注册 receiver** [V]——lint 静态分析命中 `UnspecifiedRegisterReceiverFlag`。用 `ContextCompat.registerReceiver`。
- **（前向搬运）M11 ConfigReceiver 直接 writeText 覆盖** [V]、**root 保持 val 不刷新** [V]、**文件直读跨进程** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]——均不再试。

## 5. 已知坑

- **Android 13+ registerReceiver 需 flag** [V]——必须用 `ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_EXPORTED)`，不要手写 SDK_INT 分支。
- **`androidx.core` 经传递依赖可用** [V]——build.gradle.kts 无显式条目，activity-compose 传递拉入，ContextCompat 编译+lint 通过。
- **lint baseline 不覆盖新错误** [V]——加新代码后必须本地 `./gradlew :app:lint` 验证。
- **原版/共存版布局存档不共用** [V]——Android 沙箱按包名隔离 externalFilesDir。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播 Intent.setPackage 是唯一可靠跨进程 IPC（但游戏没运行时丢弃，这是 M11 遗留 bug 的根因，待 XSharedPreferences/openRemoteFile 修复）。
- **广播首次启动滞后（本次会话定位的 M11 bug 精确机制）** [V]——游戏没运行时改配置，定向广播被系统丢弃（非持久挂起），游戏 externalFilesDir 文件停留旧值。复现步骤：①杀游戏②启动游戏改 DUAL（广播收到文件写 DUAL）③杀游戏④改 SINGLE（游戏没运行广播丢，文件保持 DUAL）⑤启动游戏→读旧 DUAL=bug。修法：改用读磁盘文件的 XSharedPreferences/openRemoteFile，不依赖广播时机。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view（rebuildFromConfigChange 或 toggle）。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。仅作用于 mapped（送 native），不影响 raw（绘制）。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [?]——广播方案落地后未使用，manifest provider 声明保留（XSharedPreferences 迁移后一并删）。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。notifyConfigChanged 在第二个 ClassLoader 是 no-op（instance 为 null）。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。

## 6. 下一步（有序）

1. **落地配置同步迁移**（核心）——选 `openRemoteFile()` 或 `XSharedPreferences` 之一。推荐顺序：先试 `openRemoteFile()`（agent 2 方案，API 102 原生 `XposedModule.openRemoteFile(String)`，Binder 到 LSPosed daemon 读模块 filesDir，改动最小：模块写 filesDir 不变，只改 `readFromTargetProcess` 走 openRemoteFile）；若 `openRemoteFile` 在 AlaMobileModule 不可调用或返回 null，回退 XSharedPreferences（agent 1 方案，manifest 加 `xposedsharedprefs=true`，ConfigActivity 写改 `getSharedPreferences(name, MODE_WORLD_READABLE)`，游戏进程读改 `XSharedPreferences(pkg, name).reload()`，删 ConfigReceiver/ConfigProvider/广播/JSON 整层）。
2. **验证修复**——按 HANDOFF 第 5 节"广播首次启动滞后"复现步骤跑一遍：杀游戏→改 DUAL（游戏运行）→杀游戏→改 SINGLE（游戏没运行）→启动游戏→应读 SINGLE（不再读旧 DUAL）。
3. **清理 IPC 层**（XSharedPreferences 路线才做）——删 `ConfigReceiver.kt`、`ConfigProvider.kt`、manifest provider/receiver 声明、`ModConfig.write` 广播段、`readFromTargetProcess` JSON 文件段。openRemoteFile 路线则保留广播作运行时即时更新，只改启动读取。
4. **发 Beta 3**——方向键修复 + 配置同步修复闭环后可发（versionName `1.0.0 Beta 3`，versionCode `100230`）。

## 7. 留给用户的开放问题

- 配置同步迁移选 openRemoteFile 还是 XSharedPreferences？研究 agent 倾向 openRemoteFile（API 102 原生，改动小），但 XSharedPreferences 生态更成熟（所有开源模块都用）。下个会话可先试 openRemoteFile，失败再回退。
- 方向键修复的 writer 早返回是否需要在 `pedal_set_throttle_value`/`pedal_set_brake_value` 的 `g_throttle_active=0 && was_active=0`（首次调用就传 0）场景也跳过 clear？当前逻辑 `if (g_throttle_active) apply else if (was_active) clear`——首次传 0 时 was_active=0，不 clear，字段保持默认 0，无副作用。已验证真机正常，无需改。
