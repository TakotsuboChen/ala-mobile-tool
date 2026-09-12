# 跨进程数据通道调研（模块进程 → 游戏进程）

> 2026-09-12 定案调研。目标问题：**模块 App 进程不运行、且 ColorOS 关联启动被关闭时，如何把模块的最新配置与登录态传给游戏进程？**
> 本文档是 7 个并行调研（NPatch 源码级 / ColorOS 管控 / 跨包文件 / libxposed API / 业界方案 / 本项目通道审计 / 保活手段）的收敛结论，供后续架构决策与 handoff 引用。

---

## 0. 一句话结论

**除"文件通道"外，没有任何手段能在约束下传递数据。** 且普通文件位置里，`Android/data` 跨包被官方明文禁止，**`Android/media/<pkg>/` 是唯一未验证的候选**。

不存在免 root、免用户操作的银弹。业界共识（dontkillmyapp、各推送 SDK、LSPatch Release Notes）的唯一路径是**引导用户开启"自启动/关联启动"**。

---

## 1. 约束与症状

- 设备：OnePlus OPD2413 / ColorOS（Android 16），设备号 `e98a35cb`
- 模式：NPatch 本地模式（非 root），共存版包名 `com.Takotsubo.AlamobileFormula`，模块包 `tools.alamobile.mod`
- 触发条件：关闭"关联启动"（`OplusAppStartupManager`）

**实证症状**（logcat）：
```
11:23:58.976 W/OplusAppStartupManager(3464): prevent start tools.alamobile.mod,
  cmp ComponentInfo{tools.alamobile.mod/...ConfigProvider}
  by contentprovider com.Takotsubo.AlamobileFormula callingUid 10046, scenePriority = 0
11:23:58.977 I/AlaMobileTool: readFromTargetProcess: ConfigProvider not reachable: Unknown authority
11:23:58.978 I/AlaMobileTool: Config via freshness arbitration, winner=local file (ts: all 0)
```
游戏想调模块 Provider 读配置 → ColorOS 在 **AMS 层**拦下"拉起模块进程" → 调用方收到**伪装的** `Unknown authority` → 三源全灭 → 落到游戏本地**过期的**旧文件。

---

## 2. 两个必须分清的同名错误

`Unknown authority` 有两个**完全不同**的根因，只能靠 logcat 有没有 `prevent start` 区分：

| 根因 | 层面 | 失败时机 | 证据 |
|---|---|---|---|
| **包可见性** | AOSP 客户端 `resolve` 阶段 | Binder call **根本没发出** | 无 `prevent start`；官版游戏无 `<queries>` 时命中 |
| **ColorOS 启动管控** | `system_server` AMS 端 | call **已发出**，目标进程不被拉起，调用方被喂伪装异常 | 有 `prevent start ... scenePriority = 0` |

- 官方文档：https://developer.android.com/training/package-visibility
- `scenePriority` **无任何公开文档**。0 = 被拦的后台跨包拉起（实证）；>0 对应豁免场景（调用方前台 Activity / 目标已 publish / 目标有前台服务）为**推测**。
- ColorOS 拦截点全部插桩在 AMS 四大组件**启动**路径（`getContentProviderImpl` / `judgeStartAllowLocked`）。**进程已运行 / provider 已 publish 时不触发**——这解释"模块活着时一切正常"。

---

## 3. ColorOS 手段裁决（关闭关联启动后）

| 档 | 手段 | 说明 |
|---|---|---|
| **可用** | 已运行进程间 Binder 直连；**文件/共享存储**（不经 AMS）；socket（但需 listener 活着） | 拦截点全在"启动"路径 |
| **不可用** | `ContentResolver.call` 冷拉；静态广播（ColorOS 14+ `shouldPreventSendReceiverReal`）；`bindService`/`startService`；`AlarmManager`/`JobScheduler`（只能调度自己）；前台服务（是豁免条件不是通道） | 全部要"拉起别人进程" |
| **需用户操作** | 开启自启动/关联启动 | 唯一业界路径，无免 root 绕过 |

