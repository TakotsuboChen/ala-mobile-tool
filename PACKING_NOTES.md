# Ala Mobile 重打包工作记录

## 🎉 破解成功！(2026-07-28)

**最终方案：LSPosed 模块 + Java 层 Hook**

成功解锁付费内容，无需修改 APK 本身。通过 LSPosed 模块在运行时 hook `BillingBridge` 类，绕过 Google Play 购买验证。

### 核心技术
```kotlin
// Hook BillingBridge.checkOwned() 和 checkOwnedInternal()
// 拦截 Google Play 查询，直接发送 "OnAlreadyOwned" 消息给 Unity
sendUnityMessage("OnAlreadyOwned", "unlock_alamobile")
```

### 为什么成功
- 直接在 Java 层短路购买验证流程
- Unity 收到 `OnAlreadyOwned` 后立即解锁所有内容
- 无需处理 `BillingManager` 的错误弹窗（因为根本没发起验证）

### 使用说明
1. 安装 LSPosed 模块（`tools.alamobile.mod`）
2. 在 LSPosed 中启用模块并勾选游戏作用域
3. 在模块配置中开启"解锁付费内容"开关
4. 启动游戏即可

---

## 项目目标

将 Ala Mobile 官方 APK 重打包为：
- 包名：`com.Takotsubo.AlamobileFormula`（与官版共存）
- 移除 Google Play 依赖
- 解锁付费内容（DLC、IAP）
- 生成单体 APK 供非 Root 用户测试

## 工作目录

所有打包工作在 `/tmp/ala-apk-work/` 临时目录进行，不污染主仓库。

### 目录结构
```
/tmp/ala-apk-work/
├── apktool_new.jar              # apktool 2.10.0（处理新 Android 版本）
├── mod_key.jks                  # 自签名密钥库
├── extracted/
│   └── base_v2/                 # 解压后的 APK（已修改）
│       ├── smali_classes2/      # 反编译的 Java 代码
│       └── assets/aa/           # Unity Addressables 资源
├── output/                      # 生成的 APK 输出目录
└── scripts/                     # 自动化脚本
```

## 工作流程

### 1. APK 解压与准备
```bash
# 解压 Split APK（base.apk + arm64 split）
cp /tmp/ala-mobile-tool/base.apk /tmp/ala-apk-work/
unzip -q /tmp/ala-apk-work/base.apk -d /tmp/ala-apk-work/extracted/base_v2/
```

### 2. 修改清单文件
**文件**: `extracted/base_v2/AndroidManifest.xml`

关键修改：
- `package="com.Vince.AlamobileFormula"` → `package="com.Takotsubo.AlamobileFormula"`
- 移除所有 `com.google.android.gms.*` 权限和服务
- 移除 Google Play 相关组件（广告、支付、分析）
- 保留 Unity 核心组件和必要权限

### 3. 绕过 License 验证
**文件**: `extracted/base_v2/smali_classes2/com/pairip/licensecheck/LicenseClient.smali`

修改 `checkLicense()` 方法：
```smali
# 直接返回 LICENSED (0)
sget-object v0, Lcom/pairip/licensecheck/LicenseClient$LicenseCheckState;->FULL_CHECK_OK:Lcom/pairip/licensecheck/LicenseClient$LicenseCheckState;
sput-object v0, Lcom/pairip/licensecheck/LicenseClient;->licenseCheckState:Lcom/pairip/licensecheck/LicenseClient$LicenseCheckState;
return-void
```

### 4. 绕过 Play Asset Delivery (PAD)
**文件**: `extracted/base_v2/smali_classes2/com/unity3d/player/PlayAssetDeliveryUnityWrapper.smali`

修改 `getAssetPackStates()` 方法，直接返回 COMPLETED 状态：
```smali
# 绕过 Play Core 服务调用
# 创建 COMPLETED 状态的 AssetPackState 对象
new-instance v0, Lcom/google/android/play/core/assetpacks/AssetPackState;
const/4 v1, 0x4  # COMPLETED = 4
invoke-direct {v0, p1, v1}, Lcom/google/android/play/core/assetpacks/AssetPackState;-><init>(Ljava/lang/String;I)V
```

### 5. 修改 Addressables 配置
**文件**: `extracted/base_v2/assets/aa/settings.json`

修改内容：
- `m_DisableCatalogUpdateOnStart`: true（禁用远程更新）
- `m_CatalogLocations[0].m_InternalId`: 指向本地 `{UnityEngine.AddressableAssets.Addressables.RuntimePath}/catalog.json`

### 6. 合并 Split APK 资源
```bash
# 从 arm64 split 复制原生库
cp -r /tmp/ala-apk-work/extracted/arm64/lib/* /tmp/ala-apk-work/extracted/base_v2/lib/

# 从 Unity data split 复制资源
cp -r /tmp/ala-apk-work/extracted/unity_data/assets/* /tmp/ala-apk-work/extracted/base_v2/assets/
```

### 7. 视频文件解压
**问题**: Unity 需要从 APK 直接读取未压缩的视频文件

**解决**: 在 `apktool.yml` 的 `doNotCompress` 列表中添加：
```yaml
doNotCompress:
  - mp4
  - webm
```

