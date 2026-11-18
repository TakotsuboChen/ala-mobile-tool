# HANDOFF — 读全文再开始干活

生成时间: 2026-11-17T15:24:00+08:00 · Git HEAD: `4e9dc91`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `4e9dc91`（2026-11-17 15:20，已 push，CI 全绿）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `4e9dc91`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA；权威以 `git log --oneline -8` 实际输出为准
- 待重探的 [?]: 见 §5
- 先读: [`docs/notes/BUILD_PERFORMANCE.md`](docs/notes/BUILD_PERFORMANCE.md)（本轮全部原始数据）

## 1. 当前目标
把模块编译速度压到极致。**已完成并封板**：debug 零改动增量 52s → **1s**，
release 零改动增量 73s → **3s**，CI 6m58s → **4m25s**。

## 2. 已验证状态 — 工作实际停在哪

- **BuildConfig 时间戳污染已根除** [V] `36580dd`：`System.currentTimeMillis()` →
  `git log -1 --format=%ct`。实测量化（连续两次一致）：debug 零改动增量
  **52s（8 tasks executed）→ 1s（2 executed）**；release 73s → 3s。
  `--info` 铁证：`Value of input property 'items' has changed`。
- **dev / staging 变体已落地** [V] `36580dd`：`dev`（`isMinifyEnabled=false`，纯 D8）
  真正重建 **15s**；`staging`（`-dontoptimize -dontobfuscate`，保留 shrink）**50s**。
- **buildDir 已迁到 `build-gradle/`** [V] `89a7042` + `4e9dc91`：`build/` 下 72 项 /
  31GB 逆向材料现在安全。**验证方式不是推理**：放标记文件 → 真跑 `./gradlew clean`
  → 标记幸存、31GB 完好、`app/build` 正常被清。
- **CI wrapper 校验失败已修** [V] `a85c614`：原 `gradle-wrapper.jar` 的 sha256
  `0fa1d4ba…` 不在 Gradle 官方 checksum 库，用 `./gradlew wrapper` 重新生成后
  = `497c8c2a…`，与 `services.gradle.org` 逐字节一致。
- **CI 全绿** [V] run `44037512983` success，4m25s（对比无缓存基线 6m58s）。
- 工作区: **干净**，`main...origin/main` 无未提交改动。

### 测试/build 输出（本次交接前的真实输出）
```
./gradlew :app:assembleDebug  → BUILD SUCCESSFUL in 1s, 40 tasks: 2 executed, 38 up-to-date
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 3s, R8 FROM-CACHE
./gradlew :app:lint → BUILD SUCCESSFUL, 68 warnings / 0 新增 error（2 errors 被 baseline 过滤）
gh run watch 44037512983 → success（lint ✓ / Build release APK ✓ / Rename APK ✓ / Upload ✓）
```

## 3. 决策与理由

- **构建时间改用 commit 时间而非删掉** [V]——`GIT_SHA` 已能定位构建，精确到秒无额外诊断价值；
  代价是同一 commit 内多次构建的「构建时间」相同。需精确时刻用 `-PbuildTimeMillis` 注入。
- **保留两个非发布变体而非一个** [V]——`dev` 因不跑 shrink **测不出 keep 规则误删反射类**，
  `staging` 才能。两者互补，不是冗余。
- **改 Gradle 指向而非搬 31GB 数据** [V]——搬运有风险且耗时，改 buildDir 零风险。
- **只改 rootProject 的 buildDir** [V]——`app/build/` 是纯 Gradle 产物，不需要改；
  改了会破坏 CI 与所有文档记录的产物路径。

## 4. 失败的尝试 — 不要再试

- [X] **`allprojects { layout.buildDirectory.set(...) }`** —— 把 `:app` 也改了，
  本地因残留旧 APK 没暴露，**CI 立即失败**：`mv: cannot stat
  'app/build/outputs/apk/release/app-release.apk'`。注释里那句「app/ 子项目不受影响」
  是凭直觉写的，未验证且错误。**必须用根 build script 的隐式 receiver**（`rootProject {}` 不是有效 DSL，实测脚本编译失败）。
