package tools.alamobile.mod.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import org.json.JSONObject
import tools.alamobile.mod.overlay.OverlayManager

/**
 * 接收 ConfigActivity 发来的配置更新广播，写入游戏进程自己的
 * getExternalFilesDir（游戏进程天然可写，绕开包可见性和 scoped storage）。
 *
 * 广播方案：ConfigActivity（模块进程）改配置 → 写模块 filesDir 备份 +
 * 发定向广播 Intent(ACTION).setPackage(gamePkg) 带 JSON → 系统直接派发给
 * 游戏包，不查 PackageManager 可见性 → 游戏进程 ConfigReceiver 收到 →
 * 合并写自己 externalFilesDir（保留游戏进程已有的 position 字段）→
 * OverlayManager 读同一路径生效 + notifyConfigChanged 触发重建。
 *
 * 合并写：广播 JSON 不含 position 三字段（ConfigActivity 不管 position，
 * position 由游戏进程拖拽时 saveOverlayPosition 写）。ConfigReceiver 收到
 * 后读已有 JSON，用 incoming 覆盖非 position 字段，position 字段保留
 * 游戏进程已有——否则直接 writeText 覆盖会丢失拖拽保存的 position。
 *
 * 首次安装/首次启动时 receiver 还没注册（要等 onPackageReady），读到默认值；
 * 用户改一次配置后重启游戏，receiver 接收并写入，之后即时生效。
 */
class ConfigReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIG_UPDATE = "tools.alamobile.mod.CONFIG_UPDATE"
        const val ACTION_REQUEST_LOGS = "tools.alamobile.mod.REQUEST_LOGS"
        const val EXTRA_JSON = "json"
        private const val FILE_NAME = "ala_tool_config.json"
        private const val TAG = "AlaMobileTool"

        // position 字段由游戏进程持有（拖拽时 saveOverlayPosition 写），
        // ConfigActivity 广播的 JSON 不含这三字段。合并时跳过，保留游戏进程已有值。
        // 定义在 ModConfig.POSITION_KEYS，这里复用避免重复维护。
        private val POSITION_KEYS = ModConfig.POSITION_KEYS
    }

    override fun onReceive(context: Context, intent: Intent) {
        // REQUEST_LOGS：模块进程导出日志前请求游戏进程重新推送最新日志。
        // 不带 JSON，不需要写配置，只推送日志文件。
        if (intent.action == ACTION_REQUEST_LOGS) {
            Log.i(TAG, "ConfigReceiver: REQUEST_LOGS received — pushing fresh logs")
            pushGameLogs(context)
            return
        }

        if (intent.action != ACTION_CONFIG_UPDATE) return
        val json = intent.getStringExtra(EXTRA_JSON)
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "ConfigReceiver: empty json")
            return
        }
        try {
            // 用游戏进程自己 context 的 externalFilesDir——游戏进程天然可写，
            // 无需权限，无 scoped storage 限制。OverlayManager 后续读同一路径。
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Log.w(TAG, "ConfigReceiver: externalFilesDir null")
                return
            }
            val file = File(dir, FILE_NAME)

            // 合并写：读已有 JSON（拖拽保存的 position 在里面），用 incoming
            // 覆盖非 position 字段，position 字段保留游戏进程已有。
            val existing = if (file.exists()) {
                try {
                    JSONObject(file.readText())
                } catch (_: Throwable) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            val incoming = JSONObject(json)
            for (key in incoming.keys()) {
                if (key !in POSITION_KEYS) {
                    existing.put(key, incoming[key])
                }
            }
            file.writeText(existing.toString(2))
            Log.i(
                TAG,
                "ConfigReceiver: merged ${json.length} bytes to ${file.absolutePath} " +
                    "pedalMode_in=${incoming.optString("pedal_mode", "?")} " +
                    "pedalMode_written=${existing.optString("pedal_mode", "?")} " +
                    "ts=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
            )

            // 通知 OverlayManager 重建——PedalOverlayView 构造拷 settings 快照，
            // 光写文件不够，必须重建 view 才能让新配置流进去。post 到主线程
            // 保证 UI 操作（removeView/addView）在主线程执行。
            // 传入广播 JSON：rebuild 优先用它解析（比 readFromTargetProcess 读
            // daemon 更及时——daemon 可能是旧值，见 M41 根因）。
            OverlayManager.notifyConfigChanged(json)

            // 实时同步 TC/ABS 开关到 native g_config——ConfigActivity（模块进程）
            // 改了 TC/ABS 后，广播到达游戏进程，这里立刻调 native 更新 g_config，
            // proxy_player_controls_update 下一帧就用新值写 tclEnable/absEnable。
            // enable_tc 已是派生值（写入端 ViewModel 按 tc_mode/tc_strength 派生）。
            val enableTc = incoming.optBoolean("enable_tc", true)
            val enableAbs = incoming.optBoolean("enable_abs", true)
            if (tools.alamobile.mod.NativeBridge.isAvailable) {
                tools.alamobile.mod.NativeBridge.setTcAbs(enableTc, enableAbs)
                Log.i(TAG, "ConfigReceiver: setTcAbs enableTc=$enableTc enableAbs=$enableAbs")
            }

            // 实时同步 TC 档位（强度插值 + 时机 ε/minSPD 配对覆写）——游戏运行中改档立即生效。
            // **必须经 tcEffectiveParams 按 tc_mode 派生**：mode=default 时无视
            // 缓存的 strength/timing（记忆值）恒为原厂透传，否则"调回游戏默认"
            // 永远恢复不了原生行为。旧广播 JSON（无新键）落到默认，与旧行为一致。
            val tcMode = ModConfig.TcMode.from(incoming.optString("tc_mode", "default"))
            val (tcMix, tcEps, tcMinspd) = ModConfig.tcEffectiveParams(
                tcMode,
                ModConfig.TcStrength.from(incoming.optString("tc_strength", "stock")),
                ModConfig.TcTiming.from(incoming.optString("tc_timing", "default"))
            )
            if (tools.alamobile.mod.NativeBridge.isAvailable) {
                tools.alamobile.mod.NativeBridge.setTcParams(tcMix, tcEps, tcMinspd)
                Log.i(TAG, "ConfigReceiver: setTcParams mix=$tcMix eps=$tcEps minspd=$tcMinspd")
            }

            // 实时同步 ABS 档位（干预强度 b 覆写 + 制动压力 T_b 等比缩放）——
            // 与 TC 档位同构：**必须经 absEffectiveParams 按 abs_mode 派生**，
            // mode=default 恒为原厂透传（bOverride=-1），否则"调回游戏默认"
            // 恢复不了原生行为。旧广播 JSON（无新键）落到默认，与旧行为一致。
            // 制动压力独立于模式（absPressure <1.0 时即使默认档/关闭档也生效）。
            val absMode = ModConfig.AbsMode.from(incoming.optString("abs_mode", "default"))
            val (absMix, absBOverride, absTbScale) = ModConfig.absEffectiveParams(
                absMode,
                ModConfig.AbsStrength.from(incoming.optString("abs_strength", "stock")),
                incoming.optDouble("abs_pressure", 1.0).toFloat().coerceIn(0f, 1f)
            )
            if (tools.alamobile.mod.NativeBridge.isAvailable) {
                tools.alamobile.mod.NativeBridge.setAbsParams(absMix, absBOverride, absTbScale)
                Log.i(TAG, "ConfigReceiver: setAbsParams mix=$absMix bOverride=$absBOverride tbScale=$absTbScale")
            }

            // 实时同步音乐替换开关——用户从配置页切到游戏时即时生效。
            // 需要 native 可用（mute 游戏音乐靠 native hook 静音 AudioSource）。
            val enableMusicReplace = incoming.optBoolean("enable_music_replace", false)
            tools.alamobile.mod.MusicPlayer.setEnabled(enableMusicReplace)
            Log.i(TAG, "ConfigReceiver: MusicPlayer.setEnabled=$enableMusicReplace")

            // 实时同步 V10 引擎声浪开关——用户从配置页切到游戏时即时生效。
            // 需要 native 可用（静音开场 introSound 靠 native hook）。
            val enableV10Sound = incoming.optBoolean("enable_v10_sound", false)
            tools.alamobile.mod.IntroSoundPlayer.setEnabled(enableV10Sound)
            Log.i(TAG, "ConfigReceiver: IntroSoundPlayer.setEnabled=$enableV10Sound")

            // 实时同步"隐藏游戏原生油门/刹车按钮"开关——
            // native 层 hide_pedals_tick 据此启停查找 + SetActive（全在 Unity 脚本线程）。
            val hideGamePedals = incoming.optBoolean("hide_game_pedals", false)
            if (tools.alamobile.mod.NativeBridge.isAvailable) {
                tools.alamobile.mod.NativeBridge.setHidePedalsEnabled(hideGamePedals)
                Log.i(TAG, "ConfigReceiver: setHidePedalsEnabled=$hideGamePedals")
            }

            // 实时同步日志开关——logEnabled 控制文件写入，logcat 始终输出。
            val logEnabled = incoming.optBoolean("log_enabled", false)
            tools.alamobile.mod.util.Logger.setEnabled(logEnabled)
            if (tools.alamobile.mod.NativeBridge.isAvailable) {
                tools.alamobile.mod.NativeBridge.setLogEnabled(logEnabled)
            }
            Log.i(TAG, "ConfigReceiver: logEnabled=$logEnabled")

            // 游戏进程把自己的日志文件内容推到模块进程，
            // 供 ConfigActivity 的"导出并分享日志"读取（跨进程文件不可直接读）。
            pushGameLogs(context)
        } catch (e: Throwable) {
            Log.e(TAG, "ConfigReceiver: write failed", e)
        }
    }

    /**
     * 游戏进程把自己的日志文件内容推到模块进程，
     * 供 ConfigActivity 的"导出并分享日志"读取（跨进程文件不可直接读）。
     * 通过 setComponent 显式广播分片推送（LSPosed + NPatch 通用）。
     */
    private fun pushGameLogs(context: Context) {
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val javaLogFile = java.io.File(extDir, "ala_tool.log")
                val nativeLogFile = java.io.File(extDir, "ala_tool_native.log")
                val javaLog = if (javaLogFile.exists()) javaLogFile.readText() else ""
                val nativeLog = if (nativeLogFile.exists()) nativeLogFile.readText() else ""
                if (javaLog.isNotEmpty() || nativeLog.isNotEmpty()) {
                    val pushed = LogReceiver.send(context, javaLog, nativeLog)
                    Log.i(TAG, "ConfigReceiver: pushed game logs via broadcast (java=${javaLog.length} native=${nativeLog.length} success=$pushed)")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ConfigReceiver: push game logs failed: ${e.message}")
        }
    }
}
