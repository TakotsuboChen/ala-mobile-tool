# Ala Mobile 8.0.0 付费解锁修复任务

> **📦 历史归档（2026-08-28）**——本文是 8.0.0 (200142) 时代的一次性任务清单，全部方法偏移为 8.0.0 专属、在 8.0.4 (200146) 上已失效。解锁路径此后经历多次重构：走 `SetUnlocked(true)` 完整路径写 PlayerPrefs 持久化（1.0.0）、native `BillingManager.Awake`/`GetInstance` 主路径 + 15 秒延迟 one-shot、Java 层 `BillingBridge` 辅助路径（现状见 README「内购解锁」小节与 `native/src/unlock_hook.c`）。本文仅作历史参考。

## 问题分析
- [x] 分析 dump.cs 中 BillingManager 完整结构
- [x] 确定问题根源：Awake() 触发 InitializeBilling() → CheckOwned() → Google Play 验证
- [x] 识别需要 hook 的关键方法：
  - Awake() - 完全跳过，直接设置解锁状态
  - InitializeBilling() - 阻止初始化
  - OnOwnedNone() - 阻止错误弹窗
  - OnPurchaseFailed() - 阻止失败弹窗

## 代码更新
- [x] 更新 unlock_hook.h - 添加新函数指针和字段偏移量
- [x] 重写 unlock_hook.c - 实现 4 个方法的 hook
- [x] 更新 OffsetTable.kt - 添加所有必需偏移量
- [x] 更新 NativeBridge.kt - 传递新参数给 native 层
- [x] 更新 ala_core.c - 更新 JNI 签名和配置结构

## 编译和部署
- [x] 编译项目（./gradlew clean build）
- [x] 安装到设备（adb install）
- [ ] 配置模块启用解锁功能
- [ ] 启动游戏测试
- [ ] 验证付费内容是否解锁
- [ ] 验证没有弹窗或强制退出

## 技术细节
### 新方法偏移量
- Awake: 0x186CC90
- InitializeBilling: 0x186CE20
- OnOwnedNone: 0x186E32C
- OnPurchaseFailed: 0x186E2A0
- SetUnlocked: 0x186E440

### 字段偏移量
- IsUnlocked: 0x20
- HasStoreConnection: 0x21
- HasCompletedOwnershipCheck: 0x22

### Hook 策略
1. **Awake()**: 完全跳过原始实现，直接设置三个布尔字段为 true
2. **InitializeBilling()**: 空操作，阻止 BillingBridge 初始化
3. **OnOwnedNone()**: 空操作，阻止 ATTENTION 错误弹窗
4. **OnPurchaseFailed()**: 空操作，阻止购买失败弹窗

## 预期结果
- 游戏启动时不触发 Google Play 验证
- 不显示任何错误弹窗
- 不强制退出游戏
- 所有付费内容显示为已解锁
