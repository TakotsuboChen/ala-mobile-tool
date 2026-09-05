# HANDOFF — 读全文再开始干活

生成时间: 2026-09-05T13:40:08+08:00 · Git HEAD: `83db4a0`（模块仓；paddock 仓 `0251dae`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `83db4a0`（2026-09-05）；paddock 仓 `main` @ `0251dae`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `83db4a0`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（积分公式 v40 已更新）+ paddock 仓 `CHANGELOG.md`

## 1. 当前目标
**围场"用户跑圈没记录"三根因修复 + 服务端积分 off-by-one 修正**：v1.0.4 Alpha 1（104100）已构建、装机、分发用户；部分用户反馈上传恢复，更多用户待观察。paddock 服务端已带 v1.0.1（积分修正）上线，验算吻合。

## 2. 已验证状态 — 工作实际停在哪
- [V] 服务端积分公式 off-by-one 修复：`(N−rank)×100/N` 与定案例句"2 人→100/50/虚位0"矛盾（off-by-one，全体偏低 100/N），改为 `(N+1−rank)×100/N`，三处 SQL（总榜/版本榜/`/v1/me`）统一；部署后 curl 验算：Takotsubo 总榜 650/摩纳哥 50、爱弥斯 200、health=1.0.1，全部与用户期望吻合
- [V] 连带修出 `/v1/me` 积分口径 bug：窗口函数 CTE 内 `WHERE user_id` 致 `n_in_track` 恒 1、每赛道白给 100 分（用户见 700 vs 总榜 600）；过滤移到外层
- [V] 围场上传链三根因修复（v1.0.4 Alpha 1, 104100）：① NPatch 下 Remote Preferences 是空壳——`references/NPatch/patch-loader/.../LSPLoader.java:484` `requestRemotePreferences` 返回 `Bundle.EMPTY`，游戏进程读到的是本地 fallback store 物理文件 ≠ 模块App经管理器写的 daemon db → 加 ConfigProvider `read_token` 第三级回落；② `saveAuth` 写 `getExternalFilesDir` 但 provider 初版读 `filesDir`——目录不一致已修正；③ 入队零日志 + 队列补传只挂"上传成功"一条路径 → 补日志 + 60s 限流重试恢复 + 恢复即 drain + 进围场页自动补传
- [V] 用户反馈：几个人成绩上传变正常（A/B 之外的实证），更多用户待观察
- [V] `./gradlew :app:lint` → EXIT=0（"0 errors, 53 warnings, 4 hints"；baseline 13 条失效提示为继承非阻塞）
- [V] 用户 C（lzfbb，1.0.3 版）案例闭环：游戏进程 token 死后无自愈，11:35/11:38 墨尔本 1:13.306 未入库（best_laps gp=0 停在 1:14.205@11:27:34）；模块App侧 token 正常（/v1/me 200）——断点只在游戏进程，v1.0.4 即解药
- [V] 日志导出器发现新 bug（未修）：java 文件不新鲜时整体回落旧缓存（`LogExporter.kt` `javaUpdated && nativeUpdated` 才算 fresh），导致 2777 进程的 java 日志全缺——诊断时 java/native 段时间线会错位
- 工作区: 两仓均 clean，全部已推送（模块仓 `c5a34d1` 修复 + `83db4a0` CLAUDE.md；paddock 仓 `0251dae`）

### 测试/build 输出（真实退出码）
```
模块仓: ./gradlew :app:lint → EXIT=0（0 errors, 53 warnings, 4 hints）
服务端验算: 部署后 curl points?version=200150 → 爱弥斯200/我150; 总榜 → 我650; health → {"status":"ok","version":"1.0.1"}
分发 APK: D:\Downloads\QQ\Ala Mobile Tool v1.0.4 Alpha 1.apk（md5 c70b295…的后续修正版, 12:xx 重建）
```

