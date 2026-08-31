# HANDOFF — 读全文再开始干活

生成时间: 2026-08-31T17:10:00+08:00 · Git HEAD: `9a8be24`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `9a8be24` (2026-08-31)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9a8be24`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（围场私服第 1 期唯一契约源，两仓共用）+ `docs/LAP_HOOK_NOTES.md`（§4a 矩阵）

## 1. 当前目标
**围场私服第 1 期（圈速排行榜）**：S1 后端 / S2 模块上传 / S3 围场 UI 三个切片代码全部完成并推送；用户定案"全过程做好再测，然后有问题再针对性修"——即先完成剩余 S4（CAMDA bot）与部署，再做一次端到端实测，按实测结果针对性修。完成定义：实机计时赛刷圈 → 成绩上榜 → 赛道中文名 Toast。

## 2. 已验证状态 — 工作实际停在哪
- [V] **S1 paddock 后端全部完成**（`8fcd44a`，paddock 仓）：axum 单二进制（/v1 API + /admin 管理端 SSR）+ 8 表 migration + Docker 四容器；curl 全链路实测：注册（含 409/400 校验）→ psql 代绑 → verify → 传圈六用例（Toast 四条件全对）→ 积分榜（#1=100/#2=1，版本隔离）→ 删圈重算回放正确 + 管理端登录/代绑/删圈实测。
- [V] **S2 模块上传链路**（模块仓 `f848a3d`）：lap_hook order==2 边界写单槽 → JNI `pollLapUpload/markLapUploadConsumed` → `PaddockUploader` 1Hz 轮询 → `PaddockClient` HTTPS 直传；待传队列 30 天 TTL；native **零新 hook**。
- [V] **S3 围场 UI**（`acc9970` + `e47e15f`）：第 4 页 pager + 方格旗自绘 icon；登录/注册两步流（围场页）；排行榜二级页（积分/赛道 tab × 16 赛道中文名 × 版本筛选）；Route.Paddock 导航。
- [V] **构建门禁**：`./gradlew :app:assembleDebug :app:lint :app:assembleRelease` → 3× BUILD SUCCESSFUL，EXIT=0（本 handoff 前完整重跑）。lint 0 errors（48 warnings 为历史 baseline 内）。
- [V] 本仓 `main` @ `9a8be24` 与 origin 同步、工作区干净；paddock 仓 `main` @ `8fcd44a` 同步、干净。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:lint :app:assembleRelease → 3× BUILD SUCCESSFUL，EXIT=0
git push → 9a8be24（CLAUDE.md）main，成功；paddock 仓最后推送 8fcd44a
```

## 3. 决策与理由
- **围场 = 私服第一步，只做计时赛有效圈**（用户 2026-08-31 定案）——S1(后端)→S2(上传)→S3(UI)→S4(bot) 切片顺序执行中，S1-S3 代码完成。
- **防伪全放行**（用户推翻早期"合理性检查"选择）：登录态是唯一门槛，成绩管理靠服务端删圈重算（已实测）。客户端 4xx 参数错丢弃、401/网络错误入队，与此精确对齐。
- **native→Java 用 1Hz 轮询单槽而非 JNI 回调**——沿用 intro/TcAbs 指示灯已验证惯例；圈完成分钟级一遇，轮询成本可忽略；native 回调 Java 需 AttachCurrentThread 复杂度不成比例。否决：JNI 向上回调。
- **单槽消费语义**：uploadLap 无论成败都 markConsumed——成功数据在服务端、失败在本地队列文件，单槽不留。seq 单调去重防双读。
- **records.alltime 行 version_code=0 占位**（复合主键列隐式 NOT NULL）；删圈重算持有者=最快圈主人（非 min(user_id)，DISTINCT ON 语义用 ORDER BY lap_ms LIMIT 1 实现）。
- **miuix 组件红线**：TextField 用 TextFieldValue 重载（新版主签名是 TextFieldState，勿混）；无 SuperArrow（是 ArrowPreference）；Text fontSize 是 sp 非 dp；askama 表达式字面量用双引号。
- 继承：圈完成挂 order==2、拦截器零浮点、日志只在边界分支、场景名拼写照抄（`Shangai` 单 a）、实机默认 release 一条链（本次 adb 无设备未装）。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录 + docs/LAP_HOOK_NOTES.md §6。