`★ 关键洞察`：**socket 死因是"模块没运行就没人 listen"**。文件通道不可替代的价值 = **write-and-die**——写完即可死，数据留盘。

来源：dontkillmyapp.com/oppo；FCMFix（ColorOS socket/广播冻结实证）；CSDN AMS 源码分析。

---

## 4. NPatch Remote Store 架构（源码级定案）

**结论：不存在"Manager 直接写、游戏直接读"的同一物理文件。是"一个 store 类、三个宿主"。**

| 项 | 结论 | 源码 |
|---|---|---|
| 实例分键 | 以 `当前进程 dataDir : modulePkg` 为 key | `NPatchRemoteStore.java:80-82` |
| SQLite 落地 | **当前进程** `<dataDir>/databases/npatch-xposed-remote.db` | `:44, :336-339` |
| → Manager 写 | `/data/data/top.nkbe.npatch/databases/...` | — |
| → 游戏 fallback 读 | `/data/user/0/<游戏包>/databases/...` | — |
| **镜像机制** | **拉式非推式**：`FallbackModuleServiceWrapper.requestRemotePreferences` 仅在 remote binder 存活时读 Manager 库，成功即全量 `clear=true` 写入**游戏本地库**（log `Synced initial preference snapshot`） | `FallbackModuleServiceWrapper.java:85-97` |
| 触发三条件 | ① NeoLocal 构造时 connect 成功 ② 模块调 getRemotePreferences ③ remote 调用不抛 | `NeoLocal:256` |
| 每次读全量覆盖 | **Manager 库为空时会把本地镜像清空** | `:71-83` |
| Manager 死后 | `markRemoteDead` → 读本地 = **上次同步的陈旧快照**；从未同步过则空 | `:42-49` |
| connect 依赖 | `content://top.nkbe.npatch.remote` 的 `getInjectedRemoteService`，**会拉起 Manager 进程**；被 ColorOS 拦 → 回落 `LocalInjectedModuleService`（**此路径永不镜像**） | `ManagerRemoteServiceBridge.java:14`；`NeoLocal:250-266` |
| 纯离线首装 | 空 | `NeoLocal:82`（警告） |

**最终判定**：
- 镜像文件是**游戏进程自己**写的（FMSW 在游戏进程内调 localStore）；Manager 无 root 写不进游戏 dataDir。
- 模块 App 进程不运行**无碍**（写侧早已进 Manager 库）；**真正卡点是游戏启动瞬间 provider 调用能否拉起 Manager**。
- 能拉起 → 最新配置/登录态直达；被拦 → 读陈旧快照；从未同步 → 空。
- **无任何绕过 Manager 取最新数据的通道。**

> 附：`App.kt:152` 注释"npatch_remote_*.xml SharedPreferences"已过时，现行源码为 SQLite（`ManagerCacheCleaner.kt:12` 仅存清理前缀）。

---

## 5. libxposed API 102 数据面

| 面 | 语义 | 约束下 |
|---|---|---|
| `requestRemotePreferences` 20 | 读 remote prefs | 三态见下 |
| `updateRemotePreferences` 21 | 写 remote prefs | 需 Manager-backed binder |
| `deleteRemotePreferences` 22 | 删 prefs | ⚠️ remove 在 LSPosed/NPatch 两侧**都不生效**，必须 `putString("")` 空串覆盖 |
| `listRemoteFiles` 30 / `openRemoteFile` 31 / `deleteRemoteFile` 32 | **独立文件通道**，落地 `<宿主 filesDir>/npatch/remote/<modulePkg>/`，与 prefs SQLite 物理分离 | store 实例**跟着 service 宿主进程走**，非全局通道 |
| `getRunningTargets` 13 / `hotReloadModule` 14 | 热重载 | 官方明文 *"not intended for propagating configuration changes"*（libxposed/api PR #62）；Embedded 模式直接 UNSUPPORTED |

**游戏进程读 prefs 三态**（`XposedInterface.java:543` → `LSPLoader.java:332`）：

