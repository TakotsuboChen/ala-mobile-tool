# HANDOFF — 读全文再开始干活

生成时间: 2026-09-10T23:43:04+08:00 · Git HEAD: `c1b375f`(工作 2 切片 + 持久文档提交后,尚未 push)
信任规则: [V] = 交接时已用命令验证;[?] = 仅记忆未复核,当线索对待;[X] = 已证伪,别用。

## 0. 复核(下一会话先做)
- 锚点: main @ `c1b375f`(工作+持久文档提交后的 HEAD;`origin/main` 仍停在 `bdbf0dc`,本地 3 个新提交待 handoff 提交后一并 push)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c1b375f`——HEAD 必是本次 handoff 提交,其 parent 才是文档记录的 SHA;不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md 的 PaddockClient/PaddockUploader 条目 + ForceUpdateGate 条目 + 记忆 `paddock-login-gate.md`

## 1. 当前目标
围场三件事,全部完成并装机(待用户实机验收):1 启动两态 Toast(连通问候 / 连不上提示);2 上传失败一律入队+提示;3 每 30s 静默补传队列。

## 2. 已验证状态 — 工作实际停在哪
- [V] **启动两态 Toast**(`ForceUpdateGate.announcePaddockConnection`):登录门控放行后独立线程 `fetchMe`——成功「已成功连接到围场,欢迎 x 号车手 xxx!」(regSeq+username,user 空则退化);失败(断网/非200/401)「网络连接异常,成绩上传可能会失败,未上传的成绩将会保存在本地,连接成功后自动补传」。两态都放行,纯提示;`Toast.LENGTH_LONG`;每进程一次(`loginEvalStarted` CAS 保证)
- [V] **上传失败一律入队**(`PaddockClient.uploadLap`):200 以外**全部**分支(4xx/5xx/IOException/非 IO 兜底异常/无 token)都 `enqueue()` 并返回 `TOAST_LAP_QUEUED`「圈速上传失败,已保存在本地,待连接后自动补传!」。改前 4xx 与非 IO 异常既不提示也不入队,圈直接蒸发(用户反馈"偶尔没见补传、丢了成绩"的根因)
- [V] **可重试判据单源**(`isRetryableStatus` = 401/408/429/5xx/-1):`uploadLap` 与 `drainQueue` 共用。改前 5xx 在直传侧当"明确拒绝"丢圈、在补传侧当可重试(判据分裂)。永久 4xx 在下一轮补传时丢弃,不占队头挡后面的好圈
- [V] **30s 静默补传**(`PaddockClient.drainPendingQueueSilently` + `PaddockUploader.maybeDrainPending`):无 token/无待传 → 直接 return(无操作无噪音);有则补一批(≤20)。补传失败**不弹 Toast**只落日志。主线程仅做时间判定,`pendingCount()`+`drainQueue` 全在 IO 线程
- [V] `:app:lint` EXIT=0(23:36 全新 shell,0 errors);`assembleRelease -x lintVital...` EXIT=0;`adb install -r` Success(381QYFCN22B9A,22:31:48,版本号未动 `1.0.4 Alpha 1`)
- 工作区: 干净。本地 `bdbf0dc`..`c1b375f` 三个提交(工作 2 + 持久文档 1),**未 push**

### 测试/build 输出(真实退出码)
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0(23:36 全新 shell;57 warnings 既有)
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → EXIT=0
adb install -r → Success
```

## 3. 决策与理由
- **问候 Toast 必须晚于 verdictDone** [V]:`fetchMe` 超时上限 = CONNECT 8s + READ 10s = 18s;若在 `verdictDone` 置位前发起,early unlock 会错过 `BillingManager.Awake()` ≈2s 窗口 → 解锁失败。故放 `verdictDone.set(true)` **之后**起独立线程。顺序契约(用户定案):判定先定论、再问候,两者不重叠
- **401 归"网络"文案** [V]:本地确有登录态但服务端不认;功能后果与断网一致(成绩失败→本地暂存→续传),用户不关心原因。另记 WARN 日志备查。否决:单列"登录失效"文案(用户只给两态规格)
- **4xx 也入队(先存后判)** [V]:网关限流/赛道映射未就绪等可能瞬时;宁可先存,是否重试由 `isRetryableStatus` 在补传时判。否决:4xx 直接丢(曾是丢圈漏点)
- **补传静默、入队提示** [V]:失败**当下**提示一次(用户刚跑完圈,需知道成绩没丢),之后周期重试不打扰。否决:补传失败也弹(周期任务反复弹=骚扰)
- **30s 挂 PaddockUploader 1Hz Handler** [V]:复用既有主线程 Handler + IO 单线程池,主线程零 IO。否决:另起 Timer/线程(多一份生命周期管理)
- 继承:登录判定走 ConfigProvider / 新装清态无条件全渠道 / 零 Hook 判定红线 / 版本号红线 / 弹窗两变量契约(外部驱动场景)

