# HANDOFF — 读全文再开始干活

生成时间: 2026-07-29T14:10:00Z · Git HEAD: 6616ca2
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复 GitHub CI/CD：让 CI 真能构建 APK + 去假绿 + 升级 actions 版本去 Node 20 deprecation +
配 release keystore secret + tag push 自动上传签名 APK 到 Release。

完成定义：tag push 后 GitHub Action 自动构建 release 签名 APK 并上传到 Release，lint 红绿反映
真实代码状态，APK 经 apksigner 验证为 release keystore 签名。**已达成。**

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `6616ca2`，工作树 clean，已 push 到 origin。
- [V] 本次改动文件：`.github/workflows/build.yml`（重写 + 加 keystore 解码 step）、
  `settings.gradle.kts`（Aliyun 条件化）、`app/build.gradle.kts`（lint baseline 配置）、
  `app/lint-baseline.xml`（新增）。
- [V] `./gradlew :app:lint --no-daemon` BUILD SUCCESSFUL（本地，lint baseline 锁住 3 个 NewApi）。
- [V] GitHub Actions run 30422457304 success（5m31s），所有 step ✓，产出 `app-release-apk` artifact，无 Node 20 deprecation 告警。
- [V] CI run 30422070544（Aliyun 502 修复后的首次成功）也是 success，证明解法稳定。
- [V] CI run 30458234235（push，keystore 解码 step 首次生效）success，`Decode release keystore` ✓，
  `Build release APK` ✓，APK 经 apksigner 验证 SHA-1=`133c43df1a9d2bbffdd300b29c999ce5296900fb`、
  SHA-256=`e0bc20a58d3c499360fd7a6e4de3155042bcb7151ba817507d61ffcac50de574`，
  与本地 `ala-mobile-tool.keystore` 指纹完全一致，确认为 release 签名（非 debug fallback）。
- [V] CI run 30458845844（tag `v1.0.0-Beta-2-test` push）success，`Upload to Release` step ✓（首次执行），
  Release `v1.0.0-Beta-2-test` 由 `github-actions[bot]` 创建，prerelease=true，Assets 含 `app-release.apk`，
  含自动生成的 changelog。事后已删 tag + release 清理，仓库恢复干净。
- [V] GitHub secret `KEYSTORE_BASE64` 已设（base64 编码的 `ala-mobile-tool.keystore`，3464 字符）。
  `KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 未设——走 Gradle 默认值 `alamobiletool`，与真实值一致。
- [V] HANDOFF.md 旧版已归档到 `.handoffs/20260729043835-handoff.md`。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:lint --no-daemon
> Task :app:lint
BUILD SUCCESSFUL in 17s

$ gh run list --limit 1
completed	success	ci: 清理诊断 step + 更新 HANDOFF 反映 CI 已修复	CI	main	push	30422457304	5m31s
```

CI run 30422457304 step 状态（全 ✓）：
- Set up job / Checkout / Set up JDK 21 ✓
- Ensure platforms;android-37 is available ✓
- Install NDK 26.1.10909125 ✓
- Run lint ✓ / Build release APK ✓
- Upload APK artifact ✓
- Upload to Release skipped（非 tag push，正确）

## 3. 决策与理由

