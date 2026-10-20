# HANDOFF — 读全文再开始干活

生成时间: 2026-10-20T13:20:00+08:00 · Git HEAD: `7fecbe0`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `7fecbe0`（已 push）· 服务端 `main` @ `668d26e`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `7fecbe0`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `paddock-api/src/api/account.rs` 的 `hide_missing` 函数头注释
  （写满了「为什么范围必须是全部缺失圈」）

## 1. 当前目标
无进行中的功能切片。本会话修复了「屏蔽缺失配置的圈速记录」的范围 bug：
从「只屏蔽每维度获胜圈」改为「屏蔽全部缺失圈」。两仓已提交推送、服务端已部署、
存量数据已回滚、用户已重新执行并验证通过。

## 2. 已验证状态 — 工作实际停在哪
- [V] **bug 根因定位**：`hide_missing` 用 `DISTINCT ON (gp_index, version_code)` 只取
  每维度最快圈 → 每维度最多屏蔽 1 条；被屏蔽的获胜圈移出 `best_laps` 后回放挑**次快圈**
  顶上，而它往往同样缺失 → 「已屏蔽」与「最佳」同挂一条。实机：飞行雪绒 65 条缺失圈
  只标 7 条（= 7 个维度的获胜圈），GP2/GP3/GP4/GP15 四条同时带两个标。
- [V] **修复已上线**：镜像 `paddock-api:1.0.1-hidefix`，`.env` 的 `PADDOCK_IMAGE` 已改，
  容器 `Up`；`/v1/leaderboard/points` 200、`/v1/me/account` 401（未鉴权正确）。
- [V] **测试**：`cargo test --bin paddock-api` → 18 passed / 0 failed / 14 ignored；
  真库串行 `-- --ignored --test-threads=1` → **14 passed / 0 failed**（新增 3 条）。
- [V] **存量数据已回滚**：飞行雪绒 `hidden_missing_at` = NULL、`hidden_missing_laps` 0 条、
  `best_laps` 恢复到 16 行（含 7 行指向缺失圈 = 屏蔽前原始状态）、`records` 16 行重算。
  备份在 VPS `~/hidefix-backup-20261020125606.sql`。
- [V] **用户已重新执行**：`hidden_missing_at` = 2026-10-20 05:10:34 UTC，
  `hidden_missing_laps` = **65 条**（全部缺失圈）。
- [V] **三条不变量全部成立**（线上查询实证）：
  `best_laps` 指向缺失圈 = **0**；`records` 指向被屏蔽圈 = **0**；
  同圈同时带两标 = **0**。
- [V] **结果**：剩余 13 个维度（GP0/1/2/4/5/6/7/8/9/10/12/13/14），配置全完整；
  GP3/GP11/GP15 无任何完整圈 → 维度整体退出榜单。积分 1873，总榜第 2。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`；服务端 `1.0.1`（镜像 tag 后缀
  `-hidefix` 只是构建标识，不是 SemVer）。
- [V] **工作区干净**：两仓 `git status` 均无未提交改动。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ cargo test --bin paddock-api                              → 18 passed; 0 failed; 14 ignored
$ DATABASE_URL=...55433 cargo test --bin paddock-api -- --ignored --test-threads=1
                                                            → 14 passed; 0 failed
$ cargo build --bin paddock-api                             → Finished（3 warnings → 修到 2）
部署：docker build → save|gzip → scp → VPS docker load → .env 改 PADDOCK_IMAGE
      → sudo docker compose -f docker-compose.vps.yml up -d app → Up
```

## 3. 决策与理由
- **屏蔽范围 = 全部缺失圈，不是每维度获胜圈** [V]——用户定案。否决方案「循环屏蔽直到
  没有缺失」，否决原因 = 首版就是按「代表项」筛，代表项被移除后接棒者同类，逻辑上必然漏。
- **`count_missing_candidates` 必须与 `hide_missing` 同源** [V]——预判与执行分叉会产生
  「说有候选 → 点了说没有」或「只屏蔽了 1/7」。两处都走 `laps::ASSIST_MISSING_SQL`。
- **重算改为「先 DELETE 再回放」** [V]——某维度屏蔽后可能一条可用圈都不剩，
  `INSERT ... ON CONFLICT DO UPDATE` 表达不了「行消失」。
- **存量数据选「回滚 + 用户重点」而非迁移脚本** [V]——用户定案。注意回滚**不能只清标记**：
  `best_laps` 已被旧逻辑按「排除 7 条」重算过，光清标记会留下「无红标但最佳不是最快圈」，
  比修复前更错乱。必须连 `best_laps`/`records` 一起回放到屏蔽前。
- **`is_assist_missing` 保留并 `#[allow(dead_code)]`** [V]——生产路径不再调用它（改走 SQL），
  但它是 SQL 表达式的**语义锚点**，测试靠它保证两者不分叉。

