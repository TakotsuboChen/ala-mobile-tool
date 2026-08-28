# Ala Mobile Tool

<p align="center">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/LSPosed%20API-102-green" alt="LSPosed API">
  <img src="https://img.shields.io/badge/version-1.0.1-green" alt="Version">
  <img src="https://img.shields.io/badge/target-Ala%20Mobile-red" alt="Target">
</p>

<p align="center">
  <strong>Ala Mobile 的体验增强 LSPosed 模块</strong><br>
  线性踏板操控 &middot; 原生 TC 控制 &middot; 音乐替换 &middot; V10 引擎声浪 &middot; 内购解锁
</p>

---

## 演示

### 踏板拓扑

| 单踏板 | 双踏板 |
|---|---|
| <img src="docs/images/single-pedal.gif" width="360" alt="单踏板演示"> | <img src="docs/images/dual-pedal.gif" width="360" alt="双踏板演示"> |

单踏板模式：手指在屏幕上下滑动，上半区控制油门、下半区控制刹车。

双踏板模式：左右两指分别控制独立的油门和刹车踏板。

### Overlay 控件

图标按钮可拖动，单击切换 Overlay 控件显示/隐藏，长按模块图标按钮进入编辑模式，拖拽移动 + 四角缩放各 Overlay 控件大小：

<img src="docs/images/overlay-edit.gif" width="360" alt="Overlay 编辑演示">

### 模块设置界面

miuix 风格三页布局（概览 / 配置 / 设置），支持深色模式：

<img src="docs/images/settings-ui.gif" width="180" alt="模块设置界面">

---

## ⚠️ 免责声明

> 本模块仅供 **个人学习、技术研究与逆向工程教学** 使用。
>
> 它通过修改游戏运行时行为（内存读写、函数钩子、输入注入）来增强操控体验，**可能违反游戏服务条款并导致账号暂时或永久封禁**。请在单机模式下使用，并自行承担所有风险。原作者不承担任何直接或间接损失的责任。

首次启动模块设置界面时会弹出完整的 **用户协议弹窗**（EULA v2），需阅读并点击「同意」后才能使用。

---

## 功能

### ✅ 已实现

#### 线性踏板（Pedal Overlay）
- **三种拓扑**：关闭、单踏板（上半油门/下半刹车）、双踏板（独立油门/刹车控件）
- **响应曲线**：线性 / 自定义（多点控制点 + 保单调三次样条），油门和刹车可分别设置
- **死区与过渡点**：单踏板模式下可调油门/刹车交界处的无效范围（0-20%）和分界线位置（20-80%）
- **踏板优先级仲裁**：双踏板模式下两指同时按下时的冲突仲裁策略，可选最早按住 / 最新触摸 / 始终油门 / 始终刹车 / 根据油门数值 / 根据刹车数值（默认），后两者配合可调过渡点（1-99%，默认 20%）
- **踏板方向反转**：双踏板模式下反转油门/刹车踏板的填充方向（关闭 / 仅油门 / 仅刹车 / 全部）

#### 主菜单音乐替换
- 将主菜单背景音乐替换为 **Hans Zimmer - F1**（320kbps）
- 默认开启。离开主菜单自动暂停，返回自动恢复
- 通过 Native Hook 静音游戏原生 `AudioSource.set_volume`，不依赖游戏设置

#### V10 引擎声浪
- 将开场动画背景音替换为 **V10 引擎声浪**
- 默认关闭。单次播放，播完即停
- 通过 Native Hook 静音游戏开场 `introSound`（真 `AudioSource.set_volume`），独立于主菜单音乐替换

#### 布局编辑
- 长按工具按钮进入编辑模式
- 拖拽移动 + 四角缩放各 Overlay 位置
- 长按编辑层重置到出厂默认位置
- 单踏板 / 双踏板模式的位置分别持久化，互不干扰

#### Overlay 视觉属性
- **可定制外观**：控件透明度（0-100%，默认 50%）、边框粗细（0-10dp，0 表示无边框，默认 5dp）、边框圆角（0-100%，圆角半径 = 比例 × 短边/2，0% 为直角，默认 50%）
- 同时作用于踏板控件与换挡按钮，配置变更后重建生效

