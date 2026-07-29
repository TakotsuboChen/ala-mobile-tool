# HANDOFF — 读全文再开始干活

生成时间: 2026-07-29T14:11:37Z · Git HEAD: db98287
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 GitHub CI/CD：让 CI 真能构建 release 签名 APK + tag push 自动上传到 Release +
冒烟验证 overlay/踏板/换挡/billing 在真机正常。**全部已达成。**

完成定义：tag push 后 CI 自动构建 release 签名 APK 上传到 Release，lint 红绿反映真实
代码状态，APK 经 apksigner 验证为 release keystore 签名，真机冒烟四项全过。**已达成。**

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `db98287`，工作树 clean，已 push 到 origin。
- [V] CI 修复全链路改动文件：`.github/workflows/build.yml`（重写 + keystore 解码 step）、
  `settings.gradle.kts`（Aliyun 条件化）、`app/build.gradle.kts`（lint baseline）、
  `app/lint-baseline.xml`（新增）。
- [V] `./gradlew :app:lint --no-daemon` BUILD SUCCESSFUL（本地，lint baseline 锁住 3 个 NewApi）。
- [V] CI run 30422457304（push）success，所有 step ✓，产出 `app-release-apk` artifact，无 Node 20 deprecation。
- [V] CI run 30458234235（push，keystore 解码 step 首次生效）success，`Decode release keystore` ✓，
  APK 经 apksigner 验证 SHA-1=`133c43df1a9d2bbffdd300b29c999ce5296900fb`、
  SHA-256=`e0bc20a58d3c499360fd7a6e4de3155042bcb7151ba817507d61ffcac50de574`，
  与本地 `ala-mobile-tool.keystore` 指纹完全一致，确认 release 签名（非 debug fallback）。
- [V] CI run 30458845844（tag `v1.0.0-Beta-2-test` push）success，`Upload to Release` ✓（首次执行），
  Release 由 `github-actions[bot]` 创建，prerelease=true，Assets 含 `app-release.apk`，事后已删 tag+release 清理。
- [V] GitHub secret `KEYSTORE_BASE64` 已设（base64 编码的 keystore，3464 字符）。
  `KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 未设——走 Gradle 默认值 `alamobiletool`，与真实值一致。
- [?] 真机冒烟测试（用户报告，未由本次命令验证）：共存版和原版 overlay 显示、踏板响应、换挡、
  billing 解锁四项全正常。这是 HANDOFF 第 6 节第 3 步的验证结论。
- [V] 旧 HANDOFF 已归档到 `.handoffs/20260729141137-handoff.md`。

### 测试/build 输出 tail（本次交接的真实输出）

```
$ gh run list --limit 3
completed  success  docs: HANDOFF 反映 keystore...  CI  main  push  (handoff commit 触发)
completed  success  ci: 加 release keystore 解码 step CI  v1.0.0-Beta-2-test  push  30458845844  5m32s
completed  success  ci: 加 release keystore 解码 step CI  main  push  30458234235  5m10s

$ ~/android-sdk/build-tools/36.1.0/apksigner verify --print-certs .../app-release.apk
Signer #1 certificate DN: CN=AlaMobileTool
Signer #1 certificate SHA-256: e0bc20a58d3c4993...c50de574   ← 与本地 keystore 一致
```

## 3. 决策与理由

- 复制 `android-37.0` → `android-37` + sed 改 4 处路径标识 [V]——runner 预装 `android-37.0`（ApiLevel=37，真 API 37 platform）。symlink 报 inconsistent location。否决：symlink，因 AGP 读 package.xml `path` 属性推断目录名。
- sed 改 `ApiLevel=37.0` → `37`（整数）[V]——AGP 按整数比较，`37.0` 不等于 `37`，导致 60 个 AAR metadata issues。
- `settings.gradle.kts` 用 `System.getenv("CI") == null` 条件化 Aliyun 镜像 [V]——本地需要 Aliyun（Clash TUN TLS 干扰），CI 不需要（Aliyun 偶发 502）。
- lint baseline 锁 3 个 NewApi [V]——不夹带不相关业务改动，后续单独修。否决：`abortOnError=false`，因失去 lint 真实红绿信号。
- actions 全升级到最新大版本 [V]——checkout v4→v7、setup-java v4→v5、action-gh-release v2→v3、upload-artifact v4→v7。消除 Node 20 deprecation。
- keystore 解码 step 用 `if: env.KS_B64 != ''` 守卫 + env 中转 secret [V]——规避 if 不能直接读 secrets 坑，且 KEYSTORE_PATH 只在解码成功时才设。否决：无条件设 KEYSTORE_PATH，因 Gradle fallback 判 env!=null 会判 true 却找不到文件崩 CI。
- `KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 不设 secret 走 Gradle 默认值 [V]——真实密码 = 默认值 `alamobiletool`，减少 secret 暴露面。隐式约束：改 keystore 密码必须同步设这两个 secret。

## 4. 失败的尝试 — 不要再试

