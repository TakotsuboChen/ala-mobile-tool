# HANDOFF — 读全文再开始干活

生成时间: 2026-10-20T14:40:00+08:00 · Git HEAD: `0e4742f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0e4742f`（已 push）· 服务端 `main` @ `8a2da78`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0e4742f`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `paddock-api/src/api/laps.rs` 的 `AUTO_HIDDEN_SQL` 定义（判据单一事实源）

## 1. 当前目标
无进行中的功能切片。本会话把「屏蔽缺失配置记录」的判据从**快照**升级为
**持续生效的开关**：补全配置自动解除、开关期间新传缺失圈自动屏蔽。
两仓已提交推送、服务端已部署、存量数据已修复、线上不变量全部验证通过。

## 2. 已验证状态 — 工作实际停在哪
- [V] **报障根因**：判据是 `lap_id ∈ hidden_missing_laps`，与配置状态**永久解耦**
  ⇒ 管理端补全配置后红标不消失、圈也回不了榜（`recalc_dims` 同样按 lap_id 排除）。
- [V] **修复已上线**：镜像 `paddock-api:1.0.1-autohide`，`.env` 的 `PADDOCK_IMAGE`
  已改，容器 `Up`；`/v1/health` 200 `{"status":"ok","version":"1.0.1"}`。
- [V] **测试**：`cargo test --bin paddock-api` → 18 passed / 0 failed / 14 ignored；
  真库串行 `-- --ignored --test-threads=1` → **14 passed / 0 failed**（新增
  `hide_missing_is_a_switch_not_a_snapshot`，删掉已推翻的快照语义测试）。
- [V] **存量数据已修复**：`deploy/hidefix-switch-20261020.sql`（幂等，临时库验证两轮）
  线上执行 → 审计表 65→62、GP3/GP11 回归、积分 1873→**2357**、`best_count` 13→**16**。
- [V] **三条不变量全部成立**（线上查询实证）：`bad_best`=0 / `bad_rec`=0 /
  `hidden AND personal_best` 同挂 = 0。
- [V] **车手主页实测**：GP3 `1:06.049`、GP11 `1:29.325` 现为 `hidden=false` +
  `personal_best=true`，配置显示「双踏板 / 关 / 关」。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`；服务端 `1.0.1`（镜像 tag 后缀
  `-autohide` 只是构建标识，不是 SemVer）。
- [V] **工作区干净**：两仓 `git status` 均无未提交改动。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ cargo test --bin paddock-api                             → 18 passed; 0 failed; 14 ignored
$ DATABASE_URL=...55434 cargo test --bin paddock-api -- --ignored --test-threads=1
                                                           → 14 passed; 0 failed
$ cargo build --bin paddock-api                            → Finished（2 warnings，均为既有）
部署：docker build → save|gzip → scp → VPS docker load → .env 改 PADDOCK_IMAGE
      → sudo docker compose -f docker-compose.vps.yml up -d app → Up
