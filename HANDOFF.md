# HANDOFF — 读全文再开始干活

生成时间: 2026-09-04T18:06:53+08:00 · Git HEAD: `bcd545e`（模块仓；paddock 仓 `a4920dd`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `bcd545e`（2026-09-04）；paddock 仓 `main` @ `a4920dd`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `bcd545e`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源，本会话未变更）+ paddock 仓 `HANDOFF.md`（服务端现状，工作重心在彼仓）

## 1. 当前目标
**围场模块端三轮用户反馈落地**（服务端配套全在 paddock 仓 v33–v35）：① 成绩 Toast 去赛道名 ② 密码框实心圆点+显隐图标 ③ 重置密码弹窗新口令文案（含二轮删"一字不差"）④ 注册重复点=服务端幂等恢复重弹（模块零改动）。模块端切片已推送、APK 已装机。

## 2. 已验证状态 — 工作实际停在哪
- [V] 工作切片 `bcd545e` 已推送：`PaddockClient.kt`（parseToast 四模板去 `$track`）+ `PaddockPagerMiuix.kt`（两处密码框 `PasswordVisualTransformation`+`Visibility/VisibilityOff` 尾图标、重置弹窗文案两轮更新）；lint EXIT=0
- [V] release APK 已装 MEIZU_20（`adb install -r` Success，mdns 端口 39403）
- [V] 服务端配套已上线 v35：register-request 幂等恢复（同名+密码一致→原 reg_code 200，模块端弹窗自然重弹）/车手号最小空缺分配（用户已移 1 号）/重置口令「我需要重置密码」严格匹配——详见 paddock 仓 HANDOFF
- [?] **装机后 UI 实测未做**：密码圆点/眼睛图标切换、Toast 新文案、注册重弹流程
- [?] 契约源 `docs/PADDOCK_PLAN.md` §6 注册流程描述仍是 v2 语义（申请时发号、"重置密码 用户名"）——服务端已 v3/v4，契约文档待同步

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL EXIT=0
./gradlew :app:assembleRelease → BUILD SUCCESSFUL；adb install Success
本会话模块仓无 native 改动（纯 Kotlin/Compose）
```

## 3. 决策与理由
- **Toast 去赛道名**（用户定案）[V]：`parseToast` 四模板去掉 `$track 的`，顺带消灭"蒙扎国家赛车场 的"空格瑕疵；服务端 `toast.track` 字段仍下发（未动协议），模块端选择性不显示。
- **密码显隐用 material icons `Visibility/VisibilityOff`** [V]：miuix-icons extended 无 eye 图标（有 Show/Hide 但模块未依赖 miuix-icons 库本身，material icons 已在用）；tint 走本文件惯用 `colorScheme.onBackground.copy(alpha=0.6f)`（miuix 色板无 `onSurfaceVariant`，那是 Material3 命名——编译前已改）。
- **注册重弹做在服务端**：幂等恢复返回原码走 200 正常路径，模块端零改动；409 错误文案经 `errText` 透传已可读。
- 继承：排行榜行级 items / 两阶段切换 / token daemon / /v1/me 显式 token 红线 / 两进程共用代码只用 Logger / order==2 挂圈 / 拦截器零浮点。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。本轮新增死路全在 paddock 仓（v33–v35，见 paddock HANDOFF §4），模块仓侧新增一条已入记忆。

- 无线 adb 旧端口直连 → 拒绝连接 [X]——端口每次漂移，必须 `adb mdns services` 重发现（记忆 `wireless-adb-mdns-rediscover`）；mdns 双条目（`_adb`+`_adb-tls-connect`）取活的那条，连不上换下一个。
- miuix 色板写 `colorScheme.onSurfaceVariant` → 无此字段（Material3 命名）[X]——miuix 用 `onBackground`/`onSurfaceContainer*` 系。
- 继承（详见 .handoffs/20260904180000-handoff.md §4）：排行榜 4 轮迭代死路 / fetchMe 漏 token / Crossfade key 绑筛选 / navigationIcon 只画不绑 / logX 进模块进程 NoClassDefFoundError / externalFilesDir 跨进程不可见 / CropImageActivity 主主题 / lap_hook 全套 / IL2CPP 扫描三坑 / adb 端口漂移。

## 5. 已知坑
- ⚠️ **PADDOCK_PLAN.md §6 契约描述滞后** [?]——注册流 v3/v4（幂等恢复、建号发号、最小空缺号）与重置新口令未回写契约文档；下次动 paddock API 时一并同步。
- ⚠️ **模块侧 Toast 文案与解析** [?]——Toast 模板已改，服务端 `toast.level` 四值映射不变；若服务端新增 level，模块 `else -> null` 静默不提示。
- ⚠️ **lint baseline 13 条失效** [?]（继承）——下次重新生成。
- ⚠️ **排行榜无实时刷新** [?]（继承，用户拍板暂不做）。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；多指日志回传、16 赛道 12/16 未实机、门禁 champ==NULL 多语义。

## 6. 下一步（有序）
1. **装机实测**：密码圆点+眼睛切换、成绩 Toast 新文案、注册弹窗关掉重新点"注册"应重弹原指令、登录显示车手号 1。
2. **群内 bot 复测**（全在服务端，见 paddock HANDOFF §6.2）：新重置口令严格匹配、裸关键词静默、带码注册车手号=2。
3. **重构**（用户定案"下个版本重构模块和服务端的一堆东西"）——范围待用户指定。
4. （可选继承）排行榜实时刷新（暂不做）/ lint baseline 重生成 / PADDOCK_PLAN §6 契约同步。

## 7. 留给用户的开放问题
- 重构范围清单待定（模块+服务端都提了"一堆东西"）。
- 播报主动消息额度（约 4 条/月/群）：接受 / 申请认证？
- 计时赛积分卡"暂无"与"加载失败"是否区分显示？
