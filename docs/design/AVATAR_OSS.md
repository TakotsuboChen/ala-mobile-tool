# 头像对象存储（阿里云 OSS 直连）

> 状态：活跃 · 最后编纂：2026-11-14
> 一句话结论：**读走客户端直连 OSS（省掉 97% 的 VPS 出口流量），写仍走 VPS**；AK/SK 从头到尾不出服务器。

## 1. 结论（发现）

### 1.1 流量构成决定了优化目标 [V]

生产实测（全期累计）：

| 端点 | 流量 | 占比 |
|---|---|---|
| `/v1/avatar/*`（读） | 17.04 GB | **97.0%** |
| 其余全部端点 | 0.52 GB | 3.0% |
| 其中 `/v1/laps`（圈速上传） | 0.5 MB | 0.003% |

⇒ 唯一值得优化的是**读**。写（头像上传、圈速上传）加起来不到 0.01%。

### 1.2 老模块天然支持绝对 URL，读直连零客户端改动 [V]

`PaddockClient.kt:1593`：

```kotlin
val url = if (avatarUrl.startsWith("http")) avatarUrl else "$serverBase$avatarUrl"
```

这一行自 2026-09-01（commit `3b26a11`）就存在。因此**服务端把相对路径换成 OSS 绝对 URL，
全部存量用户立刻直连 OSS，不需要发新版模块**。

### 1.3 AK/SK 不必进客户端：签名 ≠ 密钥 [V]

「客户端直连对象存储」与「客户端持有密钥」是两件独立的事。两条路都能做到：

- **读**：bucket 公共读，URL 即凭证，不需要任何签名。
- **写**（本次未做）：服务端签发短时 PUT 预签名 URL，客户端拿 URL 上传。签名是 HMAC
  结果，拿到一个 URL 的签名无法伪造别的 URL，且限时限 key。AK/SK 仍在服务端。

### 1.4 OSS 与 AWS S3 的三处实质差异 [V]

对官方测试向量逐字节验证后确认（`paddock-api/src/oss.rs` 的 `tests::official_vector`）：

| 项 | AWS SigV4（Garage 用） | OSS V4 |
|---|---|---|
| `host` 头 | **参与签名** | **不参与** |
| payload 哈希 | 真实 SHA256 | 只接受 `UNSIGNED-PAYLOAD` |
| 密钥派生前缀 / 终止串 | `AWS4` / `aws4_request` | `aliyun_v4` / `aliyun_v4_request` |

⇒ **两套实现不能合并**。`api/avatar.rs` 保留 Garage 版，`oss.rs` 是 OSS 版。

### 1.5 OSS 只接受 virtual-hosted style [V]

官方原文：「For security reasons, OSS accepts only virtual hosted-style URLs, not
path-style URLs」。`avatar.rs:76-78` 原有的 path-style（`endpoint/bucket/key`）必须
重写为 `https://{bucket}.{endpoint}/{key}`，且 canonical URI 里**不含 bucket**。

### 1.6 版本号必须进路径，不能进 query [V]

旧形态 `?v={version}` 之所以安全，是因为解析方是自家 axum handler。换成 OSS 后解析方
变成阿里云，而 OSS 的查询参数是**白名单式**的（`versionId` / `response-*`），文档
没有任何「未知参数会被忽略」的承诺。

⇒ 新 key 形态 `avatars/{uuid}/{version}.jpg`。三个连带好处：
① 零 query 解析风险；② 缓存语义不变（URL 变 = 内容变）；③ `avatar_key` 成为 URL 的
真正来源（旧实现查出来只为判非空，然后硬编码拼 `u.id`）。

### 1.7 image crate 的压缩代价可忽略 [V]

实测 228 张「解码 + 缩到 256 + JPEG 编码」单核 12~28 秒。真正的代价不是 CPU，是
**新增依赖**：`default-features = false` + 只开 `jpeg`/`png` 把体积代价控在可接受范围。

### 1.8 客户端 `inSampleSize` 与上传尺寸强耦合 [V]

`decodeAvatarScaled` 的循环 `while (w / (sample*2) >= 144) sample *= 2` 使解码后边长
恒落在 `[144, 288)`。上传从 3072 压到 256 后，`sample` 从 16 掉到 1，解码结果从
192px 涨到 256px ⇒ **流量降了、内存反而涨**（256 张位图 37MB→67MB）。
阈值必须同步提到 168，让 256px 输入走 `sample = 2`。

## 2. 依据（研究）

