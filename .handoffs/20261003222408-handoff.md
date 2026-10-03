# HANDOFF — 读全文再开始干活

生成时间: 2026-09-18T16:30:41+08:00 · Git HEAD: `15e84ec`
paddock 仓 HEAD: `0f8319c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `15e84ec`；paddock main @ `0f8319c`（两仓均已 push）
- 漂移检查: `git rev-parse HEAD~1`（两仓）是否仍 = 上述 SHA——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5
- 先读: `docs/PADDOCK_PLAN.md` §1（积分公式与辅助加成定案）+ `paddock-api/src/score.rs`（公式单一事实源）

## 1. 当前目标
**已完成**：「围场辅助加成 + 零辅助金标」全链路——服务端存圈辅助配置（踏板/TC/ABS 原始枚举）、积分加成（最多 +50%）、排行榜金字；模块侧随圈上报配置（整圈一致性由 native epoch 判定）；管理端配置列/编辑回填/金标播报模板；用户页人数修复。**服务端已部署上线 + 模块已装机。用户验收："可以，完美"。现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **两仓工作已提交推送**：模块 `d2bd475`（native+JNI+上传）/`f91ea22`（金字）/`326f84b`（契约+指南）/`15e84ec`（持久文档）；paddock `3fbd1bb`（后端+加成）/`121c2b7`（真库测试）/`0f8319c`（管理端 UI+金标播报）。
- [V] **门槛**：`:app:lint --rerun-tasks` → `Lint found 58 warnings, 4 hints (3 errors + 14 filtered by baseline)` + `BUILD SUCCESSFUL`；`:app:assembleRelease -x lintVital*` → `BUILD SUCCESSFUL`。
- [V] **服务端测试**：`cargo test --release` → **9 passed / 0 failed**；`DATABASE_URL=... cargo test --release -- --ignored` → **7 passed / 0 failed**（真 Postgres 容器）。
- [V] **服务端已部署**：镜像 `paddock-api:1.0.1`（同名 tag 覆盖，本地/VPS ID 一致）→ `docker load` + `compose up -d --force-recreate app` → `/v1/health` = `{"status":"ok","version":"1.0.1"}`。迁移 `0009` 已应用（`laps` 五列 + `best_laps` 四布尔）。**部署前已做完整 pg_dump 备份**（`VPS:~/paddock/backup-pre-0009-20260918143257.sql.gz`，458KB）。
- [V] **模块已装机**：APK `1.0.4 Alpha 1 / 104100`（版本号未动）→ `adb install -r` Success，设备 MEIZU 20（无线 `192.168.50.142:5555`）。
- [V] **实机日志实证全链路**：`LAPcfg: assist config updated pedal=2 tcm=2 tcs=1 absm=2 abss=3 (epoch=1)`（奇数码 = 枚举序号，pedal=2=SINGLE、tcs=1=OFF）→ 生产库出现 `Takotsubo / gp=7 / 63845ms / single+custom+off / custom+off` → 赛道榜该行 `gold=True`。
- [V] **金标播报迁移已生效**：VPS 启动日志 `[qq_bot] 已为播报规则 preset-bc-alltime / -bc-version 补上预设金标模板`，`configs` 出现 `bot_gold_template_migrated_v1` 标记；二次启动无重复补写（幂等验证过）。
- [V] 工作区: 两仓 clean。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint --rerun-tasks
  → Lint found 58 warnings, 4 hints (and 3 errors and 14 warnings filtered by baseline lint-baseline.xml)
  → BUILD SUCCESSFUL in 2m 9s   EXIT=0
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 3s   EXIT=0
$ cd paddock-api && cargo test --release
  → test result: ok. 9 passed; 0 failed; 7 ignored
$ DATABASE_URL=postgres://postgres:test@127.0.0.1:55436/paddock cargo test --release -- --ignored --test-threads=1
  → test result: ok. 7 passed; 0 failed; 9 filtered out   EXIT=0
```

