# HANDOFF — 读全文再开始干活

生成时间: 2026-09-13T12:39:47+08:00 · Git HEAD: `e135e1a`（模块仓；本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `e135e1a`（2026-09-13 12:39，已 push）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `e135e1a`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）

## 1. 当前目标
**已完成**：修复强制登录弹窗「反应慢 / 有时不弹出」。已装机 + 用户实机验收（三次「快如闪电」）。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **两个独立根因均已修复**（commit `211e4fc`，已 push）：
  1. **"有时不弹出"** = 全新安装时更新检查**永不触发**：检查内联在 `OverviewPagerMiuix.kt` 的 `LaunchedEffect(Unit)` 内 `if(eulaAccepted)` 分支——全新安装 EULA 首帧未同意，该 effect 跑完即死，同意后**无任何 effect 重跑检查** → `updateCheckDone` 恒 false → `LoginGateCoordinator.canGate` 永不满足。修复：抽 `runStartupUpdateCheck` 独立函数 + `LaunchedEffect(eulaAccepted)` 触发（对齐 `:461` 激活弹窗范式），检查期间 `updateBlocking=true` 挡门控、`onDone` 解锁。
  2. **"反应慢"** = 竞速结构性失效：`UpdateChecker.checkLatest` 用 `coroutineScope{}` 包住多源并发，而 `coroutineScope` **会 join 全部子协程才返回**，`return@withTimeoutOrNull` 只是跳出内层——**总耗时恒等于最慢源**。修复：竞速协程 launch 到**独立 `CoroutineScope`**，拿到首个成功响应即 `raceScope.cancel()` 返回。
- [V] **实测数据**（设备 OPD2413，逐源耗时日志）：修复前 5 轮总耗时 1770/4015/1705/1959/1145ms —— **每一轮都等于该轮最慢源耗时**；修复后 2 轮 609ms / 782ms —— **等于最快源耗时**（第 2 轮有源慢至 5425ms，函数 782ms 就返回了）。
- [V] **源清单调整**：5 源改 4 源（去 `ghproxy.link`，实测恒 FAIL）；保留 `api.github.com` + `gh.catmak.name` + `ghproxy.monkeyray.net` + `gh-proxy.com`。设备实测 403 的源：`ghproxy.net` / `gh.zwy.one` / `gh.nxnow.top`（releases API 被镜像站拒绝，**不是网络不通**，别误判为可用）；死源：`ghfast.top` / `dgithub.xyz` / `gh.llkk.cc` / `moeyy.xyz` / `gitmirror` / `99988866`。
- [V] 超时压至 OkHttp connect 8s / read 12s（败者 `execute()` 不可中断，靠自身超时消亡但不阻塞返回）。
- [V] 诊断日志：`result::class.simpleName` 在 release 被 R8 混淆成 `qm2`（mapping 解码证实 = `UpdateCheckResult$HasUpdate`），改用稳定字面量 `resultLabel()`。新增逐源耗时 + 总耗时日志。
- [V] **构建/lint**（全新 shell）：`./gradlew :app:lint :app:assembleRelease -x lintVital*` → BUILD SUCCESSFUL（EXIT=0）。版本号未动（`1.0.4 Alpha 1` / `104100`）。
- [V] 工作区 clean，全部已 push（`211e4fc` work + `e135e1a` CLAUDE.md）。
- [V] 装机：`adb install -r app-release.apk` → Success（设备 `e98a35cb`，非上次会话的 `381QYFCN22B9A`）。
- [?] **`MainScreen.kt` 弹窗挂载埋点** `"login gate dialog MOUNTED"` 仍在（诊断用，用户尚未决定留/删）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL (EXIT=0)
aapt2 dump badging → versionCode='104100' versionName='1.0.4 Alpha 1'（未动版本号）
实机日志（修复后）：UpdateCheck: startup check channel=0 → HasUpdate in 609ms / 782ms
```

## 3. 决策与理由
- **竞速必须用独立 scope 而非 `coroutineScope{}`** [V]——`coroutineScope` 的 join 语义会让"提前返回"名存实亡；这是 Kotlin 结构性陷阱，不是笔误。OkHttp `execute()` 不可中断的代价用压短超时缓解。
- **多源并发 vs 单源** [V]——竞速赢家 = 最快源，冗余换取抗单源故障。保留 4 源是实测平衡点（更多源会加剧带宽竞争，实测 5 源时最慢值被拉高）。
- **检查触发点从 `LaunchedEffect(Unit)` 移到 `LaunchedEffect(eulaAccepted)`** [V]——Unit effect 只在首次组合跑一次，任何"等 EULA 同意后"的逻辑放里面都是死代码，这是 React-like effect 键选择的经典坑。
- **检查期间挡门控（`updateBlocking`）而非直接放行** [V]——保持既定「更新优先于登录」优先级；出结果即解锁，两弹窗不打架。
- 继承：`action_metas()` 单一事实源 / 信箱通道优于修进程通道 / 登出写空 token / AFA 硬性前置 / 4xx 先存后判 / 零 Hook 判定红线 / 版本号红线——详见 `.handoffs/20260913123947-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
- **`coroutineScope{}` 内实现多源竞速** → 总耗时恒等于最慢源 [V]——`coroutineScope` join 全部子协程，提前 return 无效。必须独立 `CoroutineScope` + `cancel()`。
- **只加源数不改 scope 结构** → 变慢（5 源 4383ms > 2 源 2073ms）[V]——并发 TLS 握手瓜分带宽，"最快源"被拖慢。源数是次要变量，结构才是主因。
- **用 `result::class.simpleName` 记诊断日志** → release 输出 `qm2` [V]——R8 混淆 sealed 子类名，必须用稳定字面量。
- **声称"已装机"而未实际执行 `adb install`** [V]——本会话发生过一次（用户测的是旧包，白测一轮）。**装机前必须看到 `Success` 且确认设备上版本**。
- 继承 [X]：`***text***` 粗斜体 / `MarkdownText` 表格 / alpine 跑本地 glibc ELF / psql `-v` 传大 JSON / 旧快照覆盖 configs 表 / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节——详见 `.handoffs/20260913123947-handoff.md` §4。