- OSS 官方 V4 签名测试向量（PutObject 示例）：CanonicalRequest 的 SHA256 为
  `c46d9639…36eca`，最终 Signature 为 `053edbf5…61f23`。本地 Python 验算逐字节命中，
  Rust 实现亦在 `oss::tests::official_vector` 中断言。
  - ⚠️ 文档给的 `AccessKeySecret` 是占位符 `yourAccessKeySecret`，因此**SigningKey
    无法与文档值对上**；可验证的是「CanonicalRequest 逐字节一致」+「用文档 SigningKey
    反推 Signature 一致」，这两条足以证明请求构造与待签串正确。
- 流量与头像体积数据：生产库与访问日志实测（2026-11-14）。
- `PaddockClient.kt:1593` 绝对 URL 分支：git blame → commit `3b26a11`（2026-09-01）。
- 2025-03-20 新规「新开通用户内地 bucket 必须用自定义域名」：**只影响新开通用户**，
  本项目账号在此之前开通，默认域名可用。

## 3. 实现（固化）

### 3.1 服务端

| 位置 | 内容 |
|---|---|
| `paddock-api/src/oss.rs` | OSS V4 签名 + Put/Get/Head/Delete + `self_check`。配置全来自 `configs` 表 |
| `paddock-api/src/avatar_migrate.rs` | 存量迁移：DB 驱动、HeadObject 幂等探测、压缩、原图备份 |
| `paddock-api/src/api/avatar.rs` | 双后端（OSS 优先，回落 Garage）；`/v1/avatar` 在迁移完成后 302 跳转 |
| `admin.rs` | 设置页 OSS 区块 handler + `save_oss_config` 审计 action |
| 四处 SQL | `auth_handlers.rs` / `leaderboard.rs`×2 / `driver.rs` 改为只取 `avatar_key` + `avatar_version`，URL 由 `avatar::avatar_url()` 统一拼装 |

`configs` 键：`oss_endpoint` / `oss_bucket` / `oss_region` / `oss_key_id` /
`oss_key_secret` / `oss_public_base` / **`oss_migrated`（URL 直连的唯一开关）**。

### 3.2 迁移的三条不可违背约束

1. **绝不写 `avatar_updated_at`**——写了等于给全部用户加 30 天冷却。
2. **绝不改 `avatar_version`**——改了 URL 就变，全体客户端重下，制造 QPS 峰值。
3. **原图先备份到 `avatars_orig/`**——压缩不可逆，留 30 天回滚窗口。

### 3.2b URL 切换必须靠「迁移完成标记」，不能靠 key 形态 [V]

2026-11-14 上线当天踩到的核心事故：`avatar_url` 最初用 `is_migrated_key()`
（判断 key 是否 `avatars/{uuid}/{v}.jpg`）决定直连与否。

失败链条：
1. 新 `upload_avatar` 一上线就写新形态 key —— **但对象仍写进 Garage**
2. 于是出现「新形态 key + 对象在 Garage」的组合
3. 形态判断认为「已迁移」⇒ 下发 OSS 绝对 URL ⇒ 客户端直连 ⇒ **全站头像 404**

**正确做法**：`configs.oss_migrated = "1"` 是唯一开关，且只在该轮迁移
**零失败**后才写入（有失败就切会让那部分头像 404，宁可先不切让管理员重跑）。

同理迁移的幂等判据必须用 **HeadObject 真实探测**，不能用 key 字符串形态。
回归测试：`avatar.rs::tests::url_follows_migration_state_not_key_shape`。

### 3.2c bucket 必须「公共读」[V]

客户端是**匿名**访问（模块里没有 AK/SK）。bucket 私有读时：
- 服务端带签名 → 200（所以「测试连接」通过、迁移全部"成功"）
- 客户端匿名 → **403**

这个组合极具迷惑性：所有服务端侧指标都是绿的，只有最终用户看到破图。
排查时**直接 curl 一个真实头像 URL**（不带任何凭证），一分钟即可定案。


### 3.3 模块端

| 位置 | 内容 |
|---|---|
| `ui/screen/paddock/AvatarCompress.kt` | 两段式解码 + 双线性精修到 256 + JPEG q88 |
| `AvatarScreen.kt` | cropper 加 `outputRequestWidth/Height = 1024` + `RESIZE_INSIDE` 当内存护栏 |
| `PaddockClient.kt` | `AVATAR_DISK_MAX_BYTES` 5MB→100MB；`decodeAvatarScaled` 阈值 144→168 |