## 3. 决策与理由
- **客户端只报原始枚举，服务端派生"关/低/线性"** [V]——规则留在服务端，改加分不必发模块版本。否决"客户端算好再报"：规则迭代会让老客户端报错值。
- **关 TC/ABS 必须 `mode=='custom'`** [V]——`mode=default` 时 `strength` 只是 UI 记忆值不生效，读它会把"从没调过 TC"的用户误判成关 TC 白拿 10%。测试 `default_mode_never_counts_as_off` 钉住。
- **加成两道零门**：`base>0 && pct>0` [V]——缺 `pct>0` 这道，"四项全不达标"会走"不足 1 保底 1"分支**人人白送 1 分**。`bonus_rounding_edges` 钉住。
- **整圈一致性用 epoch 计数器而非逐维比对** [V]——"改了又改回"逐维比对会误判一致；epoch 抓得住。开销 = 每圈 1 次整数比较。
- **`score.rs` 作积分公式单一事实源** [V]——公式原有 4 处 SQL 副本（版本榜/总榜/`/v1/me`/管理端），改一处必漏三处。
- **金标模板是独立字段而非模板内变量** [V]——用户文案是整段多行替换（含"金标认证"句），变量替换表达不了"整段换掉"。
- **金标模板迁移必须一次性 + 独立标记** [V]——`load_rules` 每次播报都调用，"见空就补"会把用户手工清空（= 不要金标播报）悄悄填回；"字段从未存在"与"用户清空"在 JSON 里都是空串。
- **管理端配置列三维 token**（双踏板 / 关 / 最高）[V]——用户定案：`mode=default` 与 `custom+stock` 计分等价、语义也是"默认就是最高"，分两维纯占地方。`stock` 反向展开为 `mode=default`（不是 `custom+stock`，后者语义是"开过自定义"）。
- **金色流光用 `brush` 而非 `animateColor`** [V]——要的是高光**扫过**（带状移动），不是整体明暗呼吸。底色 `goldenrod #DAA520`（`darkgoldenrod #B8860B` 实机反馈偏暗像脏铜色）。
- **金标判据只看 TC/ABS，不含踏板** [V]——用户理由：原生按键物理上跑不出有效圈，实际满足者必然在用模块踏板。
- **服务端用同名 tag 覆盖部署** [V]——`Cargo.toml` 版本号一个字没动（用户红线）；镜像 tag 必须与 compose 里写死的一致，故构建产物直接覆盖同名 tag。**唯一风险是"装上去的还是旧的"，靠二进制时间戳 + 镜像 ID 比对证伪**。

