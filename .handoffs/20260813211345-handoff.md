# HANDOFF — 读全文再开始干活

生成时间: 2026-08-13T03:10:00+08:00 · Git HEAD: `aad36ce`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `feat/ala-mobile-8.0.4-adapt` @ `aad36ce` (2026-08-13)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `aad36ce`；变了说明快照可能过期。
- 待重探的 [?]: 见第 5 节。
- 先读: `CLAUDE.md` + `README.md` + 本文件。

## 1. 当前目标
**让模块适配 Ala Mobile 8.0.4 (versionCode 200146)**。偏移量迁移已完成（OffsetTable/VersionGate/unlock_hook.c）。**卡点：制作共存版 APK 时，打开游戏开屏几秒后跳转 Play 商店（"无法识别设备上安装的应用"），8.0.0 共存版 origin.apk 完全正常。** 完成定义：共存版 8.0.4 打开不跳 Play、模块 hook 正常工作。

## 2. 已验证状态 — 工作实际停在哪
- [V] **偏移量迁移完成**——`git log` HEAD=`aad36ce`，3 个文件已提交并 push 到 `origin/feat/ala-mobile-8.0.4-adapt`。
- [V] **构建通过**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 5s`（从干净 shell 跑）。
- [V] **il2cpp-dumps/v8.0.4/** 已生成——Il2CppDumper v6.7.46 + dotnet 6.0.428 跑官方 8.0.4 APKS，offsets_sheet.csv 已建。实例字段偏移全部不变。
- [V] **共存版 APK 已能安装**——单 APK（合并 arm64 libs + unity assets），zipalign + apksigner 签名，`adb install` 成功无报错。
- [V] **8.0.0 共存版参照物**——`build/origin-8.0.0.apk`（纯净共存版，包名已改、但 smali 目录保持 `com/Vince`、只改 manifest package）+ `build/origin-npatched.apk`（NPatch 注入版）。APK 签名证书 `CN=Mod`。
- **工作区**：`git status` 只剩 HANDOFF.md 归档待提交（`.` 未提交的 `HANDOFF.md` 删除 + `.handoffs/` 新增）。

### 测试/build 输出（本次交接 run）
```
./gradlew :app:assembleDebug --console=plain → BUILD SUCCESSFUL in 5s
adb install coex-8.0.4.apk → Success
```

## 3. 决策与理由
- **偏移量按类区域固定 delta 迁移** [V]——8.0.0→8.0.4：IRDSCarControllInput/Drivetrain/PlayerControls +0xDC1C，BillingManager +0x6340，handleMusicVolume +0xDDCC，AudioSource.set_volume -0x1A32384（Unity 引擎升级迹象）。
- **共存版打包只改 manifest package，不动 smali 目录** [V]——完整参照 origin.apk：smali 保持 `com/Vince/AlamobileFormula/`，只改 `package=` 和 `authorities=`，避免改坏 R 类引用。
- **禁止 checkLicense + 移除误导 Play 的 metadata** [V]——origin 把 `checkLicense` 改成 `return-void`；8.0.4 额外移除 `com.android.vending.splits.required` / `com.android.stamp.source` / `splits0.xml` / `derived.apk.id`。

## 4. 失败的尝试 — 不要再试
- **（本会话全量）所有 Java 层绕过 pairip 的思路** [V]——已试过：改 checkLicense→return-void、改 performLocalInstallerCheck→return true、LicenseActivity→空壳、删 LicenseActivity + 全部 licensecheck smali、删 splits0/stamp/derived metadata、`performLocalInstallerCheck` 改回 true——**全部无效，开屏几秒后仍跳 Play**。结论：8.0.4 的许可校验在 **Unity C#/IL2CPP 层**（libil2cpp.so 内），不在 Java 层。
- **（本会话)依赖 smali 层许可校验存在** ——8.0.4 pairip 把许可校验移到了 IL2CPP/native 层，Java 层全禁掉也没用。
- **（前向搬运 M14/M23-29 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile`、模块进程写公共 `/sdcard/`（EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local、只给 setter 加 `is_player` 条件（AI 误控）、`kotlin.daemon.enabled=false`、EULA 存 remote prefs（pm clear 清不掉）、`getScope()`/`getRunningTargets()` 判激活、Non-root 标记写 remote prefs、`gh release edit --body-file`（正确是 `--notes-file`/`-F`）、`generate_release_notes: true` 搭配手工 `body:`、自作主张裁剪 GIF/改 README——均不再试。

## 5. 已知坑
- **⚠️ 8.0.4 共存版跳 Play 商店（未解决）** [?]——怀疑正确做法是像 8.0.0 那样**保留全部 pairip 校验代码但把入口/结果改绕过**，或需要从 8.0.0 origin.apk 中 diff 出真正缺的修改（本会话只 diff 了 smali 结构，没 diff dex 字节码差异全量）。
- **⚠️ origin.apk 是纯净共存版参照物，不是 NPatch 注入版** [V]——用户明确：`Downloads/origin.apk`=注入前纯净共存版，`Downloads/origin-725-npatched.apk`=注入后版本。
- **⚠️ NPatch 需要的 APK 必须签名** [V]——未签名/仅 v1 签名会被拒（`get original signature failed`）；`apksigner` 必须签 v2/v3（targetSdk 35 强制）。
- **⚠️ `/tmp` 是 tmpfs 只有 7.3G** [V]——解压多个 500MB+ APK 会占满导致 zip CRC 损坏；工作目录必须放项目 `build/`。
- **⚠️ 8.0.0 共存版 smali 目录不动、只改 manifest** [V]——origin.apk 证明（smali/com/Vince 仍在）。
- **⚠️ 官方/共存包共存** [V]——两者都可安装（不同包名）；已装 8.0.0 共存版不冲突。
- **⚠️ `is_player_controller` 不可靠** [V]——玩家车判据走 `g_player_controller`。
- **⚠️ NPatch 双 ClassLoader** [V]——`System.setProperty(NATIVE_INSTALLED_FLAG)` 进程级标记避免双注入。
- **⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）。

## 6. 下一步（有序）
1. **从 8.0.0 origin.apk 全量 diff 出共存版制作脚本**：重点对比 origin.apk 与官方 8.0.0 base 的 **dex 字节码差异**（/tmp 或 build/ 下用 apktool 全解两个版本，diff 所有 smali 文件除 R 类/许可证类外还有哪些被改）。
2. **定位 8.0.4 的 IL2CPP 许可校验点**：8.0.4 libil2cpp.so 内搜索 pairip/license 相关字符串与函数（`strings` + 反汇编），找到 CheckLicense/PaipCheck 入口后 hook 或 patch。
3. **用 NPatch 注入 8.0.4**（用户已装好 NPatch，先手工走通注入流程，确认 NPatch 自己会重签，无需我们签名）。
4. **验证模块功能在 8.0.4 上可用**（踏板/换挡/DRS/解锁/音乐替换）。

## 7. 留给用户的开放问题
- 8.0.4 跳 Play 商店的确切触发点是 Java 层还是 IL2CPP 层？（本会话结论指向 IL2CPP 层，待验证）
- 是否需要完全复刻 8.0.0 origin 的制作流水线（用脚本固化）？
- 是否考虑放弃共存版、只支持官方版 + NPatch 直接注入？（若 Play Protect 判定无解）