## 4. 已否决的方案

- **`CropImageOptions` 的 `RESIZE_EXACT` 一步到位**：内部走
  `createScaledBitmap(..., filter = false)` = 最近邻。3072→256 是 12 倍降采样，
  实测 PSNR 比双线性低约 6dB（28.64 vs 34.70）。改为「cropper 限 1024 当护栏 +
  本层双线性精修」。
- **presigned URL 做读**：每次签名 URL 都变 ⇒ `cacheKey = avatarUrl` 缓存 100% miss
  ⇒ 直接复活 2026-09-06 修掉的「全量重下死循环」。读必须用**稳定 URL**。
- **`?v=` 查询串放版本号**：OSS 查询参数无「忽略未知参数」的承诺，见 §1.6。
- **服务端加 `image` crate 全量特性**：会带进 avif/tiff/webp 一大票依赖。只开
  `jpeg` + `png`。
- **服务端与客户端都用内网 endpoint**：内网域名不对外解析，客户端必挂；且**只有
  bucket 与 ECS 同地域**时内网才可达。配置里拆成 `endpoint` 与 `public_base` 两个字段。
- **用 ListObjects 枚举存量**：`users.avatar_key` 就是完整清单，天然过滤
  Garage 里 158~159 个孤儿 blob，也让 RAM policy 可以完全不给 `oss:ListObjects`。
- **用 key 字符串形态判断「对象在哪 / 是否已迁移」**：见 §3.2b，上线当天实际事故。
- **改 key 格式却不处理历史数据**：旧代码写 `avatars/{uuid}`，新代码写
  `avatars/{uuid}/{v}.jpg`，两者共存期间 `avatar_key` 与实际对象对不上。
  三种正确做法（一个都没做）：① 保持旧 key 格式、版本只放 URL；② 迁移任务先搬；
  ③ 读取时双形态回退。**改 key 格式 = 改数据契约**。

## 4b. 部署当天踩到的实现 bug（均已修，供复用代码时对照）

| 症状 | 根因 |
|---|---|
| 点「测试连接」403 SignatureDoesNotMatch | Canonical URI **漏了 bucket**。virtual-hosted 下 HTTP 路径只有 `/{key}`，但 OSS 算签名时仍拼回 `/{bucket}/{key}`（官方向量 `/examplebucket/exampleobject` 就是这个意思） |
| 「保存配置」显示「失败：undefined」 | 公共 `api()` 是「成功返回 message 字符串 / 失败抛异常」，被当 `{ok,message}` 对象用 |
| 点「开始迁移」完全没反应 | 追加脚本时 `</script>` 位置错，`migStart` 落在标签外被当纯文本 |
| 进度条不动 | `migPoll` 误用另一函数的局部变量 `el`，每轮 `ReferenceError` 被静默 `catch` 吞掉 |
| 「跳过 231」一张没搬 | 用 key 形态判「已迁移」，见 §3.2b |
| 迁移读 Garage 404 | `avatar_migrate` 的 S3 URL 拼成 `{ep}/avatars/{key}`，而 key 已含 `avatars/` ⇒ `avatars/avatars/...` |
| 全站头像 404（服务端全绿） | bucket **私有读**，客户端匿名访问 403。见 §3.2c |

## 5. 待验证

- [V] 真实 OSS 端到端：2026-11-14 生产验证通过（写→读→删自检 + 145 张头像
  匿名下载全成功，p50 20KB）。
- [V] 迁移实际结果：231 张全部在 OSS 上（HeadObject 探测 Ok(true)），
  体积 min 2.4KB / p50 20.5KB / max 46.9KB（压缩前 p50 197KB）。
- [?] `?v=` 在 OSS 上的实际行为：本次已绕开（版本进路径），未实测。
- [?] 100MB 磁盘缓存在低端机上的实际影响：`cacheDir` 可被系统清理，理论无风险，
  但未在存储紧张的设备上验证。
- [?] **客户端首次切 URL 后的一次性重下**：`cacheKey = avatarUrl`，URL 从相对变
  绝对 ⇒ 290 张旧缓存全失效。实测手机日志 21:37 后重新下载 145 张、缓存涨到
  4.4MB。这是预期的一次性代价（且新文件小 90%，总流量反而更低），但**未来任何
  改头像 URL 形态的动作都有同样代价**。
- [?] 61 个 `avatar_version = 1` 的异常值（正常应是 epoch millis）：对象存在且
  可访问，不影响功能，未清理。