### 8. 重新编译与签名
```bash
cd /tmp/ala-apk-work/extracted/base_v2
java -jar /tmp/ala-apk-work/apktool_new.jar b . -o /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v9.apk

apksigner sign --ks /tmp/ala-apk-work/mod_key.jks \
  --ks-key-alias mod_signer --ks-pass pass:modpass123 \
  --key-pass pass:modpass123 \
  /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v9.apk
```

## 关键问题与解决方案

### 问题 1: INSTALL_FAILED_MISSING_SPLIT
**现象**: 安装时报错 `INSTALL_FAILED_MISSING_SPLIT`

**原因**: 清单文件中保留了 Split APK 相关属性

**解决**: 
- 移除 `android:requiredSplitTypes="base__abi"`
- 移除 `android:splitTypes=""`
- 移除 `android:isSplitRequired="true"`

### 问题 2: Unity 视频文件无法读取
**现象**: `AndroidVideoMedia::OpenExtractor could not translate`

**原因**: APK 编译时压缩了 mp4/webm 文件

**解决**: 在 `apktool.yml` 的 `doNotCompress` 列表中添加视频格式

### 问题 3: VerifyError - 寄存器数量不足
**现象**: `java.lang.VerifyError: register v1 has type Conflict`

**原因**: Smali 代码中寄存器数量声明 `.locals` 不足

**解决**: 增加 `.locals` 声明并使用 `invoke-interface/range`

### 问题 4: 加载进度条卡死
**现象**: Unity 启动后卡在加载界面，日志显示：
```
<CheckAndUpdateInstalledDLCs>d__28:MoveNext()
<LoadSequence>d__14:MoveNext()
```

**原因**: Unity Addressables 等待 Play Asset Delivery 返回资源状态

**解决**: 
1. 修改 `PlayAssetDeliveryUnityWrapper.getAssetPackStates()` 直接返回 COMPLETED
2. 修改 `settings.json` 禁用远程 catalog 更新
3. 确保本地 catalog.json 存在且路径正确

### 问题 5: 签名失败
**现象**: `apksigner` 报错 `Failed to load signer`

**原因**: 
- 密钥库路径错误
- 密钥库密码错误
- 磁盘空间不足

**解决**: 
- 使用正确的密钥库路径和密码
- 清理 /tmp 目录释放空间

## 当前状态

### 已解决的问题
- ✅ 包名修改成功
- ✅ Google Play 依赖移除
- ✅ License 验证绕过
- ✅ Play Asset Delivery 绕过
- ✅ Split APK 合并
- ✅ 视频文件解压
- ✅ APK 签名成功
- ✅ Unity Data 合并
- ✅ arm64 库合并
- ✅ Addressables 配置

### v10 版本改进 (基于百分网策略)

**关键发现**:
百分网 7.7.9 破解版采用"单体 APK + 完整 doNotCompress"策略：
1. 所有 44 个 bundle 文件内嵌到 APK
2. Unity Data (17 个文件) 内嵌到 `assets/bin/Data/`
3. doNotCompress 列表包含 69 个条目
4. `playCoreApiMissing()` 返回 true，Unity 跳过 PAD

**修改内容**:
- `PlayAssetDeliveryUnityWrapper.smali`:
  - 构造函数：跳过 AssetPackManager 初始化 (a=null)
  - `playCoreApiMissing()`: 强制返回 true
  - `getAssetPackStates()`: 直接返回
  - `requestAssetPack()`/`removeAssetPack()`: 空操作
- `LicenseClient.smali`:
  - `checkLicense()`: 直接返回
  - `initializeLicenseCheck()`: 直接返回
- `AndroidManifest.xml`:
  - 包名改为 `com.Takotsubo.AlamobileFormula`
  - 移除所有 split 相关属性
  - 移除 Google Play 权限、服务、活动
- `apktool.yml`:
  - 完善 doNotCompress 列表 (69 条目)

### 待解决的问题
- ⚠️ 需要在设备上测试加载是否正常

## v10 构建详情 (2026-07-28)

### 基于百分网策略的改进

**分析百分网 7.7.9 破解版的关键发现**:
1. **单体 APK 设计** — 所有资源内嵌，不依赖 Split 或 PAD
2. **完整的 doNotCompress 列表** — 69 个条目，包括所有 bundle、unity3d、resource
3. **Play Asset Delivery 绕过** — `playCoreApiMissing()` 返回 true，Unity 跳过 PAD
4. **License 检查绕过** — `checkLicense()` 直接返回

### 构建步骤