## 5. 已知坑
- ⚠️ **OkHttp 败者协程不可中断** [?]——竞速败者靠自身超时（8s/12s）消亡，高频调用会累积后台线程占用。当前只在启动门控调一次，无实际问题；若未来提高调用频率需重新评估。
- ⚠️ **权限门视觉未实机验收** [?](继承)——需临时关 AFA 再开模块 App 看观感。
- ⚠️ **配置热更新仍走广播** [?](继承)——ColorOS 关关联启动 + 游戏不在前台时广播可能丢（信箱覆盖冷启动）。用户已说"暂时保持现状"。
- ⚠️ **信箱残留探针文件** [?](继承)——`/sdcard/Android/media/<游戏包>/` 下 `ala_probe_*` 残留，可手动删，无功能影响。
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"。
- ⚠️ **bot 投递未端到端验证** [?](继承)——本地模拟仅验证到"回复正文正确"，真实群投递需在群里发指令确认。
- ⚠️ **围场指南 1.5× 行高下弹窗可视区变少** [?](继承)——限高 `maxHeightFraction=0.55`，用户如觉得仍挤/读不完可调。
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备上 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序问题先拉触摸时间线。
- ⚠️ 继承: 导出探活广播链 ROM 限流 / lint baseline 15 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码 / ABSdiag 降频 / 闪退未定案——详见 `.handoffs/20260913123947-handoff.md` §5。

## 6. 下一步（有序）
1. （待用户决定）`MainScreen.kt` 的「login gate dialog MOUNTED」埋点保留还是删——诊断已完成，若删则单独补一个小 commit。
2. （可选）验收权限门控页观感：临时关 AFA → 开模块 App → 再开回来。
3. （可选）真实群验证 bot「查询用户名」投递 / 清理信箱探针残留 / ABSdiag·TCdiag 降频 / 闪退 crash 现场定案。

## 7. 留给用户的开放问题
- 登录门控埋点去留（见下一步 1）
- 更新检查源清单是否需要按不同地区/运营商再调（当前 4 源为设备实测）
- 围场指南弹窗限高与行高是否需要再调（0.55 屏 / 1.5× 行高）
- 信箱通道是否给游戏侧加"配置热更新"路径（现走广播）？用户已说"暂时保持现状"
- 永久 4xx 处理：先提示后静默丢弃（现状）vs 无限重试