#### 内购解锁（Unlock / IAP Bypass）
- 双重锁定路径：**Native inline Hook**（主路径）+ **Java 层 Xposed Hook**（辅助路径）
- native 路径：`BillingManager.Awake` + `GetInstance`（兜底）+ 15 秒延迟 `forceUnlockNow` one-shot
- Java 路径：`BillingBridge.checkOwned` / `checkOwnedInternal` → 注入 `OnAlreadyOwned` 消息
- 首次解锁弹窗后写 `/data/data/<pkg>/files/ala_unlock_done.flag`，后续启动不再弹窗
- 解锁走 `SetUnlocked(true)` 完整路径写 PlayerPrefs（`AnciTuttu`），修复「标记文件残留但 PlayerPrefs 丢失」导致车库车锁着的问题
- **关闭解锁开关时**仍 hook `Awake` 预设 `IsUnlocked=true` + 阻挡 `OnOwnedNone`/`OnPurchaseFailed` 弹窗，
  防止正版用户每次冷启动看到"Fresh unlock"弹窗，同时让游戏正常走 Google Play 查询流程

#### 原生 TC 控制
- 通过 hook `TractionFilter`（TC）在入口处决定是否跳过
- **TC 已生效**：关闭时 TractionFilter 直接返回原始 accel，不削减
- **档位调节**（v1.4）：削减强度（TractionFilter 返回值插值）+ 介入时机
  ((ε, minSPD) 成对覆写，含起步 0~40 km/h 低速门)，游戏默认档纯透传

#### 配置即时生效
- 三路配置同步：**Remote Preferences**（LSPosed daemon SQLite）→ **ConfigProvider**（ContentProvider）→ **定向广播**
- 游戏不运行时改配置：下次启动从 daemon 读取最新值，不再丢失
- 游戏运行时改配置：广播即时推送，Overlay 重建生效

#### 日志系统
- 「启用日志」开关（默认关闭），`logEnabled` 统一门控 Java 层 `Logger` 与 native 层 `native_log` 两侧
- 模块进程与游戏进程日志写入统一文件，设置页一键导出并分享（FileProvider + ShareSheet），导出仅含最近 24h 条目
- 游戏→模块日志经定向广播（`setComponent`）推送，规避包可见性与 IntentFirewall 限制

#### AI 车隔离
- 通过 `IRDSPlayerControls.Update()` 捕获玩家车 controller（该组件只挂在玩家车 GameObject 上）
- writer 线程只写 `g_player_controller`（由 `IRDSPlayerControls` 设置），不受 AI 车干扰
- 油门/刹车 Hook 中 `is_player_controller` 检查 `playerControls` 字段（偏移 0x108）过滤 AI 车

#### 多框架兼容
- 支持 **LSPosed**（Root）和 **NPatch**（非 Root）框架
- NPatch 路径：通过 `content://top.nkbe.npatch.remote` ContentProvider 拿 remote service binder 读写配置
- LSPosed 共存版：`System.setProperty` 进程级标记避免双 ClassLoader 重复注入

#### LSPosed 激活检测
- 概览页显示激活状态（已激活 / 未激活），冷启动检测一次后会话级固定，下次冷启动重新检测
- 事件驱动判定（去轮询）：ConnectionState 三态等待 + onServiceDied service 身份检查，避免超时误弹免 Root 弹窗
- 判定优先级：游戏进程 `onModuleLoaded` 标记 > `frameworkName == "LSPosed"` > Non-root 确认标记 > 未激活
- NPatch 等非 Root 框架（`frameworkName == "NPatch"`，API 101）不算 LSPOSED；检测到 NPatch 已安装才弹"作用域确认"弹窗，未安装时点击激活卡片 Toast 提示

#### 游戏版本检测
- 概览页标题上方显示官版/共存版版本检测胶囊（每次启动自动检测）
- 用 `<queries>` 声明两个游戏包名，`getPackageInfo` 静默查询，无需运行时权限
- 已适配（8.0.4）显示绿色胶囊，未适配显示红色胶囊，未安装显示黄色胶囊
- 下滑收缩时胶囊即时隐藏，上滑恢复后延迟渐显，避免与居中小标题重叠

