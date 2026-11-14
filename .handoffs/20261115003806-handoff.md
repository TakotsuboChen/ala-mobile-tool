# HANDOFF — 读全文再开始干活

生成时间: 2026-11-14T21:46:15+08:00 · Git HEAD: 5dcc15d
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `5dcc15d`（2026-11-14 21:46）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `5dcc15d`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5
- 先读: `docs/design/AVATAR_OSS.md`（本次全部结论的单源）

## 1. 当前目标
头像迁移到阿里云 OSS **已完成上线**。读路径已从「VPS 代理」切成「客户端直连」，
全站头像流量不再经过服务器。**写路径（上传）按用户决定暂不做直连。**

## 2. 已验证状态 — 工作实际停在哪

- **OSS 直连生效** [V] 生产 `/v1/leaderboard/points` 返回的 `avatar_url` 已是
  `https://ala-mobile-paddock.oss-cn-hangzhou.aliyuncs.com/avatars/{uuid}/{v}.jpg`
- **145 张头像抽查全部可下载** [V] `urllib` 循环请求 145/145 成功，0 失败；
  体积 min 2419B / p50 20503B / max 46851B（压缩前 p50 197KB）
- **手机端实测正常** [V] MEIZU 20 模块日志 21:37 起 `MISS, downloading https://...oss...`
  共 145 条，磁盘缓存新增 145 个文件、总量 4.4MB；用户确认「测试正常」
- **服务端测试** [V] `cargo test -p paddock-api` → `64 passed; 0 failed`，
  `cargo build` exit=0