- 复制 `android-37.0` → `android-37` + sed 改 4 处路径标识 [V]——runner 预装 `android-37.0`（ApiLevel=37，真 API 37 platform）。symlink 报 inconsistent location（Pkg.Path 不匹配），必须复制。否决方案：symlink，因 AGP 读 package.xml 的 `path` 属性推断目录名，不匹配就拒绝加载。
- sed 改 `ApiLevel=37.0` → `37`（整数）[V]——AGP 按整数比较，`37.0` 字符串解析后不等于 `37`，导致 60 个 AAR metadata issues。这是最隐蔽的坑。
- `settings.gradle.kts` 用 `System.getenv("CI") == null` 条件化 Aliyun 镜像 [V]——本地需要 Aliyun（Clash TUN 对 dl.google.com 的 TLS 干扰），CI 不需要（Aliyun 偶发 502 Bad Gateway）。否决方案：全删 Aliyun，因本地构建会因 TLS 失败；init script，因多余复杂度。
- lint baseline 锁 3 个 NewApi [V]——不影响 CI 修复任务的纯净性，后续单独修。否决方案：当场修业务代码，因夹带不相关改动；`abortOnError=false`，因失去 lint 的真实红绿信号。
- actions 全升级到最新大版本 [V]——checkout v4→v7、setup-java v4→v5、action-gh-release v2→v3、upload-artifact v4→v7。消除 Node 20 deprecation。

## 4. 失败的尝试 — 不要再试

- **方案 1：`android-actions/setup-android@v4` 装 `platforms;android-37`** [V]——`sdkmanager "platforms;android-37"` 报 `Warning: Failed to find package 'platforms;android-37'`，stable 和 `--channel=3` preview channel 都装不上。Google SDK 仓库未发布该包名。不要再试。
- **方案 2：symlink `android-37.0` → `android-37`** [V]——AGP 报 `Observed package id 'platforms;android-37.0' in inconsistent location '.../android-37' (Expected '.../android-37.0')`。symlink 保留 source.properties 的 `Pkg.Path=platforms;android-37.0`，AGP 用它推断目录名发现不匹配。必须复制 + 改路径标识。不要再试。
- **方案 3：只改 source.properties 不改 package.xml** [V]——source.properties 改对了（ApiLevel=37、Pkg.Path=platforms;android-37），但 package.xml 的 `<api-level>37.0</api-level>` 和 `path="platforms;android-37.0"` 没改，AGP 仍报 60 AAR metadata issues。4 处都要改。不要再试。
- **方案 4：YAML `- name: Fallback: symlink...`（name 值含冒号未加引号）** [V]——GitHub Actions workflow 0s 失败，无 jobs 生成。YAML 把 `Fallback: symlink` 解析成 nested mapping，`- name:` 的 value 变空。含冒号+空格的 scalar 值必须加引号。不要再试。
- **方案 5：旧 CI 的 `|| true` + `continue-on-error: true` 吞错** [V]——lint 实际有 3 个 NewApi error，但被吞成 success 假绿。已去掉。不要再试。
- **方案 6（搬运自旧 HANDOFF）：`onModuleLoaded` 立即 `markInitialized()`** [?]——自杀，会拦掉同一 ClassLoader 自己的后续回调。不要再试。
- **方案 7（搬运自旧 HANDOFF）：IPC 文件路径优化写法** [?]——`RandomAccessFile.seek+write` 非原子，`pread` 读半截数据。全试过全失败。不要再试。
- **方案 8（搬运自旧 HANDOFF）：`getLocationOnScreen()` 重建相对坐标** [?]——pairip 壳 relayout 时同样漂移。只有配置值稳定。不要再试。

## 5. 已知坑

