# HANDOFF — 读全文再开始干活

生成时间: 2026-09-13T23:33:00+08:00 · Git HEAD: `e604b33`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `e604b33`（2026-09-13 23:32）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `e604b33`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md`（本会话新产出）

## 1. 当前目标
**已完成**：诊断用户报的「游戏启动闪退、关作用域也崩、过一阵自愈」，定案为 **NPatch 框架 bug**（非模块），产出调查文档、用户话术、上游 issue。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因定案**：NPatch 的 origin-apk 缓存无完整性校验/无 fsync/无锁 → 坏缓存常驻 → 每次启动以坏文件作 `LoadedApk` sourceDir → `ResourcesManager.createAssetManager` 吞 IOException `return null` → `LoadedApk.mResources=null` → `getAssets()` NPE → 系统包成「Unable to instantiate application」。六 Agent 交叉验证（源码/AOSP/LSPatch 对照/PairIP/日志取证），全部收敛。
- [V] **模块无关铁证**：崩溃窗口 677 行日志零 `AlaMobileTool`/`libala`/`ShadowHook` 痕迹；崩溃在 `NPatch bootstrap completed` **之后**（模块已加载完）；`scope from Manager` 成功 6 次照样崩；设备重启（pid 32571→4905）后仍崩 5 次；`cacheApkPath` 14 分钟恒定、零 `Extracting origin.apk`。
- [V] **已提交**：`docs/NPATCH_CACHE_CRASH_NOTES.md`（`96b1d1c`）+ CLAUDE.md 索引（`e604b33`），均已 push。
- [V] **已提 issue**：https://github.com/7723mod/NPatch/issues/147 （账号 TakotsuboChen，纯中文，含复现/栈/根因/修复建议/日志）。**缺 `bug` 标签**——API 加标签被拒（`TakotsuboChen does not have the correct permissions`）；网页表单提交可自动打标签，或维护者会补。
- [V] 工作区 clean，与 origin/main 同步（`e604b33`）。

### 测试/build 输出
```
本会话未跑 build/lint（纯调查+文档切片，无代码改动）
git status --short --branch -uall → ## main...origin/main（clean）
```

## 3. 决策与理由
- **定案为 NPatch 框架问题而非模块问题** [V]——四条独立反证（作用域/重启/日志时间线/崩溃时序）。
- **用 `gh issue create` 而非网页表单** [V]——结果缺 `bug` 标签且加不上（权限）。**下次给外部仓库提 issue 优先用网页表单**（模板 `labels:` 会自动生效），否则先确认账号有 triage 权限。
- **issue 版本号填 `1.0.7`** [V]——最初误填 `1.0.7.0`（臆造第四段），已改。NPatch 真实版本就是三段（`build.gradle.kts:44 val verName by extra("1.0.7")`）；模板"4 digits"是防"填 latest"的说明，非四段格式。
- **文档落在 `docs/NPATCH_CACHE_CRASH_NOTES.md`** [V]——沿用项目 `*_NOTES.md` 风格 + `[V]/[?]/[X]` 分级；含"排除项"专节（可复用的"为什么不是我们"清单）。

## 4. 失败的尝试 — 不要再试
- 本会话未试错对结果有影响的方案。继承死路 [X]（详 `.handoffs/20260913233237-handoff.md` §4）：`coroutineScope{}` 内多源竞速 / 只加源数改 scope / `result::class.simpleName` 记日志 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / `MarkdownText` 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / 旧快照覆盖 configs 表 / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节。
- **[X] 靠 `screencap` 裁图目测尺寸**——被颜色掩码统计取代（上一会话）。

## 5. 已知坑
- ⚠️ **`gh` API 建 issue 不套用模板 labels** [?]——外部仓库标签需 triage 权限，普通账号加不上；改网页表单提交。本会话实证。
- ⚠️ **同类坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无 weight 的可滚动正文 + 按钮同列，平板可能重演窄条；手机不触发 largeScreen。修法见 CLAUDE.md 弹窗排版铁律。
- ⚠️ **权限门视觉未实机验收 / bot 投递未端到端验证 / 信箱探针残留 / 永久 4xx 先提示后丢弃 / OkHttp 败者不可中断** [?](继承)——均未动。
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序先拉触摸时间线。

## 6. 下一步（有序）
1. （可选，等用户决定）给 NPatch #147 补 `bug` 标签——网页表单重提，或等维护者补。
2. （可选）同法加固 `EulaDialog`/`UpdateDialog` 正文 weight（见 §5）。
3. （可选）验收权限门控页 / 真实群验证 bot 投递 / 清理信箱探针残留 / ABSdiag·TCdiag 降频。

## 7. 留给用户的开放问题
- NPatch #147 的 `bug` 标签：重提表单 / 不管它（等维护者补）？
- EulaDialog / UpdateDialog 是否加同样 weight 加固？
- 围场指南弹窗可视区（0.55 屏 / 1.5× 行高 + weight 让渡）是否需再调？
- 信箱通道是否给游戏侧加"配置热更新"路径（现走广播）？用户已说"暂时保持现状"