| 态 | 条件 | 约束下 |
|---|---|---|
| EMPTY stub | `LoadedModule.service==null` → 返回 `Bundle.EMPTY` | 永远空 |
| **in-process 本地 store** | NeoLocal → `LocalInjectedModuleService`，同进程读游戏自己 dataDir 的 SQLite，**零 Binder** | ✅ 可用，但与模块写的 Manager store 是两个物理文件 |
| Manager-backed | `FallbackModuleServiceWrapper` → Manager 进程 binder | ❌ 需 Manager 活着 |

**`openRemoteFile` 权限不对称**（设计使然）：模块进程可写（`XposedServiceBinder.kt:120`），游戏进程只读（`LocalInjectedModuleService.java:48`）。

**结论**：IXposedService 框架内**没有**"Manager 不在场也能双向通"的通道。这**不是 bug，是本地模式架构缺口**——LSPatch 官方 Release Notes v0.3.1 Known issues 自认 *"If using local patch mode, it is required to keep LSPatch manager alive manually"*，且 v0.6 曾**移除前台保活服务**。

---

## 6. 跨包文件通道裁决

（非 root / targetSdk 35 / Android 12~16）

| 手段 | 可行 | 关键限制 |
|---|---|---|
| `Android/data` 跨包 + MANAGE_EXTERNAL_STORAGE | ❌ | AFA 明文不覆盖他包 ASD：*"Apps that are granted this permission still can't access the app-specific directories that belong to other apps"* |
| Download/Documents 共享目录 | ⚠️ 半 | 写无需权限；读他包**非媒体**文件 A13+ 只剩 SAF（每次用户手势）或 AFA |
| MediaStore 留言板 | ⚠️ 仅媒体 | 读他包 `MediaStore.Downloads` 条目官方明文须走 SAF；非媒体条目他包查不到。媒体载荷（音频/图片）+ `READ_MEDIA_*` 技术上可行 |
| **`/sdcard/Android/media/<pkg>`** | ⚠️ **候选** | 不在受限清单（受限的是 `Android/data`、`Android/obb`）；AFA 文档单列"可访问 /sdcard/Android/media"。**"含他包子目录"为推测** |
| sharedUserId / 同签名 | ❌ | API 29 弃用；需同证书 + 安装时声明；游戏证书不可控 |
| SAF 持久 URI 授权 | ❌ | 授权本身须用户手势；游戏侧是改不了的 Unity 包，无法埋 SAF UI |
| Provider（官方推荐路径） | ⚠️ | `exported=true`+signature 权限可精确限访，但**驻模块进程**：冷拉进程靠 OEM 白名单，`exported`/`permission` 解决不了拉起 |

**官方设计的立场**：scoped storage **有意**不让两个独立发布的 App 共享持久文件。官方 Zebra 文档推荐路径 = Content Provider（代价 = 模块进程必须活着）。

---

## 7. 保活手段（ColorOS）

| 档 | 结论 |
|---|---|
| 官方支持 | FGS 是唯一正规手段，Android 14+ 必须声明 `foregroundServiceType`（可长期存活：`mediaPlayback`/`location`/`connectedDevice`/`specialUse`；`dataSync` 从 Android 15 起 6h/24h 超时）。ColorOS 即便对 FGS 也照杀（dontkillmyapp 评 High） |
| 用户设置可达成 | **四项全做**才稳：①最近任务锁定 ②允许后台活动/关速冻 ③允许自启动 ④电池不优化。缺一仍可能被杀 |
| hack | 双进程守护（Android 5 起整组回收）、1px Activity（Android 10+ 禁后台启 Activity）、同步拉活、悬浮窗伪装——**全面失效** |

**对比结论**：保活要用户开**四个**开关 + 常驻通知，比"关联启动"**一个**开关更贵。不应作为"游戏冷启动读一次配置"的替代，只适合作为"实时双向同步"的补充。

---

## 8. 业界方案

