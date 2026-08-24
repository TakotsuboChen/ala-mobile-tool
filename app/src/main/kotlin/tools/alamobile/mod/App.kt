package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import tools.alamobile.mod.config.ModConfig

/**
 * 模块进程的 Application，负责连接 Xposed 框架的 XposedService。
 *
 * **两种框架路径**：
 * - **LSPosed（root 框架）**：[XposedServiceHelper.registerListener] 异步绑定 LSPosed
 *   daemon。daemon 常驻，[onServiceBind] 回调里拿到 [XposedService] 实例存静态变量，
 *   供 [tools.alamobile.mod.config.ModConfig.write] 写 daemon SQLite。daemon binder 由
 *   LSPosed Manager 经 `XposedProvider.call("SendBinder")` 推给模块进程。
 * - **NPatch（非 root 框架）**：NPatch 无 daemon，[XposedServiceHelper] 收不到 binder。
 *   但 NPatch 管理器进程暴露 `content://top.nkbe.npatch.remote` ContentProvider
 *   （[NPATCH_REMOTE_AUTHORITY]），`call("getRemoteService", modulePackageName=...)`
 *   返回一个可写 [IXposedService] binder，包装着 NPatch 管理器的 [NPatchRemoteStore]
 *   （SharedPreferences-backed）。[bindNpatchRemoteService] 主动调一次 ContentResolver
 *   拿这个 binder，构造 [XposedService] 存进 [xposedService] —— 对 [ModConfig.write]
 *   透明，它调 `getRemotePreferences().edit().putString(...).apply()` 时无感区分框架。
 *   游戏进程读配置时，[tools.alamobile.mod.AlaMobileModule] 的 [remoteConfigReader]
 *   调 `getRemotePreferences()`，NPatch loader 经 [ManagerRemoteServiceBridge]
 *   反向调同一个 ContentProvider 拿 read-only [ILSPInjectedModuleService] binder，
 *   读同一份 SharedPreferences。两端经 NPatch 管理器中转，不依赖任一进程常驻。
 *
 * **何时绑 NPatch service**：[onCreate] 调 [XposedServiceHelper.registerListener] 后，
 * LSPosed 路径会异步回调 [onServiceBind]；NPatch 路径不会。所以 [onCreate] 末尾
 * 立即调一次 [bindNpatchRemoteService] 兜底 —— 如果 NPatch 管理器装了 + 模块已注册
 * + 包可见性通过（manifest 的 `<queries>` 声明了 `top.nkbe.npatch`），就拿到 binder，
 * 构造 [XposedService] 存入 [xposedService]，[ModConfig.write] 直接可用；否则失败
 * 静默吞掉，[ModConfig.write] 走 filesDir + 广播兜底（现有行为）。
 *
 * **激活状态语义**：[LsposedStatus] 用 `xposedService != null` 判激活 —— LSPosed 真
 * 激活（daemon 绑定）和 NPatch 管理器模式（remote service 绑定）都判为已激活，
 * 不再走 Non-root 手动确认路径。这符合用户语义"模块当前被框架加载"。
 * NPatch embedded/local 模式（无管理器）仍走 Non-root 路径 —— 用户在弹窗里
 * 选"是"写 `nonroot_confirmed` 标记。
 *
 * 异步陷阱：[onCreate] 调 registerListener 后，[onServiceBind] 在未来某个时刻才回调。
 * ConfigActivity.onCreate 时 [.xposedService] 可能仍为 null —— [ModConfig.write] 必须
 * fallback：service 为 null 时仍写 filesDir + 发广播（旧方案兜底，零回归）。
 */
/**
 * XposedService 连接状态（三态密封接口，参照 AdClose ServiceManager.ConnectionState）。
 *
 * 激活检测的核心状态——UI 订阅 [App.connectionState] 事件驱动刷新：
 * - [Connecting]：已注册 listener，等待 daemon 推 binder
 * - [Connected]：onServiceBind 回调，frameworkName 区分 LSPosed / NPatch
 * - [Disconnected]：onServiceDied / 超时 / clearService
 */
sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val service: XposedService) : ConnectionState
    data object Disconnected : ConnectionState
}

