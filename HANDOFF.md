# HANDOFF — 读全文再开始干活

生成时间: 2026-09-10T15:47:06+08:00 · Git HEAD: `92ca3f3`（工作+持久文档提交后，已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `92ca3f3`（2026-09-10 15:47，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `92ca3f3`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md ForceUpdateGate.kt / PaddockClient.kt 条目 + 记忆 `xposed-remote-prefs-remove-broken.md`

## 1. 当前目标
围场登录门控：**未登录 = 模块和游戏功能全禁**——已完成并实机验收通过（用户"我测了几次是 OK 的"）。本轮追加三件事全部完成：①token 残留根因修复（退出登录清不干净）②新安装强制清一次登录态 ③点输入框页面乱漂修复。遗留：一次无法复现的"闪退"（无堆栈，证据指向系统杀后台，见 §5）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **登录门控（游戏侧）**：`ForceUpdateGate.evaluateLoginGate` 经 `PaddockClient.queryLoginStateFromModule()`（ConfigProvider read_token → 模块进程读 external auth 文件，唯一读写闭环可靠的存储）判定；读不到 token = 未登录 → 零 hook + 第四态循环 Toast「您尚未登录围场，模块功能将不会生效，请先返回模块 App 登录！」；fail-closed；更新失配优先于登录（双失配时 Toast 只显示更新文案）
- [V] **登录门控（模块侧）**：`LoginGateCoordinator` 协调弹窗优先级（最低，低于 EULA/更新/NPatch 激活，埋点汇报 `eulaDone/updateCheckDone/updateBlocking/activationPending`）；MainScreen 状态机 = 弹窗期间不锁页，**点「去登录」→ 退出动画播完（onDismissFinished）→ gateLocked=true 才锁**（手势禁用+底栏只留围场+返回键不回概览），登录完成自动解锁；「退出」= 动画完 finish Activity
- [V] **实机验证（12:42 自动测试）**：未登录冷启动游戏 → "login gate verdict in flight, re-dispatching deferred block" → "ACTIVATED (not logged in to paddock)" → 全 hook 路径 skipping → 0 次 "Successfully hooked"；用户复测"我测了几次是 OK 的"
- [V] **token 残留根因**：三个物理存储（模块 external auth 文件 / lspd daemon sqlite / NPatch 管理器 store）——`App.xposedService` 单 binder 升级模式只写一个 store；`remove()` 在两框架 daemon 都不生效（put/remove 打成功日志、DB 行还在，拉库对拍实证）→ 修复 = `App.allServices` 遍历全部 binder + `putString("")` 空串覆盖 + 判定权威改走 ConfigProvider（不读 daemon）
- [V] **新装清登录态**：`PaddockClient.wipeAuthIfFreshInstall`——内部 prefs 标记 `auth_wipe_done_v1`（升级保留/卸载随删）与 external auth 文件（跨卸载存活）存活期错开构成 fresh-install 指纹；标记缺失 = 无条件全渠道清（不只看本地文件——残留可能只在 daemon store）；升级不清。实机双路径验证：首装清（用户测"测试成功"）+ 升级保留（`paddock_install_state.xml` 标记在 + auth 文件在 + 重装后仍登录，用户"还在，OK 了"）
- [V] **焦点乱漂修复**：`MainPagerState.springAnimateToPage` 结尾无条件 `scrollToPage(target)`——原版"误差<1px 跳过归位"在锁页（userScrollEnabled=false，无手势可触发 snap）下亚像素残差常驻，TextField 聚焦/IME 弹出的 bringIntoView 请求经 `PagerBringIntoViewSpec` 算出整页吸附距离 → pager 无手势凭空跳邻页（日志实锤：`show(ime)` 与 `targetPage` 跳变仅差 56ms，跳页时刻零触摸事件）；修复后几十次复现 0 跳页
- [V] `:app:lint` EXIT=0（全新 shell 15:38 重跑）；`assembleRelease -x lintVital…` BUILD SUCCESSFUL；装机 Success（版本号未动 `1.0.4 Alpha 1`）
- 工作区: 干净全推送。`ab5fa56`(登录门控 feat) → `b8e30ef`(token 链路 fix) → `d9de91a`(焦点闪跳 fix) → `92ca3f3`(CLAUDE.md)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0（15:38 全新 shell）
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL
adb install -r → Success（381QYFCN22B9A，Flyme/魅族机）
实机：门控开/关、退出登录即失效、新装清态、升级保留、点输入框 0 跳页——全部用户确认
```

## 3. 决策与理由
- **登录判定走 ConfigProvider 不走 daemon remote prefs** [V]：两框架 remove() 都失效 + 双 store 物理分裂，daemon 值可能是残留旧 token；auth 文件是唯一"写删都可靠"的闭环存储（saveAuth/clearAuth 第一落点）
- **新装清态无条件全渠道清**（用户定案"作用在我自己上面"）[V]：不只看本地文件是否存在——残留 token 可能只在 daemon store（退出登录时本地已删但 daemon 空串覆盖前残留）；clearAuth 对不存在的存储是 no-op
- **锁页时序 = 点「去登录」之后才锁**（用户 mid-turn 纠正三次）[V]：弹窗期间可自由浏览，与前置弹窗不打架的关键；锁页动作在 onDismissFinished（退出动画播完）里做，goLoginAsked 分流「去登录/退出」
- **焦点乱漂修在归位不在 bringIntoView 拦截** [X→V]：先试 `LocalBringIntoViewSpec` 覆盖返回 0 距离——编译过但实测仍跳（foundation 1.11.4 的 Pager 在 `ComposeFoundationFlags.isBringIntoViewRltBouncyBehaviorInPagerFixEnabled=true` 分支内 remember 包装 spec，覆盖时序不对）——后改 `springAnimateToPage` 无条件归位，残差物理性消失，对正常切页零观感差异
- 继承：游戏版本判定无 fail-open / 零 Hook 判定红线 / 非游戏包放行 / 版本号红线 / token 三级回落 / 配置 saved_at 仲裁 / 弹窗两变量契约

## 4. 失败的尝试 — 不要再试
- **本轮新增 [X]：`LocalBringIntoViewSpec` CompositionLocal 覆盖治 Pager 焦点跳页**——编译通过但实机仍跳（targetPage 照变）；foundation 1.11.4 HorizontalPager 无该参数，内部 remember 包装使覆盖不达 ContentInViewNode。有效修法 = `springAnimateToPage` 末尾无条件 `scrollToPage(target)`。不要再试覆盖 spec。
- **本轮新增 [X]：`evaluateLoginGate` 开头用 `loginVerdictDone` 初始值 true 做 early return**——整个函数变 no-op，查询永不发起、门控永不激活、early unlock 看到初始 true 直接放行装 hook（hook 抢跑 24ms 实测）；判定"未出结果"的唯一权威是 `loginEvalStarted` CAS
- **本轮新增 [X]：Remote Preferences `remove()` 清 token**——两框架 daemon 都不生效（日志成功、DB 不变）；必须 `putString("")` 空串覆盖
- **本轮新增 [X]：只写 `App.xposedService` 单 binder**——LSPosed 覆盖 NPatch 后 NPatch store 残留旧 token（"退出登录游戏仍放行+上传 401"根因）；必须遍历 `App.allServices`
- 继承（前向有效）[X]：`Unsupported game version, attempting hooks anyway` 旧路径 / 采样率差异假说 / 游戏插帧假说 / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / grep FATAL 零命中≠没崩 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260910003000-handoff.md` §4