#### 现代 UI
- Miuix 风格三页布局（概览 / 配置 / 设置），支持深色模式
- 顶栏/底栏毛玻璃（blurRadius=12f），下滑顶栏折叠
- 内嵌 GitHub 与 QQ 群入口图标（SVG Path 渲染）

#### Tool Button（工具按钮）
- 96×96dp 圆角矩形按钮，前景为 App 图标（Base64 内嵌，绕过跨进程资源访问限制）
- 单击：切换 Overlay 展开/折叠
- 长按：进入编辑模式
- 拖动：移动按钮自身位置（**不持久化**，每次打开游戏重置到默认位置）

#### 版本门控
- `VersionGate` 精准校验包名（原版 + 共存版）、`versionName` 和 `versionCode`
- 非 8.0.4（200146）版本拒绝加载 Native Hook，避免 IL2CPP 偏移不匹配导致崩溃

#### 用户协议（EULA）
- 强制确认弹窗，按版本号持久化（当前 v2），条款更新重新弹窗
- 存于模块进程 `filesDir`，`pm clear` / 卸载重装自动清除，语义正确
- 协议同意前激活弹窗不可见，保证协议优先
- 协议弹窗需滚到底部阅读完才能点击「同意」

#### vivo/iQOO OriginOS 兼容
- 所有重操作（ShadowHook 初始化、JNI 调用、Service 绑定）延迟到 `Handler.post` 下一主循环
- 避开 `createOrUpdateClassLoaderLocked` 内部 Resources 未初始化时序窗口

#### 检查更新
- 启动时自动检查 GitHub Releases（官方 + 镜像站竞速），有新版本自动弹窗
- 稳定版通道仅检查正式 Release，预览版通道同时检查 Pre-release
- Release Note 轻量 Markdown 渲染，下载进度实时显示，下载完成调起安装器
- 支持「跳过该版本」（仅影响自动弹窗，手动检查不受限）和「清除跳过更新标记」
- 同版本已下载的 APK 不重复下载，直接调起安装器；启动时自动清理旧版本残留 APK

#### 原生 TC/ABS 控制
- 牵引力控制（TC）：默认 / 自定义（削减强度 + 介入时机档位，自定义时机经 (ε, minSPD) 成对覆写生效）；关闭 TC 档直接返回原始 accel
- 防抱死制动系统（ABS）：默认 / 自定义（干预强度档位，覆写 pulse 释放深度 b，抬升"干预方波平均"缓解游戏 ABS 全段过度保护）；关闭 ABS 档经 per-wheel `usesABS=false` 双重门控，重刹轮子直接锁死
- 最大制动压力：50–100% 无级，等比缩放制动摩擦扭矩上限（与 ABS 模式正交，关 ABS 下也生效，缓解"关 ABS 秒锁死"的制动基数病态）；踏板响应曲线不受影响

#### 支持开发
- 概览页「支持开发」卡片，展示收款码，可保存到相册

### 🚧 开发中

- **自动 DRS**：已完成 `drsToggle()` 拦截，telemetry 赛道数据解析待实现
- **手动换挡**：`DoGearShifting` Hook 导致起步失败，需重新实现

---

## 安装

### 前提条件

- 一部已 Root 的 Android 手机，安装了 **LSPosed**（或兼容分支）框架
- 已安装 **Ala Mobile 8.0.4**（versionCode 200146）

### 步骤

