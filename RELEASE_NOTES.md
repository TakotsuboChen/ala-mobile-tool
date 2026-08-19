# v1.0.0 — 2026-08-19

Version Code: 100300
Tag: v1.0.0

Ala Mobile Tool 首个正式版本。自 Beta 5 以来新增完整日志系统与 NPatch 激活确认流程收窄，并修复多项稳定性问题。

## Features

- **完整日志系统**：新增「启用日志」开关（默认关闭），统一门控 Java/native 两侧文件输出；游戏与模块日志写入统一文件，设置页可一键导出并分享
- **NPatch 激活确认流程收窄**：激活弹窗收敛为「NPatch 作用域确认」，仅在检测到 NPatch 管理器已安装时弹出；未安装时点击激活卡片 Toast 提示

## Bug Fixes

- 修复了响应曲线图表在平板/横屏下高度溢出撑爆卡片的问题（高度钳制到屏幕 50%）
- 修复了 LSPosed 激活检测轮询超时误弹免 Root 弹窗的问题（改为事件驱动）
- 修复了解锁标记文件残留但 PlayerPrefs 丢失时车库车辆锁着的问题（解锁走 `SetUnlocked(true)` 完整路径重写 PlayerPrefs）