- **模块端构建** [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL，
  `compressAvatar` 在 `classes10.dex` 命中 3 次；`:app:lint` 0 error
- **生产镜像** `paddock-api:1.0.1-oss8`（VPS `.env` 的 `PADDOCK_IMAGE`）
- 工作区: 两仓均已提交并 push，干净

### 关键数字（生产实测）
| 项 | 值 |
|---|---|
| 头像总数 | 231（全部已在 OSS 上，HeadObject 探测 Ok(true)） |
| 压缩效果 | p50 197KB → 20KB，降约 90% |
| 原 `/v1/avatar/*` 占全站出流量 | 97.0%（17.04GB / 17.56GB） |
| VPS | 阿里云广州 `8.134.50.222`；bucket 在**杭州** |

## 3. 决策与理由

- **读直连、写不直连** [V]——用户明确定案。写入引流仅占 0.003%（圈速上传全期
  0.5MB），且写直连需发新版模块。服务端已留接口位。
- **URL 切换用 `configs.oss_migrated` 标记** [V]——**不能用 key 字符串形态判断**。
  key 形态由代码写死、与对象实际位置无关（详见 §4 第一条死路）。
- **版本号编进路径** `avatars/{uuid}/{v}.jpg`，不用 `?v=` [V]——OSS 查询参数是
  白名单式的（`versionId`/`response-*`），无「忽略未知参数」的承诺。
- **迁移零失败才写标记** [V]——有失败时切 URL 会让那部分头像 404。
- **OSS V4 独立实现，不与 Garage 的 AWS SigV4 合并** [V]——三处实质差异：
  `host` 不参与签名 / `x-oss-content-sha256` 只收 `UNSIGNED-PAYLOAD` /
  密钥前缀 `aliyun_v4`。官方测试向量逐字节验证通过。
- **`image` crate 只开 jpeg+png** [V]——镜像 20.4MB → 21.1MB，仅 +0.7MB。

## 4. 失败的尝试 — 不要再试

- [X] **用 `key` 字符串形态判断「对象在哪」**——`is_migrated_key()` 认为
  `avatars/{uuid}/{v}.jpg` = 已迁移。但新 `upload_avatar` 一上线就往 **Garage**
  写新形态 key ⇒「新形态 key + 对象在 Garage」⇒ 231 张全部误判、一张没搬，
  且 URL 指向 OSS ⇒ 全站 404。**判「有没有」必须发 HeadObject，别查字段格式。**
- [X] **用「OSS 配置项是否齐全」判断能否直连**——配好 OSS 但没迁移时，对象还在
  Garage，直接下发 OSS URL ⇒ 全站 404（中间态可长达数天）。
- [X] **从 `key` 末段反解版本号**——存量 key 末段是 UUID，得到 `?v=01a06d14-...`。
  不报错不 404，但缓存失效语义永久错误（换头像后客户端一直显示旧图）。
  必须从 `users.avatar_version` 列读。
- [X] **Canonical URI 只写 `/{key}`**——OSS 签名的 Canonical URI **必须含 bucket**，
  即使走 virtual-hosted（HTTP 路径只有 `/{key}`）。漏了 ⇒ 403 SignatureDoesNotMatch。
  官方向量 `/examplebucket/exampleobject` 就是这个意思。
- [X] **迁移模块 S3 URL 写 `{ep}/avatars/{key}`**——key 已含 `avatars/` 前缀 ⇒
  `avatars/avatars/...` ⇒ Garage 404。
- [X] **追加 `<script>` 用 `rfind('</script>')` 定位**——模板已有多个脚本块时命中的
  不是想的那一个，`migStart` 落在标签外被当纯文本 ⇒ 按钮完全无反应。
- [X] **轮询函数复用外部局部变量 `el`**——每轮 `ReferenceError` 被
  `catch { /* 静默 */ }` 吞掉 ⇒ 进度条不动。轮询必须自取 DOM。
- [X] **`api()` 当 `{ok,message}` 对象用**——它是「成功返回字符串 / 失败抛异常」。
  成功路径因此显示「失败：undefined」。
- [X] **先上生产再看 bucket 权限**——bucket 私有读时服务端带签名一切正常
  （测试连接通过、迁移成功），客户端匿名访问全 403。**部署前先匿名 curl 一个对象。**
- [X] 继承死路（详 `.handoffs/20261114214606-handoff.md` §4 及更早，含 120FPS
  与更早期全部条目，**一条未删**）

## 5. 已知坑

- ⚠️ `cacheKey = avatarUrl` ⇒ **任何改头像 URL 形态的动作都会让全量缓存失效**
  [V] 本次从相对切绝对时实测重下 145 张。未来换域名/换 key 格式前要预期这个代价。
- ⚠️ **bucket 必须公共读** [V]——模块端没有 AK/SK，只能匿名 GET。
- ⚠️ **`avatar_migrate.rs` 与 `AvatarCompress.kt` 各有一个 `AVATAR_SIDE = 256`**
  [?] 改必须两边同改（代码里已加注释，但无编译期约束）。
- ⚠️ **`decodeAvatarScaled` 阈值 168 与上传尺寸 256 绑定** [V]——上传压到 256 后
  `inSampleSize` 从 16 掉到 1，解码 192→256px，memAvatarCache 反而 37MB→67MB。
  改上传尺寸必须重算这个阈值。
- ⚠️ **61 个 `avatar_version = 1` 的异常值** [?]（正常应是 epoch millis）——
  对象存在且可访问，不影响功能，未清理。
- ⚠️ **前缀继承项** [?]：VPS 时钟慢 3 分钟 / 魅族 20 IP 会变（`.142`↔`.123`）/
  `best_laps` 是物化快照须重算 / 覆盖重发后同版本用户收不到更新提示。

## 6. 下一步（有序）

1. **（待你决定）模块端三项改动是否发版**：256 压缩 / 100MB 缓存 / 解码阈值
   168 是一套，一起发才有意义。已提交在 `ad8d613`，未发版。
2. **（待你决定）写直连**：服务端已留接口位（`avatar.rs` 的双后端结构），
   需加 `/v1/me/avatar/prepare` 签 presigned PUT + `/confirm` 发号，模块改
   `uploadAvatar` 三步走。收益是流量 0.003%，价值在架构纯度。
3. **（待你决定）清理 61 个 `avatar_version=1` 的异常值**。
4. **（待你决定）Garage 收尾**：迁移已完成，可关掉 garage 容器（保留数据卷）。
   compose 里 `GARAGE_*` 环境变量与 `NO_PROXY` 的 `garage` 可一并清。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。
6. 继承的待办见 `.handoffs/20261114214606-handoff.md` §6（120FPS 刷新率层 /
   LogExporter 段缺失静默 / TG 播报提示 / 服务端发版 / 应用调教回写）——均未动。

## 7. 留给用户的开放问题

- 模块三项改动何时发版？（版本号是你的决策域）
- 写直连要不要做？
- Garage 现在可以关掉，还是想再观察几天？
- 61 个 `avatar_version=1` 要不要归一化？
