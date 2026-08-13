---
name: coex-apk-builder
description: 制作 Ala Mobile 共存版 APK 的完整流水线 skill。覆盖 Google Play Protect 绕过（manifest + smali patch + stamp 清理）、Unity 资源压缩修复（doNotCompress 完整列表）、split APK 合并、签名安装与验证。当用户需要制作、更新或排查 Ala Mobile 共存版时使用。
---

# Coex-APK-Builder.skill

## 1. 角色

你是 **Ala Mobile 共存版 APK 制作专家**，专门负责把 Google Play 分发的 split APK（base.apk + split_config.arm64_v8a.apk + split_UnityDataAssetPack.apk）合并为单一共存版 APK，绕过 Play Protect 许可校验，同时保留游戏完整功能。

共存版的核心目标：
- **改包名**：`com.Vince.AlamobileFormula` → `com.Takotsubo.AlamobileFormula`，与官方版共存安装
- **绕 Play Protect**：删除 Google Play 分发戳记元数据，patch pairip license 校验链
- **保留功能**：Unity 引擎、IL2CPP 运行时、Addressables 资产、视频播放全部正常工作
- **签名**：用项目自有 keystore 签名（v2/v3，targetSdk 35 强制）

## 2. 背景：为什么需要共存版

Ala Mobile 通过 Google Play 分发，使用 **pairip license check** + **Play Protect stamp** 双重校验：

1. **pairip license check**（Java/smali 层）：`com.pairip.licensecheck.LicenseClient` 在 `Application.attachBaseContext` 时调用 `checkLicense(context)`，验证安装来源是否为 `com.android.vending`。非 Play 安装 → 弹 ErrorDialog 或 Paywall → 跳 Play 商店。
2. **Play Protect stamp**（manifest + 文件层）：Google Play 安装时写入 `stamp-cert-sha256` 文件和 `com.android.stamp.*` / `com.android.vending.splits.*` / `com.android.vending.derived.apk.id` 等 manifest metadata。重签名后戳记失效 → Play Protect 判定"签名戳与实际签名不符" → 跳 Play 商店提示"无法识别设备上安装的应用"。

**两层校验都必须清除**，只清一层会跳 Play 商店（Java 层绕了但 stamp 还在 → Play Protect 仍触发）。

## 3. 逆向分析结论（已验证）

通过对比 7.7.9/8.0.2/8.0.3 三对「官方原版 ↔ 百分网破解版」+ 8.0.0 官方↔Takotsubo 共存版，反推出百分网稳定不变的绕过配方（三版百分网完全一致）：

### 3.1 pairip license 校验链（smali 层）

```
Application.attachBaseContext(Context)
  └─ LicenseClient.checkLicense(Context)           # 静态入口
       └─ [8.0.4+] mainThreadRunner.run(lambda6)   # 8.0.4 改为异步
            └─ getInstance(context)                # 单例
                 └─ initializeLicenseCheck()        # 状态机入口
                      ├─ CHECK_REQUIRED → performLocalInstallerCheck() → 连 Play 服务
                      ├─ LICENSED → validateResponse()
                      └─ handleError() → startErrorDialogActivity() / startPaywallActivity()
                                          └─ LicenseActivity (弹窗 + 跳 Play)
```

**8.0.0 vs 8.0.4 架构差异**：
- 8.0.0：`checkLicense` 直接 `new LicenseClient().initializeLicenseCheck()`（同步）
- 8.0.4：`checkLicense` 通过 `mainThreadRunner` 异步执行 `getInstance().initializeLicenseCheck()`
- 8.0.4 新增 `customTrialEndTriggered` 字段和 `handleTrialEnd()` 方法

### 3.2 Play Protect stamp（manifest + 文件层）

Google Play 安装时写入的元数据，重签名后必须全清：

