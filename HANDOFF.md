# HANDOFF — 读全文再开始干活

生成时间: 2026-09-12T22:22:53+08:00 · Git HEAD: `8701dbf`（工作 + 持久文档提交后，尚未含本次 handoff 提交）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `8701dbf`（2026-09-12 22:20；工作 071215f + 文档 8701dbf 均已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `8701dbf`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/CROSS_PROCESS_CHANNELS.md`（跨进程通道全景）+ CLAUDE.md 的 PaddockClient 条目（跨包信箱 + allServices 契约 + 权限门外壳契约）

## 1. 当前目标
上一目标（权限门控页 UI 与模块主界面统一）已于 2026-09-12 完成编码 + 构建 + 装机。**现无进行中目标**；下一步是 v1.0.4 定版发布（等用户）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **权限门控页 UI 统一**（071215f）：`ConfigActivity` 未授权分支此前只包一层 `MiuixTheme` 便 `return@setContent` → 三处断裂（`enableEdgeToEdge` 排在 return 后未生效 / pageScale·blur·底栏 CompositionLocal 未 provide / 页面是手写裸 Column）。改为：`enableEdgeToEdge` 上移到 return 前 + provide 与主界面同套 CompositionLocal；`PermissionGateScreen` 重写为共享外壳（`Scaffold + BlurredBar 毛玻璃 TopAppBar + LazyColumn(overScrollVertical+scrollEndHaptic) + layerBackdrop`，12dp 页边距/12dp 卡间距同概览页），卡片图标 tint 改 `onBackground`、字号对齐，按钮改两个全宽（上=蓝「去授予权限」主操作、下=灰「退出模块」）
- [V] **文案**：描述改为「若不授权则模块将无法运行」（用户定案）；全仓 grep 无其它引用点
- [V] **CLAUDE.md 契约**（8701dbf）：PaddockClient 条目 + PermissionGateScreen 文件条目写入「门控页必须与主界面同套外壳 + enableEdgeToEdge 必须在 return 之前」的强制约定
- [V] 构建/lint/装机（全新 shell）：`compileDebugKotlin` BUILD SUCCESSFUL；`:app:lint` BUILD SUCCESSFUL（62 warnings / 15 条 baseline 失效，**本次改动 0 新增**）；`assembleRelease -x lintVital...` BUILD SUCCESSFUL；`adb install -r` Success（MEIZU 20，USB）
- [V] 版本号未动（仍 `1.0.4 Alpha 1`）；工作区干净，全部已 push（仅 handoff 提交后含 HANDOFF.md）

### 测试/build 输出（真实退出码）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0（0 new）
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL, EXIT=0
adb install -r app-release.apk → Success
```

## 3. 决策与理由
- **抽共享外壳而非逐项调样式** [V]——根因是 `return@setContent` 的位置挡住了初始化路径（edge-to-edge 未启用），不是配色偏差；照抄概览页结构一次性对齐最省且不易漏。否决：只改颜色/间距（治标，edge-to-edge 与 pageScale 仍缺）
- **`enableEdgeToEdge` 上移到 return 前** [V]——`DisposableEffect` 是副作用、必须在组合路径上；放在 return 后等于门控页从未执行。否决：在门控页内另起一个 `DisposableEffect`（重复窗口配置，两处易漂移）
- **门控页 provide 全套 CompositionLocal 而非只 `MiuixTheme`** [V]——`MiuixTheme` 只提供颜色/排版；`LocalDensity`/`LocalEnableBlur` 等自定义 Local 必须显式 provide 才向下传播，"半继承"会丢用户设置
- **按钮竖排两个全宽** [V]——用户选定方案（选项 A）；主操作（蓝）在上符合 miuix 页面底部的层级感
- 继承: 信箱通道优于修复进程通道 / 登出写空 token 而非删文件 / 信箱"存在即权威"短路 / AFA 硬性前置 / fetchMe 晚于 verdictDone / 4xx 先存后判(isRetryableStatus 单源) / 零 Hook 判定红线 / 版本号红线——详见 `.handoffs/20260912222253-handoff.md` §3