## 5. 已知坑
- ⚠️ **登录门控模块侧 UI 未实测弹窗视觉** [?]——用户测的是"游戏侧失效 + 重装清态"；模块 App 弹窗→去登录→锁页→登录解锁全链路用户只口头确认过交互时序，未做逐帧视觉验收
- ⚠️ **一次"闪退"未定案** [?]——装机后第二次点输入框闪退一次，之后几十次不复现；logcat 后抓无堆栈、crash buffer 空、dropbox 无记录、模块 CrashCatcher 无捕获；同窗口日志见 `Transaction failed because process frozen`（Flyme 冻结期 binder 失败）+ `lowmemorykiller medium pressure`——证据指向系统杀后台/Flyme 管控而非模块 bug，按"无法证实"挂起；复现时立即抓 logcat
- ⚠️ **MainScreen settledPage/targetPage 诊断埋点保留** [V]（有意决策）——Logger 落文件、每切页一行，对将来 pager 类问题排查有价值；嫌吵可删（MainScreen.kt 两处 LaunchedEffect）
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V]（继承）——环境问题，`-x` 跳过法已入 CLAUDE.md
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——等用户 crash 现场；**模块 App 闪退排查未结案** [?]（继承）
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?]（继承）——用户未拍板
- ⚠️ **对话纪律（前向有效）** [V]——①mid-turn 消息逐条消化 ②旧快照不当现在时断言 ③装机前先验设备上 APK 版本。本轮新证：**调查日志前先对齐"几次"这类计数**（本轮把用户 3 次正常杀 App 误报成"三次闪退"，被用户纠正）；**修 UI 时序问题先拉触摸时间线**（系统层 MotionEvent 日志 + 应用内状态埋点对照），不靠复述猜
- ⚠️ 继承：导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260910003000-handoff.md` §5

## 6. 下一步（有序）
1. **等用户反馈**（登录门控 + 新装清态 + 乱漂修复均已实机验收；闪退案等复现）
2. v1.0.4 正式发布待用户定版（版本号红线；Release Notes 应含：启动门控四态 Toast + 登录门控 + 新装清登录态 + 焦点乱漂修复）
3. （可选）FAQ 条目「iQOO/OriginOS 用户：开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪
4. （可选，继承）ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 闪退若复现：立即回报，logcat 在抓的状态下拿堆栈（另可试导出日志看 ala_tool_crash.log）
- v1.0.4 正式版版本号与发布时机（Release Notes 范围见 §6.2）
- FAQ 条目要不要加、加在哪（README / 设置页说明 / 群公告）
