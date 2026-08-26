# HANDOFF — 读全文再开始干活

生成时间: 2026-08-27T00:10+08:00 · Git HEAD: `c7b51f5`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `c7b51f5` (2026-08-27)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c7b51f5`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

"隐藏游戏原生油门/刹车按钮"功能已实现并测试稳定（正赛瞬间隐藏、计时赛 0.5s 延迟，20+ 遍无闪退）。多指触摸修复（commit `2b5997c`）**用户测试不成功**，仍需修复。

## 2. 已验证状态 — 工作实际停在哪

- [V] **隐藏油门/刹车按钮功能** — `git show 6a893b2` 确认。IRDSUIMobileControls 递归遍历 + il2cpp_runtime_invoke(SetActive)，TICK_INTERVAL=30（≈0.5s），从 proxy_player_controls_update(is_player) 驱动。
- [V] **正赛测试** — 用户实测 20+ 遍，每次按钮直接不可见，无隐藏过程，无闪退。
- [V] **计时赛测试** — 用户实测 20+ 遍，按钮有约 0.5s 可见延迟后隐藏，无闪退。
- [V] **Build** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL (exit 0)
- [X] **多指触摸修复不成功** — 用户明确反馈"依然存在问题"。commit `2b5997c` 的 active pointer 跟踪未解决实际 bug。
- 工作区: 干净。`git status --short` 无输出。

### Build 输出
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1s (exit 0)
```

## 3. 决策与理由

- **IRDSUIMobileControls + 递归遍历** [V]——GameObject.Find 在重新开始后搜不到按钮（场景限制）；IRDSUIMobileControls 的 layoutObject Transform 子树能稳定找到 "Throttle"/"Brake"。否决：GameObject.Find（重新开始后失效）、Transform.Find via il2cpp_runtime_invoke（string 参数传递崩溃）。
- **il2cpp_runtime_invoke 调用 SetActive** [V]——直接调 RVA + NULL MethodInfo* 崩溃（SetActive 内部需要 MethodInfo* 做回调分派）。否决：直接调 RVA（fault addr 0x20002b00040d0e）。
- **ELF 符号查找替代 dlopen** [V]——LSPosed LspModuleClassLoader 独立 linker namespace，dlopen("libil2cpp.so") 失败。通过解析 ELF PT_DYNAMIC → .dynsym + GNU hash table 查找 il2cpp_runtime_invoke 等导出函数。
- **TICK_INTERVAL=30** [V]——每帧执行导致正赛卡顿（递归遍历 3 布局 × Transform 树）。0.5s 间隔平衡延迟和性能。否决：每帧执行（卡顿）、TICK_INTERVAL=120（延迟 2s 太慢）。
- **get_gameObject 通过 il2cpp_runtime_invoke** [V]——直接调 Component.get_gameObject() RVA 返回 this（RectTransform）而非 GameObject。il2cpp_object_get_class 确认返回 'RectTransform'。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增
- [V] 后台 pthread 调用 Unity API → SetActive 触发 OnDisable 回调，非 Unity 脚本线程崩溃。不要再试。
- [V] Android 主线程 Handler.postDelayed 调用 il2cpp_runtime_invoke(SetActive) → 长时间运行后竞态崩溃（fault addr 0x10）。不要再试。
- [V] 直接调 SetActive RVA + NULL MethodInfo* → SetActive 内部需要 MethodInfo* 做回调分派，解引用 NULL+偏移量 → SIGSEGV。不要再试。
- [V] dlopen("libil2cpp.so") → LSPosed LspModuleClassLoader 独立 linker namespace，dlopen 失败（library not found）。改用 ELF 符号查找。不要再试。
- [V] Component.get_gameObject() RVA 直接调用 → 返回 this（RectTransform）而非 GameObject。改用 il2cpp_runtime_invoke。不要再试。
- [V] Transform.Find via il2cpp_runtime_invoke → string 参数传递方式不确定（&il2cpp_str 崩溃，il2cpp_str 返回 NULL）。不要再试。
- [V] GameObject.Find 作为唯一路径 → 重新开始后返回 NULL（按钮在新场景，Find 搜索范围有限）。只能作为快速路径 + IRDSUIMobileControls fallback。不要再试做唯一路径。
- [V] 递归遍历每帧执行 → 严重卡顿。必须降频。不要再试。
- [V] 递归遍历 + GameObject.Find 双路径每帧执行 → 误隐藏所有按键 + 暂停菜单变白。不要再试。
- [V] proxy_fixed_update is_player 块外调用 hide_pedals_tick → 重新开始后 is_player 返回 false（playerControls 字段未设置），但仍卡顿。不要再试。
- [V] Transform.Find 直接调 RVA + NULL MethodInfo* → 不稳定（混乱现象：全部按键消失、闪现）。不要再试。