```bash
# 1. 解压 base.apk
cd /tmp/ala-apk-work
java -jar apktool_new.jar d /tmp/ala-icon-extract/apks/base.apk -o repack_v10/base_decompiled -f

# 2. 合并 Split APK 资源
cd repack_v10/base_decompiled
mkdir -p lib
unzip -q /tmp/ala-icon-extract/apks/split_config.arm64_v8a.apk 'lib/*' -d .
unzip -q -o /tmp/ala-icon-extract/apks/split_UnityDataAssetPack.apk 'assets/*' -d unity_data_tmp
cp -r unity_data_tmp/assets/* assets/
rm -rf unity_data_tmp

# 3. 精确修改 AndroidManifest.xml (Python)
python3 /tmp/ala-apk-work/fix_manifest.py AndroidManifest.xml

# 4. 绕过 License 检查
python3 /tmp/ala-apk-work/fix_license.py smali/com/pairip/licensecheck/LicenseClient.smali

# 5. 绕过 Play Asset Delivery
python3 /tmp/ala-apk-work/fix_pad.py smali_classes2/com/unity3d/player/PlayAssetDeliveryUnityWrapper.smali

# 6. 更新 doNotCompress 列表
python3 /tmp/ala-apk-work/fix_apktool_yml.py apktool.yml .

# 7. 重新编译
java -jar /tmp/ala-apk-work/apktool_new.jar b . -o /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk --use-aapt2

# 8. 对齐 + 签名
zipalign -f 4 /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10_aligned.apk
mv /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10_aligned.apk /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk
apksigner sign --ks /tmp/ala-apk-work/mod_key.jks --ks-key-alias mod_signer --ks-pass pass:modpass123 --key-pass pass:modpass123 /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk
```

### 输出文件

**路径**: `/tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk`
**大小**: 509MB
**包名**: `com.Takotsubo.AlamobileFormula`
**签名**: ✅ 已签名
**对齐**: ✅ 已对齐

### 验证结果

```
Package: com.Takotsubo.AlamobileFormula
Split 引用: 0 (已清除)
License 引用: 0 (已清除)
Bundle 文件: 44 个
Unity Data: 17 个文件
arm64 库: 4 个 .so
```

## 下一步调试方向

1. **设备安装测试**
   ```bash
   adb install /tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v10.apk
   ```

2. **检查加载日志**
   ```bash
   adb logcat -s Unity | grep -i "addressable\|catalog\|bundle\|error\|pad"
   ```

3. **如果仍然卡死**
   - 检查 `catalog.json` 路径解析是否正确
   - 验证 bundle 文件是否完整（对比官版）
   - 查看 Unity 是否在等待其他异步操作（如 DLC 检查）

4. **IL2CPP 层调试**
   - 使用 LSPosed 模块 hook `CheckAndUpdateInstalledDLCs` 方法
   - 强制返回"已安装"状态

## 技术要点

### Unity Addressables 工作原理
- `settings.json`: Addressables 主配置文件
- `catalog.json`: 资源目录，描述所有 bundle 的位置和依赖关系
- `AssetPackManager`: Play Core 提供的资源管理 API
- `PlayAssetDeliveryUnityWrapper`: Unity 对 PAD 的封装层

### PAD 状态码
```java
UNKNOWN = 0
PENDING = 1
DOWNLOADING = 2
TRANSFERRING = 3
COMPLETED = 4
FAILED = 5
CANCELED = 6
WAITING_FOR_WIFI = 7
NOT_INSTALLED = 8
```

### 关键 Smali 修改模式
```smali
# 创建对象
new-instance v0, Lcom/package/ClassName;

# 调用构造函数
invoke-direct {v0, p1}, Lcom/package/ClassName;-><init>(I)V

# 设置字段
sput-object v0, Lcom/package/ClassName;->fieldName:Lcom/package/FieldType;

# 返回值
return-object v0
```

## 工具与资源

### 必需工具
- `apktool` 2.10.0+（处理新 Android 版本）
- `apksigner`（APK 签名）
- `adb`（安装和调试）
- `jadx-gui`（可视化反编译，可选）

### 密钥库信息
- 路径: `/tmp/ala-apk-work/mod_key.jks`
- 别名: `mod_signer`
- 密码: `modpass123`

### 生成的 APK
- 路径: `/tmp/ala-mobile-tool/AlaMobile_8.0.0_Takotsubo_v9.apk`
- 包名: `com.Takotsubo.AlamobileFormula`
- 版本: 8.0.0 (200142)

## 时间线

| 时间 | 里程碑 | 状态 |
|------|--------|------|
| 10:30 | 开始重打包工作 | ✅ |
| 10:45 | 完成清单修改 | ✅ |
| 11:00 | 绕过 License 验证 | ✅ |
| 11:15 | 绕过 Play Asset Delivery | ✅ |
| 11:30 | 修改 Addressables 配置 | ✅ |
| 11:45 | 解决视频文件问题 | ✅ |
| 12:00 | 完成签名和安装 | ✅ |
| 12:05 | 发现加载卡死问题 | ⚠️ |

## 总结

本次重打包工作成功实现了大部分目标：
- ✅ 包名修改和共存
- ✅ Google Play 依赖移除
- ✅ License 验证绕过
- ✅ Play Asset Delivery 绕过
- ✅ APK 编译和签名

**剩余问题**: 游戏加载卡死，需要进一步调试 Unity Addressables 的资源加载流程。

建议下一步：
1. 使用 `adb logcat` 深入分析 Addressables 初始化日志
2. 在 IL2CPP 层面 patch 游戏启动逻辑
3. 或考虑使用 Unity Asset Bundle 提取工具重新打包资源
