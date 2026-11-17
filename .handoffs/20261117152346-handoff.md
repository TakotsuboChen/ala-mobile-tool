# HANDOFF — 读全文再开始干活

生成时间: 2026-11-15T00:38:10+08:00 · Git HEAD: `f93d73f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f93d73f`（2026-11-15 00:38）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f93d73f`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA；权威以 `git log --oneline -5` 实际输出为准
- 待重探的 [?]: 见 §5
- 先读: `docs/notes/PADDOCK_UI_NOTES.md`（本次两 bug 的单源）+
  `docs/design/AVATAR_OSS.md`（头像存储架构）

## 1. 当前目标
修「头像上传成功后页面不退出」与「`avatar_url` 被解析成字符串 `"null"`」两个 bug。
**两个都已修复、实测通过、已提交推送。**

## 2. 已验证状态 — 工作实际停在哪

- **导航 bug 已修** [V] `56c67ca`：根因 = `LaunchedEffect` 竞态重复 push，
  栈里两层 `Route.Avatar`，单次 `pop()` 只删一层。实机日志铁证：两条 push 相距 72ms、
  `前 size=3 stack=g22, x12, x12` → `后 size=2`。
- **JSON null bug 已修** [V] `c77d4d8`：`JSONObject.optString` 把 JSON null coerce 成
  字符串 `"null"`（fallback 只覆盖 key 缺失）。4 处解析改走 `avatarUrlOf()`（用 `isNull`）。
- **三个入口实测通过** [V] 注册流程（00:13:05）+ 账号设置（00:15:44、00:35:05），
  均上传成功且页面退出。
- **压缩链路逐字节验证** [V] 日志 `压缩后字节=N` 与 OSS `Content-Length` 三组全等：
  7341 / 19975 / 20725。
- **`cloudnull` 假错误消失** [V] 修复前全文件 5 次 → 修复后 0 次；
  「非法 URL 已跳过」也 0 次（说明源头修好、兜底未触发）。
- **构建/静态检查** [V] `assembleRelease` BUILD SUCCESSFUL；
  `:app:lint` 无新增 Error（68 Warning 全是既有 `UseKtx`）。
- 工作区: **干净**，三切片均已 push（`60a3b94..f93d73f`）
- 生产服务端: `paddock-api:1.0.1-oss8`（未动，本次全在模块端）

### 本次 build/lint 真实输出摘要
```
BUILD SUCCESSFUL   (:app:assembleRelease, 跳过 lintVital* —— 本机 FIR 崩溃已知问题)
Lint found 68 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline)
BUILD SUCCESSFUL   (:app:lint)   # 报告 XML 中 Error 级 0 条
```

## 3. 决策与理由

- **`push` 去重判据用 `lastOrNull()==key` 而非 `contains`** [V]——只有**连续**压同一
  route 才是 bug；`Main→Driver(A)→Driver(B)` 这类合法回访不受影响。
- **成功退出用 `popUntil { it is Route.Main }` 而非单次 `pop()`** [V]——不管栈里残留
  几层都能一次退干净，比"保证栈正确"更抗未来回归。
- **删除「再换一张」状态** [V]——用户定案原话「这个再换一张根本没有意义」。成功后
  页面已退出，该按钮永远没机会显示，留着只会误导。
- **`avatarUrlOf()` 返回空串而非 null** [V]——与 `MeResult`/`UiState` 既有的
  「非空 String + 空串哨兵」约定一致，调用方零改动（编译器报错后才发现的约束）。
- **`fetchAvatar` 加非法 URL 防御** [V]——即使将来别处再传非法值，也不会伪装成
  网络故障。实测「非法 URL 已跳过」零命中 = 兜底未被触发，符合预期。

## 4. 失败的尝试 — 不要再试

- [X] **改动 `onUploaded` 接线后，靠"代码在包里"证明修复生效**——静态验证全通过
  （mapping 有 compressAvatar 行号、`grep -a` 命中特征串、stash 前后 pop 调用 6→7），
  页面仍不退。**代码一直是对的，问题在运行时。**
- [X] **用 `Navigator.pop()` 单次弹栈**——栈里有**两层** Avatar 时只删一层。
- [X] **`.optString(k).takeIf { it.isNotEmpty() }` 判空**——`"null"` 长度是 4，
  非空判定完全失效。看起来有防御、实际防错维度。
- [X] **`optString(k, fallback)` 传 fallback 兜底**——fallback 只覆盖 key 缺失，
  值为 JSON null 时依然返回 `"null"`。
- [X] **用 `javac` + `android.jar` 实测 `optString` 行为**——android.jar 是 stub，
  抛 `RuntimeException: Stub!`。改用 AOSP 源码 + 官方文档交叉确认。
- [X] **`find / -name "*.jar"` 找 org.json 实现**——超时被移入后台（`find /` 本就是
  错误方案，见 memory `announce-long-running-commands`）。改从 AOSP raw 源码取。
- [X] 继承死路（头像 OSS 迁移期九条，详 `.handoffs/20261115003806-handoff.md` §4
  及 `.handoffs/20261114214606-handoff.md` §4，**一条未删**）

## 5. 已知坑

- ⚠️ **`push` 去重的合法回访场景未实测** [?]——`Driver(A) → Driver(B)` 连续访问两个
  不同车手主页再返回，未验证栈行为。依赖：需要实际点两次不同车手主页。
- ⚠️ **`popUntil { it is Route.Main }` 在 Main 不在栈底时的行为未实测** [?]——当前
  所有入口都由 Main 之上的页面 push。依赖：从更深的栈进入头像页。
- ⚠️ **`cacheKey = avatarUrl`** [V]（继承）——任何改头像 URL 形态的动作都会让全量
  缓存失效，实测重下 145 张。
- ⚠️ **bucket 必须公共读** [V]（继承）——模块端无 AK/SK，只能匿名 GET。
- ⚠️ **`AVATAR_SIDE = 256` 双处** [?]（继承）——`avatar_migrate.rs:116` 与
  `AvatarCompress.kt:67`，无编译期约束。本次已复核两者一致。
- ⚠️ **`decodeAvatarScaled` 阈值 168 与上传尺寸 256 绑定** [V]（继承）——改上传尺寸
  必须重算，否则 `memAvatarCache` 反而暴涨。
- ⚠️ **61 个 `avatar_version = 1` 的异常值** [?]（继承）——对象可访问，不影响功能。
- ⚠️ **前缀继承项** [?]：VPS 时钟慢 3 分钟 / 魅族 20 IP 会变（`.142`↔`.123`，
  本次实测已在 `.123`）/ `best_laps` 是物化快照须重算 / 覆盖重发后同版本用户收不到
  更新提示。

## 6. 下一步（有序）
1. **（待你决定）模块发版**：本次两个 bug 修复属用户可感知缺陷。版本号是你的决策域
   （当前 `1.0.5 Alpha 1` / `105110`），未获明确答复前不改。
2. **（待你决定）`uploadAvatar` 全链路日志**：目前只有压缩字节数与上传结果，
   HTTP 状态码仍未落盘（`uploadAvatar` 返回 `String?`，错误直接进 Toast 就消失）。
3. **（待你决定）清理「测试」号的 30 天冷却**：若要继续测换头像，需再置
   `avatar_updated_at = NULL`。
4. **（待你决定）头像 OSS 收尾项**（继承自上一份）：写直连（流量仅 0.003%，
   价值在架构纯度）/ 清理 61 个 `avatar_version=1` / 关掉 garage 容器。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。
6. 继承待办见 `.handoffs/20261115003806-handoff.md` §6（120FPS 刷新率层 /
   LogExporter 段缺失静默 / TG 播报提示 / 服务端发版 / 应用调教回写）——均未动。

## 7. 留给用户的开放问题
- 本次修复要不要发版？版本号定多少？
- `uploadAvatar` 的 HTTP 状态码日志要补吗？
- 头像 OSS 那批继承项（写直连 / 61 个异常值 / 关 garage）何时处理？
