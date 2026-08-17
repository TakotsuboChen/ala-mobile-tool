# HANDOFF — 读全文再开始干活

生成时间: 2026-08-17T17:49:05+08:00 · Git HEAD: `3448337`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `3448337` (2026-08-17)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `3448337`；变了说明快照可能过期
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**发布 v1.0.0 Beta 4 + 修复镜像仓库 Release Notes 拿不到手工 Notes 的问题。** 已完成：Beta 4 发布闭环（版本号同步 / tag / CI 全绿 / 源+镜像 body 均为手工 Notes），workflow 改为 Notes 先于 CI 存在。遗留：position 合并修复待用户真机确认、janky 根因待排查。

## 2. 已验证状态 — 工作实际停在哪

- [V] **v1.0.0 Beta 4 发布完成** — `acb8109` 同步版本号（build.gradle.kts + module.prop → `1.0.0 Beta 4` / `100240`），tag `v1.0.0-Beta-4` 已推送，CI run `32014327251` 全绿（lint / build / rename / upload / sync-lsposed），源 release body 已覆盖为手工 Notes。
- [V] **镜像仓库 Release Notes 修复完成** — 根因：`sync-lsposed` job `needs: build`，build 一结束就从源 release 读 body 同步到镜像，手工 `gh release edit` 永远晚于 CI 内 sync。修复：`build.yml` 的 `Upload to Release` 改用 `body_path: RELEASE_NOTES.md`（softprops 参数名，非 `body-file`），新增 `Prepare release notes` 步骤（RELEASE_NOTES.md 缺失时 fallback 生成默认安装说明）。当前 Beta 4 镜像 body 已手动覆盖为手工 Notes（`gh release edit 100240-1.0.0_Beta_4 --repo Xposed-Modules-Repo/tools.alamobile.mod`）。
- [V] **`/release` skill 改为项目检测分支** — 通用流程（步骤 5/5B）恢复原样，新增步骤 5C 仅当检测到 `ala-mobile-tool`（存在 `sync-lsposed-mirror.yml` 或 remote 含 `TakotsuboChen/ala-mobile-tool`）时执行 Notes 先于 CI 流程。skill 在 `~/.claude/skills/`，非 git 仓库，改动直接生效。
- [V] **README 版本历史更新** — `3448337` 在版本历史顶部插入 Beta 4 条目。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [?] **position 合并修复** — 上一会话 `resolveLatestSettings()` 从本地 externalFilesDir 合并 position，`mergePositionFromLocalPublic()` 公开化。用户未确认是否生效。
- [?] **切换模式后位置丢失** — 用户反馈"启动时调过位置大小的单踏板，切双踏板再切回单踏板，位置/大小变成默认状态"。上述 position 合并修复已尝试解决，用户未确认。

### 测试/build 输出（本次交接 run 的真实输出）
```
gh run watch 32014327251 → ✓ 全步骤通过（lint / build / rename / upload / sync-lsposed），5m53s
gh run watch 32016189764 → ✓ build.yml 改动验证 CI 全绿（push 到 main，Prepare release notes / Upload to Release 步骤 skipped 符合预期）
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/build.yml'))" → YAML OK, body_path = RELEASE_NOTES.md
```

## 3. 决策与理由

- **Notes 先于 CI 存在** [V]——镜像同步发生在 CI 内（`needs: build`），任何"事后覆盖"都治标不治本。让正确内容在 CI 启动前就存在（`RELEASE_NOTES.md` 随版本号一起提交），源和镜像都拿到同一份手工 Notes。
- **`body_path` 而非 `body-file`** [V]——`softprops/action-gh-release@v3` 的输入参数是 `body_path`（下划线），用错参数 body 会静默为空。
- **`/release` skill 用项目检测分支而非全盘改** [V]——skill 是全局的，其他项目没有 `RELEASE_NOTES.md` 约定/镜像仓库/相同 CI 结构。通用流程恢复原样，项目特定逻辑独立成步骤 5C，步骤 0 加检测。否决方案：全盘照搬本项目情况改通用流程，会污染其他项目。
- **YAML heredoc 缩进** [V]——block scalar 里 heredoc 内容必须缩进到与 `cat` 同级，否则 YAML 认为 `run:` 块提前结束。YAML 解析自动剥离公共缩进，bash 收到的仍是顶格内容。

## 4. 失败的尝试 — 不要再试

- [X] **`useLegacyPackaging = true`** — M40 照搬 KernelSU 加的，导致 `libshadowhook.so not found` 的 native 加载失败。不要再加。
- [X] **`readFromTargetProcess` 作为 rebuild 唯一配置来源** — daemon 写入滞后于广播，rebuild 读到旧 pedalMode/curve。`rebuildFromConfigChange` 和 `toggleOverlays` 必须优先用广播 JSON。不要再单用 `readFromTargetProcess`。
- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — M38 M39 已验证，换了仍 22-38% janky。
- [X] **关 blur / 移除 rememberContentReady / ModConfig.read 异步 / 各种 janky 优化** — 所有 M38-M40 的 janky 修复尝试均无效，详见旧 HANDOFF.md。
- [X] **手工 `gh release edit` 事后覆盖镜像 body** — 永远晚于 CI 内 sync（`sync-lsposed needs: build`），镜像拿到默认安装说明。不要再依赖事后覆盖，必须 Notes 先于 CI 存在。

## 5. 已知坑

- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。但 daemon 异步绑定可能延迟，广播比 remote 先到。`readFromTargetProcess` 读 remote（daemon 旧值）≠ 刚写入的配置。解决方案：`rebuildFromConfigChange` 和 `toggleOverlays` 优先用广播 JSON。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position（游戏进程拖拽时 `saveOverlayPosition` 写本地）。用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position，否则重建后位置/大小丢回默认。`resolveLatestSettings()` 已处理此逻辑。
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错。
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰，用本地 NDK 26 替代。
- ⚠️ **miuix SwitchPreference 在我们的 app 中 22% janky，KSU 同版本 0.10%** [V]——仍待排查，R8 优化差异可能。
- ⚠️ **lint 的 NewApi 检查会拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 KernelSU minSdk 更高，其 API 调用可能超出我们的 minSdk。新增高版本 API 调用时用 `values-vNN` 拆分或 `SDK_INT` 守卫。
- ⚠️ **镜像仓库 Release Notes 时序** [V]——已修复（Notes 先于 CI 存在），但发版必须走 `/release` skill 步骤 5C：写 `RELEASE_NOTES.md` → 同步版本号 → commit + tag + push。手动打 tag 没写 RELEASE_NOTES.md 时 CI 会 fallback 到默认安装说明。

## 6. 下一步（有序）

1. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。如果仍丢回默认，需排查 `resolveLatestSettings()` 的 `mergePositionFromLocalPublic` 是否正确合并了 position。
2. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB），见 M40 HANDOFF。

## 7. 留给用户的开放问题

- 切换模式后单踏板位置丢失问题是否已修复？需要用户真机验证。
- 自定义曲线图表布局（分隔线/卡片结构/轴标签位置）是否满意？之前用户说"不改了"，但交互细节可继续调整。