| 方案 | 机制 | 结论 |
|---|---|---|
| **Xpatch** | sdcard 共享文件 `/mnt/sdcard/xposed_config/modules.list` | Android 9/10 可行；11+ scoped storage 失效（源码 fail-open 注释即此症） |
| LSPatch/NPatch 本地模式 | 无共同存储 | 官方答案就是"保活管理器" |
| **注入期固化**（集成模式） | 模块 APK 嵌入被修补 APK 的 assets | 宿主完全脱离管理器；代价 = 配置变即重新注入重装 |
| VirtualApp/太极 | 模块与宿主同 UID 同容器 | 配置天然共享；ColorOS 管控无工程解，太极提供一次性 ADB 设置（机制未公开，推测 dpm/系统授权） |

**判断**：本项目现有 auth 文件 + ConfigProvider 双回落，实际已是"注入期固化"的运行时变体。

---

## 9. 本项目现有通道审计

| 通道 | 位置 | 写前提 | 读前提 | 约束下 |
|---|---|---|---|---|
| ① remote prefs 配置 | 写 `ModConfig.kt:770-783`；读 `ModConfig.kt:862-873` | 模块活 + service 绑 | daemon/NPatch loader 存活 | 历史值可用，新写不可 |
| ② 模块 filesDir 备份 | 写 `ModConfig.kt:786-792` | 模块活 | 经 Provider（被拦） | 持久但"沉睡" |
| ③ 定向广播 ConfigReceiver | 发 `ModConfig.kt:800-810`；收 `ConfigReceiver.kt:44` | 模块活 | 游戏活 + receiver 注册 | 游戏没运行=丢 |
| ④ 游戏侧 externalFilesDir | 写 `ConfigReceiver.kt:60-86`；读 `ModConfig.kt:891-901` | 广播曾送达 | 无（本地） | 历史值 100% 可读 |
| ⑤ ConfigProvider read_config | 发 `ModConfig.kt:875-889`；收 `ConfigProvider.kt:103-113` | 系统可拉起模块 | 同左 | ❌ 被拦 → null |
| ⑥ token remote prefs | `PaddockClient.kt:270-293` / `:296-327` | 模块 + service | 框架 | 同 ① |
| ⑦ token Provider read_token | 发 `PaddockClient.kt:187-202` | 可拉起模块 | 同左 | ❌ 不可达 |
| **⑧ GATE_CACHE** | `PaddockClient.kt:203-238` | **仅游戏进程** | **仅游戏进程** | ✅ **100% 可用** |
| ⑨ 日志分片广播 LogReceiver | 发 `LogReceiver.kt:78-114` | 游戏 | 系统拉起模块 | ColorOS 可能拦 → 分片丢 |
| ⑩ REQUEST_LOGS | `LogExporter.kt:67-71` | 模块 | 游戏活 | 游戏没运行=读旧缓存 |
| ⑪ saveOverlayPosition | `ModConfig.kt:821-831` | 仅游戏 | 仅游戏 | ✅ 不依赖模块 |

**规律**：凡读前提含"系统可拉起模块进程"的通道（⑤⑦⑨）**全灭**。剩下的只有游戏进程自己持久化的（④⑧⑪）和 LSPosed daemon 常驻的（①⑥，NPatch 下不可用）。

> **⑧ GATE_CACHE 是唯一 100% 可用的通道**——这既印证它作为"最后兜底"的价值，也说明**反向失效（模块登出后清不掉游戏进程缓存 → 误放行）是当前最大风险**。

---

## 10. 已确认但未修的 Bug 清单

