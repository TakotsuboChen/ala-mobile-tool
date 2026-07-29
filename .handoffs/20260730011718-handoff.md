# HANDOFF — 读全文再开始干活

生成时间: 2026-07-29T16:56:18Z · Git HEAD: c5fc691
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

落地版本命名规则到代码 + CI + Release 全链路，并闭环验证 CI artifact 和 Release assets
的文件名呈现。**全部已达成。**

完成定义：versionName 空格风格、versionCode 6 位编码自洽、CI 用 archive:false 产直接 apk
artifact（非 zip）、tag push 发 Pre-release 且 asset 保留空格文件名、Beta 1 回归 CI 全绿。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `c5fc691`，工作树 clean，已 push 到 origin。
- [V] 命名规则改动文件：`app/build.gradle.kts`（versionName 空格风格）、
  `module.prop`（version 同步）、`.github/workflows/build.yml`（Rename APK step +
  archive:false + 精确路径）、`CLAUDE.md`（Version Naming Convention + Release Stage Policy 节）。
- [V] versionCode=100210 / versionName=`1.0.0 Beta 1` 自洽（1.0.0 Beta 1 build 0），
  稳定版规则：阶段=3、D=0、文件名无标注（如 1.5.9→159300，非 310）。
- [V] CI run 30469377106（Beta 1 push，archive:false 首次生效）success，14 step 全 ✓，
  artifact `Ala Mobile Tool v1.0.0 Beta 1 CI.apk`，size 2073332 字节，API 元数据
  保留空格+`.apk` 后缀，curl 直接下载得单文件 apk（非 zip）。
- [V] CI run 30471924653（tag `v1.0.0-Beta-2` push）success，`Upload to Release` ✓，
  Release `isPrerelease: True`（Beta 正确发为 Pre-release），浏览器实际 asset 名
  `Ala Mobile Tool v1.0.0 Beta 2.apk`（空格保留），事后已删 tag+release 清理。
- [V] CI run 30472426491（Beta 1 回归，revert 后）success，14 step 全 ✓（Upload to Release
  正确 skipped，因非 tag push）。确认折腾后最终代码状态无回归。
- [V] 旧 HANDOFF 已归档到 `.handoffs/20260729165618-handoff.md`。
- [?] 真机冒烟测试（用户报告，未由本次命令验证）：共存版和原版 overlay/踏板/换挡/billing 四项全正常。

### 测试/build 输出 tail（本次交接的真实输出）

```
$ gh run view 30472426491 --json status,conclusion
{"conclusion":"success","status":"completed"}

$ gh api repos/.../actions/runs/30469377106/artifacts
"name": "Ala Mobile Tool v1.0.0 Beta 1 CI.apk"
"size_in_bytes": 2073332
"digest": "sha256:26dcebeeca7cc1e6abd084a17ffbf6a20d7c372ef6fae62d1154dc6744b0de38"

$ gh release view v1.0.0-Beta-2 --json isPrerelease,assets
"isPrerelease": true
浏览器实际 asset 名: Ala Mobile Tool v1.0.0 Beta 2.apk  ← 空格保留
```

## 3. 决策与理由

- versionName 空格风格 `1.0.0 Beta 1`（无 v 前缀）[V]——用户拍板，APK 文件名前自动加 `v`。module.prop 同步。
- 稳定版阶段位=3、D=0、文件名无 Alpha/Beta 标注 [V]——用户强调 159300 不是 310，D 位对稳定版永远是 0。
- CI APK 文件名用 versionName 从 build.gradle.kts grep 提取派生 [V]——单一事实源，避免双源维护。否决：硬编码版本号在 workflow，因改版本号要改两处。
- artifact 用 `actions/upload-artifact@v7` 的 `archive: false`（2026-02 平台新特性）[V]——跳过强制 zip 包装，浏览器直接下载原始命名 apk。硬限制：单文件、无 glob、忽略 name 参数（显示名=原始文件名）。否决：默认 zip 包装，因用户要直接 apk 不要 zip。
- Upload artifact 用精确路径 `steps.rename_apk.outputs.apk_path` 不用 glob [V]——`archive: false` 不支持 glob，且 Rename 后只剩一个 apk。
- Release Stage Policy：正式版发 Release（prerelease=false），Alpha/Beta 发 Pre-release（prerelease=true）[V]——用户拍板。当前 workflow 硬编码 `prerelease: true`，正式版发版时必须改。