- **`android-actions/setup-android@v4` 装 `platforms;android-37`** [V]——`sdkmanager` stable 和 `--channel=3` preview 都装不上。Google SDK 仓库未发布该包名。不要再试。
- **symlink `android-37.0` → `android-37`** [V]——AGP 报 `Observed package id 'platforms;android-37.0' in inconsistent location '.../android-37' (Expected '.../android-37.0')`。必须复制 + 改路径标识。不要再试。
- **只改 source.properties 不改 package.xml** [V]——package.xml 的 `<api-level>37.0</api-level>` 和 `path="platforms;android-37.0"` 没改，AGP 仍报 60 AAR metadata issues。4 处都要改。不要再试。
- **YAML `- name: Fallback: symlink...`（name 值含冒号未加引号）** [V]——workflow 0s 失败，无 jobs 生成。含冒号+空格的 scalar 值必须加引号。不要再试。
- **旧 CI 的 `|| true` + `continue-on-error: true` 吞错** [V]——lint 实际有 3 个 NewApi error 但被吞成 success 假绿。已去掉。不要再试。
- **`onModuleLoaded` 立即 `markInitialized()`** [?]——自杀，会拦掉同一 ClassLoader 自己的后续回调。不要再试。
- **IPC 文件路径优化写法** [?]——`RandomAccessFile.seek+write` 非原子，`pread` 读半截数据。全试过全失败。不要再试。
- **`getLocationOnScreen()` 重建相对坐标** [?]——pairip 壳 relayout 时同样漂移。只有配置值稳定。不要再试。

## 5. 已知坑

- **AGP ApiLevel 整数比较** [V]——`AndroidVersion.ApiLevel=37.0`（浮点字符串）不等于 `37`（整数），AGP 按整数解析导致 60 个 AAR metadata issues。复制 platform 后必须 sed 改 `37.0` → `37`。
- **AGP inconsistent location 检查** [V]——AGP 读 package.xml `path` 属性推断目录名，不匹配拒绝加载。必须改 package.xml `path` + source.properties `Pkg.Path`。
- **Google SDK 仓库未发布 `platforms;android-37`** [V]——但 GitHub runner 预装 `android-37.0`（ApiLevel=37，真 API 37），复制改路径标识可用。
- **Aliyun 镜像 CI 502** [V]——`settings.gradle.kts` 已用 `System.getenv("CI") == null` 条件化，CI 直连 Google Maven。
- **lint baseline 不锁 AGP AAR metadata 检查** [V]——baseline 只锁 lint 规则结果，不锁 `checkDebugAarMetadata`。AAR metadata 问题必须从根上修（platform 文件正确）。
- **miuix 0.9.3 AAR metadata `minCompileSdk=37` 硬要求** [V]——不能降 compileSdk 到 36 绕过。
- **LSPosed 双 ClassLoader 注入** [?]——共存版触发双注入，第二副本 hook 全失败但仍启动 writer 线程。必须 Java 层 `System.setProperty` 守卫。
- **pairip 壳 relayout 漂移** [?]——`event.getY()` 和 `getLocationOnScreen()` 都漂移。必须用 `event.rawY - settings.pedalPosition.topPx()`。
- **keystore 不在 git** [V]——`.gitignore` 排除 `*.keystore`。CI 靠 `KEYSTORE_BASE64` secret 注入。
- **module.prop versionCode 必须同步** [?]——LSPosed 用 module.prop 识别模块更新。
- **游戏刹车辅助** [?]——弯道自动刹车，表现为"突突突顿挫"，和模块无关。用户需在游戏设置里关掉。
- **GitHub Actions `if` 里不能直接引用 `secrets`** [V]——必须先 `env: KS: ${{ secrets.X }}` 再 `if: ${{ env.KS != '' }}`。keystore 解码 step 即用此模式（`KS_B64` 中转）。
- **keystore 解码 step 的 `KEYSTORE_PATH` 必须只在解码成功时才设** [V]——Gradle fallback 判 `env != null` 为 true，若无条件设但文件不存在会崩。必须用 `if: env.KS_B64 != ''` 守卫整个 step。
- **`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 走 Gradle 默认值的隐式约束** [V]——真实密码 = 默认值 `alamobiletool`，CI 不设这两个 secret 也能签对。**若日后改 keystore 密码**，必须同步设这两个 secret，否则签名失败。

## 6. 下一步（有序）

1. ~~已完成~~ CI 真能构建 APK（lint + assembleRelease），tag push 时自动上传到 Release。
2. ~~已完成~~ 打测试 tag 验证 Release 自动创建 + APK 上传 + release 签名，已清理。
3. ~~已完成~~ 真机冒烟测试：共存版和原版 overlay/踏板/换挡/billing 四项全正常（用户报告 [?]）。
4. ~~已完成~~ 配 `KEYSTORE_BASE64` secret，CI 产 release 签名 APK（apksigner 指纹已验证）。
5. （可选）修 lint baseline 锁住的 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode），让 baseline 清零。影响 Android 8.0-8.1 用户，LSPosed 实际部署都在更高版本。
6. （可选）发正式版：打 `v1.0.0-Beta-2` tag，CI 自动创建 prerelease + 上传 release 签名 APK。

## 7. 留给用户的开放问题

- ~~是否需要配 release keystore secret？~~ 已配，CI 产 release 签名 APK，apksigner 验证指纹一致。
- ~~是否需要在共存版上进一步验证 billing 解锁？~~ 已验证，四项全正常（用户报告 [?]）。
- 是否需要修 lint baseline 锁住的 3 个 NewApi？（影响 Android 8.0-8.1 用户，LSPosed 实际部署都在更高版本。）
- 是否准备好发正式版 `v1.0.0-Beta-2`？（CD 流水线已闭环验证，打 tag 即自动发布。）