## 4. 失败的尝试 — 不要再试
- **MTDataFilesProvider 自授 URI 传配置** → 游戏 stopped state 下 `FileNotFoundException: No content provider`(AOSP 规则:停止状态禁止自动拉起组件) [V]——仅"游戏在后台未划掉"时可用。不要再当"游戏未运行时可写"的通道
- **`am kill` 杀前台进程** → `am kill` 只杀后台进程，前台时空操作；`kill -9` 被 SELinux 拒(shell uid=2000) [V]——想测"进程死但非 stopped"要先退后台再 `am kill`
- **给模块加 AFA 后 `appops set` 授权** → shell uid 无 `MANAGE_APP_OPS_MODES`，必须用户在系统设置手动开 [V]
- **provider + SAF 手动树授权** → 授权可持久化，但 provider 宿主进程不在时 `No content provider`；SAF 需一次用户手势是硬约束 [V]
- **无线 adb 开飞行模式** → 连接必断（无线调试跑在网络栈上）；断网场景验证必须走 USB [V]。另:每次重连端口都变，`adb connect` 前必须 `adb mdns services` 重发现
- **本轮：USB 后台抓 logcat 的命令** → 退出码 255 失败 [V]——后台任务在前台命令流里被中断；此类抓取用前台或有明确生命周期的命令
- 继承(前向有效) [X]: `LocalBringIntoViewSpec` 覆盖治 Pager 焦点跳页 / `evaluateLoginGate` 用 `loginVerdictDone` 初始值 true early return / Remote Preferences `remove()` 清 token / 旧 hook-anyway 路径 / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号 / ConfigProvider 当全模式唯一权威登录通道——详见 `.handoffs/20260912222253-handoff.md` §4

## 5. 已知坑
- ⚠️ **本轮的权限门视觉未实机验收** [?]——设备当前应为**已授权**态，门控页不显示；需临时关掉 AFA（设置→应用→Ala Mobile Tool→所有文件访问→不允许）再开模块 App 看观感（期待：毛玻璃 TopAppBar「必要权限」+ 与概览页同款的卡片 + 两个全宽按钮）
- ⚠️ **配置热更新仍走广播** [?]——冷启动读信箱已解决；运行中靠 `ConfigReceiver` 广播改 native `g_config`。ColorOS 关关联启动 + 游戏不在前台时广播可能丢（此场景由信箱覆盖）。用户明确说"暂时保持现状"
- ⚠️ **信箱残留探针文件** [?]——`/sdcard/Android/media/<游戏包>/` 下有三个测试残留（`ala_probe_from_game.txt`/`ala_probe_from_module.json`），非代码产物，可手动删，无功能影响
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"
- ⚠️ **上轮弹窗改动未实机视觉验收** [?](继承)——注册弹窗新文案+跳群、忘记密码新正文、未登录页下拉无指示器
- ⚠️ **对话纪律** [V](继承)——1mid-turn 消息逐条消化 2旧快照不当现在时断言 3装机前先验设备上 APK 版本 4调查日志前先对齐"几次"计数 5修 UI 时序问题先拉触摸时间线
- ⚠️ 继承: 导出探活广播链 ROM 限流边界 / lint baseline 15 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码 / ABSdiag 降频 / 闪退未定案——详见 `.handoffs/20260912222253-handoff.md` §5

## 6. 下一步（有序）
1. **（设备侧）验收权限门观感**：临时关闭 AFA → 打开模块 App 确认与主界面观感一致 → 再开回来
2. **v1.0.4 正式发布待用户定版**（版本号红线）：Release Notes 应含——权限门控页 UI 统一 + 跨包信箱通道 + AFA 权限门 + 配置仲裁 null 源短路修复 + 反向红线修复（登出空 token）+ `drainQueue` 计数 + 配置写入遍历 allServices + 前几轮门控/两级回落/丢圈保护
3. (可选)清理设备上信箱探针残留文件
4. (可选,继承)ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- 信箱通道是否需要在游戏侧也加"配置热更新"路径（现走广播，ColorOS 下可能丢）？用户已说"暂时保持现状"
- 永久 4xx 的处理: 先提示后静默丢弃(现状)vs 无限重试(不丢但可能堵队头)
- v1.0.4 正式版版本号与发布时机
- 官版游戏（无 AFA 声明）是否也要加权限门？当前只有模块 App 需要 AFA，游戏侧读自己目录零权限——预期无需改动，但未实机验证官版全链路
