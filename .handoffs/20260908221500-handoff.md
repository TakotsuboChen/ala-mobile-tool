# HANDOFF — 读全文再开始干活

生成时间: 2026-09-07T10:51:56+08:00 · Git HEAD: `83dcb32`（模块仓；paddock 仓 `75dadf9` 均已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `83dcb32`（2026-09-07）；paddock 仓 `main` @ `75dadf9`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `83dcb32`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 本文件 §2（头像缓存自噬的日志证据链）+ `docs/PADDOCK_PLAN.md` §4（avatar_url 契约）

## 1. 当前目标
围场体验三需求（用户指令）：①进围场主页自动刷新积分 ②围场主页/排行榜下拉刷新 ③头像缓存止血 VPS 流量出口。**已全部完成、部署、装机、实机验证通过**。悬而未决的旧目标仍是游戏进程闪退定案（等用户复现 crash 现场）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **头像三级缓存闭环实证**：`PaddockClient.fetchAvatar` = 内存 LruCache(256张)→磁盘 `cacheDir/paddock_avatars/`(存降采样JPEG, 5MB上限)→网络；失效靠服务端版本化 URL `?v=avatar_version`（上传即变）。实机日志：装机后首轮 38 条 `MISS, downloading` → 重启后 108 条全部 `disk HIT` 零下载
- [V] **缓存自噬 bug 已根治**：首版磁盘存原图字节（实测单张最大 989KB），18 张顶满 5MB 上限 → 下载过程 trim 自删本轮文件 → 重启大面积 miss → 全量重下死循环（设备文件 mtime 全同分钟铁证）。改存降采样 JPEG（~20KB/张，67人<1.5MB）后 trim 永不触发
- [V] **服务端已部署**：镜像 `paddock-api:1.0.1`（版本号未动）本地 build→save|gzip→scp -P 4142→VPS load→compose up；迁移 0008 生效（69 用户 67 个 avatar_version=1 回填）；线上榜单 avatar_url 已带 `?v=1`，curl HTTP 200
- [V] **进页自动刷新**：`PaddockPager(isCurrentPage)` 以「本页落定 && 导航栈归位(backStack.size<=1)」为键触发 `PaddockViewModel.refresh(onDone)`；覆盖横滑进页/冷启动/从二级页返回三条路径
- [V] **下拉刷新**：两页各包 miuix `PullToRefresh`（hoisted isRefreshing + contentPadding=innerPadding + topAppBarScrollBehavior）；排行榜走 `switchSeq++`(两阶段淡出)+`refreshSeq++`(同参数重跑 LaunchedEffect)；文案已中文化（REFRESH_TEXTS 四状态，`LeaderboardScreen.kt` 文件级 internal 常量，同包共用）
- [V] `:app:assembleRelease` BUILD SUCCESSFUL；`:app:lint` 0 errors（54 warnings 全为既有）；`cargo build` 通过（2 warnings 既有 server_best）；装机 Success ×3（`1.0.4 Alpha 1`，版本号未动）
- 工作区: 干净全推送。模块仓 `2c35f83`(功能) → `83dcb32`(CLAUDE.md)；paddock 仓 `75dadf9`(服务端)