class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "AlaMobileTool"
        const val PREF_GROUP = "ala_mobile_tool"
        const val KEY_CONFIG_JSON = "config_json"

        // 激活状态相关 key（存同一份 Remote Preferences）。
        // module_loaded：模块 onModuleLoaded 执行时写 "1"，作为"曾被框架加载"的持久信号。
        // nonroot_confirmed：用户在弹窗里确认用了 LSPatch/NPatch/FPA 等 Non-root 框架后写 "1"。
        const val KEY_MODULE_LOADED = "module_loaded_v1"
        const val KEY_NONROOT_CONFIRMED = "nonroot_confirmed_v1"

        /**
         * NPatch 管理器的 RemoteApiProvider authority。
         * 见 references/NPatch/manager/src/main/.../RemoteApiProvider.kt —
         * manifest 里 `authorities="${applicationId}.remote"`，applicationId
         * 默认 `top.nkbe.npatch`（见 Constants.MANAGER_PACKAGE_NAME）。
         */
        private const val NPATCH_REMOTE_AUTHORITY = "top.nkbe.npatch.remote"
        private const val NPATCH_METHOD_GET_REMOTE_SERVICE = "getRemoteService"
        private const val NPATCH_KEY_MODULE_PACKAGE = "modulePackageName"
        private const val NPATCH_KEY_BINDER = "binder"

        @Volatile
        var xposedService: XposedService? = null
            private set

        /**
         * 协程 scope，用于 service 绑定超时兜底（参照 AdClose ServiceManager.scope）。
         * 用 Default dispatcher，不依赖主线程 Handler——主线程阻塞时不影响超时。
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * XposedService 连接状态流（三态，参照 AdClose ServiceManager）。
         *
         * 初始值 [ConnectionState.Connecting]——App.onCreate 调 doServiceBinding
         * 注册 listener 时就进入 Connecting，UI 首次组合直接看到"检测中"。
         *
         * - [ConnectionState.Connecting]：已注册 listener，等待 daemon 推 binder
         * - [ConnectionState.Connected]：onServiceBind 回调，存 service 实例
         * - [ConnectionState.Disconnected]：onServiceDied（仅当前 service 死）/ 1.5s 超时 / clearService
         *
         * UI 订阅此 Flow 事件驱动刷新激活状态，不再轮询 [xposedService]。
         */
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        /**
         * 清掉内存中的 [xposedService] 引用（不调 onServiceDied，不通知框架）。
         *
         * 供 [LsposedStatus.clearAll] 用：用户在设置页点"清除激活标记"时，
         * 仅清持久标记（nonroot flag / property / remote key）不够——进程不
         * 重启时 [xposedService] 仍在内存，下次 [LsposedStatus.evaluate] 立即
         * 命中路径 2 返回 LSPOSED，"清除"形同虚设。清掉它后 evaluate 才会真正
         * 走完整检测流程（下次 service 异步重新绑上时自然恢复 LSPOSED）。
         *
         * **不调 [onServiceDied]**：那会向框架注销死亡回调，副作用超出"清激活
         * 标记"的语义。这里只是置 null，让 [evaluate] 不再看到旧引用。
         */
        fun clearService() {
            xposedService = null
            _connectionState.value = ConnectionState.Disconnected
            Log.i(TAG, "App: xposedService cleared by LsposedStatus.clearAll")
        }

        /**
         * 主动从 NPatch 管理器的 RemoteApiProvider 拿可写 IXposedService binder。
         *
         * 只在 [xposedService] 仍为 null（LSPosed daemon 没绑上）时调用。成功则
         * 构造 [XposedService] 存入 [xposedService]，对 [tools.alamobile.mod.config.ModConfig]
         * 透明 —— 它调 `getRemotePreferences().edit().putString(...).apply()` 时
         * 经此 binder Binder-IPC 到 NPatch 管理器的 NPatchRemoteStore，写管理器
         * 进程的 `npatch_remote_<modulePkg>_<group>.xml` SharedPreferences。
         * 游戏进程的 [tools.alamobile.mod.AlaMobileModule.remoteConfigReader] 调
         * `getRemotePreferences()` 时，NPatch loader 反向调同一 ContentProvider
         * 拿 read-only service binder，读同一份 SharedPreferences。
         *
         * 失败条件（任一即 fallback 到 filesDir + 广播）：
         * - NPatch 管理器没装（包不可见，`<queries>` 声明也 resolve 不到）。
         * - 模块没在 NPatch 管理器里注册（`isKnownModule` 校验失败，Provider 返回 null）。
         * - 调用 UID 不属于模块包（`modulePackageName !in callerPackages` 校验失败）。
         * - embedded 模式（无管理器，patched APK 自带模块）。
         *
         * 幂等：已绑上（[xposedService] 非空）时直接返回，不重复绑。
         */
        fun bindNpatchRemoteService(context: Context) {
            if (xposedService != null) return
            try {
                val resolver = context.contentResolver
                val extras = Bundle().apply {
                    putString(NPATCH_KEY_MODULE_PACKAGE, context.packageName)
                }
                val result = resolver.call(
                    Uri.parse("content://$NPATCH_REMOTE_AUTHORITY"),
                    NPATCH_METHOD_GET_REMOTE_SERVICE,
                    null,
                    extras
                )
                val binder: IBinder? = result?.getBinder(NPATCH_KEY_BINDER)
                if (binder == null) {
                    Log.i(TAG, "App: NPatch remote service returned null binder (manager not installed or module not registered)")
                    return
                }
                // XposedService 构造器包级私有，但 XposedServiceHelper.onBinderReceived
                // 同包可调 —— 复用它把 binder 包成 XposedService 并触发已注册的 listener。
                // 这条路径等价于"框架经 XposedProvider.call(SendBinder) 推 binder 进来"。
                XposedServiceHelper::class.java.getDeclaredMethod("onBinderReceived", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, binder)
                Log.i(TAG, "App: NPatch remote service bound via $NPATCH_REMOTE_AUTHORITY")
                // NPatch 绑上后同样 flush filesDir → remote（与 LSPosed 路径
                // onServiceBind 对称）。NPatch 无 daemon 异步推 binder，bindNpatchRemoteService
                // 是主动一次性调用；成功后 onServiceBind 会被 onBinderReceived 触发，
                // 那里会调 flushLocalConfigToRemote，所以这里不需要重复调。
            } catch (e: Throwable) {
                Log.i(TAG, "App: NPatch remote service bind failed (likely LSPosed or embedded mode): ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ⚠️⚠️ vivo/OriginOS/Android 16 闪退修复：App.onCreate 在游戏进程的
        // createOrUpdateClassLoaderLocked 内部同步调用（模块 Application 被实例化时）。
        // XposedServiceHelper.registerListener 和 bindNpatchRemoteService（ContentResolver.call）
        // 都是重操作，在 Resources 初始化之前执行会干扰 LoadedApk.getResources() →
        // makeApplicationInner 时 Resources.getAssets() NPE 闪退。
        // 修复：全部延迟到 next main loop。handleBindApplication 先完成（Resources 就绪），
        // 然后再绑 service。ConfigActivity（模块自己的进程）不受影响——它的
        // Application.onCreate 不在 createOrUpdateClassLoaderLocked 路径里。
        val isGameProcess = packageName != "tools.alamobile.mod"
        if (isGameProcess) {
            Log.i(TAG, "App: game process detected, deferring service binding to next main loop")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                doServiceBinding()
            }
        } else {
            // 模块进程：初始化 Logger（用 filesDir 写日志文件）
            tools.alamobile.mod.util.Logger.init(this, isModuleProcess = true)
            try {
                val settings = ModConfig.read(this)
                tools.alamobile.mod.util.Logger.setEnabled(settings.logEnabled)
            } catch (_: Throwable) {
                // 配置读失败不阻塞 service binding
            }
            // LogReceiver 通过 manifest 静态注册，系统在广播到达时自动拉起模块进程。
            doServiceBinding()
        }
    }

    private fun doServiceBinding() {
        try {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "App: XposedServiceHelper listener registered")
        } catch (e: Throwable) {
            Log.w(TAG, "App: failed to register XposedServiceHelper listener", e)
        }
        // NPatch 路径兜底：LSPosed daemon 没异步回调时，主动从 NPatch 管理器
        // RemoteApiProvider 拿可写 service binder。LSPosed 路径下此调用失败
        // （Provider 不可见 / isKnownModule 校验不过），静默吞掉。
        bindNpatchRemoteService(this)
        // 1.5s 超时兜底（参照 AdClose CONNECT_TIMEOUT_MS）：协程 delay，不依赖
        // 主线程 Handler——主线程阻塞时超时仍准时触发。用 update 原子检查：
        // 仅在仍为 Connecting 时才设 Disconnected，已 Connected 则不动。
        scope.launch {
            delay(1500)
            _connectionState.update { currentState ->
                if (currentState is ConnectionState.Connecting) {
                    Log.i(TAG, "App: service connection timed out (1.5s), likely not activated")
                    ConnectionState.Disconnected
                } else {
                    currentState
                }
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        // 用 update 原子操作。LSPosed 优先级高于 NPatch：
        // - 已有 LSPosed → 忽略一切新 binder（NPatch 不能覆盖 LSPosed）
        // - 当前 NPatch，新来 LSPosed → 升级覆盖（LSPosed 优先）
        // - 当前 NPatch，新来 NPatch → 忽略（防重复）
        // - Connecting/Disconnected → 接受
        //
        // **关键语义**：NPatch service 绑定只是配置读写通道（bindNpatchRemoteService
        // 拿可写 binder 写 remote prefs），**不是激活信号**。激活检测只认
        // frameworkName == "LSPosed"（UI 侧判断），NPatch 走 Non-root 手动确认。
        val newName = try { service.frameworkName } catch (_: Throwable) { "Unknown" }
        var shouldUpdate = false
        _connectionState.update { currentState ->
            when (currentState) {
                is ConnectionState.Connected -> {
                    val currentName = try { currentState.service.frameworkName } catch (_: Throwable) { "Unknown" }
                    if (currentName == "LSPosed") {
                        currentState  // 已有 LSPosed，忽略
                    } else if (newName == "LSPosed") {
                        shouldUpdate = true
                        ConnectionState.Connected(service)  // NPatch→LSPosed 升级
                    } else {
                        currentState  // NPatch→NPatch，忽略
                    }
                }
                else -> {
                    shouldUpdate = true
                    ConnectionState.Connected(service)
                }
            }
        }
        if (shouldUpdate) {
            xposedService = service
            Log.i(TAG, "App: XposedService bound (framework=$newName)")
            // service 绑上时把 filesDir 里的最新配置 flush 到 remote prefs。
            //
            // 兜底场景：用户在 ConfigActivity 改配置时 xposedService 还没绑上
            //（XposedServiceHelper 异步绑定延迟，或 LSPosed daemon 推 binder 时机
            // 不确定），ModConfig.write 只写了 filesDir + 广播，remote 没写。等
            // service 异步绑上时，这里把 filesDir 的最新配置补写到 remote——
            // 游戏不运行时改的配置就不会丢失。
            //
            // filesDir 的 JSON 始终是最新值（ModConfig.write 每次都写 filesDir），
            // 重复 flush 无害：RemotePreferences.doCommit 只在有 diff 时推。
            flushLocalConfigToRemote(service)
        }
    }

    /**
     * 把模块 filesDir 的 [FILE_NAME] 配置 JSON flush 到 remote prefs。
     *
     * 只在 [onServiceBind] 时调——service 刚绑上，是"之前 xposedService 为
     * null 时漏写的 remote 配置"的补写时机。filesDir 不存在（用户从没改过
     * 配置）时 no-op。
     */
    private fun flushLocalConfigToRemote(service: XposedService) {
        try {
            val file = java.io.File(filesDir, "ala_tool_config.json")
            if (!file.exists()) {
                Log.i(TAG, "App: flushLocalConfigToRemote — no local config, nothing to flush")
                return
            }
            val json = file.readText()
            service.getRemotePreferences(PREF_GROUP)
                .edit()
                .putString(KEY_CONFIG_JSON, json)
                .apply()
            Log.i(TAG, "App: flushed local config to remote prefs (${json.length} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "App: flushLocalConfigToRemote failed", e)
        }
    }

    override fun onServiceDied(service: XposedService) {
        // 参照 AdClose ServiceManager.onServiceDied：用 update 原子操作，
        // 仅当当前绑定的 service === deadService 时才设 Disconnected。
        // 这防止了 bindNpatchRemoteService 引入的第二个 service binder
        // 在后台死亡时误触 Disconnected——NPatch 管理器进程被系统杀时，
        // NPatch service binder 死亡触发 onServiceDied，但当前可能已切到
        // LSPosed service，不应该掉激活。
        var isDisconnected = false
        _connectionState.update { currentState ->
            if (currentState is ConnectionState.Connected && currentState.service === service) {
                isDisconnected = true
                ConnectionState.Disconnected
            } else {
                currentState
            }
        }
        if (isDisconnected) {
            xposedService = null
            Log.w(TAG, "App: XposedService died")
        }
    }
}
