package tools.alamobile.mod

import android.content.Context
import android.util.Log

/**
 * 模块激活状态判定。
 *
 * 判定基准（用户确认："Manager 当前启用"语义）：
 * - **LSPosed 真激活** = LSPosed daemon 已绑定到本进程。daemon binder 只在
 *   LSPosed Manager（root 框架）启用本模块时由框架经 `XposedProvider.call("SendBinder")`
 *   触发；用户在 Manager 里关掉模块 → 不触发 → `App.xposedService == null`。
 *   这个信号随 Manager 启用态实时变化，不需要进程重启，也不依赖 onModuleLoaded
 *   时机（onModuleLoaded 只在目标 App 进程调，ConfigActivity 进程永不调——
 *   旧的 System.getProperty 路径对 ConfigActivity 根本不可达，已废弃）。
 * - **Non-root 框架**（LSPatch/NPatch/FPA）：不装 LSPosed Manager，不绑 daemon，
 *   `App.xposedService == null` → 落到手动手动确认路径。用户在弹窗里选"是"后
 *   写 `nonroot_confirmed` 标记（daemon 没绑时写本地 filesDir）。
 *
 * 迁移规则：LSPosed 真激活覆盖并忘掉 Non-root 标记（清 nonroot_confirmed）；
 * 之后 LSPosed 关了 → 不保留 Non-root 已激活状态，必须重新点选。
 *
 * 异步陷阱：[App.xposedService] 在 ConfigActivity.onCreate 时可能仍为 null
 * （XposedServiceHelper 异步绑定）。[evaluate] 的 `awaitService` 参数提供轮询
 * 兜住这个绑定窗口——首次进入页面时传 true，后续刷新传 false。
 */
object LsposedStatus {

    private const val TAG = "AlaMobileTool"

    /** 激活状态。UI 据此渲染卡片标题、描述、颜色与点击行为。 */
    enum class Status {
        /** 真被 LSPosed 加载（daemon 已绑定 = Manager 当前启用本模块）。 */
        LSPOSED,
        /** 用户确认用 Non-root 框架（LSPatch/NPatch/FPA）加载。 */
        NONROOT,
        /** 未激活。 */
        INACTIVE
    }

    /**
     * 判定当前激活状态。
     *
     * @param context ConfigActivity 的 context（模块进程）
     * @param awaitService 是否轮询等待 daemon 绑定（处理 XposedServiceHelper 异步
     *   绑定晚于读检测的情况）。首次进入页面时传 true，后续手动刷新传 false。
     */
    fun evaluate(context: Context, awaitService: Boolean = false): Status {
        // 1) LSPosed daemon 绑定 = Manager 当前启用本模块。
        //    App.xposedService 由 App.onServiceBind 赋值，只在框架触发 binder 时调到。
        //    异步绑定可能晚于首次读检测——轮询等待最多 ~3s。
        if (App.xposedService != null) {
            clearNonRootConfirmed(context)
            return Status.LSPOSED
        }
        if (awaitService) {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (App.xposedService != null) {
                    clearNonRootConfirmed(context)
                    return Status.LSPOSED
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }

        // 2) Non-root 用户确认标记 —— 用户在弹窗里选了"是"。
        if (readNonRootConfirmed(context)) {
            return Status.NONROOT
        }

        return Status.INACTIVE
    }

    /** 用户在弹窗里选"是"（用了 Non-root 框架）→ 写持久标记。 */
    fun confirmNonRoot(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_NONROOT_CONFIRMED, "1")
                    .apply()
                Log.i(TAG, "confirmNonRoot: remote prefs updated")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "confirmNonRoot: remote prefs failed, trying local", e)
            }
        }
        // 兜底：service 没绑上时写模块 filesDir（ConfigActivity 进程可写）。
        try {
            val file = java.io.File(context.filesDir, "nonroot_confirmed.flag")
            file.writeText("1")
        } catch (e: Throwable) {
            Log.w(TAG, "confirmNonRoot: local fallback failed", e)
        }
    }

    /** 用户在弹窗里选"否" → 清掉 Non-root 标记，保持未激活。 */
    fun clearNonRootConfirmed(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_NONROOT_CONFIRMED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").delete()
        } catch (_: Throwable) {}
    }

    /** "设置 → 清除激活标记"用：彻底清掉所有激活痕迹。 */
    fun clearAll(context: Context) {
        clearNonRootConfirmed(context)
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
        val service = App.xposedService
        if (service != null) {
            try {
                val v = service.getRemotePreferences(App.PREF_GROUP)
                    .getString(App.KEY_NONROOT_CONFIRMED, null)
                if (v == "1") return true
            } catch (e: Throwable) {
                Log.w(TAG, "readNonRootConfirmed: remote prefs failed", e)
            }
        }
        // 兜底读本地。
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