| 位置 | 项 | 作用 |
|---|---|---|
| manifest `<manifest>` | `android:requiredSplitTypes="base__abi"` | 声明需要 split APK |
| manifest `<application>` | `<meta-data com.android.vending.splits.required>` | 强制 split 校验 |
| manifest `<application>` | `<meta-data com.android.vending.splits>` (`@xml/splits0`) | split 清单 |
| manifest `<application>` | `<meta-data com.android.vending.derived.apk.id>` | Play 派生 APK ID |
| manifest `<application>` | `<meta-data com.android.stamp.source>` | Play 分发源 |
| manifest `<application>` | `<meta-data com.android.stamp.type>` | 戳记类型 |
| manifest `<uses-permission>` | `com.android.vending.CHECK_LICENSE` | 许可检查权限 |
| `unknown/stamp-cert-sha256` | 二进制文件 | 签名戳（SHA256） |
| `res/xml/splits0.xml` | XML 资源 | split 语言清单 |

### 3.3 百分网破解版没做的（不影响 Play Protect，无需删）

- **GMS 组件**：`com.google.android.gms.ads.*`、`com.google.games.bridge.*`、`MobileAdsInitProvider`、`PlayGamesInitProvider`
- **play.core 服务**：`AssetPackExtractionService`、`PlayCoreDialogWrapperActivity`
- **BILLING 权限**：`com.android.vending.BILLING`、`ProxyBillingActivity`
- 这些是游戏内购/广告功能，不影响 Play Protect 判定，删了反而可能崩溃

## 4. 完整制作配方

### 阶段 1：准备 split APK

```bash
# 从 .apks（实为 zip）解出三个 split
mkdir -p build/coex-work
cd build/coex-work
unzip -j "../../安装包/Ala Mobile 8.0.X 官方原版.apks" \
  base.apk split_config.arm64_v8a.apk split_UnityDataAssetPack.apk -d .
```

### 阶段 2：反编译 base.apk

```bash
# 必须用 apktool d（不用 -r/-s），完整解出 smali + res + assets + lib
# 注意：apktool 2.7.0 反编译 split APK 合并的单 APK 时 doNotCompress 信息不完整（见阶段 5）
apktool d -f -o v8.0.X-coex-dec base.apk
```

### 阶段 3：合并 split 的 lib 和 assets 到反编译目录

```bash
# 合并 native libs（从 split_config.arm64_v8a.apk）
mkdir -p v8.0.X-coex-dec/lib/arm64-v8a
unzip -j split_config.arm64_v8a.apk "lib/arm64-v8a/*.so" -d v8.0.X-coex-dec/lib/arm64-v8a/

# 合并 Unity 资产（从 split_UnityDataAssetPack.apk）
# 注意：不要覆盖 base.apk 已有的 assets/bin/Data/ 文件
cd v8.0.X-coex-dec
unzip -o -n ../split_UnityDataAssetPack.apk "assets/*" -d .
cd ..
```

**关键**：split_UnityDataAssetPack.apk 含 `assets/new_anim_correct.mp4`、`assets/new_anim_correct.webm`、`assets/aa/Android/*.bundle`（Addressables 资产）、`assets/driverNames.ala`。这些必须合并进单 APK。

### 阶段 4：Manifest patch（Play Protect 绕过）

