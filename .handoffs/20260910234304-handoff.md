# HANDOFF — 读全文再开始干活

生成时间: 2026-09-10T17:05:00+08:00 · Git HEAD: `77ad0ec`（工作+持久文档提交后，已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `77ad0ec`（2026-09-10 17:05，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `77ad0ec`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md PaddockClient.kt / paddock UI 目录条目 + 记忆 `paddock-login-gate.md`

## 1. 当前目标
围场注册/忘记密码弹窗文案定稿 + 注册按钮复制指令并跳转 QQ 群 + 未登录页禁用下拉刷新——**全部完成并装机**（待用户实机验收反馈）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **注册弹窗重做**（`PaddockPagerMiuix.kt` RegisterDialog）：正文换防呆文案（勿改指令文字/勿当入群答案），删指令原文展示与"返回此处登录"两行；唯一蓝按钮「复制申请指令并跳转QQ群」= onCopy 复制 → show=false 关弹窗 → `openQqGroup` 拉群卡片页（降级网页兜底）；标题补 `fontWeight = Bold`（对齐弹窗排版铁律）
- [V] **忘记密码弹窗正文定稿**（ResetPasswordDialog）：「请在模块 QQ 交流群内发送「我需要重置密码」7 个字，智能助理会根据你的群身份自动匹配围场账号回复重置码，将其填到此处：」
- [V] **群号/URL 单源化**：`UrlUtils.kt` 顶部 `MODULE_QQ_GROUP_CODE`("757940708") + `MODULE_QQ_GROUP_FALLBACK_URL` 常量；OverviewPagerMiuix「QQ 群」入口与 RegisterDialog 共用
- [V] **未登录页禁用下拉刷新**：根因 = miuix PullToRefresh 靠 NestedScroll onPostScroll 仲裁，只在内容滚到顶后仍有剩余下拉位移才触发；未登录核验页内容不满一屏，LazyColumn 零消费，下拉全量直达 → 凭空拉出指示器。修法 = LazyColumn 提为共用 `paddockList` lambda，仅 `uiState.loggedIn` 分支包 PullToRefresh，未登录直接渲染列表。登录后行为不变（满屏内容下拉先被列表消费，问题从未暴露）
- [V] `:app:lint` EXIT=0（全新 shell 17:00）；`assembleRelease -x lintVital…` EXIT=0；`adb install -r` Success（381QYFCN22B9A，版本号未动 `1.0.4 Alpha 1`）
- 工作区: 干净全推送。`407e44e`(工作切片) → `77ad0ec`(CLAUDE.md+README)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0（17:00 全新 shell；57 warnings 既有）
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → EXIT=0
adb install -r → Success
```

## 3. 决策与理由
- **未登录禁刷新修在组合层** [V]：条件化挂载（loggedIn 才包 PullToRefresh）而非给 miuix 加 enabled 参数——后者要改库源码且语义不贴（该页面状态下根本没有刷新功能）。否决方案：fork miuix PullToRefresh 加开关。
- **注册弹窗用单变量 show 非两变量契约** [V]：它无外部驱动 show（ViewModel 只下发 regDialogCommand），`onDismissFinished` 回调清空 command → 组合树移除，退出动画正常播；CLAUDE.md 两变量契约针对外部状态驱动场景，此处不适用。
- **复制先于跳转** [V]：剪贴板写入是同步内存操作，QQ 拉起后指令已在板上。
- 继承：登录判定走 ConfigProvider / 新装清态无条件全渠道 / 锁页时序=点「去登录」之后 / 焦点乱漂修在归位 / 零 Hook 判定红线 / 版本号红线 / 弹窗两变量契约（外部驱动场景）

## 4. 失败的尝试 — 不要再试
- 继承（前向有效）[X]：`LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页（foundation 1.11.4 内部 remember 包装使覆盖不达 ContentInViewNode）——有效修法 = `springAnimateToPage` 末尾无条件 `scrollToPage(target)`
- 继承 [X]：`evaluateLoginGate` 用 `loginVerdictDone` 初始值 true 做 early return（函数变 no-op，门控永不激活）；判定"未出结果"唯一权威 = `loginEvalStarted` CAS
- 继承 [X]：Remote Preferences `remove()` 清 token（两框架 daemon 都不生效）——必须 `putString("")` 空串覆盖；只写 `App.xposedService` 单 binder 也不行——必须遍历 `App.allServices`
- 继承 [X]：旧 hook-anyway 路径 / 采样率差异假说 / 游戏插帧假说 / scheduleDraw 治 LTPO / 单变量弹窗挂载（外部驱动场景）/ 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260910153000-handoff.md` §4

## 5. 已知坑
- ⚠️ **本轮弹窗改动未实机视觉验收** [?]——装机 Success 但用户尚未回报：注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器，三点都待确认
- ⚠️ **一次"闪退"未定案** [?]（继承）——装机后第二次点输入框闪退一次，之后几十次不复现；logcat 后抓无堆栈、crash buffer 空；证据指向 Flyme 杀后台非模块 bug，按"无法证实"挂起；复现时立即抓 logcat
- ⚠️ **模块侧登录弹窗未逐帧视觉验收** [?]（继承）——用户只口头确认交互时序
- ⚠️ **MainScreen settledPage/targetPage 诊断埋点保留** [V]（有意决策，继承）——Logger 落文件每切页一行；嫌吵可删（MainScreen.kt 两处 LaunchedEffect）
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V]（继承）——环境问题，`-x` 跳过法已入 CLAUDE.md
- ⚠️ **游戏闪退真凶未定案** [?]（继承）；**ABSdiag/TCdiag 高频诊断扰度未处理** [?]（继承）——用户未拍板
- ⚠️ **对话纪律（前向有效）** [V]（继承）——①mid-turn 消息逐条消化 ②旧快照不当现在时断言 ③装机前先验设备上 APK 版本 ④调查日志前先对齐"几次"计数 ⑤修 UI 时序问题先拉触摸时间线
- ⚠️ 继承：导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260910153000-handoff.md` §5

## 6. 下一步（有序）
1. **等用户实机验收**：注册弹窗（新文案+按钮跳群）、忘记密码弹窗新正文、未登录页下拉无刷新指示器
2. v1.0.4 正式发布待用户定版（版本号红线；Release Notes 应含：启动门控四态 Toast + 登录门控 + 新装清登录态 + 焦点乱漂修复 + 本轮弹窗文案/跳群/未登录禁刷新）
3. （可选）FAQ 条目「iQOO/OriginOS 用户：开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪
4. （可选，继承）ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 弹窗三点验收结果（尤其跳群在无 QQ 设备上的网页兜底表现）
- v1.0.4 正式版版本号与发布时机
- FAQ 条目要不要加、加在哪（README / 设置页说明 / 群公告）