## 3. 决策与理由
- **版本号红线入全局 CLAUDE.md**（用户严令）[V]：同日两次越权（模块擅自 1.0.3→1.0.4 Alpha 1 且定错 103310；服务端擅自 1.0.0→1.0.1）——"上线修复/装机"指令不含授权升版，必须先问"版本号定多少"。已写入 `~/.claude/CLAUDE.md` 全局段 + 项目记忆 version-number-requires-approval + 全局 MEMORY 索引
- **服务端 1.0.1 现状追认**（用户"现在就算了"）[V]：Cargo.toml/compose/CHANGELOG 三处已同步 0251dae，不回滚；tag `v1.0.1` 未打
- **NPatch token 通道选 ConfigProvider 而非修 daemon 读取** [V]：config 已验证可用同款通道（游戏进程 call 时系统自动拉起模块进程）；修 NPatch loader 超出项目边界。否决：继续加重试（daemon 通道物理断裂，重试无用）
- **积分公式语义以例句为准** [V]：`(N+1−rank)×100/N`（第一100/递减100/N/虚位0/N=1自然100）；v39 数学式与例句矛盾时 SQL 抄了错的那半——契约文档已标注 v40 定案与矛盾来源
- 继承：积分公式 v40 / 版本独立赛季 / 字段核对清单=OFF_* 全集 / RVA 单一事实源=OffsetTable / 规则防覆盖 / token 三级回落 / order==2 挂圈 / 拦截器零浮点

## 4. 失败的尝试 — 不要再试
- **NPatch 下靠 Remote Preferences 传 token** [X]——loader 返回空壳 Bundle，游戏进程本地 fallback store 与模块App经管理器写的 daemon db 是两个物理文件，41 次重试全 null。不要再往这条路上加重试。
- **ConfigProvider 读 `filesDir`** [X]——saveAuth 写 `getExternalFilesDir`，两目录不同，provider 永远返回 null（"token null" 日志实证）。已修正，勿再改回。
- **诊断时把"导出日志缺段"当"代码没执行"** [V]——LogExporter java 段回落旧缓存 + native 2MB 轮转，双重盲区。看日志先核对 java/native 段时间窗是否覆盖目标进程时段，再下结论。
- **SQL 窗口函数 CTE 内先 WHERE 再 rank** [X]——`n_in_track` 恒 1，排名失去意义（`/v1/me` 700 分假象）。窗口必须基于全量数据计算后再过滤。
- **未经同意改版本号**（两仓各一次）[V]——用户决策域，"上线/装机"≠"授权升版"。全局红线已入 `~/.claude/CLAUDE.md`。
- 继承（前向有效，见 `.handoffs/20260905124700-handoff.md` §4）：Release Notes 凭 commit message 直写不核实 / scp 用错用户 root@ / VPS compose 镜像行以实际 grep 为准 / IL2CPP dump 用 Windows dotnet / mdns 端口漂移 / serde Option 不兜空串 / askama 模板禁调函数 / adb push 必须完整路径 / lap_hook 全套 / IL2CPP 扫描三坑。

## 5. 已知坑
- ⚠️ **LogExporter java/native 段可各自回落不同时期缓存** [?]——修复方案未定（应改为"每个文件独立判断新旧"），下次诊断用户日志前先看段时间窗
- ⚠️ **NPatch 管理器 binder 时序** [?]——登录瞬间 bindNpatchRemoteService 可能失败（管理器未就绪），saveAuth 降级本地；靠 ConfigProvider 回落兜底但前提是模块App的 auth 文件已写
- ⚠️ **lint baseline 13 条失效** [?]（继承）——下次重新生成
- ⚠️ **paddock 版本三处同步无校验** [?]（继承）——deploy.sh 固化仍未做
- ⚠️ **镜像仓 README 同步以内容指纹验证** [V]（继承）
- ⚠️ 继承：排行榜无实时刷新 / 管理端网页验证未做 / 群内 bot 复测未做 / 双仓赛道中文名两份硬编码 / Garage 206 测试对象

## 6. 下一步（有序）
1. **继续收集用户反馈**：v1.0.4 Alpha 1 分发范围扩大后，若仍有"没记录"，要日志并先核对 java/native 段时间窗（§4 第三条）；重点看 `auth restored via ConfigProvider` 是否出现
2. （可选）修 LogExporter 缓存回落 bug（按文件独立判断新旧）
3. （可选继承）`deploy/deploy.sh` 固化 paddock 部署链 + 版本三处一致性校验 / lint baseline 重生成
4. v1.0.4 正式发布（版本号/tag/Release Notes）**待用户定版**——遵守版本号红线

## 7. 留给用户的开放问题
- v1.0.4 正式版版本号与发布时机（Alpha 结束条件：多少用户反馈正常算稳定？）
- LogExporter 修复优先级（影响诊断效率，不影响功能）
- 爱弥斯反馈的具体现象仍未澄清（她的数据实际全部正常入库）
