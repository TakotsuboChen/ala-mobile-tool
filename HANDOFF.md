# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T02:45:38+08:00 · Git HEAD: 7fad94d1a1a32fbdc8ffe53dc20d510ea0ac944a
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

发布 1.0.0-Alpha-1 并继续修复踏板/换挡体验。
完成定义：release 构建通过、APK 可安装、设置界面正常、GitHub pre-release 和 README 描述准确。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，与 `origin/main` 同步（`git status` 验证）。
- [V] `./gradlew :app:assembleRelease` 构建成功（本次交接 run 输出见下方）。
- [V] release APK 已安装到真机，设置界面能正常打开并保存配置。
- [V] 踏板/刹车 overlay 已在真机验证生效（换挡、自动 DRS 仍待完善）。
- [V] GitHub pre-release `v1.0.0-alpha.1` 已发布并附带 APK。
- [V] README 和 release notes 已按用户反馈修正措辞（换挡冲突、高频顿挫等）。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
> Task :app:lintVitalRelease
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease
[Incubating] Problems report is available at: file:///home/takotsubo/projects/ala-mobile-tool/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 2s
52 actionable tasks: 6 executed, 46 up-to-date
```

## 3. 决策与理由

- 将 IL2CPP 方法偏移从 file Offset 改为 RVA [V]——之前 hook 的函数根本没被执行，proxy 日志一次都没有；改为 RVA 后踏板立刻生效。
- hook `IRDSCarControllInput.FixedUpdate` [V]——仅 hook setter 不够，FixedUpdate 里能拿到 controller 实例并强制写字段，保证 overlay 输入生效。
- MIUIX 设置界面使用 `Switch`、`Slider`、`TabRow` [V]——不再用 Button 拼凑，视觉效果与系统一致。
- 发布时使用临时签名 [V]——用户未提供正式 keystore，先用临时签名生成可安装的 release APK；正式发布前必须替换。

## 4. 失败的尝试 — 不要再试

- 用 file Offset（Il2CppDumper 的 `Offset` 字段）直接加 base 做 hook [V]——函数入口根本不走，proxy 一次都没触发。必须使用 RVA。
- 只在 `setThrottleInput`/`setBrakeInput` setter 处替换参数 [V]——原函数没被走到，必须 hook `FixedUpdate` 并在原函数返回后写字段兜底。
- 使用 `SharedPreferences`/`MODE_WORLD_READABLE` 跨进程共享配置 [V]——Android 10+ 抛 SecurityException。已改用 JSON + 外部存储。
- 在 `onPackageReady` 立即安装 native hooks [V]——`libil2cpp.so` 尚未加载。已改为延迟 15 秒。

## 5. 已知坑

- 当前 release APK 使用临时签名 [V]——正式发布前需替换为正式 keystore，否则无法覆盖安装。
- 换挡按钮会调用原方法，但游戏自动换挡未关闭，可能与手动换挡冲突 [V]。
- 踏板覆盖时行驶过程中可能出现高频顿挫 [?]——原因待排查，可能与 FixedUpdate 中强制写字段的时机或字段选择有关。
- 自动 DRS 目前只是开关拦截，没有真正的自动判断逻辑 [?]。
- 设置界面使用临时签名密钥的密码硬编码在 `app/build.gradle.kts` 中 [V]。

## 6. 下一步（有序）

1. 排查并修复踏板覆盖时的行驶顿挫（可能与 FixedUpdate 写入时机、字段选择或 carPilot 判断有关）。
2. 实现关闭游戏自动换挡，或让 overlay 手动换挡真正生效。
3. 实现基于 telemetry 的自动 DRS 逻辑（读取 inDRSZone、speed、throttle 等字段）。
4. 正式发布前替换正式签名并清理 build.gradle.kts 中的硬编码密码。

## 7. 留给用户的开放问题

- 是否需要先排查顿挫问题，还是优先做自动 DRS/换挡逻辑？
- 是否已准备好正式 release 签名 keystore？
