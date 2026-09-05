# HANDOFF — 读全文再开始干活

生成时间: 2026-09-05T23:00:00+08:00 · Git HEAD: `e0a62b8`（模块仓；paddock 仓 `0251dae`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `e0a62b8`（2026-09-05）；paddock 仓 `main` @ `0251dae`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `e0a62b8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（积分公式 v40）+ 本文件 §2 闪退排查结论

## 1. 当前目标
**模块 App 闪退排查（用户报告"点配置/设置两个菜单都有可能闪退"）+ 崩溃自捕基建落地**：CrashCatcher 已并入并构建分发版，等用户复现闪退后导出日志，第一段即堆栈，据此定案修复。

## 2. 已验证状态 — 工作实际停在哪
- [V] **CrashCatcher 崩溃自捕**已落地（`e8f7787`）：`util/CrashCatcher.kt` 新增——模块进程 UncaughtExceptionHandler，堆栈无条件落盘 `filesDir/ala_tool_crash.log`（不受日志开关控制；链式转发系统 handler 保留崩溃弹窗+系统上报；512KB 滚动）；`App.onCreate` 模块进程分支 install；`LogExporter.export` 合并为导出文件第一段"模块进程崩溃记录"且**不走 24h filterRecent**（崩溃可能发生在 logEnabled=false 时段）
- [V] Release 构建通过：`app/build/outputs/apk/release/app-release.apk`，1.0.4 Alpha 1 / 104100（**仓库版本原样构建，未升版**），V2 签名指纹 `e0bc20a5…de574` 与 CI keystore 一致；`./gradlew :app:lint` → EXIT=0
- [V] 闪退日志分析（`/mnt/d/Downloads/QQ/ala_tool_log_20260905_183211.txt`，22730 行三段：模块Java/游戏Java/Native）：模块 App **18:13–18:32 换 6 个 pid**（19527→15314→17625→23388→25703→31926），每个存活 7–30s，最后动作全是 fetchPointsBoard/TrackBoard HTTP 200 成功后数秒；全部 `[I/]` 无 `[E/]`——未捕获异常不走 Logger，堆栈只在系统 crash buffer（本文件注定没有）→ 这就是 CrashCatcher 的动机
- [V] **积分榜体积暴涨旁证**：9/4 190B → 18:13 1058B → 18:24 3885B → 18:32 4044B（用户量暴涨，榜单 ~70+ 行）
- [V] **"点哪个页都会崩"的结构性解释**：`MainScreen.kt:134` `beyondViewportPageCount = LAST_PAGE_INDEX(=3)` → App 打开后 4 页（概览0/配置1/围场2/设置3）**全部同时组合**，任何一页组合期异常=无论用户点哪都崩；围场页 `PaddockViewModel.init` 的 fetchMe/drain 在后台照跑（解释"日志全是拉榜"与"用户点配置/设置崩"并存）。用户报告原话是"配置、设置两个菜单都有可能"——不要把崩溃源锁定在用户点的那一页
- [V] 设置页/配置页自身路径干净：无跨进程/网络/新依赖调用（grep 验证）
- [V] 围场页序实证：`MainScreen.kt` `when(page)` 2=PaddockPager，即底栏**第 3 页**；CLAUDE.md 曾写"4th"已修正（`e0a62b8`），教训入项目记忆 paddock-page-index
- [V] 用户 C 案例（上会话遗留）闭环维持：1.0.3 游戏进程 token 死后无自愈，v1.0.4 解药
- 工作区: 两仓均 clean，全部已推送（模块仓 `e8f7787` CrashCatcher + `e0a62b8` CLAUDE.md 页序；paddock 仓 `0251dae`）

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → EXIT=0（BUILD SUCCESSFUL，0 errors）
./gradlew :app:assembleRelease → BUILD SUCCESSFUL（app-release.apk 11.2MB）
apksigner verify → V2 CN=AlaMobileTool SHA-256 e0bc20a58d3c499360fd7a6e4de3155042bcb7151ba817507d61ffcac50de574
```