备份：~/paddock/backup-pre-autohide-20261020142303.sql.gz（1.3MB）
```

## 3. 决策与理由
- **判据形态 = 谓词，不是快照** [V]——用户定案。`AUTO_HIDDEN_SQL` =
  「`hidden_missing_at IS NOT NULL` AND 该圈五维任一 NULL」。否决方案「继续按
  lap_id 快照」，否决原因 = 快照与配置永久解耦，补全后永远解除不了。
- **`hidden_missing_laps` 降级为纯审计表** [V]——不删表（要新 migration，且
  `hidden_at` 时间戳有留痕价值），只退出判据。
- **开关必须先写再重算** [V]——谓词依赖开关，沿用首版「最后写标记」的顺序会让
  重算时谓词恒假、一条都排除不掉。
- **`upload_lap` 必须单独加闸门** [V]——屏蔽时 best_laps 行已删，
  `UPDATE ... WHERE lap_ms > $4` 命中 0 行会走 insert 分支，不看判据直接插回榜单。
- **存量数据用 SQL 脚本而非应用内迁移** [V]——脚本幂等、可在临时库预演、
  可重复执行；应用内迁移无法在部署前验证副作用。
- **UI 文案全部保持不动** [V]——用户逐条确认「保持现有文案不改」（确认弹窗正文 /
  入口 summary / 已屏蔽 Toast 三项）。

## 4. 失败的尝试 — 不要再试
- [X] **按 `lap_id` 快照实现屏蔽** [V]——与配置状态永久解耦，管理端补全配置后
  红标不消失、圈回不了榜。用户原话：「我手动改过之后应该自动解除屏蔽才对」。
  判据：被过滤对象的属性**会不会变**？会变就必须用谓词。
- [X] **在 `hide_missing` 末尾才写 `hidden_missing_at`** [V]——谓词依赖开关，
  后写则重算时恒假。必须提到所有重算之前。
- [X] **`SELECT ... FROM users u WHERE u.id=$1 AND AUTO_HIDDEN_SQL`** [V]——
  `AUTO_HIDDEN_SQL` 引用 `l.` 前缀，该查询没有 `l` 别名，编译期不报错但运行期
  列不存在。上传路径改走 Rust 版 `is_assist_missing` + 单独查开关。
- [X] **临时库里手写 `INSERT INTO users` 不带 `pass_hash`** [V]——NOT NULL 约束；
  且 `reg_seq` 有唯一索引，测试数据要避开 1/2 这类已被占用的值。
- [X] **验证解除屏蔽时忘了先把测试数据改成「已补全」** [V]——第一次跑脚本结果
  不变，误以为是脚本 bug，实际是那条圈配置仍缺失、按新语义本就该被屏蔽。
  验证「解除」场景必须先 `UPDATE laps SET pedal_mode=...` 补全再跑。
- [X] 继承死路（详 `.handoffs/20261020143748-handoff.md` §4 及更早）：
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
  把 UI 文案表格贴在正文里让用户自由回复 / 按「每维度获胜圈」筛缺失配置。

## 5. 已知坑
- ⚠️ **隐藏/屏蔽类功能：路径全覆盖 + 范围对 + 判据形态对** [V]——三层递进，见
  memory `data-filter-must-cover-all-recalc-paths`（本会话补了「快照 vs 谓词」一节）。
- ⚠️ **UI 的图标/文案/命名是用户的决策域，必须用 AskUserQuestion 逐条确认** [V]——
  见全局记忆 `ask-before-designing-ui`。未确认前不构建、不装机、不部署。
- ⚠️ **VPS compose 必须带 `-f docker-compose.vps.yml`，切镜像只改 `.env`** [V]——
  见 memory `paddock-deploy-chain-local-build` / `paddock-compose-must-match-vps`。
- ⚠️ **本机已有 docker，真库测试直接本地起临时库** [V]——见 memory
  `sql-test-must-run-serial-and-temp-db`（本会话更正了「本地无 docker」的旧说法）。
  `psql` 仍未安装，执行 `.sql` 用 `docker exec -i <容器> psql`。
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
- ⚠️ **`/v1/health` 可用、裸 `/health` 是 404** [V]——路由挂在 `/v1` 前缀下，
  探活用 `/v1/health` 或 `/v1/leaderboard/points`。
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
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：该用户仍有 21 条红标
   （142 条圈里），要不要在 `DriverScreen` 的筛选里加开关？
2. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像（`1.0.1-paging`、
   `1.0.1-driverstats`、`1.0.1-recfix`、`1.0.1-sort`、`1.0.1-account`、
   `1.0.1-hidefix`、`1.0.1-autohide`）+ 备份 `.tar.gz` + 多个 `.env.bak-*`
   + 两个 `~/backup-pre-*.sql.gz`。清理前先确认当前实跑是 `1.0.1-autohide`。
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
- 屏蔽现在是「持续生效的开关」——用户已开启后，之后每次旧版模块上传缺失圈都会被
  自动屏蔽且**无任何提示**。要不要给用户一个通知/日志可见性？