### 从旧 HANDOFF 搬运
- [V] `event.rawY`(=getRawY(0)) 不做 active pointer 跟踪 → 多指按下/抬起后 index 0 漂移。已用 findPointerIndex 修复但**用户测试不成功**。需重新排查。
- [V] `Path.op(INTERSECT)` 填充 + `drawPath(STROKE)` 边框 → 圆角处缝隙。不要再试。
- [V] `Path.op(DIFFERENCE)` 环形边框 → flatten 弧线成折角。不要再试。
- [V] `alphaOf(ratio) = ratio*255` → 语义反了。改为 `(1-ratio)*255`。不要再试。
- [V] `borderPaint` 用 `Color.WHITE` 不读 alpha → 边框不受透明度控制。不要再试。
- [V] `drawRoundRect(0,0,w,h,...)` 画边框不内缩 → 圆角处比直线处粗。不要再试。
- [V] `clipPath` 裁剪到 `(0,0,w,h)` → 填充延伸到边框下面。不要再试。
- [V] `LAST_TOUCHED` 每次 MOVE 更新 → 先按的手指微动夺回优先。改为只在按下瞬间更新。不要再试。
- [V] `adb install -r` 覆盖安装时旧版运行 → force-stop 再安装。不要再试。
- [V] `awaitLsposedSettled` 等 Connected(LSPosed)+2s → NPatch 太慢。不要再试。
- [V] 等"非 Connecting"+删事件驱动 → App 1.5s 兜底 Disconnected。不要再试。
- [V] 等 Connected 5s 超时 → NPatch binder 先到 → INACTIVE 固定。不要再试。
- [V] NONROOT 立即写缓存 → LSPosed 后到补不上。只有 LSPOSED 立即写。不要再试。
- [V] `clearAll` 调 `App.clearService()` → connectionState 变化触发弹窗。删掉。不要再试。
- [V] 保留 onResume 重新检测 → 与"状态固定"矛盾。不要再试。
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触。改为 `===`。不要再试。
- [V] 去掉 `evaluate()` service 检查 → onResume 恒 INACTIVE。恢复路径 2。不要再试。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到。改为 LSPosed 优先。不要再试。
- [V] 弹窗并行 → npatchInstalled 竞态。恢复顺序执行。不要再试。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。不要再试。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败。改用 `gh-proxy.com`。不要再试。
- [V] `CHUNK_SIZE=256K` → TransactionTooLargeException / Thread.sleep → ANR。不要再试。
- [V] 手动 `rememberNavigationEventDispatcherOwner` → 弹窗收不到返回键。不要再试。
- [V] LSPosed 下 ContentProvider IPC → Unknown authority / 定向广播 → 包不可见 / 非定向广播 → flyme IntentFirewall。不要再试。
- [V] Remote Preferences `commit()` → UnsupportedOperationException / 广播传 300KB+ → Binder 溢出风险。不要再试。

## 5. 已知坑

- ⚠️ 多指触摸修复不成功 [V]——用户测试 active pointer 跟踪后仍存在踏板值漂移问题。需重新排查根因。
- ⚠️ flyme 后台白名单限制 [?]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。
- ⚠️ miuix `TopAppBar` spring 不跟随 fraction [?]——小标题不即时变化。
- ⚠️ miuix `TopAppBar` 内部自带状态栏 inset [?]——外层加 Spacer 重复计算。
- ⚠️ 广播 JSON 不含 position 字段 [?]——从本地 externalFilesDir 合并。
- ⚠️ miuix 无 `LinearProgressIndicator` [?]——用 Text 显示百分比。
- ⚠️ lint NewApi 拦 minSdk 26 下高版本 API [?]——照搬 KernelSU 注意 minSdk 差异。
- ⚠️ `OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume` [?]——introSound 用真 `AudioSource.set_volume` (0x325040C)。
- ⚠️ LSPosed 下 Remote Preferences/Files 在 Hook 进程只读 [V]——`getRemotePreferences().edit()` 抛异常。
- ⚠️ LSPosed 下游戏进程对模块包不可见 [V]——用 setComponent 显式组件广播绕过。
- ⚠️ BillingHook 在 NPatch 模式下永远失败 [V]——解锁靠 native hook。
- ⚠️ GitHub 代理镜像可用性会变 [V]——`gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 可用（2026-08-19）。
- ⚠️ NPatch binder 同步先于 LSPosed daemon [V]——detectOnce 不能立即写 NONROOT 缓存。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化比正赛晚 ~2s [V]——导致计时赛按钮隐藏有延迟。
- ⚠️ 计时赛重新开始后 proxy_player_controls_update 调用频率极低 [V]——每 ~2s 才调用一次。

## 6. 下一步（有序）

1. **重新排查多指触摸 bug** — 用户明确反馈 active pointer 跟踪修复不成功。需要重新复现问题、抓 logcat、分析实际 pointer 行为。
2. **计时赛延迟优化（可选）** — 当前 0.5s 延迟可接受但不够好。需找一个在计时赛加载期间也稳定调用的 hook 点（proxy_fixed_update 在计时赛中调用频率也低）。
3. **真机验证 NPatch 未安装路径** — 未装 NPatch 点卡片弹 Toast 路径仍未验证。
4. **清理 ConfigProvider 无用代码** — `pushGameLog`/`readGameLog` 已被广播方案替代。
5. **全量替换裸 `Log.*` 为 `Logger.*`**
6. **继续排查 janky 根因** — R8 映射文件对比。
7. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- 多指触摸 bug 的实际复现条件和根因是什么？用户说"依然存在问题"但未描述具体现象。
- 计时赛 0.5s 延迟是否需要进一步优化？用户目前表示"很稳定"但"看得见隐藏过程"。
- V10 游戏内引擎声浪是否继续实现？
- overlay 圆角平滑度是否需要进一步优化？