1. 从 [Releases 页面](https://github.com/TakotsuboChen/ala-mobile-tool/releases) 下载最新 APK
2. 安装 APK 到手机
3. 打开 **LSPosed Manager**，在模块列表中找到 **Ala Mobile Tool** 并启用
4. 在作用域中勾选 **Ala Mobile**（原版 `com.Vince.AlamobileFormula` 或共存版 `com.Takotsubo.AlamobileFormula`）
5. 启动游戏，进入后点击左上角工具按钮显示 Overlay

> 💡 **首次使用**：建议先进入模块设置界面（打开模块 App）确认配置，再进入游戏。

### 非 Root 用户（NPatch）

1. 安装 [NPatch](https://github.com/nkbe/npatch) 框架、共存版游戏与模块
2. 在 NPatch 中 Ala Mobile 的模块作用域勾选 Ala Mobile Tool

---

## 配置

在 LSPosed 模块列表中点击 **Ala Mobile Tool** 打开设置界面（三页布局）：

### 概览页
- 模块激活状态卡片（已激活 / 未激活，绿/红配色）
- 游戏版本检测胶囊：官版与共存版适配状态（已适配绿色 / 未适配红色 / 未安装黄色）
- 设备信息：版本名称、版本号、Android 版本、厂商、型号
- 链接：GitHub Releases、QQ 群（直接拉起 QQ App）、GitHub 源代码
- 支持开发卡片：展示收款码，可保存到相册

### 配置页

| 分类 | 功能项 | 说明 |
|---|---|---|
| 游戏原生特性控制 | 解锁付费内容 | 强制解锁 DLC 和 IAP（默认关闭） |
| | 牵引力控制（TC） | 默认 / 自定义：削减强度（关闭 TC~最高）+ 介入时机（较晚（默认）~实时） |
| Overlay 控件 | 线性踏板 | 拓扑选择：关闭 / 单踏板 / 双踏板 |
| | 死区（单踏板） | 0-20%，踏板过渡区域附近的无效范围 |
| | 过渡点（单踏板） | 20-80%，油门与刹车区域的分界线 |
| | 踏板优先级（双踏板） | 最早按住 / 最新触摸 / 始终油门 / 始终刹车 / 根据油门数值 / 根据刹车数值 |
| | 油门过渡点（双踏板） | 1-99%，选"根据油门数值"时显示（默认 20%） |
| | 刹车过渡点（双踏板） | 1-99%，选"根据刹车数值"时显示（默认 20%） |
| | 踏板方向反转（双踏板） | 关闭 / 仅油门 / 仅刹车 / 全部 |
| | 控件透明度 | 0-100%，整个控件的透明度（默认 50%） |
| | 边框粗细 | 0-10dp，0 表示不显示边框（默认 5dp） |
| | 边框圆角 | 0-100%，圆角半径 = 比例 × 短边/2，0% 时为直角（默认 50%） |
| 响应曲线 | 油门响应曲线 | 线性 / 自定义（多点控制点），线性踏板关闭时整组收回 |
| | 刹车响应曲线 | 线性 / 自定义（多点控制点），线性踏板关闭时整组收回 |
| 杂项 | 替换主菜单音乐 | 更换为 Hans Zimmer - F1（默认开启） |
| | 替换开场动画背景音 | 更改为 V10 引擎声浪（默认关闭） |

### 设置页

| 功能项 | 说明 |
|---|---|
| 模块更新通道 | 稳定版（仅正式 Release）或预览版（含 Pre-release） |
| 启用日志 | 开关日志记录（logEnabled 统一门控 Java/native 文件输出，默认关闭） |
| 导出并分享日志 | 合并模块与游戏进程日志，经 FileProvider 分享 |
| 清除激活标记 | 删除 Non-root 确认标记与旧版激活残留（filesDir，pm clear 可清）；不碰 EULA 同意状态；清除后本次会话状态不变，下次冷启动重新检测 |
| 清除跳过更新标记 | 恢复被跳过版本的自动弹窗提示 |
| 用户协议 | 重新查看并确认用户协议 |

---

## 已测试版本

| 版本 | versionCode | 架构 |
|---|---|---|
| 8.0.4 | 200146 | arm64-v8a |

> 已做版本门控，其他版本不会生效。IL2CPP 方法地址随版本变化，非匹配版本无法加载 Native Hook。

---

## 已知问题

- 自动 DRS 仅完成 `drsToggle()` 拦截，赛道数据自动判断尚未实现
- 手动换挡因 `DoGearShifting` Hook 导致起步失败，**已暂时禁用**，待重新实现
- ~~ABS 原生 Hook（`HandleABS`）被编译器内联~~ → 已解决：carController hook + per-wheel `usesABS=false` 双重门控
- **弯道「顿挫」通常来自游戏内置的刹车辅助**，与模块无关，请在游戏设置中关闭辅助功能
- 共存版（重打包改包名）需配合双 ClassLoader 守卫，当前版本已修复

---

## 技术架构

```
Ala Mobile Tool (LSPosed 模块 APK)
├── ConfigActivity          # Compose + miuix 设置界面（模块进程）
├── AlaMobileModule         # Xposed 模块入口（游戏进程）
├── NativeBridge            # JNI 桥接（Java → C）
├── PedalOverlayView        # 双区踏板覆盖（Canvas View）
├── GearShiftView           # 换挡按钮（Canvas View）
├── ToolButtonView          # 工具按钮（Canvas View）
├── OverlayManager          # WindowManager 覆盖层管理
├── OverlayEditView         # 编辑模式拖拽/缩放层
├── MusicPlayer             # 主菜单音乐播放器（MediaPlayer + 轮询）
├── IntroSoundPlayer        # V10 引擎声浪播放器（MediaPlayer + 轮询）
├── ConfigProvider          # 跨进程配置 IPC（ContentProvider）
├── ConfigReceiver          # 配置广播接收器
├── BillingHook             # Java 层内购解锁（Xposed Hook）
├── LsposedStatus           # 激活状态判定（frameworkName 区分框架 + Non-root 弹窗确认）
├── EulaManager             # 用户协议管理
├── VersionGate             # 版本门控
└── libala-core.so          # ShadowHook 原生引擎（arm64-v8a）
    ├── pedal_hook          # 油门/刹车/换挡 + writer 线程
    ├── drs_hook            # DRS 拦截
    ├── unlock_hook         # BillingManager 解锁（主路径）
    ├── music_hook          # 主菜单音乐静音 + 心跳检测
    └── intro_hook          # 开场 V10 引擎声：静音 introSound + one-shot 信号
```

**技术栈：**
- 现代 libxposed API 102（min 101）
- Kotlin 2.4.10 + Jetpack Compose + miuix KMP 0.9.3
- ByteDance ShadowHook 2.0.1（UNIQUE 模式）
- C 语言 IL2CPP 运行时 inline Hook
- AGP 9.3.1 / NDK 26.1.10909125 / Gradle 9.6.1

---

## 自行构建

需要 Android SDK（API 37）和 NDK r26c。

```bash
./gradlew :app:assembleDebug    # 调试构建
./gradlew :app:assembleRelease  # 发布构建（需签名配置）
./gradlew :app:lint             # 运行 lint（含 baseline）
```

**逆向工具：**
```bash
tools/run-il2cpp-dumper.sh
```

该脚本调用 [Il2CppDumper](https://github.com/Perfare/Il2CppDumper) 生成 `dump.cs`，用于提取目标方法偏移并更新 `OffsetTable.kt`。

---

## 路线图

- [ ] 自动 DRS：赛道 telemetry 解析 + 自动开关
- [ ] 手动换挡：重新实现 `DoGearShifting` Hook
- [x] 原生 ABS 开关：carController hook + per-wheel usesABS=false 双重门控
- [ ] 工具按钮位置持久化开关
- [ ] 多语言支持

---

## 社区与支持

- **GitHub Issues**：[报告 bug 或建议新功能](https://github.com/TakotsuboChen/ala-mobile-tool/issues)
- **QQ 群**：[加入玩家交流群](https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi/jgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9ocjI1NXo5T1hLZ2FJVkZXZkhlMVAiLCJ1aW4iOiIxMjU5OTc2NTIwIn0=&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info)
- 欢迎提交 Pull Request

---

## 许可证

本项目基于 **Apache License 2.0** 开源。详见 [LICENSE](LICENSE) 文件。

游戏名称、素材、商标版权归其各自所有者；与游戏开发商、发行商无任何关联或授权关系。

---

## 版本历史

<details>
<summary>展开查看</summary>

**v1.0.1**（2026-08-26，正式版，versionCode 101300）
> 踏板操控与激活检测增强。新增踏板优先级枚举（6 策略仲裁 + 油门过渡点，替代旧单一刹车优先）、踏板方向反转枚举（油门/刹车可独立反转）、Overlay 视觉属性（透明度/边框粗细/边框圆角三个可配置滑块，作用于踏板与换挡控件）；LSPosed 激活检测稳定性大修（去轮询改事件驱动 + ConnectionState 三态 + service 身份检查，根治超时误弹免 Root 弹窗）；激活状态会话级持久化（冷启动检测一次固定到下次冷启动，清除标记下次冷启动重新检测）。

**v1.0.0**（2026-08-19，正式版，versionCode 100300）
> 首个正式版本。新增完整日志系统（logEnabled 开关统一门控 Java/native 文件输出 + 一键导出分享）；NPatch 激活确认流程收窄（激活弹窗收敛为「NPatch 作用域确认」，仅检测到 NPatch 已安装才弹出，未安装点击激活卡片 Toast 提示）；解锁持久化加固（走 `SetUnlocked(true)` 完整路径写 PlayerPrefs，修复标记文件残留导致车库车锁着的问题）。修复响应曲线图表在平板/横屏下高度溢出撑爆卡片、LSPosed 激活检测轮询超时误弹免 Root 弹窗。

**v1.0.0 Beta 5**（2026-08-18）
> 新增 V10 引擎声浪替换开场动画背景音、应用内检查更新（GitHub 官方+镜像竞速，Markdown Release Note 渲染，下载+安装+跳过版本）、更新通道设置（稳定版/预览版）、概览页游戏版本检测胶囊（官版/共存版适配状态）；恢复 EULA 启动门控（滚到底才能同意）；弹窗退出动画；支持开发卡片+捐赠弹窗；QQ 群卡片直接拉起 QQ App。配置页重组：功能开关改名游戏原生功能控制（仅解锁+TC），主菜单音乐与 V10 声浪移至杂项分类；设置页重组为模块更新通道/日志/激活协议三组，新增清除跳过更新标记；线性踏板关闭时响应曲线整组收回；移除显示悬浮窗开关。修复弹窗系统返回键不关闭直接退桌面（删除手动 NavigationEventDispatcherOwner）、清除激活标记误清 EULA 同意状态。

**v1.0.0 Beta 4**（2026-08-17）
> 新增自定义油门/刹车响应曲线编辑器（多点控制点 + 保单调三次样条）；修复配置修改后不立即生效（daemon 旧值覆盖新配置）、切换踏板模式后位置/大小丢失、native 库加载失败（移除 useLegacyPackaging）；适配 Ala Mobile 8.0.4（versionCode 200146，8.0.0 用户收到不支持警告）；底栏快速切换与切页掉帧修复。CI 自动构建 + tag 触发 Pre-release。

**v1.0.0 Beta 3**（2026-08-12）
> 配置页 UI 视觉大改。底栏切 tab 动画改为 KernelSU 式 `animateScrollBy`（连点不卡不错乱）；下滑顶栏折叠、标题居中；顶栏/底栏加毛玻璃（blurRadius=12f）；新增 miuix-blur 依赖。新增用户协议强制确认弹窗、配置页开关图标自定义、主菜单音乐替换（Hans Zimmer - F1，320kbps）、原生 TC 开关；修复 AI 车油门误控、LSPosed 激活判定误判（改 `frameworkName`）、NPatch 非 Root 配置跨进程同步、WSL2 Kotlin 编译卡死。真实设备已验证。

**v1.0.0 Beta 2**（2026-07-30）
> 踏板与配置体验大改。新增单/双踏板拓扑下拉、双踏板刹车优先仲裁与方向反转开关、长按重置出厂布局、SINGLE/DUAL 位置隔离持久化、配置即时生效（Remote Preferences 三路同步）；修复游戏自带方向键被 writer 持续清零导致的转向失灵；概览页激活卡片改为 KernelSU 风格对称配色，激活检测改用 LSPosed daemon 绑定状态。

**v1.0.0 Beta 1**（2026-07-29）
> 共存版稳定性大修。修复双 ClassLoader 注入导致的重复 hook/双 writer 线程；废除非原子的文件 IPC 路径，统一走 JNI 直调；用 rawY + 配置值坐标绕开 pairip 壳的 relayout 漂移。CI 自动构建 + tag 触发 Pre-release。

**v1.0.0 Alpha 2**（2026-07-28）
> UI 重构为 KernelSU 风格，修复多个编译错误，改进深色模式支持。

**v1.0.0 Alpha 1**（2026-07-28）
> 初始发布，包含踏板覆盖、换挡按钮和基础 DRS 拦截功能。

</details>