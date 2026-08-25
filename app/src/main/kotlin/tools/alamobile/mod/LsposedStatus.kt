package tools.alamobile.mod

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * 模块激活状态判定。
 *
 * 判定基准（参照 AdClose `ServiceManager` 的 `onServiceBind` 即激活思路）：
 * - **LSPosed（root）**：LSPosed daemon 只在模块**被启用**时向模块进程推 binder，
 *   触发 `XposedServiceHelper.onBinderReceived` → `App.onServiceBind` →
 *   `App.xposedService` 赋值。这是框架契约，比读 scope / running targets 可靠：
 *   - 不用 `getScope()` —— scope 存 daemon SQLite，用户关开关**不会清**，`pm clear`
 *     也清不掉，禁用后首次打开会残留误判（M27 曾踩）。
 *   - 不用 `getRunningTargets()` —— 返回**当前正在注入**的目标进程，游戏没在跑时
 *     为空数组，会把"开关开着但游戏没跑"误判成未激活（本会话新踩）。
 *   - 用 `service.frameworkName == "LSPosed"` 区分框架即可。
 * - **NPatch（非 root）**：无 daemon，`onServiceBind` 不会被触发；但
 *   `App.bindNpatchRemoteService()` 会主动从 `content://top.nkbe.npatch.remote`
 *   拿一个 API 101 的 service binder（`frameworkName == "NPatch"`）。这个 binder
 *   **不算 LSPOSED** —— 用户没用 root LSPosed，落 Non-root 弹窗确认路径。
 *
 * 判定优先级：
 * 1. `hasModuleLoadedFlag()` —— 目标进程（游戏进程）路径，`onModuleLoaded` 执行过。
 * 2. `App.xposedService != null && frameworkName == "LSPosed"` —— ConfigActivity
 *    进程路径，LSPosed daemon 真绑上了。
 * 3. `readNonRootConfirmed()` —— 用户在弹窗里确认了 Non-root 框架。
 * 4. 默认 `INACTIVE`。
 *
 * 迁移规则：LSPosed 真激活覆盖并忘掉 Non-root 标记（清 nonroot_confirmed）；
 * 之后 LSPosed 关了 → 不保留 Non-root 已激活状态，必须重新点选。
 *
 * 异步时序：[App.xposedService] 在 ConfigActivity.onCreate 时可能仍为 null
 * （XposedServiceHelper 异步绑定）。UI 首次组合时等 [App.connectionState] 稳定
 * （最多 2s）后调 [detectOnce] 做一次完整检测并缓存——**本次会话内状态固定**，
 * 之后 [evaluate] 只读缓存，不再实时检测（用户中途开关框架 / 清除激活标记
 * 都不影响，下次冷启动重新检测才生效）。
 */
object LsposedStatus {

    private const val TAG = "AlaMobileTool"

    /** NPatch 管理器包名。Android 11+ 包可见性已在 AndroidManifest <queries> 声明。 */
    const val NPATCH_PKG = "top.nkbe.npatch"

    /**
     * 会话级激活状态缓存（进程级）。
     *
     * 冷启动（进程启动）时 [detectOnce] 执行一次完整检测并写入；之后本次会话内
     * [evaluate] 一律返回缓存值，**不再实时检测**——激活状态从这次冷启动到下次
     * 冷启动完全固定。用户中途开关 LSPosed 管理器 / 清除激活标记都不影响本次
     * 会话状态，下次冷启动重新检测时才会反映变化。
     */
    @Volatile
    private var cachedStatus: Status? = null

    /**
     * 首次检测是否已完成（进程级）。
     *
     * 供 UI 判断"是否真正首次检测"：Activity 重建（进程存活）时 [detectOnce]
     * 直接返回缓存，弹窗逻辑据此跳过，不重复弹窗。
     */
    @Volatile
    private var detectionDone = false

    /** 激活状态。UI 据此渲染卡片标题、描述、颜色与点击行为。 */
    enum class Status {
        /** 真被 LSPosed 加载（onModuleLoaded 在本进程执行过）。 */
        LSPOSED,
        /** 用户确认用 Non-root 框架（LSPatch/NPatch/FPA）加载。 */
        NONROOT,
        /** 未激活。 */
        INACTIVE
    }