## 4. 失败的尝试 — 不要再试
- 继承(前向有效)[X]:`LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页——有效修法 = `springAnimateToPage` 末尾无条件 `scrollToPage(target)`
- 继承 [X]:`evaluateLoginGate` 用 `loginVerdictDone` 初始值 true 做 early return(函数变 no-op,门控永不激活);判定"未出结果"唯一权威 = `loginEvalStarted` CAS
- 继承 [X]:Remote Preferences `remove()` 清 token(两框架 daemon 都不生效)——必须 `putString("")` 空串覆盖;只写 `App.xposedService` 单 binder 也不行——必须遍历 `App.allServices`
- 继承 [X]:旧 hook-anyway 路径 / 采样率差异假说 / 游戏插帧假说 / scheduleDraw 治 LTPO / 单变量弹窗挂载(外部驱动场景)/ 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260910153000-handoff.md` §4

## 5. 已知坑
- ⚠️ **本轮三件事未实机验收** [?]——装机 Success 但用户尚未回报:启动两态 Toast 文案/号手、上传失败入队提示、30s 静默补传(飞行模式跑圈 → 恢复网络后应自动补上)
- ⚠️ **4xx 会先弹"已保存在本地"再被 30s 后静默丢弃** [?](本会话判断,待用户确认)——永久 4xx 不重试(防堵队头),代价是约 30s 的"善意的假话"。要"宁可留着也不能丢"则去掉 `isRetryableStatus` 的丢弃判据
- ⚠️ **上一轮弹窗改动未实机视觉验收** [?](继承)——注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器,三点待确认
- ⚠️ **一次"闪退"未定案** [?](继承)——装机后第二次点输入框闪退一次,之后几十次不复现;证据指向 Flyme 杀后台非模块 bug;复现时立即抓 logcat
- ⚠️ **MainScreen settledPage/targetPage 诊断埋点保留** [V](有意决策,继承)——嫌吵可删(MainScreen.kt 两处 LaunchedEffect)
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V](继承)——环境问题,`-x` 跳过法已入 CLAUDE.md
- ⚠️ **游戏闪退真凶未定案** [?](继承);**ABSdiag/TCdiag 高频诊断扰度未处理** [?](继承)——用户未拍板
- ⚠️ **对话纪律(前向有效)** [V](继承)——1mid-turn 消息逐条消化 2旧快照不当现在时断言 3装机前先验设备上 APK 版本 4调查日志前先对齐"几次"计数 5修 UI 时序问题先拉触摸时间线
- ⚠️ 继承:导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260910153000-handoff.md` §5

## 6. 下一步(有序)
1. **等用户实机验收本轮三件事**:飞行模式跑有效圈(应弹入队提示)→ 保持飞行模式看 30s 静默重试日志 → 恢复网络 30s 内应自动补传;联网启动应弹车手问候
2. **push 三个本地提交**——若 `git log origin/main..HEAD` 显示未推送且用户确认,再 `git push`;否则跳过
3. v1.0.4 正式发布待用户定版(版本号红线;Release Notes 应含:启动门控四态 Toast + 登录门控 + 新装清登录态 + 焦点乱漂修复 + 弹窗文案/跳群/未登录禁刷新 + 启动两态 Toast + 丢圈保护)
4. (可选)FAQ 条目「iQOO/OriginOS 用户:开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪
5. (可选,继承)ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 三件事的验收结果(尤其静默补传在弱网→恢复网络后的补传表现)
- 永久 4xx 的处理:先提示后静默丢弃(现状)vs 无限重试(不丢但可能堵队头)
- v1.0.4 正式版版本号与发布时机
- FAQ 条目要不要加、加在哪