## 4. 失败的尝试 — 不要再试

- **`gh run download` 验证 `archive: false` artifact** [V]——CLI 把 apk 当目录解包成子文件（AndroidManifest.xml/classes.dex 等），误导判断"archive:false 把 apk 解包了"。实际上 CLI 行为 ≠ 平台行为，网页端和 curl 下载是单文件 apk。验证 artifact 必须用 `curl`/浏览器，不要用 `gh run download`。不要再试。
- **`gh release view` 查 asset 文件名** [V]——API 返回 `Ala.Mobile.Tool.v1.0.0.Beta.2.apk`（点号），但浏览器实际是 `Ala Mobile Tool v1.0.0 Beta 2.apk`（空格保留）。`gh` CLI 的 asset name 字段会误导。验证 Release asset 名必须看浏览器，不要信 `gh release view`。不要再试。
- **`android-actions/setup-android@v4` 装 `platforms;android-37`** [V]——`sdkmanager` stable 和 `--channel=3` preview 都装不上。Google SDK 仓库未发布该包名。不要再试。
- **symlink `android-37.0` → `android-37`** [V]——AGP 报 `inconsistent location`。必须复制 + 改路径标识。不要再试。
- **只改 source.properties 不改 package.xml** [V]——package.xml 的 `<api-level>37.0</api-level>` 和 `path` 属性没改，AGP 仍报 60 AAR metadata issues。4 处都要改。不要再试。
- **YAML `- name:` 值含冒号未加引号** [V]——workflow 0s 失败，无 jobs 生成。含冒号+空格的 scalar 值必须加引号。不要再试。
- **旧 CI 的 `|| true` + `continue-on-error: true` 吞错** [V]——lint 实际有 3 个 NewApi error 但被吞成 success 假绿。已去掉。不要再试。
- **`onModuleLoaded` 立即 `markInitialized()`** [?]——自杀，会拦掉同一 ClassLoader 自己的后续回调。不要再试。
- **IPC 文件路径优化写法** [?]——`RandomAccessFile.seek+write` 非原子，`pread` 读半截数据。全试过全失败。不要再试。
- **`getLocationOnScreen()` 重建相对坐标** [?]——pairip 壳 relayout 时同样漂移。只有配置值稳定。不要再试。

## 5. 已知坑