### 测试/build 输出（真实退出码）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL
./gradlew :app:lint → Lint found 54 warnings, 4 hints (3 errors+17 warnings filtered by baseline) BUILD SUCCESSFUL
cargo build (paddock) → Finished dev profile
docker build paddock-api:1.0.1 → 导出 5.9MB → VPS load → app 容器 Started+Healthy
adb install -r → Success；dumpsys versionName=1.0.4 Alpha 1（未升版）
实机日志：grep fetchAvatar → 38 MISS(23:56 首轮) / 108 disk HIT(00:01 重启后)
```

## 3. 决策与理由
- **版本化 URL 而非 ETag/304** [V]：失效逻辑全落在「URL 字符串不同」，客户端零协商代码；旧客户端拿 `?v=` URL 也照常工作（axum 忽略 query）→ 服务端可先部署。否决 ETag：要实现 If-None-Match/304 分支 + 处理「304 但本地无缓存」边界
- **磁盘缓存存降采样 JPEG（85 质量）而非原图字节** [V]：显示才 36~56dp，缓存形态必须匹配消费形态；原图缓存 5MB 上限必自噬（实证）。否决调大上限：治标，2MB/张的原图缓存本身就不合理
- **磁盘淘汰用总量上限+最旧先删，否决按 URL 集合精确清理** [V]：URL 集合随 tab/筛选变化（积分榜 205 人 vs 赛道榜 16 人），按集合清理必误删其他条件下仍有效的缓存；旧 ?v= 文件自然淘汰
- **进页刷新键 = isCurrentPage && backStack.size<=1** [V]：仅 isCurrentPage 不够——从排行榜 pop 回来 pager 页不变、键不变不触发；导航栈归位补上这条路径
- **下拉刷新复用两阶段时序（switchSeq++）而非直接换数据** [V]：与切筛选视觉语言一致，避免「下拉后内容瞬变」；refreshSeq 自增解决「相同参数 LaunchedEffect 不重跑」
- 继承：crash 文件独立落盘 / PC 偏移对拍 / 版本号红线 / token 三级回落 / order==2 挂圈 / 积分公式 v40

## 4. 失败的尝试 — 不要再试
- **磁盘缓存存原图字节** [V]——18 文件 5MB 顶满上限，trim 在下载过程中删掉本轮文件，重启后大面积 miss 全量重下（实机 mtime+日志双证）。降采样 JPEG 后闭环。不要再把「原字节直存」当省事方案
- **pruneAvatarCache(aliveKeys) 按 URL 集合清理** [V]——设计阶段即否决（集合随筛选变化必误删），已改为 trimAvatarDiskCache 总量策略；`fetchAvatar(avatarUrl): ByteArray?` 旧签名已删，现在返回 `Bitmap?`，勿按旧签名写调用
- **凭印象直接 `scp root@8.134.50.222`** [V]——22 端口被拒。正确参数在记忆 `paddock-vps-ssh-port-4142`：takotsubo@8.134.50.222 -p 4142 + 密钥 ~/.ssh_paddock/id_rsa（MEMORY.md 索引行已补端口特征）。部署前先翻记忆不凭印象
- **Edit 契约文档时 old_string 覆盖过宽误删 `POST /v1/laps` 段** [V]——编辑表格型文档时 old/new 必须保住相邻段落，提交前 diff 自查
- **PaddockPagerMiuix 用 `remember(uiState.userId, uiState.totalPoints)` 拐弯推 URL** [V]——脏 key 绑定；正确做法是 avatarUrl 进 UiState（已改）
- **grep FATAL/SIGSEGV 零命中=没有崩溃** [X]（继承）——游戏进程 native 崩溃无声死亡+pid 跳变
- 继承（前向有效，见 `.handoffs/20260907005000-handoff.md` §4）：SettingsPagerMiuix 删 UI 块保住兄弟组件起始行 / native_log_init 悬空函数体 / Java UncaughtExceptionHandler 覆盖不了游戏进程 / `<@openid>` 旧格式 [X] / 国旗 compact 逐码点剥空格 [X] / NPatch 靠 Remote Preferences 传 token [X] / ConfigProvider 读 filesDir [X] / 未经同意改版本号 [V] / VPS 禁 cargo build / mdns 端口漂移 / lap_hook 全套 / IL2CPP 扫描三坑 / TC/ABS 指示灯信号链 / FPSIMD 污染

## 5. 已知坑
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——crash_hook 已随 1.0.4 Alpha 1 装机，等用户复现导出日志；crash 文件 pc 偏移对拍 `OffsetTable.kt` 即定案。若「游戏进程崩溃记录」段为空→查 sigaltstack/SA_ONSTACK 与 Unity handler 兼容性
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在分发版里，等用户日志
- ⚠️ **老客户端仍全量下载头像** [V]（本会话实证存活）——流量 relief 只覆盖升级用户；v1.0.4 正式发布后随更新扩散
- ⚠️ **服务端无 HTTP 访问日志** [V]（本会话发现）——容器日志只有 qq_bot 流量，头像请求量要靠客户端日志推断；若要服务端观测需加 axum trace 层
- ⚠️ **avatar_version 语义**：迁移 0008 把存量头像回填 v=1，新上传=epoch millis——两者共存，失效逻辑只要求「变过即不同」，无需统一 [V]
- ⚠️ **lint baseline 13 条失效** [?]（继承）——重生成时机待定
- ⚠️ **LogExporter java/native 段可各自回落不同时期缓存** [?]（继承，未修）
- ⚠️ **该设备 ConfigProvider `Unknown authority`** [?]（继承，未修）——影响 token 第三级回落
- ⚠️ 继承：NPatch 管理器 binder 时序 / paddock 版本三处同步无校验 / 排行榜无实时刷新 / 管理端网页验证未做 / 双仓赛道中文名两份硬编码 / Garage 206 测试对象 / SA_ONSTACK 64KB 未实测

## 6. 下一步（有序）
1. **等用户反馈游戏闪退 crash 现场**（继承主目标）→ 对拍 OffsetTable 定案 → 修元凶（嫌疑：pedal 写线程悬空写）
2. v1.0.4 正式发布待用户定版（版本号红线；本会话三个特性已随 Alpha 1 装机验证）
3. （可选）lint baseline 重生成 / 服务端 axum 访问日志层 / LogExporter 缓存回落修复

## 7. 留给用户的开放问题
- 闪退用户反馈何时能拿到（crash 现场定案的唯一依赖）
- v1.0.4 正式版版本号与发布时机（是否把本轮三特性写进 Release Notes）
- 服务端头像流量下降是否达标（无访问日志，只能间接从 VPS 监控看出口流量曲线）