| # | Bug | 位置 | 影响 |
|---|---|---|---|
| B1 | `savedAt(null)` 返回 0 参与比较，三源 ts 全 0 时首条分支恒真 → `bestJson=null` → 丢弃已读到的配置 | `ModConfig.kt:918` | ✅ **2026-09-12 已修**（null 源先排除再比 ts） |
| B2 | 配置写入只用单例 `App.xposedService`，未遍历 `App.allServices`；双框架并存时配置只写 LSPosed store，NPatch 游戏读 Manager store 读不到（token 早已修过同款坑） | `ModConfig.kt:770` | 双框架下配置丢失 |
| B3 | `drainQueue` 的 `ok` 计数**永不递增**，恒返回 0 → 日志谎报 `drained 0 laps` / `0/N uploaded`（实际成功） | `PaddockClient.kt:679-707` | 日志不可信，误判补传失效 |
| B4 | 反向分裂：`clearGateCache()` 清的是**调用方进程**的 prefs，模块 App 登出时清不到**游戏进程**的 `paddock_gate_token` → 全通道被拦时误放行 | `PaddockClient.kt:241-246, 302` | 违反用户反向红线 |
| B5 | 门控激活时 15s 主路径跳过，`PaddockUploader` 不启动 → 误锁后无自愈循环 | `AlaMobileModule.kt:510-514` | 误锁后必须重启游戏 |
| B6 | `ConfigProvider.pushGameLog`/`readGameLog` + `PUSH/READ_GAME_LOG_METHOD` **零调用方**（死代码）；`ModConfig.withPositionDefaults` 死代码 | `ConfigProvider.kt:47-79`；`ModConfig.kt:969-975` | 无功能影响，清理项 |
| B7 | `LogReceiver` 分片推送用 `FLAG_INCLUDE_STOPPED` 拉模块进程，被 ColorOS 拦 → 分片丢；导出只判空 3s 窗口，不重试 | `LogReceiver.kt:78-114` | 日志导出偶尔不全 |

---

## 11. 候选方案对比

| 方案 | 依赖模块运行 | 依赖关联启动 | 依赖用户操作 | 代价 | 状态 |
|---|---|---|---|---|---|
| **A. Android/media 文件通道** | ❌ | ❌ | 开一次"所有文件访问"（仅共存版能加权限） | 需重打包共存版；AFA 读他包 media 未验证 | **待验证** |
| B. 引导开启关联启动 | — | ✔（要开） | 一个开关 | toast 引导；用户可能不照做 | 兜底 |
| C. 保活模块进程 | ✔（要活） | ❌ | 四个开关 + 常驻通知 | 比 B 更贵 | 不推荐 |
| D. 注入期固化配置 | ❌ | ❌ | 配置变即重新注入 | 用户体验差 | 不适用（配置需频繁改） |
| E. 修 B2~B5 | — | — | — | 不做架构变更 | 应立即做 |

---

## 12. 待验证事项

1. **【关键】AFA 能否读他包 `Android/media/<pkg>/` 子目录** —— 官方文档只说"可访问 /sdcard/Android/media"，未明说含他包子目录。**必须实机验证**（共存版加 `MANAGE_EXTERNAL_STORAGE` + 引导开 AFA，实测读模块写的文件）。
2. `Android/media` 在 ColorOS 上是否有 OEM 层额外收紧。
3. NPatch Manager 进程在 ColorOS 上的存活率（决定方案 B 的"历史值"新鲜度）。
4. 镜像快照的刷新时机（决定"能拉起 Manager 时"配置的新鲜度）。

---

## 13. 附录：调研分工与证据来源

| 调研方向 | 关键结论 | 主要来源 |
|---|---|---|
| NPatch store 架构 | 双物理库 + 拉式镜像 | `references/NPatch/**` 源码 |
| ColorOS 管控 | 拦截点在 AMS 启动路径；无免 root 绕过 | dontkillmyapp.com/oppo；FCMFix；CSDN AMS 分析 |
| 跨包文件 | `Android/data` 官方排除；`Android/media` 唯一候选 | developer.android.com scoped storage / manage-all-files / shared media |
| libxposed API | 框架内无"Manager 不在场双向通"通道 | `references/libxposed-service/**`；LSPatch Release Notes |
| 业界方案 | 无银弹；Xpatch sdcard 方案 11+ 失效 | windysha/xpatch；LSPosed/LSPatch releases |
| 本项目审计 | 11 条通道逐条裁决 + 7 个已确认 bug | 本仓 app/src/** |
| 保活 | 四开关 + 常驻通知，比关联启动更贵 | dontkillmyapp.com/oppo；Android FGS types 变更页 |