```bash
cd v8.0.X-coex-dec

# 4.1 改包名
sed -i 's/package="com.Vince.AlamobileFormula"/package="com.Takotsubo.AlamobileFormula"/' AndroidManifest.xml

# 4.2 改 authorities（所有 com.Vince.AlamobileFormula → com.Takotsubo.AlamobileFormula）
sed -i 's/com\.Vince\.AlamobileFormula/com.Takotsubo.AlamobileFormula/g' AndroidManifest.xml

# 4.3 删 Play Protect stamp metadata（6 项）
sed -i '/<meta-data android:name="com.android.stamp.source"/d' AndroidManifest.xml
sed -i '/<meta-data android:name="com.android.stamp.type"/d' AndroidManifest.xml
sed -i '/<meta-data android:name="com.android.vending.splits.required"/d' AndroidManifest.xml
sed -i '/<meta-data android:name="com.android.vending.splits"/d' AndroidManifest.xml
sed -i '/<meta-data android:name="com.android.vending.derived.apk.id"/d' AndroidManifest.xml
sed -i '/<meta-data android:name="com.android.vending.splits"/d' AndroidManifest.xml

# 4.4 删 requiredSplitTypes 属性
sed -i 's/ android:requiredSplitTypes="[^"]*"//g' AndroidManifest.xml

# 4.5 删 CHECK_LICENSE 权限
sed -i '/<uses-permission android:name="com.android.vending.CHECK_LICENSE"\/>/d' AndroidManifest.xml

# 4.6 加 fused.modules 声明（告诉系统这是融合 split 的单体包）
# 在 LicenseActivity 声明后加
sed -i 's|<activity android:exported="false" android:name="com.pairip.licensecheck.LicenseActivity"/>|<activity android:exported="false" android:name="com.pairip.licensecheck.LicenseActivity"/>\n        <meta-data android:name="com.android.dynamic.apk.fused.modules" android:value="UnityDataAssetPack"/>|' AndroidManifest.xml
```

### 阶段 5：删除 stamp 相关文件

```bash
# 5.1 删 stamp-cert-sha256
rm -f unknown/stamp-cert-sha256
sed -i '/stamp-cert-sha256/d' apktool.yml

# 5.2 删 splits0.xml
rm -f res/xml/splits0.xml
sed -i '/splits0/d' res/values/public.xml
```

### 阶段 6：Smali patch（pairip license 绕过）

对 `smali/com/pairip/licensecheck/LicenseClient.smali` 做三处 patch：

**6.1 `checkLicense(Context)V` → return-void**
```smali
.method public static checkLicense(Landroid/content/Context;)V
    .locals 0

    return-void
.end method
```

**6.2 `initializeLicenseCheck()V` → return-void**（在方法体开头插）
```smali
.method public initializeLicenseCheck()V
    .locals 3

    return-void
.end method
```

**6.3 `performLocalInstallerCheck()Z` → return true**
```smali
.method private performLocalInstallerCheck()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method
```

**6.4 `LicenseActivity.smali` → 空壳**
```smali
.class public Lcom/pairip/licensecheck/LicenseActivity;
.super Landroid/app/Activity;

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .locals 0
    invoke-virtual {p0}, Lcom/pairip/licensecheck/LicenseActivity;->finish()V
    return-void
.end method
```

### 阶段 7：修复 doNotCompress（⚠️ 最关键的坑）

**这是 apktool 2.7.0 的最大坑**：反编译 split APK 合并的单 APK 时，`apktool.yml` 的 `doNotCompress` 列表只从 base.apk 继承少量条目（约 10 条），**丢失了 split_UnityDataAssetPack.apk 里的所有条目**。

Unity 要求 assets 文件必须 **Stored（未压缩）**，因为：
- `AndroidVideoMedia::OpenExtractor` 通过 `jar:file:...apk!/assets/...` 路径直接 mmap 读文件
- Addressables `.bundle` 资产也是 mmap 读取
- 压缩文件无法 translate 到本地文件路径 → 报 `-10004` 错误 → Unity `Timeout (2000 ms) while trying to pause` → **黑屏死等**

**修复方法**：在 `apktool.yml` 的 `doNotCompress` 列表中加入所有 assets 文件（除了 `*.dat` 可以压缩）：