    /**
     * 冷启动完整检测。
     *
     * **只有 LSPOSED 立即写缓存**——LSPosed 是最高优先级，确定后不会降级，
     * 可以安全固定。NONROOT / INACTIVE 不写缓存：LSPosed 可能晚到覆盖
     * （NPatch binder 同步先到 → detectOnce 返回 NONROOT，但 LSPosed daemon
     * 可能 2s 后才推 binder），留给 [OverviewPagerMiuix] 的
     * `LaunchedEffect(connectionState)` 事件驱动补刷新——补上 LSPOSED 后
     * 写缓存固定。
     *
     * NONROOT / INACTIVE 不写缓存但 [detectOnce] 仍返回结果给 UI 显示，
     * 所以 NPatch 用户 2s 内就能看到 NONROOT（和上次会话一样快）。5s 超时
     * 调 [forceSettle] 兜底写缓存（调 [evaluateInternal] 获取当前状态，
     * NPatch 用户写 NONROOT，无框架写 INACTIVE），用户无感知。
     */
    @Synchronized
    fun detectOnce(context: Context): Status {
        cachedStatus?.let { return it }
        val s = evaluateInternal(context)
        if (s == Status.LSPOSED) {
            cachedStatus = s
            detectionDone = true
        }
        return s
    }

    /**
     * 读取本次会话的固定激活状态。
     *
     * **不再实时检测**——只返回 [detectOnce] 写入的缓存；尚未检测时返回
     * [Status.INACTIVE]。语义：这次冷启动到下次冷启动之间，激活状态完全固定。
     */
    fun evaluate(context: Context): Status = cachedStatus ?: Status.INACTIVE

    /** 首次检测是否已完成（缓存已写入，状态固定）。 */
    fun isDetectionDone(): Boolean = detectionDone

    /**
     * 超时兜底：强制写缓存固定。
     *
     * 供 UI 侧 [OverviewPagerMiuix] 的 `LaunchedEffect(Unit)` 在 5s 后调用：
     * 如果此时 [detectOnce] 还没写缓存（NONROOT/INACTIVE 等事件驱动但
     * LSPosed 一直没绑上），调 [evaluateInternal] 获取当前状态并写缓存固定
     *（NPatch 用户 → NONROOT，无框架 → INACTIVE），避免状态一直不固定。
     */
    @Synchronized
    fun forceSettle(context: Context) {
        if (cachedStatus == null) {
            cachedStatus = evaluateInternal(context)
            detectionDone = true
            Log.i(TAG, "forceSettle: settled to $cachedStatus after timeout")
        }
    }

    /**
     * 判定当前激活状态（纯同步快速检测，无轮询）。仅由 [detectOnce] 调用。
     *
     * **判定优先级**：
     * 1. `hasModuleLoadedFlag()` —— 目标进程（游戏进程）路径：`onModuleLoaded`
     *    执行过，markActivated 设了进程级 property。模块被真正启用时才有。
     * 2. `isLsposedService(App.xposedService)` —— ConfigActivity 进程路径：
     *    LSPosed daemon 已绑定（`frameworkName == "LSPosed"`）。
     *    NPatch service（`frameworkName == "NPatch"`）不算激活，走路径 3。
     * 3. `readNonRootConfirmed()` —— 用户在弹窗里确认了 Non-root 框架。
     * 4. 默认 `INACTIVE`。
     */
    private fun evaluateInternal(context: Context): Status {
        // 1) 目标进程路径：onModuleLoaded 执行过（游戏进程被真正注入）。
        if (hasModuleLoadedFlag()) {
            Log.i(TAG, "detectOnce: hasModuleLoadedFlag=true → LSPOSED")
            clearNonRootConfirmed(context)
            return Status.LSPOSED
        }
        Log.i(TAG, "detectOnce: hasModuleLoadedFlag=false, continue")

        // 2) ConfigActivity 进程路径：LSPosed daemon 已绑定。
        //    App.xposedService 由 App.onServiceBind 赋值。只认 frameworkName=="LSPosed"，
        //    NPatch service 不算激活（NPatch 是纯手动确认，走路径 3）。
        val service = App.xposedService
        if (service != null) {
            if (isLsposedService(service)) {
                Log.i(TAG, "detectOnce: frameworkName=LSPosed → LSPOSED")
                clearNonRootConfirmed(context)
                return Status.LSPOSED
            }
            Log.i(TAG, "detectOnce: service is not LSPosed framework → fall through")
        }

        // 3) Non-root 用户确认标记 —— 用户在弹窗里选了"是"。
        if (readNonRootConfirmed(context)) {
            Log.i(TAG, "detectOnce: nonroot_confirmed=true → NONROOT")
            return Status.NONROOT
        }

        Log.i(TAG, "detectOnce: no path matched → INACTIVE")
        return Status.INACTIVE
    }

