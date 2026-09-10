# HANDOFF — 读全文再开始干活

生成时间: 2026-09-11T00:13:03+08:00 · Git HEAD: `c3c5567`(工作切片 + 持久文档提交后,尚未含本次 handoff 提交)
信任规则: [V] = 交接时已用命令验证;[?] = 仅记忆未复核,当线索对待;[X] = 已证伪,别用。

## 0. 复核(下一会话先做)
- 锚点: main @ `c3c5567`(2026-09-11 00:13;工作 f9e10b1 + 文档 c3c5567 均已 push)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c3c5567`——HEAD 必是本次 handoff 提交,其 parent 才是文档记录的 SHA;不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md 的 PaddockClient 条目(登录判定两级回落契约)+ 记忆 `paddock-login-gate.md`

## 1. 当前目标
修复"LSPosed 读不到登录态、NPatch 正常"(模块已登录仍被误判未登录→零 hook+循环 Toast),已修复并实机验收通过。

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因**:官版游戏 APK 改不了 manifest(queries 不含 `tools.alamobile.mod`),Android 11+ 包可见性下游戏进程跨包 resolve 模块 ConfigProvider 失败 → `Unknown authority`,Binder call 根本没发出去。旧代码把该异常当"未登录"(fail-closed)→ 误判。NPatch 正常:共存版 APK 进程内 call 必达(且走 ConfigProvider 是唯一通道)
- [V] **修复**:`PaddockClient.queryLoginStateFromModule` 两级回落——① ConfigProvider read_token(NPatch 必达,权威)返回三态 true/false/null;null(通道不可达)→ ② 回落 `remoteTokenReader` 读 LSPosed daemon remote prefs(实机 `remote token read: len=48` 证明读侧通)
- [V] **实机验收通过**(00:04:07 日志):`Unknown authority` → `remote prefs fallback → true` → `gate pass` → BillingManager 全套 hook 装上 → 「已成功连接到围场,欢迎 1 号车手 Takotsubo!」问候 Toast,全链路 488ms
- [V] **定案修正**:"绝不读 remote prefs"旧定案过修——其不可靠只在**写侧**(remove() 两框架不落盘,已由空串覆盖修复),读侧无残留误判(清过的 key 读回空串)。写侧铁律不变:清 token 空串覆盖+遍历 `App.allServices`
- [V] `:app:lint` EXIT=0(00:10 全新 shell);`assembleRelease -x lintVital...` EXIT=0(00:06);`adb install -r` Success(381QYFCN22B9A,版本号未动 `1.0.4 Alpha 1`)
- 工作区: 干净,全部已 push(`origin/main` = HEAD)

### 测试/build 输出(真实退出码)
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0(00:10 全新 shell)
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → EXIT=0(00:06)
adb install -r → Success
实机日志关键链(00:04:07):Unknown authority → remote prefs fallback → true → gate pass → hooks installed → 问候 Toast
```

## 3. 决策与理由
- **回落只挂在"通道不可达"(null)上,不挂在 false 上** [V]:false = Provider 可达且 auth 文件确实无 token(NPatch 权威判定),回落会绕过它造成"模块文件没 token 但 daemon 残留"误放行。否决:任何异常都回落(fail-closed 被掏空)
- **读侧/写侧分离定案** [V]:remote prefs 写侧不可靠(remove 不落盘)≠ 读侧不可靠;本轮 `len=48` 实证 LSPosed 读侧通。否决:继续"绝不读 remote prefs"一刀切(LSPosed 用户全被误判)
- 继承:fetchMe 晚于 verdictDone / 401 归网络文案 / 4xx 先存后判(isRetryableStatus 单源)/ 补传静默入队提示 / 30s 挂 PaddockUploader 1Hz Handler / 零 Hook 判定红线 / 版本号红线

## 4. 失败的尝试 — 不要再试
- **ConfigProvider 当全模式唯一权威登录通道** → LSPosed 下游戏包对模块包无包可见性,`Unknown authority` 被当"未登录",模块已登录仍被锁(hook 全禁+循环 Toast)[V]——实机日志 23:48:24 实证。修法=两级回落。不要再把 Provider 当 LSPosed 可用通道。
- 继承(前向有效)[X]:`LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页(有效修法=`springAnimateToPage` 末尾无条件 `scrollToPage(target)`)/ `evaluateLoginGate` 用 `loginVerdictDone` 初始值 true early return(查询永不发起)/ Remote Preferences `remove()` 清 token(必须空串覆盖+全 binder 遍历)/ 旧 hook-anyway 路径 / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260910234304-handoff.md` §4

## 5. 已知坑
- ⚠️ **飞行模式补传链路仍未实机验收** [?]——上传失败入队提示+30s 静默补传是上轮装机功能,用户一直未跑飞行模式验证;本轮修复后也未测
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](上轮定案待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话";要"宁可留不丢"就去掉丢弃判据
- ⚠️ **上轮弹窗改动未实机视觉验收** [?](继承)——注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器
- ⚠️ **一次"闪退"未定案** [?](继承)——装机后第二次点输入框闪退一次,几十次不复现;证据指向 Flyme 杀后台;复现时立即抓 logcat
- ⚠️ **MainScreen settledPage/targetPage 诊断埋点保留** [V](有意决策,继承)——嫌吵可删
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V](继承)——环境问题,`-x` 跳过法已入 CLAUDE.md
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?](继承)——用户未拍板
- ⚠️ **对话纪律(前向有效)** [V](继承)——1mid-turn 消息逐条消化 2旧快照不当现在时断言 3装机前先验设备上 APK 版本 4调查日志前先对齐"几次"计数 5修 UI 时序问题先拉触摸时间线
- ⚠️ 继承:导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260910234304-handoff.md` §5

## 6. 下一步(有序)
1. **实机验收飞行模式补传链**:飞行模式跑有效圈(应弹入队提示)→ 保持飞行模式看 30s 静默重试日志 → 恢复网络 30s 内应自动补传
2. v1.0.4 正式发布待用户定版(版本号红线;Release Notes 应含:启动门控四态 Toast + 登录门控+两级回落修复 + 新装清登录态 + 焦点乱漂修复 + 弹窗文案/跳群/未登录禁刷新 + 启动两态 Toast + 丢圈保护)
3. (可选)FAQ 条目「iQOO/OriginOS 用户:开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪
4. (可选,继承)ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 飞行模式补传链验收结果(尤其弱网→恢复网络后的补传表现)
- 永久 4xx 的处理:先提示后静默丢弃(现状)vs 无限重试(不丢但可能堵队头)
- v1.0.4 正式版版本号与发布时机
- FAQ 条目要不要加、加在哪
