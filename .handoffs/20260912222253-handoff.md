# HANDOFF — 读全文再开始干活

生成时间: 2026-09-12T21:40:00+08:00 · Git HEAD: `88d46ef`(工作 + 持久文档提交后,尚未含本次 handoff 提交)
信任规则: [V] = 交接时已用命令验证;[?] = 仅记忆未复核,当线索对待;[X] = 已证伪,别用。

## 0. 复核(下一会话先做)
- 锚点: main @ `88d46ef`(2026-09-12 21:37;工作 0189f99/b9f918d + 文档 88d46ef 均已 push)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `88d46ef`——HEAD 必是本次 handoff 提交,其 parent 才是文档记录的 SHA;不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/CROSS_PROCESS_CHANNELS.md`(跨进程通道全景)+ CLAUDE.md 的 PaddockClient 条目(跨包信箱 + allServices 契约)

## 1. 当前目标
上一目标(模块 App 不运行时配置与登录态 100% 正确传给游戏)已于 2026-09-12 完成并双向验收。本会话清掉审计遗留的 3 个小 bug + 补完飞行模式补传链实机验收。**现无进行中目标**;下一步是 v1.0.4 定版发布(等用户)。

## 2. 已验证状态 — 工作实际停在哪
- [V] **③ `drainQueue` 计数修复**(0189f99):`ok` 声明后从未 `ok++`,恒返回 0 → 日志谎报 `drained 0 laps`。改为仅 2xx 计成功(非重试 4xx 丢弃但不计,避免二次谎报)。实机:飞行模式补传成功那行从 `0/1` 变 `1/1`
- [V] **④ 配置写入遍历 `allServices`**(0189f99):`ModConfig.write` 此前只写单例 `App.xposedService`,双框架下漏写 NPatch store。改遍历 `App.allServices` + `App.xposedService==null` 时主动 `bindNpatchRemoteService`。实机双框架:打出 `(NPatch)` + `(LSPosed)` 两条
- [V] **⑤ 删死代码**(b9f918d):`ConfigProvider.pushGameLog/readGameLog`(零调用方)+ `ModConfig.withPositionDefaults`(@Suppress unused)+ 专用常量,净删 92 行
- [V] **飞行模式补传链实机验收**(HANDOFF §6.1 遗留项):跑有效圈 `LAPbest 1:13.407` → 断网 Toast 入队 → 周期重试 `0/1`×3(保留)→ 恢复网络 `1/1`(自动补传闭环)
- [V] 构建/lint(全新 shell):`:app:lint` EXIT=0;`assembleRelease -x lintVital...` BUILD SUCCESSFUL;`adb install -r` Success(MEIZU 20,USB)
- [V] 版本号未动(仍 `1.0.4 Alpha 1`);工作区干净,全部已 push(仅 handoff 提交后含 HANDOFF.md)

### 测试/build 输出(真实退出码)
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL, EXIT=0
adb install -r → Success
实机补传(21:35:52): periodic drain: 1/1 pending laps uploaded  ← ③ 修复判据
实机配置(21:36:33): Config written via remote preferences (NPatch) + (LSPosed)  ← ④ 修复判据
```

## 3. 决策与理由
- **③ 只把 2xx 计为成功** [V]——`ok` 的实际用途是 callsite 的"队列是否有进展"日志判据;永久 4xx 虽也移出队列(丢弃)但不是上传成功,混入会二次谎报。否决:把丢弃也计入(日志再次失真)。队列状态本就正确,改的纯是可观测性
- **④ 对齐 token 路径** [V]——token 的 saveAuth/clearAuth 早已遍历 `allServices`,配置写入漏了同款修复(两处仅隔几个文件)。NPatch 无 daemon 异步推 binder,故补主动绑定。否决:只遍历不绑定(纯 NPatch 冷启动时 allServices 为空,照样写不进)
- 继承: 信箱通道优于修复进程通道 / 登出写空 token 而非删文件 / 信箱"存在即权威"短路 / AFA 硬性前置 / fetchMe 晚于 verdictDone / 4xx 先存后判(isRetryableStatus 单源) / 零 Hook 判定红线 / 版本号红线——详见 `.handoffs/20260912151438-handoff.md` §3