- **`archive: false` 对 apk 有效但 CLI 误导** [V]——`gh run download` 会本地解包成目录，但网页端/curl 下载是单文件 apk。验证手段必须用 curl 或浏览器。
- **`gh release view` asset name 字段会转义空格成点号** [V]——API 返回点号风格，但浏览器实际保留空格。验证 Release asset 名必须看浏览器。
- **AGP ApiLevel 整数比较** [V]——`AndroidVersion.ApiLevel=37.0`（浮点字符串）不等于 `37`（整数），AGP 按整数解析导致 60 个 AAR metadata issues。复制 platform 后必须 sed 改 `37.0` → `37`。
- **AGP inconsistent location 检查** [V]——AGP 读 package.xml `path` 属性推断目录名，不匹配拒绝加载。必须改 package.xml `path` + source.properties `Pkg.Path`。
- **Google SDK 仓库未发布 `platforms;android-37`** [V]——但 GitHub runner 预装 `android-37.0`（ApiLevel=37，真 API 37），复制改路径标识可用。
- **Aliyun 镜像 CI 502** [V]——`settings.gradle.kts` 已用 `System.getenv("CI") == null` 条件化，CI 直连 Google Maven。
- **lint baseline 不锁 AGP AAR metadata 检查** [V]——baseline 只锁 lint 规则结果，不锁 `checkDebugAarMetadata`。AAR metadata 问题必须从根上修。
- **miuix 0.9.3 AAR metadata `minCompileSdk=37` 硬要求** [V]——不能降 compileSdk 到 36 绕过。
- **LSPosed 双 ClassLoader 注入** [?]——共存版触发双注入，第二副本 hook 全失败但仍启动 writer 线程。必须 Java 层 `System.setProperty` 守卫。
- **pairip 壳 relayout 漂移** [?]——`event.getY()` 和 `getLocationOnScreen()` 都漂移。必须用 `event.rawY - settings.pedalPosition.topPx()`。
- **keystore 不在 git** [V]——`.gitignore` 排除 `*.keystore`。CI 靠 `KEYSTORE_BASE64` secret 注入。
- **module.prop versionCode 必须同步** [?]——LSPosed 用 module.prop 识别模块更新。
- **游戏刹车辅助** [?]——弯道自动刹车，表现为"突突突顿挫"，和模块无关。用户需在游戏设置里关掉。
- **GitHub Actions `if` 里不能直接引用 `secrets`** [V]——必须先 `env: KS: ${{ secrets.X }}` 再 `if: ${{ env.KS != '' }}`。keystore 解码 step 即用此模式。
- **keystore 解码 step 的 `KEYSTORE_PATH` 必须只在解码成功时才设** [V]——Gradle fallback 判 `env != null` 为 true，若无条件设但文件不存在会崩。必须用 `if: env.KS_B64 != ''` 守卫整个 step。
- **`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 走 Gradle 默认值的隐式约束** [V]——真实密码 = 默认值 `alamobiletool`，CI 不设这两个 secret 也能签对。**若日后改 keystore 密码**，必须同步设这两个 secret，否则签名失败。
- **workflow `prerelease: true` 硬编码** [V]——对 Alpha/Beta 正确，但正式版发 Release 时必须改为 `false`（或根据 versionName 是否含 Alpha/Beta 自动判断）。

## 6. 下一步（有序）

1. ~~已完成~~ 命名规则落地 + CI archive:false + Release Pre-release 闭环验证。
2. ~~已完成~~ Beta 2 tag 测试 Release 呈现，测完已清理。
3. ~~已完成~~ Beta 1 回归 CI 全绿。
4. （可选）修 lint baseline 锁住的 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode），让 baseline 清零。影响 Android 8.0-8.1 用户，LSPosed 实际部署都在更高版本。
5. （可选）发正式版 Beta 2：改 versionCode=100220 + versionName=`1.0.0 Beta 2` → commit → 打 `v1.0.0-Beta-2` tag → push，CI 自动创建 Pre-release + 上传 `Ala Mobile Tool v1.0.0 Beta 2.apk`。
6. （可选）发正式版 Stable：workflow `prerelease: true` 改 `false`，versionCode 阶段位改 3、D=0。

## 7. 留给用户的开放问题

- ~~CI artifact 是否能直接下载 apk 而非 zip？~~ 已用 `archive: false` 实现，curl 验证 2073332 字节单文件 apk。
- ~~Release asset 文件名是否保留空格？~~ 浏览器验证保留空格 `Ala Mobile Tool v1.0.0 Beta 2.apk`。
- 是否需要修 lint baseline 锁住的 3 个 NewApi？（影响 Android 8.0-8.1 用户，LSPosed 实际部署都在更高版本。）
- 是否准备好发正式版 `v1.0.0-Beta-2`？（CD 流水线已闭环验证，打 tag 即自动发布 Pre-release。）
- 正式版发 Release 时，`prerelease: true` 硬编码要不要改成根据 versionName 自动判断？