- **AGP ApiLevel 整数比较** [V]——`AndroidVersion.ApiLevel=37.0`（浮点字符串）不等于 `37`（整数），AGP 按整数解析导致 60 个 AAR metadata issues。复制 platform 后必须 sed 改 `37.0` → `37`。
- **AGP inconsistent location 检查** [V]——AGP 读 package.xml 的 `path` 属性推断目录名，`path="platforms;android-37.0"` 但目录是 `android-37` → 拒绝加载。必须改 package.xml 的 `path` 属性 + source.properties 的 `Pkg.Path`。
- **Google SDK 仓库未发布 `platforms;android-37`** [V]——`sdkmanager` stable 和 preview channel 都装不上。但 GitHub runner 预装了 `android-37.0`（ApiLevel=37，真 API 37，Platform 17，PreviewSdkInt=0，IsBaseSdk=true），复制改路径标识可用。
- **Aliyun 镜像 CI 502** [V]——`maven.aliyun.com` 在 GitHub runner 上偶发 502 Bad Gateway，导致依赖解析失败。`settings.gradle.kts` 已用 `System.getenv("CI") == null` 条件化，CI 直连 Google Maven。
- **lint baseline 不锁 AGP AAR metadata 检查** [V]——baseline 只锁 lint 规则结果（NewApi 等），不锁 `checkDebugAarMetadata` task 的依赖解析阶段检查。AAR metadata 问题必须从根上修（platform 文件正确）。
- **miuix 0.9.3 AAR metadata `minCompileSdk=37` 硬要求** [V]——不能降 compileSdk 到 36 绕过。必须用 compileSdk=37 + 真 API 37 platform。
- **LSPosed 双 ClassLoader 注入** [?]（搬运）——共存版触发双注入，第二副本 hook 全失败但仍启动 writer 线程。必须 Java 层 `System.setProperty` 守卫。
- **pairip 壳 relayout 漂移** [?]（搬运）——`event.getY()` 和 `getLocationOnScreen()` 都漂移。必须用 `event.rawY - settings.pedalPosition.topPx()`。
- **keystore 不在 git** [?]（搬运）——CI 需配 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` secret，否则 fallback debug 签名。
- **module.prop versionCode 必须同步** [?]（搬运）——LSPosed 用 module.prop 识别模块更新。
- **游戏刹车辅助** [?]（搬运）——弯道自动刹车，表现为"突突突顿挫"，和模块无关。用户需在游戏设置里关掉。
- **GitHub Actions `if` 里不能直接引用 `secrets`** [?]（搬运）——必须先 `env: KS: ${{ secrets.X }}` 再 `if: ${{ env.KS != '' }}`。本次 keystore 解码 step 即用此模式（`KS_B64` 中转）。
- **keystore 解码 step 的 `KEYSTORE_PATH` 必须只在解码成功时才设** [V]——Gradle 的 fallback 判定是 `file("...keystore").exists() || System.getenv("KEYSTORE_PATH") != null`，只要 env 非 null 就判 true。若 step 无条件设 `KEYSTORE_PATH` 但文件解码失败/secret 未配，Gradle 会判 true 却找不到文件，`assembleRelease` 报 keystore not found 崩 CI。必须用 `if: env.KS_B64 != ''` 守卫整个解码 step。
- **`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 走 Gradle 默认值的隐式约束** [V]——Gradle `signingConfig` 对 storePassword/keyAlias/keyPassword 都用 `?: "alamobiletool"` fallback。当前 keystore 真实密码 = 默认值 `alamobiletool`，所以 CI 不设这两个 secret 也能签对。**若日后改 keystore 密码**，必须同步设 `KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` secret，否则签名会用错密码失败。

## 6. 下一步（有序）

1. ~~已完成~~ CI 现在真能构建 APK（lint + assembleRelease），tag push 时自动上传到 Release。
2. ~~已完成~~ 打测试 tag `v1.0.0-Beta-2-test` 验证 Release 自动创建 + APK 上传 + release 签名，已清理。
3. 在共存版和原版上各跑一次冒烟测试，确认 overlay 显示、踏板响应、换挡、billing 解锁都正常。
4. ~~已完成~~ 配 `KEYSTORE_BASE64` secret，CI 现产 release 签名 APK（apksigner 指纹已验证）。
5. （可选）修 lint baseline 锁住的 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode），让 baseline 清零。

## 7. 留给用户的开放问题

- ~~是否需要配 release keystore secret？~~ 已配，CI 产 release 签名 APK，apksigner 验证指纹一致。
- 是否需要修 lint baseline 锁住的 3 个 NewApi？（影响 Android 8.0-8.1 用户，LSPosed 实际部署都在更高版本。）
- 是否需要在共存版上进一步验证 billing 解锁的稳定性？（上次只验证了 overlay + 踏板 + 换挡。）