## 4. 失败的尝试 — 不要再试
- **MTDataFilesProvider 自授 URI 传配置** → 游戏 stopped state 下 `FileNotFoundException: No content provider`(AOSP 规则:停止状态禁止自动拉起组件,非 ColorOS 亦非权限问题)[V]——仅"游戏在后台未划掉"时可用。不要再当"游戏未运行时可写"的通道
- **`am kill` 杀前台进程** → `am kill` 只杀后台进程,前台时空操作;`kill -9` 被 SELinux 拒(shell uid=2000)[V]——想测"进程死但非 stopped"要先退后台再 `am kill`
- **给模块加 AFA 后 `appops set` 授权** → shell uid 无 `MANAGE_APP_OPS_MODES`,必须用户在系统设置手动开(声明权限后设置页才出现入口)[V]
- **provider + SAF 手动树授权** → 授权可持久化,但 provider 宿主进程不在时 `No content provider`;SAF 需一次用户手势是硬约束(`MANAGE_DOCUMENTS` 是 signature|role)[V]
- **无线 adb 开飞行模式** → 连接必断(无线调试跑在网络栈上);此类断网场景验证必须走 USB(2026-09-12 实测)[V]。另:每次重连端口都变,`adb connect` 前必须 `adb mdns services` 重发现
- 继承(前向有效)[X]: `LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页 / `evaluateLoginGate` 用 `loginVerdictDone` 初始值 true early return / Remote Preferences `remove()` 清 token / 旧 hook-anyway 路径 / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号 / ConfigProvider 当全模式唯一权威登录通道——详见 `.handoffs/20260912151438-handoff.md` §4

## 5. 已知坑
- ⚠️ **配置热更新仍走广播**(游戏运行时改配置即时生效) [?]——冷启动读信箱已解决;运行中靠 `ConfigReceiver` 广播改 native `g_config`。ColorOS 关关联启动 + 游戏不在前台时广播可能丢(此场景由信箱覆盖,下次启动读最新)。用户明确说"暂时保持现状"
- ⚠️ **信箱残留探针文件** [?]——`/sdcard/Android/media/<游戏包>/` 下有三个测试残留(`ala_probe_from_game.txt`/`ala_probe_from_module.json`),非代码产物,可手动删,无功能影响
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"
- ⚠️ **上轮弹窗改动未实机视觉验收** [?](继承)——注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器
- ⚠️ **对话纪律** [V](继承)——1mid-turn 消息逐条消化 2旧快照不当现在时断言 3装机前先验设备上 APK 版本 4调查日志前先对齐"几次"计数 5修 UI 时序问题先拉触摸时间线
- ⚠️ 继承: 导出探活广播链 ROM 限流边界 / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码 / ABSdiag 降频 / 闪退未定案——详见 `.handoffs/20260912151438-handoff.md` §5

## 6. 下一步(有序)
1. **v1.0.4 正式发布待用户定版**(版本号红线):Release Notes 应含——跨包信箱通道 + AFA 权限门 + 配置仲裁 null 源短路修复 + 反向红线修复(登出空 token)+ `drainQueue` 计数 + 配置写入遍历 allServices + 前几轮门控/两级回落/丢圈保护
2. (可选)清理设备上信箱探针残留文件
3. (可选,继承)ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 信箱通道是否需要在游戏侧也加"配置热更新"路径(现走广播,ColorOS 下可能丢)?用户已说"暂时保持现状"
- 永久 4xx 的处理: 先提示后静默丢弃(现状)vs 无限重试(不丢但可能堵队头)
- v1.0.4 正式版版本号与发布时机
- 官版游戏(无 AFA 声明)是否也要加权限门?当前只有模块 App 需要 AFA,游戏侧读自己目录零权限——官版应无需改动,但未实机验证官版全链路
