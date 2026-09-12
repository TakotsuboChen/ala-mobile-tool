# HANDOFF — 读全文再开始干活

生成时间: 2026-09-12T15:20:00+08:00 · Git HEAD: `7b492db`(工作 + 持久文档提交后,尚未含本次 handoff 提交)
信任规则: [V] = 交接时已用命令验证;[?] = 仅记忆未复核,当线索对待;[X] = 已证伪,别用。

## 0. 复核(下一会话先做)
- 锚点: main @ `7b492db`(2026-09-12 15:14;工作 472af9c + 文档 7b492db 均已 push)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `7b492db`——HEAD 必是本次 handoff 提交,其 parent 才是文档记录的 SHA;不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/CROSS_PROCESS_CHANNELS.md`(跨进程通道全景 + 已确认 bug 清单)+ CLAUDE.md 的 PaddockClient 条目(跨包信箱通道契约)

## 1. 当前目标
解决"模块 App 不运行时,配置与登录态仍需 100% 正确传给游戏"(用户红线,无商量余地)。已找到唯一可行通道(跨包信箱)并实机双向验收。

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因(配置)**: 三条写入通道在"用户改配置时游戏没在运行"下全断——① NPatch remote prefs 是两个物理 store ② 模块 filesDir 要经 Provider(被 ColorOS 拦 `prevent start ... scenePriority=0`) ③ 广播因游戏没开而丢失。**游戏侧读取路径一直是好的**,坏的是没人把新配置写进去(游戏 external 文件停在 2026-08-20,806B 无 saved_at)
- [V] **新通道(跨包信箱)**: 模块 App 持 AFA 写 `/sdcard/Android/media/<游戏包>/`;游戏进程同 uid 直读自己包该目录(零权限)。**不经 AMS**(ColorOS 启动管控失效)、**不经组件拉起**(stopped state 失效)、纯内核文件读写。实机: `Mailbox write OK`(模块进程,游戏未运行) → 游戏 `auth restored from mailbox (authoritative)` / `winner=mailbox`
- [V] **官方裁决 + 实机复核**: AFA **不覆盖**他包 `Android/data`(AFA 后仍 `ENOENT`);`Android/media` 不在受限区(无 AFA `EPERM` → 有 AFA 读写成功)。这是方案成立的唯一缺口
- [V] **配置仲裁 null 源短路 bug 修复**: `savedAt(null)=0` 参与比较,三源 ts 全 0 时首条分支恒真 → `bestJson=null` → 丢弃已读到的配置(实机: `ConfigProvider ok len=865` 下一行即 `No config ... using defaults`)。修法: null 源先排除再比 ts
- [V] **反向红线修复(用户原话: "模块 App 没登录,游戏打开读缓存 Token 能登录" 不可以)**: `clearAuth` 写信箱**空 token 而非删文件**——"文件存在但 token 空"= 权威已登出(游戏据此 `clearGateCache()` 否决缓存),"文件不存在"= 从未登录(回落缓存)。实机: 登出→杀模块→开游戏 → `auth: mailbox authoritative → logged OUT (gate cache cleared)` + 零 Hook
- [V] **AFA 权限门**: `ConfigActivity` 未授权时 `return@setContent`(主界面不组合),满屏「必要权限」页;`LifecycleEventObserver` 监听 `ON_RESUME` 驱动 key 重查(非 `LifecycleResumeEffect`——它只在首次进入组合时跑,感知不到从外部设置页归来)
- [V] `:app:lint` EXIT=0(全新 shell);`assembleRelease -x lintVital...` EXIT=0;`adb install -r` Success(e98a35cb,版本号未动 `1.0.4 Alpha 1`)
- [V] 用户实机验收: 正向(登录→杀模块→开游戏 gate pass) + 反向(登出→杀模块→开游戏 未登录+零 Hook) **两端均通过**
- 工作区: 干净,全部已 push(仅在 handoff 提交后才含 HANDOFF.md)

### 测试/build 输出(真实退出码)
```
./gradlew :app:lint → EXIT=0(全新 shell)
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL
adb install -r → Success
实机正向(15:07:34): mailbox ok len=927 → auth restored from mailbox (authoritative) → gate pass
实机反向(15:08:12): Mailbox write OK paddock_auth.json (12B) → auth: mailbox authoritative → logged OUT (gate cache cleared)
```

## 3. 决策与理由
- **改用"文件信箱"而非修复进程通道** [V]——三重约束(模块不运行 / ColorOS 关关联启动 / 游戏 stopped state)下,凡"读前提含拉起模块进程"或"需组件拉起"的通道全灭(7 份调研交叉印证)。文件通道把跨进程通信降级为共享文件系统读写,两侧时间解耦。否决: 保活模块进程(要四个用户开关+常驻通知,比"关联启动"更贵)/ 引导开关联启动(会被系统重置)/ provider+SAF(游戏 stopped state 下 `No content provider`)
- **登出写空 token 而非删文件** [V]——"存在+空"与"不存在"编码两种状态,前者才能权威否决门控缓存。否决: 删文件(与"从未登录"不可区分 → 游戏回落陈旧缓存 → 踩反向红线)
- **信箱"存在即权威"短路返回** [V]——信箱是模块侧权威写入,命中即 return,不再看后续通道(否则被缓存捞回)
- **AFA 设为硬性前置(满屏不可跳过)** [V](用户定案)——无 AFA 时模块无法可靠传状态,给"尽力而为"的降级比直接拦住更糟
- 继承: fetchMe 晚于 verdictDone / 401 归网络文案 / 4xx 先存后判(isRetryableStatus 单源)/ 补传静默入队提示 / 零 Hook 判定红线 / 版本号红线

## 4. 失败的尝试 — 不要再试
- **MTDataFilesProvider 自授 URI 传配置** → 自授成功(`grantUriPermission OK`)、游戏活着时可读写,但**游戏 stopped state 下 `FileNotFoundException: No content provider`**(AOSP 规则: 停止状态禁止自动拉起组件,非 ColorOS 也非权限问题)[V]——`am force-stop`/最近任务划掉后实测 `stopped=true`。不要再把它当"游戏未运行时可写"的通道(仅"游戏在后台未划掉"时可用)
- **`am kill` 杀前台进程** [V]——`am kill` 只杀后台进程,前台时是空操作(系统保护);`kill -9` 被 SELinux 拒(shell uid=2000)。想测"进程死但非 stopped"要先退到后台再 `am kill`
- **给模块加 `MANAGE_EXTERNAL_STORAGE` 后 `appops set` 授权** [V]——shell uid 无 `MANAGE_APP_OPS_MODES`,必须**用户在系统设置手动开**(声明权限后设置页才会出现该入口——没声明就没有)
- **provider + SAF 手动树授权** → 授权可持久化(`takePersistableUriPermission OK` + `persistedUriPermissions size=1`),但 provider 宿主进程不在时 `No content provider`[V]。SAF 需一次用户手势是硬约束(`MANAGE_DOCUMENTS` 是 `signature|role`,普通 App 拿不到)
- 继承(前向有效)[X]: `LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页 / `evaluateLoginGate` 用 `loginVerdictDone` 初始值 true early return / Remote Preferences `remove()` 清 token(必须空串覆盖+全 binder 遍历) / 旧 hook-anyway 路径 / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号 / ConfigProvider 当全模式唯一权威登录通道——详见 `.handoffs/20260912151438-handoff.md` §4