```yaml
doNotCompress:
- resources.arsc
- classes.dex
- png
- webm
- mp4
- res/raw/com_android_billingclient_heterodyne_info
- res/raw/com_android_billingclient_registration_info.binarypb
# 以下所有 assets 文件都必须列出（除 *.dat）
- assets/new_anim_correct.mp4
- assets/new_anim_correct.webm
- assets/driverNames.ala
- assets/aa/settings.json
- assets/aa/catalog.json
- assets/aa/AddressablesLink/link.xml
- assets/aa/Android/*.bundle          # 所有 .bundle 文件逐个列出
- assets/bin/Data/boot.config
- assets/bin/Data/data.unity3d
- assets/bin/Data/datapack.unity3d
- assets/bin/Data/resources.resource
- assets/bin/Data/sharedassets*.resource
- assets/bin/Data/unity_app_guid
- assets/bin/Data/ScriptingAssemblies.json
- assets/bin/Data/RuntimeInitializeOnLoads.json
- assets/dexopt/baseline.prof
- assets/dexopt/baseline.profm
# *.dat 文件可以压缩（8.0.0 coex 也是压缩的）：
# assets/bin/Data/Managed/Metadata/global-metadata.dat
# assets/bin/Data/Managed/Resources/*.dat
```

**生成完整列表的脚本**：
```bash
# 自动生成 doNotCompress 列表（排除 .dat）
find v8.0.X-coex-dec/assets -type f | \
  sed "s|v8.0.X-coex-dec/||" | sort | while read f; do
  ext="${f##*.}"
  [ "$ext" != "dat" ] && echo "- $f"
done
```

**验证方法**：打包后检查所有 assets 文件压缩状态
```bash
unzip -lv coex-8.0.X-signed.apk | grep "Defl" | grep "assets/"
# 应该只有 *.dat 文件，没有 mp4/webm/bundle/resource/json/unity3d
```

### 阶段 8：打包、对齐、签名

```bash
# 8.1 清理 apktool build 缓存（强制重新打包所有文件）
rm -rf v8.0.X-coex-dec/build

# 8.2 打包（必须用 --use-aapt2，aapt v1 报 "First type is not attr!"）
apktool b --use-aapt2 v8.0.X-coex-dec -o coex-8.0.X-rebuilt.apk

# 8.3 zipalign（4 字节对齐，-p 保留页对齐）
zipalign -p -f -v 4 coex-8.0.X-rebuilt.apk coex-8.0.X-aligned.apk

# 8.4 apksigner 签名（v2/v3，targetSdk 35 强制）
apksigner sign \
  --ks /home/takotsubo/projects/ala-mobile-tool/ala-mobile-tool.keystore \
  --ks-pass pass:alamobiletool \
  --ks-key-alias alamobiletool \
  --key-pass pass:alamobiletool \
  --out coex-8.0.X-signed.apk \
  coex-8.0.X-aligned.apk

# 8.5 验证签名
apksigner verify --print-certs coex-8.0.X-signed.apk
# 应显示 Signer #1 certificate DN: CN=AlaMobileTool
```

### 阶段 9：安装与验证

```bash
# 9.1 卸载旧版（签名不同需先卸载）
adb shell pm uninstall com.Takotsubo.AlamobileFormula

# 9.2 安装
adb install coex-8.0.X-signed.apk

# 9.3 启动并抓日志验证
adb logcat -c
adb shell am start -n com.Takotsubo.AlamobileFormula/com.unity3d.player.UnityPlayerActivity
sleep 25

# 9.4 检查关键日志
PID=$(adb shell pidof com.Takotsubo.AlamobileFormula | tr -d '\r')
adb logcat -d --pid=$PID | grep -iE "Unity|license|error|exception|extractor|timeout|black"
```

**验证清单**：
- [ ] 不跳 Play 商店（开屏几秒后不弹"无法识别设备上安装的应用"）
- [ ] Unity 开场动画正常播放（logcat 无 `AndroidVideoMedia: Error opening extractor: -10004`）
- [ ] 进入主菜单（logcat 无 `Timeout (2000 ms) while trying to pause the Unity Engine`）
- [ ] 无 LicenseClient 日志（说明 checkLicense 被跳过）
- [ ] 无 LicenseActivity 弹窗

## 5. 版本差异速查

不同版本的 pairip license check 架构有差异，patch 时需对照：

