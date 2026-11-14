# 围场 UI 实现笔记

> 状态：活跃 · 最后编纂：2026-11-15
> 一句话结论：围场页的导航栈与 JSON 解析各有一个"看着对、实际静默失效"的坑——`push` 不幂等、`optString` 把 JSON null 变 `"null"`。

---

## 1. 结论（发现）

### 1.1 `Navigator.push` 不幂等，重复压栈会让 `pop()` 失效 [V]

`Navigator.push()` 原实现是无条件 `backStack.add(key)`。当同一个 route 被连续压两次，
栈里出现两层，此后**单次 `pop()` 只删一层，页面视觉上纹丝不动**。

- 触发条件：`LaunchedEffect` 里 "读 state → 改 state → push" 的写法
- 机理：`markAvatarDone()` 走 `_uiState.update`（Compose 快照写入，异步传播），
  不保证在下一个 effect 实例启动前可见 ⇒ 两个 effect 实例都读到旧值，各 push 一次
- 实机指纹：两条 `push Route.Avatar` 相距 **72ms**，`backStack` = `[Main, Avatar, Avatar]`

### 1.2 `JSONObject.optString` 把 JSON `null` 转成字符串 `"null"` [V]

```java
// AOSP platform/libcore JSONObject.java
public String optString(String name, String fallback) {
    Object object = opt(name);
    String result = JSON.toString(object);
    return result != null ? result : fallback;
}
```

JSON null 的代表是哨兵对象 `JSONObject.NULL`（不是 Java null），`JSON.toString()`
把它 coerce 成 `"null"` ⇒ `result != null` 成立 ⇒ **fallback 永不生效**。

⇒ **`optString` 的 fallback 只覆盖「key 缺失」，不覆盖「值为 null」。**
`.takeIf { it.isNotEmpty() }` 拦不住（`"null"` 长度 4）。

### 1.3 上传成功后必须立刻退出头像页 [V]

本页没有任何"已上传"的视觉状态，留在原地 = 用户以为没传成功（实机：用户连点 4 次
「选择图片」，而第一次其实已经上传成功、服务端 `has_avatar=true`）。

**「再换一张」这个状态不该存在**（用户 2026-11-15 原话：「这个再换一张根本没有意义」）
——成功后页面已退出，按钮永远没机会显示。

---

## 2. 依据（研究）

### 2.1 导航 bug 的实机日志

```
2026-11-15 00:07:49.457 [I/NavDiag] push Route.Avatar (needsAvatar=true, loggedIn=true)
2026-11-15 00:07:49.529 [I/NavDiag] push Route.Avatar (needsAvatar=true, loggedIn=true)
...
2026-11-15 00:08:26.588 [I/NavDiag] onUploaded: 前 size=3 stack=g22, x12, x12
2026-11-15 00:08:26.588 [I/NavDiag] onUploaded: 后 size=2 stack=g22, x12
```

`x12` = `Route.Avatar`（R8 混淆名）。`前 size=3` 证明栈里有两层 Avatar，
`后 size=2` 证明 pop 只删了一层。

### 2.2 `optString` 的官方源码与文档

AOSP 源码见 §1.2。官方文档原文：
> "the sentinel value `JSONObject#NULL` is coerced to the string `"null"`"

### 2.3 假网络错误的日志指纹

```
[I/PaddockClient] fetchAvatar: MISS, downloading null
[W/PaddockClient] fetchAvatar failed: Unable to resolve host "paddock.takotsubo.cloudnull"
```

`$serverBase` + `"null"` = `https://paddock.takotsubo.cloudnull`。
**看起来是 DNS 故障，实际是 JSON 解析层类型错误。**

### 2.4 修复后的验证

| 项 | 结果 |
|---|---|
| `cloudnull` 报错 | 修复前全文件 5 次 → 修复后 **0 次** [V] |
| 「非法 URL 已跳过」 | 0 次——说明源头已修好，兜底未被触发 [V] |
| 上传字节 ↔ OSS `Content-Length` | 7341 / 19975 / 20725 三组**逐字节吻合** [V] |
| 三个入口 | 注册流程 + 账号设置，均上传成功且退出 [V] |