- [X] **R8 五种加速手段**（全部实测无效，原始数据见 `BUILD_PERFORMANCE.md` §4.1）：
  `enableR8.fullMode=false`（81 vs 81）、`-Xmx7g`（80 vs 81，RSS 稳定 3GB 未触顶）、
  `-XX:+UseTransparentHugePages`（73 vs 73，各 3 次）、`android.r8.gradual.support=true`
  （53 vs 50）、`android.r8.threadPoolSize`（默认已是 12 线程）。**采样实证：R8 运行时
  115 个线程里只有 1 个 RUNNABLE、CPU 260~350%** —— 全程序分析天然串行，物理下限。
- [X] **换掉 `material-icons-extended`** —— 11,105 个 class / 36MB，但**零 `@Composable` 注解**，
  Kotlin 按需加载，R8 A/B 实测仅 +0.3~1.1s。换掉需自绘 **34 个图标**。性价比不成立。
- [X] **拆模块加速** —— `parallel` 只在模块间并行；拆模块把文件粒度换成模块粒度，
  社区实测 3 模块改 library 反而慢 40s。
- [X] **WSL2 里设 `scaling_governor`** —— `/sys/devices/system/cpu/cpu0/cpufreq/` **不存在**，
  WSL2 是 Hyper-V 虚拟机，cpufreq 属宿主 Windows。
- [X] **「Gradle UP-TO-DATE 谎报」** —— 实测 Gradle 按**内容哈希**判定（`touch` 不触发重跑），
  它没谎报；是 `buildConfigField` 污染了输入。CLAUDE.md 那条旧记载已改。
- [X] **第三方 AI 的三条错误说法**（已逐条实测证伪）：`proguard-android.txt` 已移除（实际存在
  `-9.3.1`）／`isMinifyEnabled` 已废弃（**未标 `@Deprecated`**）／`multiDexKeepFile` 能修
  16 个 dex（治错了病，minSdk 26 原生 multidex）。
- [X] 继承死路见 `.handoffs/20261117152346-handoff.md` §4（头像 OSS 九条，**一条未删**）。

## 5. 已知坑

- ⚠️ **并发 Gradle daemon 会搞挂 WSL2** [V]——本会话实测：4~5 个 daemon × 2.84GB RSS
  撞穿 10GB 上限，WSL2 失去响应，用户被迫重启。见 memory `concurrent-gradle-daemons-oom-wsl`。
- ⚠️ **冷热态构建数字差 13 倍** [V]——`compileDebugKotlin` 冷 2m13s / 热 10.2s。
  报结论必须标 daemon 温度。见 memory `cold-vs-warm-build-numbers`。
- ⚠️ **CI 的 `gradle/actions/setup-gradle@v6` 只在 CI 环境验证过** [?]——本地无法复现；
  已实跑通过一次，但后续若换 runner 镜像需重新确认。
- ⚠️ **`staging` / `dev` 变体的长期维护成本未验证** [?]——每次改 `buildTypes` 要同步三处。
  若觉得碍事可撤掉，`release` 与 `debug` 不受影响。
- ⚠️ **`cacheKey = avatarUrl`** [?]（继承）——改 URL 形态会让全量头像缓存失效，实测重下 145 张。
- ⚠️ **`AVATAR_SIDE = 256` 双处** [?]（继承）——`avatar_migrate.rs:116` 与 `AvatarCompress.kt:67`，
  无编译期约束。本会话未复核。
- ⚠️ **61 个 `avatar_version = 1` 异常值** [?]（继承）——不影响功能。
- ⚠️ **前缀继承项** [?]：VPS 时钟慢 3 分钟 / 魅族 20 IP 会变（`.142`↔`.123`）/ `best_laps`
  是物化快照须重算 / 覆盖重发后同版本用户收不到更新提示。

## 6. 下一步（有序）
1. **（待你决定）模块发版**：本会话的构建优化属工程内部改动，用户无感知；
   先前两个头像 bug 修复仍待发版决策（版本号是你的决策域，当前 `1.0.5 Alpha 1` / `105110`）。
2. **（待你决定）`dev` / `staging` 变体是否保留**——不用可撤，减少维护面。
3. **（待你决定）初始化汇总里列的插件/文档外移项**——本会话未动。
4. 继承待办见 `.handoffs/20261117152346-handoff.md` §6（120FPS 刷新率层 / LogExporter 段缺失
   静默 / TG 播报提示 / 服务端发版 / 应用调教回写 / 历史研究成果回收）——**均未动**。

## 7. 留给用户的开放问题
- 发不发版？版本号定多少？
- `dev` / `staging` 变体要留着吗？
- 20GB 级逆向产物要不要另做备份（`build/` 现在安全但磁盘单点）？