## 5. 已知坑
- ⚠️ **配置热更新仍走广播**(游戏运行时改配置即时生效) [?]——本轮只切了"冷启动读"到信箱;游戏运行中改配置靠 `ConfigReceiver` 广播立即改 native `g_config`。ColorOS 关关联启动 + 游戏不在前台时广播可能丢,但这场景本就是信箱覆盖的(下次启动读最新)。用户明确说"暂时保持现状"
- ⚠️ **信箱残留探针文件** [?]——`/sdcard/Android/media/<游戏包>/` 下有三个测试残留(`ala_probe_from_game.txt`/`ala_probe_from_module.json`),非代码产物,可手动删,无功能影响
- ⚠️ **飞行模式补传链路仍未实机验收** [?](继承)——上传失败入队提示+30s 静默补传是上轮装机功能,用户一直未跑飞行模式验证
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"
- ⚠️ **上轮弹窗改动未实机视觉验收** [?](继承)——注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器
- ⚠️ **`ConfigProvider.pushGameLog/readGameLog` 是死代码** [V](本会话审计发现)——零调用方,日志实走 LogReceiver 分片;`ModConfig.withPositionDefaults` 同
- ⚠️ **`drainQueue` 的 `ok` 计数永不递增** [V](本会话审计发现)——恒返回 0,日志谎报 `drained 0 laps`(实际成功)。日志不可信,未修
- ⚠️ **配置写入未遍历 `App.allServices`** [V](本会话审计发现)——`ModConfig.write` 只用单例 `App.xposedService`,双框架并存时配置只写 LSPosed store(token 早已修过同款坑)。未修(信箱通道已兜底)
- ⚠️ **对话纪律(前向有效)** [V](继承)——1mid-turn 消息逐条消化 2旧快照不当现在时断言 3装机前先验设备上 APK 版本 4调查日志前先对齐"几次"计数 5修 UI 时序问题先拉触摸时间线
- ⚠️ 继承: 导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码 / ABSdiag 降频 / 闪退未定案——详见 `.handoffs/20260912151438-handoff.md` §5

## 6. 下一步(有序)
1. **实机验收飞行模式补传链**(继承未做): 飞行模式跑有效圈(应弹入队提示)→ 保持飞行模式看 30s 静默重试日志 → 恢复网络 30s 内应自动补传
2. **修复审计发现的三个小 bug**(均 [V] 已定位,未修): ③ `drainQueue` 计数递增 ④ 配置写入遍历 `App.allServices` ⑤ 删死代码 `ConfigProvider.pushGameLog/readGameLog` + `withPositionDefaults`
3. **v1.0.4 正式发布待用户定版**(版本号红线;Release Notes 应含: 跨包信箱通道 + AFA 权限门 + 配置仲裁短路修复 + 反向红线修复 + 前几轮的门控/两级回落/丢圈保护等)
4. (可选)清理设备上信箱探针残留文件
5. (可选,继承)ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 信箱通道是否需要在游戏侧也加"配置热更新"路径(现走广播,ColorOS 下可能丢)?用户已说"暂时保持现状"
- 永久 4xx 的处理: 先提示后静默丢弃(现状)vs 无限重试(不丢但可能堵队头)
- v1.0.4 正式版版本号与发布时机
- 官版游戏(无 AFA 声明)是否也要加权限门?当前只有模块 App 需要 AFA,游戏侧读自己目录零权限——官版应无需改动,但未实机验证官版全链路
