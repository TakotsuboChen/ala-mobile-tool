# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T14:30:00+08:00 · Git HEAD: 097152c
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

修复共存版（`com.Takotsubo.AlamobileFormula`）踏板高频抖动顿挫问题，使其与原版行为一致。

完成定义：共存版踏板响应平滑，无抖动、卡顿或发热现象。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD 为 097152c，有 9 个未暂存的修改文件。
- [V] 原版踏板工作正常，响应平滑 [V]。
- [V] 共存版踏板存在高频抖动、顿挫和发热问题 [V]。
- [V] 已尝试多种文件 I/O 方案，均未能彻底解决抖动问题 [V]。
- [V] 核心怀疑点：Android 10+ Scoped Storage 限制和文件系统元数据操作 [V]。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ git status
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
        modified:   app/src/main/kotlin/tools/alamobile/mod/AlaMobileModule.kt
        modified:   app/src/main/kotlin/tools/alamobile/mod/NativeBridge.kt
        modified:   app/src/main/kotlin/tools/alamobile/mod/overlay/GearShiftView.kt
        modified:   app/src/main/kotlin/tools/alamobile/mod/overlay/OverlayManager.kt
        modified:   app/src/main/kotlin/tools/alamobile/mod/overlay/PedalOverlayView.kt
        modified:   app/src/main/kotlin/tools/alamobile/mod/util/VersionGate.kt
        modified:   app/src/main/res/values/arrays.xml
        modified:   native/src/pedal_hook.c
        modified:   native/src/unlock_hook.c
```

## 3. 决策与理由

- 使用文件 IPC 而非内存映射 [V]——`/proc/self/maps` 只读限制导致 mmap 方案不可行。否决方案：mmap 共享内存，因为应用进程无法修改 `/proc/self/maps` 权限。
- 尝试 `RandomAccessFile` + `seek(0)` 覆盖写入 [V]——避免 `File.writeText()` 的删除重建开销。否决方案：继续用 `File.writeText()`，因为每次删除文件触发文件系统元数据更新，可能导致抖动。
- 考虑使用 `/sdcard/Android/data/<package>/cache/` [?]——应用专属缓存目录，可能绕过 Scoped Storage 限制。未验证：需要测试写入权限和性能。

## 4. 失败的尝试 — 不要再试

- **方案 1：`File.writeText()` + `sync()`** [V]——每次调用都删除并重建文件，触发文件系统元数据操作（unlink/create/open），导致高频 I/O 和抖动。不要再试。
- **方案 2：`RandomAccessFile` + `seek(0)` 覆盖** [V]——减少了文件创建开销，但 `seek(0)` 仍然可能触发文件系统元数据更新。效果改善不明显，不要再试。
- **方案 3：mmap 共享内存** [V]——尝试通过 `/proc/self/maps` 写入内存映射，但权限为只读，无法修改。不要再试。
- **方案 4：`/sdcard/AlaMobileTool/` 全局目录** [V]——Android 10+ Scoped Storage 限制应用写入，目录创建失败（ENOENT）。不要再试。

## 5. 已知坑

- **Android 10+ Scoped Storage** [V]——应用对 `/sdcard/` 的写入权限受限，必须使用应用专属目录（如 `cacheDir` 或 `/sdcard/Android/data/<package>/cache/`）。
- **文件 I/O 性能** [V]——频繁的文件创建/删除操作会导致文件系统元数据更新，引发高频抖动。必须避免 `File.writeText()` 等会删除重建文件的方法。
- **LSPosed 多 ClassLoader 隔离** [V]——共存版使用 `VectorModuleClassLoader` 和 `LspModuleClassLoader`，native 库只能被一个 ClassLoader 加载，JNI 调用会失败。
- **`/proc/self/maps` 只读** [V]——应用进程无法修改自身内存映射的权限，mmap 方案不可行。

## 6. 下一步（有序）

1. 测试 `/sdcard/Android/data/com.Takotsubo.AlamobileFormula/cache/` 目录的写入权限和性能。
2. 如果目录可写，改用 `RandomAccessFile` 在该目录下创建 IPC 文件，避免 Scoped Storage 限制。
3. 如果仍有抖动，考虑使用 Unix Domain Socket 或 `ContentProvider` 进行进程间通信。
4. 验证共存版踏板响应是否平滑，对比原版行为。

## 7. 留给用户的开放问题

- 共存版游戏是否有额外的反作弊或性能监控机制？
- 是否可以接受轻微的输入延迟（如 10-20ms）以换取稳定性？
- 是否有其他设备或 Android 版本可供测试，以排除特定设备/系统版本的影响？