## 4. 失败的尝试 — 不要再试
- **[X] 在 `load_rules` 里"见空就补" gold_template** [V]——每次播报都调用，会把用户手工清空静默填回。改用一次性迁移 + `configs` 标记。
- **[X] 管理端 lapEdit 传旧字段名（`dataset.tcm/tcs/absm/abss`）** [V]——data-* 已改成 `data-tc/data-abs`，传 undefined → 全部落在"缺失"选项。**这类键名不匹配 lint/单测/编译全发现不了**，只有真渲染 DOM 才看得见。
- **[X] QQ Markdown 做文字颜色（`<font color>` / `<span style>`）** [V]——官方白名单只有标题/加粗/斜体/删除线/链接/图片/列表/引用/分割线，**无颜色语法**。那是钉钉/飞书的能力。用户已决定放弃。
- **[X] 给 `leaderboard.rs` 的三层 CTE 里漏带派生列** [V]——`scored` CTE 没带上 `pedal_linear`/`abs_low` → 榜单端点 500 `column s.pedal_linear does not exist`。**单元测试测不出**（它测 Rust 实现，线上跑 SQL 字符串），靠 `score_sql_tests.rs` 真库测试抓出。
- **[X] 掏生产库验证时把 SQL 单独跑了就下结论** [V]——那次 `data-pedal` 属性 grep 为空、我误判成"容器没重启"，实际是旧模板（即上述 lapEdit bug 的表现）。**线索要串起来看，别单点归因。**
- **[X] hooks 里"每次读都补空值"式的补数据** [V]——凡是在高频读路径上做"见缺就补"的迁移，都会覆盖用户的有意清空。一律改为一次性 + 标记。
- 继承死路 [X]（详 `.handoffs/` 归档 §4）：hook `HybridComponent.EnableOTK/DisableOTK` / 给 `HybridComponent` 写身份白名单 / `viewBox` 非零原点照搬 / hook `IRDSCarControllInput.drsToggle` / 模块自行解析赛道 DRS 区域 / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` 扫 libil2cpp / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `withEndAction` 淡出收尾 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` 清 token / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` 等宽。

## 5. 已知坑
- ⚠️ **`/v1/me` 的 `total_points` 是独立于榜单的第二份 SQL** [V]——已改成走 `score.rs` 生成片段，但**仍是独立查询**。改公式后两处都要验（用真库跑一遍对比榜单端点最直接）。
- ⚠️ **服务端镜像 tag 是临时状态** [?]——目前 `paddock-api:1.0.1` 被本次构建覆盖，`Cargo.toml` 未升版。**若之后要正式发版，需用户定版本号**才能让 tag/Cargo.toml/git tag 三处对齐。
- ⚠️ **VPS 实测访问方式** [V]——`curl 127.0.0.1:8080` 正常；但管理端登录需要 `PADDOCK_ADMIN_USER`（真实值 **`Takotsubo`**，不是 `admin`）+ `.env` 里的密码。远程 bash 里嵌套引号易被吞（`unmatched '`），改用 `python3` 脚本或 heredoc 传 `bash -s`。
- ⚠️ **金标金光实际观感未验收** [?]——周期 2.4s 是我定的初值；底色刚从 darkgoldenrod 调成 goldenrod，用户尚未反馈新观感。
- ⚠️ **金标播报模板未实机触发验证** [?]——迁移已生效（文案在库），但还没有"零辅助圈破纪录"的真实播报发生。
- ⚠️ **官版（com.Vince）未验证** [?]——本会话全部日志来自共存版。
- ⚠️ **自动 DRS 的 `playercar`(0x9C) 白名单回落分支未实机验证** [?]（继承）——实机走 `icinp == pedal_get_controller()` 主判据。
- ⚠️ **地效车与空力车的确认粒度** [?]（继承）——自动 DRS 日志无 `carType`。
- ⚠️ **自锁型超车按键的"抬起被吞"缺直接日志证据** [?]（继承）——结论由开/关严格交替逻辑排除法得出。
- ⚠️ **手势路径未 hook** [?]（继承）——自锁只作用屏幕按钮。
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——读图先降采样到长边 ≤1568px。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——每次先 `adb devices -l`。
- ⚠️ **编辑模式仅共存版 + SINGLE 模式验证** [?]（继承）；**榜单 tnum 只在 ColorOS 16 平板验过** [?]（继承）；**同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?]（继承）。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号**。

## 6. 下一步（有序）
1. 用户在实机看金标观感（榜单金字 + 金标播报），按反馈调 `GOLD_BASE`/周期或金标模板文案。
2. （可选）造一次"零辅助破纪录"实测金标播报：管理端把某条已有成绩改成 `双踏板/关/关` 并保存（若它恰好是当前纪录即触发）。
3. （可选）正式发版时需先问用户版本号，再做 tag/Cargo.toml/compose 三处对齐。
4. （可选）关掉踏板替换后回归自动 DRS 的 `playercar` 白名单回落分支（继承）。
5. （可选）官版（com.Vince）回归：编辑模式 + 日志导出 + 自动 DRS + 自锁超车四处对称验证（继承）。

## 7. 留给用户的开放问题
- 金标金光亮度与速度合适吗？（底色已改 goldenrod，周期 2.4s）
- 金标播报的文案要不要在 `# 纪录快讯` 后加 emoji 之类装饰？（已按原文逐字保留，未加）
- 自动 DRS 的部署时机体感如何、要不要加会话限制？（继承）
- 自锁型超车按键要不要覆盖手柄 nitroButton 路径？（继承）
