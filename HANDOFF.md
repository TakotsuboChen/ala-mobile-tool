# HANDOFF — 读全文再开始干活

生成时间: 2026-09-06T20:22:00+08:00 · Git HEAD: `f4e50f8`（模块仓；paddock 仓 `1dbe7a2` 无改动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `f4e50f8`（2026-09-06）；paddock 仓 `main` @ `1dbe7a2`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f4e50f8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 本文件 §2 日志证据表（游戏闪退的时间线结论都在这）

## 1. 当前目标
**游戏进程闪退排查**（≠模块 App 闪退，是另一个问题）——本会话完成：日志证据分析锁定崩溃窗口 + 游戏进程 native CrashCatcher 落地 + 日志强制开启。**现在等目标设备用户复现闪退反馈 crash 现场数据**（不是等 Takotsubo 本人——新装机版分发给了用户）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **日志证据分析定案崩溃窗口**（用户日志 `ala_tool_log_20260906_021353.txt`，17082 行，两代进程交错）：native 段 pid 序列 22237→14213→31690 = 两次无声死亡（01:49 / 02:13）。四次 `LAPsession[awake]`（= 进赛道场景加载）**2 死 2 活**：01:49:26（22237）死、01:51:13（14213）活、02:05:31（14213）活、02:13:35（14213）死。两次死亡都停在 LLV.Awake 打印后、pedal `baseline captured` 之前——**场景加载窗口竞态，~50% 复现率**。日志零堆栈零 FATAL = native SIGSEGV 指纹
- [V] **嫌疑排序**（推断，待 crash 堆栈定案）：① pedal_hook 输入写线程悬空写（唯一持续写游戏内存的组件，场景重载旧 carController 销毁窗口）② lap_hook awake 链（纯只读，且两次死亡时 LAPsession[awake] 行完整打出=日志段已走完）③ 游戏自身 bug（无法排除）
- [V] **crash_hook.c 落地**（新增 `native/src/crash_hook.c/.h`）：六信号 sigaction handler → 落盘 `/sdcard/Android/data/<游戏包>/files/ala_tool_crash_native.log`（PC/LR 相对段基址偏移 + x0-x3/sp/fault_addr）；`sigaltstack`+`SA_ONSTACK` 覆盖栈溢出型；全 async-signal-safe；链式转发旧 handler；`NativeBridge.init` 内安装（hook 之前）
- [V] **日志强制开启**：logEnabled 全链路删除（Logger/native_log 两层门控 + ModConfig 字段 + UI 开关 + ConfigReceiver 同步 + JNI 桥 setLogEnabled）。旧 JSON 残留 `log_enabled` key 被静默忽略，零迁移
- [V] **导出链闭环**：pushGameLogs 带上 crash 文件 → LogReceiver 新增 "nativecrash" 通道 → LogExporter「游戏进程崩溃记录」段（不走 24h 过滤）
- [V] **已装机用户设备**：mdns 重发现 38357 端口 → install Success → 装机 `versionName=1.0.4 Alpha 1 / versionCode=104100`（未升版）
- [V] `./gradlew :app:lint` → BUILD SUCCESSFUL（0 errors）；native ninja ala-core → 编译通过；`./gradlew :app:assembleRelease` → BUILD SUCCESSFUL
- 工作区: 干净全推送。`8d3c701`(工作切片) → `f4e50f8`(CLAUDE.md/README 持久文档)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL
ninja ala-core (SDK cmake 3.22.1) → libala-core.so 链接成功，crash_hook.c 无警告
./gradlew :app:assembleRelease → BUILD SUCCESSFUL
adb install -r → Success；dumpsys → versionName=1.0.4 Alpha 1 versionCode=104100
```

## 3. 决策与理由
- **crash 文件独立落盘不进 ala_tool_native.log** [V]：崩溃可能发生在日志写入路径上（磁盘满/锁），同文件互相污染；与模块进程 Java CrashCatcher 的独立文件策略同构
- **PC 偏移而非完整 backtrace** [V]：`pc = libil2cpp.so 段基址 + 偏移` 对拍 OffsetTable RVA 即知死在哪个被 hook 函数里，解析成本比 unwind 低一个量级；本次嫌疑（pedal 写线程/hook 回调）都在 OffsetTable 管辖内
- **日志强制开启（用户明确指令）** [V]：排查闪退发现关日志=丢现场（本次日志若非用户碰巧开着，连时间线都拿不到），2MB 滚动上限足够控制存储
- **两个特性合一个 commit** [V]：LogExporter/ConfigReceiver 双特性共改，hunk 级拆分风险大于收益
- 继承：CrashCatcher(Java) 只装模块进程 / 积分公式 v40 / token 三级回落 / order==2 挂圈 / 版本号红线

## 4. 失败的尝试 — 不要再试
- **SettingsPagerMiuix 删 SwitchPreference 时把 `ArrowPreference(` 行一起删掉** [V]——删 UI 块时 old_string 覆盖了下一组件的开括号行，编译报 @Composable 语法错；修复=补回开括号行。教训：删嵌套 UI 块时 old/new 都要保留下一个兄弟组件的起始行
- **Write 工具路径参数写坏（路径里混了 `"..."`）** [V]——报 EACCES mkdir 失败，一次即可察觉
- **native_log_init 删定义时留了悬空函数体开头** [V]——只删了上半段注释了下半段还在，clang "function definition is not allowed here"；顺带确认 native_log_init 无调用者后连声明一起删干净
- **Java UncaughtExceptionHandler 覆盖不了游戏进程闪退** [V]——native SIGSEGV 不产生 Java 异常，且 CrashCatcher(Java) 只装模块进程；这正是 crash_hook 信号级方案存在的原因
- **grep 崩溃指纹（FATAL/SIGSEGV/backtrace）在自导日志中零命中是常态** [V]——游戏进程 native 崩溃不经过任何 Java 层，无声死亡+pid 跳变才是指纹；别因 grep 无命中就下结论"没有崩溃"
- 继承（前向有效，见 `.handoffs/20260906170000-handoff.md` §4）：`<@openid>` 旧格式发 @ [X] / `<qqbot-at-user>` 走纯文本通道 [X] / 国旗 compact 逐码点剥空格 [X]（成对码点判定）/ "QQ 群 bot 完全不支持 @" [X] / 靠自导日志定位模块 App 闪退 [X]（同本会话教训）/ NPatch 靠 Remote Preferences 传 token [X] / ConfigProvider 读 filesDir [X] / SQL CTE 内先 WHERE 再 rank [X] / 未经同意改版本号 [V] / Release Notes 凭 commit message 直写 / scp -P 大写 / VPS 禁 cargo build / IL2CPP dump 用 Windows dotnet / mdns 端口漂移 / serde Option 不兜空串 / askama 禁调函数 / lap_hook 全套 / IL2CPP 扫描三坑

## 5. 已知坑
- ⚠️ **游戏闪退真凶未定案** [?]——crash_hook 已随 1.0.4 Alpha 1 装机，等用户复现导出日志；crash 文件第一段 pc 偏移对拍 `OffsetTable.kt` 即定案。若「游戏进程崩溃记录」段为空→crash 链本身失效（优先查 sigaltstack/SA_ONSTACK 与 Unity 自有 handler 的兼容性）
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在 1.0.4 Alpha 1 分发版里，等用户复现导出日志；若模块崩溃段为空→坐实 MIUI 系统杀
- ⚠️ **crash_hook 的 SA_ONSTACK 备用栈 64KB 静态分配** [?]——handler 内 snprintf+maps 解析栈深未实测；若 crash 文件出现半截报告→栈不够，加大 g_sigstack_mem
- ⚠️ **两代 crash 机制对同一进程无重叠** [V]（设计如此）——Java CrashCatcher=模块进程未捕获异常，crash_hook=游戏进程 native 信号；但游戏进程的 Java 未捕获异常（理论存在）无覆盖
- ⚠️ **avatarCache 无界** [?]（继承，未修）——`LeaderboardScreen.kt:289` 无 LRU 上限
- ⚠️ **LogExporter java/native 段可各自回落不同时期缓存** [?]（继承，未修）
- ⚠️ **该设备 ConfigProvider `Unknown authority`** [?]（继承，未修）——影响 token 第三级回落
- ⚠️ **lint baseline 13 条失效** [V]（本会话 lint 输出实证）——`13 errors/warnings were listed in the baseline but not found`；重生成时机待定
- ⚠️ 继承：NPatch 管理器 binder 时序 / paddock 版本三处同步无校验 / 排行榜无实时刷新 / 管理端网页验证未做 / 双仓赛道中文名两份硬编码 / Garage 206 测试对象

## 6. 下一步（有序）
1. **等用户反馈**：复现游戏闪退 → 导出日志 → 「游戏进程崩溃记录」段拿 pc 偏移 → 对拍 OffsetTable 定案
2. 定案后修元凶（嫌疑排序见 §2）；修复验证需用户多轮复现（原 50% 复现率反而是优势）
3. （可选）lint baseline 重生成（13 条已失效）/ avatarCache LRU 加固 / LogExporter 缓存回落修复
4. v1.0.4 正式发布待用户定版（版本号红线；两项诊断基建已随 Alpha 1 分发）

## 7. 留给用户的开放问题
- 闪退用户反馈何时能拿到（crash 现场定案的唯一依赖）
- crash_hook 若在真机上抓不到（崩溃记录段为空），是否加 tombstone 提示引导用户抓 logcat
- v1.0.4 正式版版本号与发布时机