## 3. 决策与理由
- **崩溃自捕而非教用户抓 logcat** [V]：闪退用户是小白（"没有可能让小白用户搞这些"——用户原话），CrashCatcher 让"导出日志"这一个既有动作带回堆栈。否决：让用户 adb logcat -b crash（门槛不可接受）
- **CrashCatcher 只装模块进程** [V]：闪退报告全部指向模块 App；游戏进程 native 崩溃（SIGSEGV）不走 Java handler，装了也抓不到，强行写文件反而可能扩大伤害
- **排查未定案，三嫌疑待堆栈分辨** [?]：① 头像并发加载（`LeaderboardScreen.kt:289` `avatarCache` 是无界 ConcurrentHashMap，榜单暴涨后每页几十路并发 fetchAvatar+解码）② PaddockPager `LaunchedEffect(loggedIn, needsAvatar)` 自动 push 头像页（`Route.Avatar`，canhub cropper——CLAUDE.md 记过 CropImageActivity 主题坑， IllegalStateException 会无日志闪退）③ MIUI 后台管控杀进程（该设备实证 MIUI15；分辨方法=问用户"闪退有无'应用已停止'弹窗"，有=Java 异常 CrashCatcher 必抓，无=系统杀）
- **游戏进程上报 `Unknown authority tools.alamobile.mod.config`**（日志 18:25:53）[?]：该设备上 ConfigProvider 不可达（MIUI 管控/包不可见），影响上传链第三级回落，但非本次闪退主因，未修
- 继承：积分公式 v40 / 版本独立赛季 / token 三级回落 / order==2 挂圈 / 拦截器零浮点 / 版本号红线（全局 CLAUDE.md）

## 4. 失败的尝试 — 不要再试
- **靠自导日志定位模块 App 闪退** [X]——未捕获异常不经过 Logger（只记主动调用），自导文件永远没有堆栈。不要再在旧导出文件里找崩溃原因；等 CrashCatcher 数据
- **把用户口头崩溃入口当排查目标** [V]——用户说"配置/设置"但 `beyondViewportPageCount=3` 全页预组合下入口页不可信；排查以崩溃时序+堆栈为准
- **诊断时把"导出日志缺段"当"代码没执行"** [V]（继承仍有效）——LogExporter java/native 段可各自回落旧缓存，看日志先核对段时间窗
- **NPatch 下靠 Remote Preferences 传 token** [X]（继承）——loader 返回空壳 Bundle，物理断裂。ConfigProvider 是正道
- **ConfigProvider 读 filesDir** [X]（继承）——必须与 saveAuth 同用 getExternalFilesDir
- **SQL 窗口函数 CTE 内先 WHERE 再 rank** [X]（继承）
- **未经同意改版本号** [V]（继承）——全局红线
- 继承（前向有效，见 `.handoffs/20260905230000-handoff.md` §4）：Release Notes 凭 commit message 直写不核实 / scp 用错用户 / VPS compose 镜像行以 grep 为准 / IL2CPP dump 用 Windows dotnet / mdns 端口漂移（本次实证仍有效：旧端口 5555 offline，mdns 无发现）/ serde Option 不兜空串 / askama 禁调函数 / lap_hook 全套 / IL2CPP 扫描三坑

## 5. 已知坑
- ⚠️ **avatarCache 无界** [?]——`LeaderboardScreen.kt:289` `ConcurrentHashMap<String, Bitmap>` 无 LRU 上限，榜单增长下去迟早 OOM；即使不是本次元凶也建议加并发上限/容量上限
- ⚠️ **LogExporter java/native 段可各自回落不同时期缓存** [?]（继承，未修）——下次诊断用户日志前先看段时间窗
- ⚠️ **该设备 ConfigProvider `Unknown authority`** [?]——MIUI 管控下游戏进程够不着模块进程 provider，影响 token 第三级回落
- ⚠️ **NPatch 管理器 binder 时序** [?]（继承）
- ⚠️ **lint baseline 13 条失效** [?]（继承）——下次重新生成
- ⚠️ **paddock 版本三处同步无校验** [?]（继承）——deploy.sh 固化仍未做
- ⚠️ 继承：排行榜无实时刷新 / 管理端网页验证未做 / 群内 bot 复测未做 / 双仓赛道中文名两份硬编码 / Garage 206 测试对象

## 6. 下一步（有序）
1. **等用户复现闪退后导出日志**：第一段"模块进程崩溃记录"即堆栈，据此定案修复。若用户回报"无崩溃弹窗直接消失"→ 转向 MIUI 后台管控方向（白名单/锁后台），CrashCatcher 抓不到系统杀进程
2. （可选）分发前置问句：闪退有无"应用已停止"弹窗（二分 Java 异常 vs 系统杀）
3. （可选）avatarCache 加 LRU/并发上限防御性加固
4. （可选继承）LogExporter 缓存回落修复（按文件独立判断新旧）/ deploy.sh 固化 / lint baseline 重生成
5. v1.0.4 正式发布（版本号/tag/Release Notes）**待用户定版**——遵守版本号红线

## 7. 留给用户的开放问题
- 闪退用户复现后日志何时能拿到；若 CrashCatcher 段为空则基本坐实 MIUI 系统杀
- avatarCache 防御性加固是否随本次修复一起做
- v1.0.4 正式版版本号与发布时机