## 4. 失败的尝试 — 不要再试
- [X] **按「每维度获胜圈」筛缺失配置** [V]——次快圈接棒成「最佳」且同样缺失，
  用户报「只有少部分显示已屏蔽，还有一堆没屏蔽，而且缺失配置的还有显示最佳的」。
  判据：任何「按每维度取代表项再筛条件」的写法，先问「移除代表项后接棒的那个是否也满足」。
- [X] **`l.pedal_mode IN ('single','dual')` 直接写进 `NOT NULL` 布尔列** [V]——列为 NULL 时
  表达式结果也是 **NULL 不是 false**，撞 `best_laps.pedal_linear NOT NULL`，整个事务回滚。
  派生位一律 `COALESCE(<expr>, false)`。
- [X] **`sudo docker compose up` 在 `~/paddock` 下裸跑** [V]——报
  `no configuration file provided: not found`（该目录只有 `docker-compose.vps.yml`）。
- [X] 继承死路（详 `.handoffs/20261020131933-handoff.md` §4 及更早）：
  `PickVisualMedia` / `createChooser` 单目标直通 / `EXTRA_INITIAL_INTENTS` /
  `ResolveInfo.loadLabel()` / Kotlin 块注释写 `image/*` / `color = Color(...)` 做金色流光 /
  `loadingMore` 写正常路径末尾 / 0/1/2 固定分栏 / VPS 上 `sed` 改 compose /
  两个 BlurredBar 共用 backdrop / `listState.scrollToItem(0)` 放请求前 /
  `unzip -p | strings | grep`（中文需 `grep -a` 直接搜 dex）/ 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号 / 改完文案就构建装机（未先确认）/
  把 UI 文案表格贴在正文里让用户自由回复。

## 5. 已知坑
- ⚠️ **UI 的图标/文案/命名是用户的决策域，必须用 AskUserQuestion 逐条确认** [V]——
  见全局记忆 `ask-before-designing-ui`。未确认前不构建、不装机、不部署。
- ⚠️ **隐藏/屏蔽类功能：路径要全覆盖 + 执行侧范围也要对** [V]——见 memory
  `data-filter-must-cover-all-recalc-paths`（本会话补了「范围」与「SQL 三值逻辑」两节）。
- ⚠️ **VPS compose 必须带 `-f docker-compose.vps.yml`，切镜像只改 `.env`** [V]——
  见 memory `paddock-deploy-chain-local-build` / `paddock-compose-must-match-vps`。
- ⚠️ **中文文案验证必须直接 grep dex 字节** [V]——`unzip -p | strings | grep` 会假阴性。
- ⚠️ **`/handoff` 的 push 无需额外授权，且必须做完** [V]（继承）——报告里出现「待 push」= 违规。
- ⚠️ **选图/选文件必须自绘选择器** [V]（继承）——见 memory `android-file-picker-no-gms`。
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]（继承）——见 memory
  `miuix-text-color-overrides-brush`。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]（继承）——见 memory
  `coroutine-flag-needs-try-finally`。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——本会话未操作任何设备。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）——见 memory `rank-row-number-not-rank`。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——见 memory
  `compose-maxlines-overflow-orthogonal`。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）——见 memory
  `lazylist-pagination-sentinel-trap`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]（继承）——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。
- ⚠️ **VPS docker 需 `sudo`** [V]——SSH 用户不在 docker 组。
- ⚠️ **真库测试必须串行 + 临时库** [V]（继承）——见 memory
  `sql-test-must-run-serial-and-temp-db`。本会话用 `-p 55433` 临时容器，跑完 `docker stop`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [?]（继承）——看日志环境块的构建指纹。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含
  `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **`/health` 端点不存在（404）** [?]（继承）——探活用 `/v1/leaderboard/points`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：屏蔽后 65 条红标占列表近一半
   （该用户 139 条圈）。要不要在 `DriverScreen` 的筛选里加开关？还是保持「全部历史可见」？
2. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像（`1.0.1-paging`、
   `1.0.1-driverstats`、`1.0.1-recfix`、`1.0.1-sort`、`1.0.1-account`、`1.0.1-hidefix`）
   + 备份 `.tar.gz` + 多个 `.env.bak-*` + 本次的 `~/hidefix-backup-*.sql`。
   清理前先确认当前实跑是 `1.0.1-hidefix`。
3. **（待你决定）大奖赛页的产品形态**：当前是占位文案。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块
   `1.0.4 Alpha 4`、服务端 `1.0.1` 均未动。

## 7. 留给用户的开放问题
- 圈速卡的「间隔 2 字符」是否合适？（上上会话遗留，未回答）
- 「全服最快圈数量」显示跨版本累计数。是否需要在界面上标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成）。界面上目前不提这件事，是否需要提示？
- **屏蔽后车手主页列表被红标淹没**（见下一步 1）。