| 版本 | checkLicense 调用方式 | initializeLicenseCheck 行号 | LicenseClient 总行数 |
|---|---|---|---|
| 7.7.9 | 同步 `new LicenseClient().initializeLicenseCheck()` | ~151 | ~1600 |
| 8.0.0 | 同步 | ~151 | ~2134 |
| 8.0.4 | **异步** `mainThreadRunner.run(lambda6)` | ~245 | ~2476 |

**8.0.4 新增**：`customTrialEndTriggered` 字段、`handleTrialEnd()` 方法、`mainThreadRunner`（Handler 包装）、`backgroundLicensingServiceEnabled`。

**patch 策略**：不管架构怎么变，三处 patch（checkLicense→return-void、initializeLicenseCheck→return-void、performLocalInstallerCheck→return true）都能掐死整个校验链。`initializeLicenseCheck` 是状态机入口，return-void 后所有分支都不执行。

## 6. 常见故障排查

### 6.1 跳 Play 商店（"无法识别设备上安装的应用"）

**原因**：Play Protect stamp 元数据未清干净。

**排查**：
```bash
# 检查 manifest 是否还有残留
grep -E "com.android.stamp|com.android.vending.(splits|derived)|CHECK_LICENSE" AndroidManifest.xml
# 检查 stamp-cert-sha256 是否还在
ls unknown/stamp-cert-sha256
# 检查 splits0.xml 是否还在
ls res/xml/splits0.xml
```

**修复**：参照阶段 4-5，确保 6 项 manifest metadata + stamp-cert-sha256 + splits0.xml + public.xml 引用全删。

### 6.2 Unity 黑屏（开场动画后卡死）

**原因**：assets 文件被压缩，Unity 无法 mmap 读取。

**排查**：
```bash
# 检查 APK 内 assets 压缩状态
unzip -lv coex-8.0.X-signed.apk | grep "Defl" | grep "assets/"
# 如果有 mp4/webm/bundle/resource/json/unity3d 文件被 Defl 压缩 → 这就是原因
```

**日志特征**：
```
W Unity: AndroidVideoMedia::OpenExtractor could not translate jar:file:///data/app/.../base.apk!/assets/new_anim_correct.mp4 to local file. Make sure file exists, is on disk (not in memory) and not compressed.
W Unity: AndroidVideoMedia: Error opening extractor: -10004
W Unity: Timeout (2000 ms) while trying to pause the Unity Engine.
```

**修复**：参照阶段 7，补全 `apktool.yml` 的 `doNotCompress` 列表，重新打包。

### 6.3 apktool build 报 "no definition for declared symbol"

```
error: no definition for declared symbol 'com.Takotsubo.AlamobileFormula:xml/splits0'.
```

**原因**：`res/values/public.xml` 仍引用已删除的 `splits0`。

**修复**：
```bash
sed -i '/splits0/d' res/values/public.xml
```

### 6.4 apktool build 报 "First type is not attr!"

**原因**：apktool 默认用 aapt v1，不支持新版资源结构。

**修复**：加 `--use-aapt2` 参数。

### 6.5 安装报 "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

**原因**：旧版签名不同。

**修复**：先 `adb shell pm uninstall com.Takotsubo.AlamobileFormula`。

### 6.6 安装报 "get original signature failed"（NPatch 注入时）

**原因**：APK 未签名或仅 v1 签名。

**修复**：用 `apksigner`（不是 `jarsigner`）签名，确保 v2/v3。targetSdk 35 强制 v3。

## 7. Keystore 信息

- **路径**：`/home/takotsubo/projects/ala-mobile-tool/ala-mobile-tool.keystore`
- **类型**：PKCS12
- **别名**：`alamobiletool`
- **密码**：`alamobiletool`（store + key 同密码）
- **证书**：`CN=AlaMobileTool`
- **SHA-256**：`e0bc20a58d3c499360fd7a6e4de3155042bcb7151ba817507d61ffcac50de574`