---

## 3. 实现（固化）

### 3.1 导航栈去重（`ui/navigation3/Navigator.kt`）

```kotlin
fun push(key: NavKey) {
    if (backStack.lastOrNull() == key) return   // 不是 contains
    backStack.add(key)
}
```

判据用 `lastOrNull() == key` 而非 `contains`：只有**连续**压同一 route 才是 bug，
`Main → Driver(A) → Driver(B)` 这类合法回访不受影响。

### 3.2 push 前的第二道保险（`PaddockPager.kt`）

```kotlin
LaunchedEffect(uiState.loggedIn, uiState.needsAvatar) {
    if (uiState.loggedIn && uiState.needsAvatar) {
        viewModel.markAvatarDone()
        if (navigator.backStack.lastOrNull() !is Route.Avatar) {
            navigator.push(Route.Avatar)
        }
    }
}
```

### 3.3 成功退出用 `popUntil`（`ConfigActivity.kt` 的 `entry<Route.Avatar>`）

```kotlin
AvatarScreen(onUploaded = { navigator.popUntil { it is Route.Main } })
```

`popUntil` 保留 predicate 为真的那一项、删其上全部 ⇒ 无论残留几层都能一次退干净。

### 3.4 `avatarUrlOf()` 统一解析（`PaddockClient.kt`）

```kotlin
private fun JSONObject.avatarUrlOf(): String {
    if (isNull("avatar_url")) return ""
    return optString("avatar_url")
}
```

返回空串表示无头像，与本类 `MeResult.avatarUrl` / `UiState.avatarUrl` 的
「非空 String + 空串哨兵」既有约定一致，调用方继续 `takeIf { it.isNotEmpty() }`。
4 处解析全部改用它。

### 3.5 `fetchAvatar` 的防御

```kotlin
if (!avatarUrl.startsWith("http") && !avatarUrl.startsWith("/")) {
    Logger.log(Log.WARN, TAG, "fetchAvatar: 非法 URL 已跳过 [$avatarUrl]")
    return null
}
```

避免将来别处再传非法值时又伪装成网络故障。

### 3.6 日志特征串（排障时 grep 这些）

| 特征串 | 含义 |
|---|---|
| `[I/AvatarScreen] upload: 压缩后字节=N` | 压缩是否生效的直接指纹 |
| `[I/AvatarScreen] upload: 结果 err=...` | 上传结果（`err=null` 即成功） |
| `[W/PaddockClient] fetchAvatar: 非法 URL 已跳过` | 上游又传了非法 URL |
| `Unable to resolve host "...cloudnull"` | **旧 bug 指纹**，修复后应为 0 |

---

## 4. 已否决的方案

- [X] **只改 `onUploaded` 接线，不动 `push`**（第 1 轮尝试）——静态验证"代码在包里"
  全部通过，但页面仍不退。接线本来就是对的，问题在运行时栈状态。
- [X] **靠 stash 前后对拍 `pop()` 调用数（6→7）证明修复生效**——只证明代码进包，
  证不了运行时效果。这一轮白做。
- [X] **埋点只记「调用 onUploaded」+「onUploaded 已返回」**——两条都出现，
  仍无法定位。必须记 `backStack` 的 size 与每项类型才有用。
- [X] **`.optString(k).takeIf { it.isNotEmpty() }` 判空**——`"null"` 长度为 4，
  非空判定完全失效。看起来有防御、实际防错维度，比没防御更危险。
- [X] **改为 `optString(k, fallback)` 传 fallback**——fallback 只覆盖 key 缺失，
  值为 JSON null 时依然返回 `"null"`。

---

## 5. 待验证

- [?] `push` 去重是否影响其他合法回访路径（`Driver(A) → Driver(B)` 未实测回退场景）。
  依赖：需要连续访问两个不同车手主页再返回，检查导航是否正确。
- [?] `popUntil { it is Route.Main }` 在 `Main` 不在栈底时（例如从某子页进入头像页）
  的行为。当前所有入口都由 `Main` 之上的页面 push，未实测更深的栈。