### 本轮新增（后端实测揪出）
- [X] **records 复合主键列放可 NULL 维度** → Postgres 主键列隐式 NOT NULL，alltime 行插不进。改 alltime 行 version_code=0 占位，kind 列做真正区分。
- [X] **Toast 两纪录维度共用 if/else 出口** → 首圈只建 alltime 行，第二圈更差圈被误判"版本首建"误报 version_server。两维度必须独立判定独立 upsert。
- [X] **同名并发注册不拦** → 同名双 pending 同时在途。pending_regs 锁存 username + 在途 409 + verify 校验一致（顺带堵换名骗码）。
- [X] **sqlx `migrate!("migrations")` 相对路径 / `query_as!` 宏离线编译** → 退运行时形态：`sqlx::migrate!()` + `query_as::<_,T>` + FromRow。FromRow 查询 SELECT 必须含全部字段（缺列报 "no column found"）。
- [X] **askama 模板单引号字符串 / 嵌套 content 不加 |safe** → 表达式字面量要双引号；嵌套变量输出默认 HTML 转义。
- [X] **axum `nest("/admin")` + route("/") 对 `/admin/`（带斜杠）404** → `/admin` 无斜杠正常；未深究，绕过。
- [X] **askama 0.14 / miuix 无 SuperArrow**（KernelSU 记忆里的组件名）→ miuix 0.14.x 叫 `ArrowPreference`；查 references/miuix 本地源码为准。
- [X] **miuix TextField 新版主签名是 TextFieldState** → 但保留 TextFieldValue 重载；UI 层持 TextFieldValue state、onValueChange 同步 ViewModel String。
- [X] **Write 工具输出污染**（本轮两次：PaddockClient drainQueue 塞入大量垃圾 token）→ 定位后 python 按行精准修复。大文件生成后先 grep 抽查污染再继续。
- [X] **axum 0.8 积分 SQL 里 `?` 接线后 `min(user_id)` 重算语义** → 删圈重算持有者不能 min(user_id)（拿到的可能是慢圈车主），必须 `ORDER BY lap_ms ASC, created_at ASC LIMIT 1` 取最快圈主人。

### 继承死路（lap_hook 三版演化 + 指示灯信号链 + FPSIMD，全部实机实证，详见 .handoffs/20260831170000-handoff.md §4）
- [X] 圈完成等 order 2→0 回绕 / sectorOrder 1-based / HandleSectorsTimes 当过线单事件 / gate v1 "champ==NULL→放行" / trackToRace 当赛道名 / bestLapTimeInfo 当历史最佳 / raceModes 当判据 / 切片提交混 git rename / 拦截器浮点操作 / ABS v1-v5 / 指示灯 v1-v5 / proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API——全部前向有效，勿重试。

## 5. 已知坑
- ⚠️ **拦截器回调零浮点 / order==2 判定 / 边界日志三项红线** [V]（继承，长期有效）。
- ⚠️ **服务端 track_display_name 与模块端 LeaderboardScreen 赛道中文名是两份硬编码拷贝** [V]——契约源 PADDOCK_PLAN §5，加赛道（8.0.5+）必须两侧同改；`Shangai` 单 a。
- ⚠️ **PaddockClient DEFAULT_SERVER 是占位域名** `https://paddock.example.com` [V]——VPS 部署后必须替换 + 服务器地址覆盖接 ModConfig（S3 遗留 TODO）。
- ⚠️ **QQ webhook Ed25519 签名细节 / member_openid 退群语义** [?]（S4 实现时核对官方文档）。
- ⚠️ 继承 [?]：多指污染修复等红米日志回传；Bug B 未复现；16 赛道表 12/16 未实机；门禁 champ==NULL 多语义；矩阵空格（Q2/Q3/多人）。
- ⚠️ 继承死路：曲线编辑器 QP/pager 回抓等——见 `.handoffs/20260831170000-handoff.md` §4/§5（本会话未恶化未动）。

## 6. 下一步（有序）
1. **VPS 部署 paddock**（compose 栈就绪：app/pg/garage/caddy）→ 换 PaddockClient 真实域名 → 服务器地址覆盖接 ModConfig + 设置页 UI。
2. **S4 CAMDA bot**：webhook 处理器（Ed25519 验签）+ 校验码监听→绑 member_openid→被动回复 + 重置密码码。依赖 VPS 公网 HTTPS + 开放平台回调三处配置（见 PADDOCK_PLAN §4a 调研结论）。
3. **实机端到端验证**（用户定案：全过程做好再测）：装机 release → 围场页注册（走管理端代绑或 bot）→ 计时赛刷圈 → 成绩上榜 + Toast → 有问题针对性修。
4. （可选清理）删 `hidePedalsApply()` 死代码 + abs_diag_log rfHits/hitAge 行。
5. （远期）围场头像上传+裁剪；矩阵空格补采（Q2/Q3/多人）。

## 7. 留给用户的开放问题
- VPS 与域名何时到位（S4 bot 与真实域名替换的前置）。
- 头像裁剪上传优先级（可推迟到榜单功能验证后）。
- 继承：多指日志回传裁决、工具按钮重置入口、指示灯亮度调节等（未恶化不动）。