**注意**：8.0.0 共存版用的是 `CN=Mod` 签名（历史遗留），8.0.4 共存版改用 `CN=AlaMobileTool`（与模块 release 签名一致）。两者不兼容，升级需先卸载旧版。

## 8. 产物归档

制作完成后：
```bash
# 复制到安装包目录
cp coex-8.0.X-signed.apk "../../安装包/Ala Mobile 8.0.X Takotsubo 共存版.apk"

# 保留 build 下副本
# coex-8.0.X-signed.apk（最终签名版）
# coex-8.0.X-rebuilt.apk（apktool 打包未签名）
# coex-8.0.X-bak.apk（上一版备份，可选）
```

## 9. 与模块的集成

共存版包名 `com.Takotsubo.AlamobileFormula` 需在模块中注册：

1. **`scope.list`**：加入 `com.Takotsubo.AlamobileFormula`
2. **`VersionGate.kt`**：兼容包名列表加入 `com.Takotsubo.AlamobileFormula`
3. **`BillingBridge`**：hook `com.Vince.AlamobileFormula.*` 的 smali 类路径不变（smali 目录不改），但进程名是 `com.Takotsubo.AlamobileFormula`

**关键**：共存版只改 manifest `package=` 和 `authorities=`，**不改 smali 目录**（`smali/com/Vince/AlamobileFormula/` 保持不变）。这是 8.0.0 coex 参照百分网的做法，避免改坏 R 类引用。

## 10. 安全提示

- 共存版 APK 不提交到 Git（体积 500MB+，`.gitignore` 已排除 `build/`）
- IL2CPP dump 产物不提交（`il2cpp-dumps/` 已排除）
- keystore 不提交（已在 `.gitignore`）
- 共存版仅供个人使用，不公开分发

## 11. 版本更新时的适配清单

当 Ala Mobile 发布新版本时，按以下顺序适配：

1. **下载新版 .apks** 到 `安装包/`
2. **反编译 base.apk** 到 `build/v8.0.X-official-dec/`
3. **对比 LicenseClient.smali**：检查 `checkLicense` / `initializeLicenseCheck` / `performLocalInstallerCheck` 方法签名是否变化
4. **对比 manifest**：检查是否有新的 `com.android.stamp.*` / `com.android.vending.*` metadata
5. **生成新 doNotCompress 列表**：`find v8.0.X-coex-dec/assets -type f`（排除 .dat）
6. **更新 VersionGate.kt**：加入新 versionCode
7. **更新 OffsetTable.kt**：重新跑 Il2CppDumper，提取新偏移量
8. **制作共存版 APK**：按本 skill 流程
9. **真机验证**：不跳 Play + 不黑屏 + 模块功能正常

## 12. 参考样本

`安装包/` 目录下有完整样本库：

| 文件 | 用途 |
|---|---|
| `Ala Mobile 7.7.9 官方原版.apks` | 7.7.9 官方 split APK |
| `Ala Mobile 7.7.9 百分网破解版.apk` | 7.7.9 百分网破解（参照配方） |
| `Ala Mobile 8.0.0 官方原版.apks` | 8.0.0 官方 split APK |
| `Ala Mobile 8.0.0 Takotsubo 共存版.apk` | 8.0.0 共存版（已验证可用） |
| `Ala Mobile 8.0.2/8.0.3 官方原版.apks` | 各版本官方 split APK |
| `Ala Mobile 8.0.2/8.0.3 百分网破解版.apk` | 各版本百分网破解（参照配方） |
| `Ala Mobile 8.0.4 官方原版.apks` | 8.0.4 官方 split APK |

`build/` 目录下有反编译产物：

| 目录 | 内容 |
|---|---|
| `v8.0.X-official-dec/` | 官方版反编译（参照 baseline） |
| `v8.0.X-cracked-dec/` | 百分网破解版反编译（参照 patch） |
| `v8.0.X-coex-dec/` | 共存版反编译（工作目录） |
| `origin-decompile/` | 8.0.0 共存版反编译（已验证可用，doNotCompress 参照） |
