# HANDOFF — 读全文再开始干活

生成时间: 2026-11-18T12:40:00+08:00 · Git HEAD: `68c42a9`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `68c42a9`（ala-mobile-tool 仓库；本次 handoff 提交的 parent）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `68c42a9`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 姊妹仓 `../ala-mobile-paddock` 本次也有提交：`c9d59cf`（已 push，`main...origin/main` 同步）[V]
- 待重探的 [?]: 见 §5
- 先读: 本次新增研究见 **`../ala-mobile-paddock/docs/BUILD_PERFORMANCE.md`**（491+ 行，服务端构建性能全部结论）

## 1. 当前目标
模块侧（本仓）已封板：debug 零改动增量 52s→**1s**，release 73s→**3s**，CI 6m58s→**4m25s**。
本轮工作全部在**姊妹仓 paddock（Rust 服务端）**：把 `docker build` 从 3m42s 降到 **2m00s**（cache mount），
并证明**剩余 2 分钟已无优化空间**（86% 在 fat LTO，物理下限）。

## 2. 已验证状态 — 工作实际停在哪

### 本仓（ala-mobile-tool）— 与上一轮交接一致，未改动 [V]
- 工作区干净，`main...origin/main` 同步，HEAD `68c42a9` [V] `git status --short --branch`
- BuildConfig 时间戳污染已根除（`36580dd`）：debug 增量 52s→1s，release 73s→3s [V]
- `buildDir` 已迁 `build-gradle/`，`build/` 下 31GB 逆向材料安全（标记文件 + 真跑 clean 实测）[V]
- CI 全绿：run `44037512983` success，4m25s [V]

### 姊妹仓（ala-mobile-paddock）— 本轮全部工作 [V]
- **Dockerfile**：`RUN --mount=type=cache,target=/build/target,id=paddock-target` + registry cache mount + 产物 cp 到 `/build/out`。`docker build --check` → **no warnings found** [V]
- **效果**：改一行 3m42s → **2m00s**（−45%），产物字节级不变 [V]
- **文档**：`docs/BUILD_PERFORMANCE.md`（491+ 行）已提交并 push [V]
- **提交**：`30938c4`（Dockerfile）→ `2a56537`（文档）→ `c9d59cf`（thin LTO 定案）[V] `git log --oneline -3`

### 本轮实测的关键数字 [V]
```
宿主 gnu fat LTO（干净窗口 load 1.5~2.1）：58.2 / 61.3 / 66.6s
容器 musl fat LTO（-Z time-passes）：~103s，其中 LTO 段 84%
容器 musl thin LTO（-Z time-passes）：~129s，LLVM_thinlto 73.9s > LLVM_fatlto 60.3s
docker build 改一行：113~143s（load 1.98~2.35）
docker build 全量（冷 cache）：223s
```

## 3. 决策与理由
- **用 cache mount 而非 cargo-chef / sccache** [V]——三者的缓存前提（多仓/CI/多分支）本项目全无；实测 sccache 反慢 8%（2m02→2m12）。否决方案：cargo-chef，叠加 stage 与 recipe 失效点。
- **维持 `[profile.release]` 原样（z + fat + cgu1 + strip）** [V]——四参数逐一实测：`opt-level` z 最快（同窗口 3×3：z 69.2s vs s 73.3s）；`cgu=16` 单独改是**负收益**（codegen 5.2s→10.4s）；`lto=thin` 体积 +41% 且容器内更慢；`strip` 无空间。
- **否决换 glibc 基础镜像** [V]——容器内 `LLVM_thinlto 73.9s` 比 `LLVM_fatlto 60.3s` 还慢 23%，说明是 thin LTO 在 musl target 下执行更久，不是分配器/调度问题；换 glibc rustc 编译 musl target 产出仍是 musl 二进制，救不了。
- **用户纠正①「可是你之前每次编译都要几分钟」** [V]——我先前测的是本地 `cargo build`（2.5s），用户真正在等的是 `docker build`（3m42s）。**必须区分优化对象**。
- **用户纠正②「什么意思，提升不了吗？」** [V]——我说「已到物理下限」范围太宽。准确边界是：**fat LTO 那 86% 无解**，不是整个构建无解（cache mount 就砍掉了 45%）。

