package tools.alamobile.mod

import android.content.Context
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
 * （XposedServiceHelper 异步绑定）。`awaitService` 参数提供 3s 轮询兜底。
 */
object LsposedStatus {

    private const val TAG = "AlaMobileTool"

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
     * 判定当前激活状态。
     *
     * **判定优先级**：
     * 1. `hasModuleLoadedFlag()` —— 目标进程（游戏进程）路径：`onModuleLoaded`
     *    执行过，markActivated 设了进程级 property。模块被真正启用时才有。
     * 2. `isLsposedService(App.xposedService)` —— ConfigActivity 进程路径：
     *    LSPosed daemon 只在模块启用时推 binder，`App.onServiceBind` 赋值。
     *    `frameworkName == "LSPosed"` 确认是真 LSPosed（排除 NPatch 的
     *    API 101 binder）。
     * 3. `readNonRootConfirmed()` —— 用户在弹窗里确认了 Non-root 框架。
     * 4. 默认 `INACTIVE`。
     */
    fun evaluate(context: Context, awaitService: Boolean = false): Status {
        // 1) 目标进程路径：onModuleLoaded 执行过（游戏进程被真正注入）。
        if (hasModuleLoadedFlag()) {
            Log.i(TAG, "evaluate: hasModuleLoadedFlag=true → LSPOSED")
            clearNonRootConfirmed(context)
            return Status.LSPOSED
        }
        Log.i(TAG, "evaluate: hasModuleLoadedFlag=false, continue")

        // 2) ConfigActivity 进程路径：LSPosed daemon 已绑定（frameworkName 确认真 LSPosed）。
        //    App.xposedService 由 App.onServiceBind 赋值，只在 LSPosed daemon 推 binder 时调到。
        //    异步绑定可能晚于首次读检测——轮询等待最多 ~3s。
        val service = App.xposedService
        Log.i(TAG, "evaluate: App.xposedService=${service} (awaitService=${awaitService})")
        if (service != null) {
            if (isLsposedService(service)) {
                Log.i(TAG, "evaluate: frameworkName=LSPosed → LSPOSED")
                clearNonRootConfirmed(context)
                return Status.LSPOSED
            }
            Log.i(TAG, "evaluate: service is not LSPosed framework → fall through")
        } else if (awaitService) {
            Log.i(TAG, "evaluate: service==null, starting awaitService poll (3s)")
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val s = App.xposedService
                if (s != null) {
                    if (isLsposedService(s)) {
                        Log.i(TAG, "evaluate: poll got LSPosed service → LSPOSED")
                        clearNonRootConfirmed(context)
                        return Status.LSPOSED
                    }
                    break
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "evaluate: awaitService poll finished, no LSPosed service")
        }

        // 3) Non-root 用户确认标记 —— 用户在弹窗里选了"是"。
        if (readNonRootConfirmed(context)) {
            Log.i(TAG, "evaluate: nonroot_confirmed=true → NONROOT")
            return Status.NONROOT
        }

        Log.i(TAG, "evaluate: no path matched → INACTIVE")
        return Status.INACTIVE
    }

    /** 本进程是否执行过 onModuleLoaded（markActivated 设的进程级 property，仅目标进程）。 */
    private fun hasModuleLoadedFlag(): Boolean =
        System.getProperty(AlaMobileModule.MODULE_LOADED_FLAG) == "true"

    /**
     * 判断绑定到的 service 是否来自**真正的 LSPosed（root）框架**。
     *
     * 依据 `frameworkName`（框架自己上报的标识）：
     * - 真 LSPosed daemon 返回 `"LSPosed"`。
     * - NPatch 的 `XposedServiceBinder.getFrameworkName()` 返回 `"NPatch"`（API 101）。
     *
     * 用 frameworkName 而非 apiVersion / getScope / getRunningTargets 的原因：
     * - `apiVersion`：NPatch 正式版只到 101，但未来升级可能到 102，靠版本号猜
     *   框架不可靠。
     * - `getScope()`：scope 存 daemon SQLite，关开关不清，NPatch 也会返回记忆的
     *   scope → 误判 LSPOSED。
     * - `getRunningTargets()`：返回**当前正在注入**的目标，游戏没在跑时为空数组，
     *   会把"开关开着但游戏没跑"误判成未激活（本会话踩过）。
     * - `frameworkName` 是框架直接上报的、语义明确的字段，最可靠。
     *
     * 异常处理：读 frameworkName 失败时保守返回 false——避免框架标识读不到时
     * 误判为已激活（"未激活→已激活"比"已激活→未激活"更误导用户）。
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
     * 用户在弹窗里选"是"（用了 Non-root 框架）→ 写持久标记。
     *
     * **只写模块进程 filesDir，不写 remote prefs** —— 原因：
     * - `nonroot_confirmed` 是"每个安装会话"的本地状态，只在 ConfigActivity 进程
     *   判断（同 EulaManager 的 EULA 标记策略）。
     * - 若写 remote prefs（NPatch 管理器进程的 SharedPreferences），`pm clear`/
     *   卸载重装**清不掉**该标记（它不在模块进程数据目录）→ 导致"清数据后仍显示
     *   Non-root 已激活、不弹窗询问"（用户实测 bug）。
     * - 只存 filesDir，`pm clear`/卸载重装自然清掉，语义正确。
     */
    fun confirmNonRoot(context: Context) {
        try {
            val file = java.io.File(context.filesDir, "nonroot_confirmed.flag")
            file.writeText("1")
            Log.i(TAG, "confirmNonRoot: wrote local flag (filesDir)")
        } catch (e: Throwable) {
            Log.w(TAG, "confirmNonRoot: local flag write failed", e)
        }
    }

    /** 用户在弹窗里选"否" → 清掉 Non-root 标记，保持未激活。 */
    fun clearNonRootConfirmed(context: Context) {
        try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").delete()
        } catch (_: Throwable) {}
    }

    /** "设置 → 清除激活标记"用：彻底清掉所有激活痕迹。 */
    fun clearAll(context: Context) {
        clearNonRootConfirmed(context)
        EulaManager.clear(context)
        // 旧版本（property/daemon module_loaded 路径）残留清理，向后兼容。
        // 新方案下这两个标记已不再写，但用户可能从旧版升级，清掉避免迷惑。
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