    /**
     * 检测 NPatch 管理器是否已安装。
     *
     * 与 [tools.alamobile.mod.util.checkGameVersion] 同款静默查询模式：
     * 依赖 AndroidManifest <queries> 已声明 `top.nkbe.npatch`（API 30+ 包可见性）。
     * 未安装 / 查询失败一律返回 false，不抛异常。
     */
    fun isNpatchInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    NPATCH_PKG,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(NPATCH_PKG, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 本进程是否执行过 onModuleLoaded（markActivated 设的进程级 property，仅目标进程）。 */
    private fun hasModuleLoadedFlag(): Boolean =
        System.getProperty(AlaMobileModule.MODULE_LOADED_FLAG) == "true"

    /**
     * 判断绑定到的 service 是否来自**真正的 LSPosed（root）框架**。
     *
     * `frameworkName == "LSPosed"` 确认真 LSPosed，排除 NPatch 的 binder
     *（`frameworkName == "NPatch"`，不算激活——NPatch 是纯手动确认）。
     */
    private fun isLsposedService(service: io.github.libxposed.service.XposedService): Boolean {
        return try {
            val name = service.frameworkName
            Log.i(TAG, "isLsposedService: frameworkName=$name")
            name == "LSPosed"
        } catch (e: Throwable) {
            Log.w(TAG, "isLsposedService: getFrameworkName() failed, assuming not LSPosed", e)
            false
        }
    }

    /**
     * 用户在弹窗里选"是"（用了 Non-root 框架）→ 写持久标记 + 会话缓存。
     *
     * **只写模块进程 filesDir，不写 remote prefs** —— 原因：
     * - `nonroot_confirmed` 是"每个安装会话"的本地状态，只在 ConfigActivity 进程
     *   判断（同 EulaManager 的 EULA 标记策略）。
     * - 若写 remote prefs（NPatch 管理器进程的 SharedPreferences），`pm clear`/
     *   卸载重装**清不掉**该标记（它不在模块进程数据目录）→ 导致"清数据后仍显示
     *   Non-root 已激活、不弹窗询问"（用户实测 bug）。
     * - 只存 filesDir，`pm clear`/卸载重装自然清掉，语义正确。
     *
     * 同时写会话缓存 [cachedStatus] = [Status.NONROOT]：用户主动确认是本次会话
     * 内唯一允许立即改变激活状态的操作（不等下次冷启动）。
     */
    fun confirmNonRoot(context: Context) {
        try {
            val file = java.io.File(context.filesDir, "nonroot_confirmed.flag")
            file.writeText("1")
            Log.i(TAG, "confirmNonRoot: wrote local flag (filesDir)")
        } catch (e: Throwable) {
            Log.w(TAG, "confirmNonRoot: local flag write failed", e)
        }
        cachedStatus = Status.NONROOT
    }

    /** 用户在弹窗里选"否" → 清掉 Non-root 标记，保持未激活。 */
    fun clearNonRootConfirmed(context: Context) {
        try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").delete()
        } catch (_: Throwable) {}
    }

    /**
     * "设置 → 清除激活标记"用：清掉所有**持久**激活痕迹。
     *
     * **本次会话激活状态不变**——只清持久标记（nonroot flag / remote key /
     * property），不动 [cachedStatus] 会话缓存，也不改 [App.connectionState]
     * （置 Disconnected 会触发 UI 事件驱动刷新当场弹窗）。
     * 清除结果在**下次冷启动**（进程重启）重新检测时才生效。
     *
     * **只清激活状态**，不碰 EULA 同意标记——两者语义独立，设置页有单独的
     * "用户协议"入口负责重置协议同意状态。早期版本曾在此调用 [EulaManager.clear]，
     * 导致用户只想重置激活时协议同意状态被一并清掉（下次启动重新弹协议）。
     */
    fun clearAll(context: Context) {
        clearNonRootConfirmed(context)
        // 旧版本（property/daemon module_loaded 路径）残留清理，向后兼容。
        // 新方案下这两个标记已不再写，但用户可能从旧版升级，清掉避免迷惑。
        // 只读 service 引用（配置写入通道），不改 connectionState。
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_MODULE_LOADED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            System.clearProperty(AlaMobileModule.MODULE_LOADED_FLAG)
        } catch (_: Throwable) {}
    }

    private fun readNonRootConfirmed(context: Context): Boolean {
        // 只读模块 filesDir（与 confirmNonRoot 的写入位置对称）。不读 remote prefs：
        // 旧版本可能残留 remote 标记（NPatch 管理器进程，pm clear 清不掉），
        // 若读它会造成"清数据后仍显示 Non-root 已激活"（用户实测 bug）。
        return try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").exists() &&
                java.io.File(context.filesDir, "nonroot_confirmed.flag").readText() == "1"
        } catch (_: Throwable) { false }
    }

    /**
     * 旧 API 兼容：[OverviewPage] 原先直接拿 Boolean。
     * 保留以减少调用方改动，但语义已改为"非未激活"（LSPOSED 或 NONROOT）。
     */
    fun isActivated(context: Context): Boolean = evaluate(context) != Status.INACTIVE
}