## 4. 失败的尝试 — 不要再试
- **换链接器（lld / mold / wild）** [V]——`run_linker` 仅 0.406s(宿主)/0.74s(容器)，占 0.5%。mold 最快也只省 0.54s，且 `RUSTFLAGS` 会让 301 依赖全量重编。**上限 0.7 秒**。
- **`codegen-units` 1→16** [V]——实测 codegen **5.2s→10.4s（负收益）**；`cgu=1` 也从不是 fat LTO 的语义要求（官方文档无此说法，串行根因是合并成单 module 送一个 backend job）。
- **`opt-level` z→s** [V]——曾出现「s 快 20%」，是 load 12+ 窗口的假象；同窗口 3×3 对照 **z 快 6%**。
- **`lto="thin"`（Docker 路径）** [V]——同窗口 A/B：fat 113/125s vs thin 119/118s（打平）；容器内 time-passes 显示 thinlto pass 本身慢 23%。镜像 +3.2MB。
- **Cranelift** [V]——`lto=true` 在源码里是 `sess.dcx().fatal()`，一行编不过；且 nightly-only、`opt-level="z"` 语义被丢弃、`panic=abort` 默认开。
- **sccache / kache / Hurry** [V]——`bin` crate 结构上不缓存（crate-type 判据）；Hurry 还硬阻塞在只支持 Rust ≤1.92。
- **Nix / Bazel / Buck2** [V]——卖点是多仓/CI/多分支产物共享，本项目一个都没有；Buck2 官方明确「还没有 release 模式机制」。
- **parallel frontend** [V]——只加速前端 3.8s，`LLVM_fatlto` 一秒不动；未进任何 stable。
- **升 toolchain 1.100** [V]——build-dir layout v2 只对多 features/worktree 共享有用，本项目全不适用；代价是一次性全量重编 + crater 回归 rust-lang/rust#164098。
- **并发跑多个 agent 构建** [V]——同一配置两轮差 1.48 倍（61.6s@load4.78 vs 91.5s@load9.76）；本轮多次数据失真最高 1.5 倍，全部作废重测。
- **`opts`/`opt3` 的全量对比** [V]——`s` 全量 177s 最慢，`3` 168s 且体积 +37.5%；方向反了。

## 5. 已知坑
- ⚠️ **BuildKit 默认 GC 会在 48h / 13GiB 时静默清掉 cache mount** [V]——本机 `docker buildx inspect --bootstrap` 实测 rule#0 的 Filters 含 `type==exec.cachemount`。**不需要跑任何 prune**。日常替代：`docker buildx prune --filter "type!=exec.cachemount"`。
- ⚠️ **`--mount=type=cache` 默认 id 就是 target 路径** [V]——实测 `id "//build/target"`。已改为显式 `id=paddock-target`，避免将来被别的 Dockerfile 静默共用。
- ⚠️ **容器 musl 路径的 linker 是 GNU ld 2.45.1，不是 rust-lld** [V]——宿主 gnu 路径才用 rust-lld。且镜像自带的 rust-lld **编译时没开 zlib**，链接本项目直接报 `ELFCOMPRESS_ZLIB` 错误。Alpine 仓库里最高只有 lld22（22.1.3，比捆绑的还旧）。
- ⚠️ **本机构建耗时噪声底 ±5~9%** [V]——同样 load（2.11 vs 2.08）下测出 61.3s vs 66.6s。**小于 10% 的差异测不出来**，报数字必须带 load average。
- ⚠️ **`/tmp` 是 tmpfs** [V]——构建 benchmark 的 target dir 必须放 `/home`（`/dev/sdd` 真盘），否则吃 10GB 内存上限。
- ⚠️ 继承项（本仓，仍未复核）[?]：`cacheKey = avatarUrl` 改 URL 形态会让全量头像缓存失效；`AVATAR_SIDE = 256` 双处（`avatar_migrate.rs:116` 与 `AvatarCompress.kt:67`）；61 个 `avatar_version = 1` 异常值；VPS 时钟慢 3 分钟 / 魅族 20 IP 会变（`.142`↔`.123`）/ `best_laps` 是物化快照须重算。

## 6. 下一步（有序）
1. **（待你决定）paddock 服务端发版**：本轮构建优化属工程内部改动，用户无感知，不需要单独发版；但它已推送到 `main`，下次发版自然带上。
2. **（待你决定）模块发版**：先前两个头像 bug 修复仍待发版决策（版本号是你的决策域，当前 `1.0.5 Alpha 1` / `105110`）。
3. **（待你决定）`dev` / `staging` 变体是否保留**——不用可撤，减少维护面。
4. 继承待办见 `.handoffs/20261117152346-handoff.md` §6（120FPS 刷新率层 / LogExporter 段缺失静默 / TG 播报提示 / 服务端发版 / 应用调教回写 / 历史研究成果回收）——**均未动**。

## 7. 留给用户的开放问题
- 发不发版？版本号定多少？（模块侧；paddock 侧无需单独发版）
- `dev` / `staging` 变体要留着吗？
- 20GB 级逆向产物要不要另做备份（`build/` 现在安全但磁盘单点）？
- paddock 构建已到物理下限（fat LTO 86%），是否接受现状？唯一未试的方向是「放弃 fat LTO 换启动速度」，但那会牺牲运行性能，不推荐。
