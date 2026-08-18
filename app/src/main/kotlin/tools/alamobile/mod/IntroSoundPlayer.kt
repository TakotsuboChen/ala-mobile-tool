package tools.alamobile.mod

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * V10 引擎声浪播放器。在游戏进程内运行，负责：
 * 1. 从模块 APK 的 assets 资源中提取 V10 MP3 到缓存目录
 * 2. 轮询 [NativeBridge.isIntroStarted] 检测开场动画是否开始（one-shot 信号）
 * 3. 检测到开场开始时播放 MP3（单次，不循环），播完即停
 * 4. 通过 [NativeBridge.setV10Sound] 同步 native 层静音游戏开场 introSound
 *
 * 与 [MusicPlayer] 完全独立，不共享 MediaPlayer / 状态。两者各自开关互不协调。
 *
 * MP3 提取方式与 MusicPlayer 一致：优先拿模块 APK 绝对路径后用 ZipFile 直接解压
 * assets/ 条目（[NativeBridge.resolveModuleApkPath]）。assets/ 下的文件 R8 不重命名。
 *
 * 生命周期：AlaMobileModule.doPackageReadyDeferred 延迟 15s 后创建，
 * 随游戏进程存活。
 */
class IntroSoundPlayer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AlaMobileTool"
        private const val APK_ENTRY_PATH = "assets/f1_v10_sound.mp3"
        private const val RAW_RESOURCE_PATH = "assets/f1_v10_sound.mp3"
        private const val CACHE_FILE_NAME = "ala_f1_v10.mp3"
        private const val POLL_INTERVAL_MS = 500L

        @Volatile
        var instance: IntroSoundPlayer? = null
            private set

        /**
         * 初始化 IntroSoundPlayer 单例。在游戏进程初始化路径中调用一次。
         * 提取 MP3 到缓存目录，启动轮询 timer。
         */
        fun init(context: Context) {
            if (instance != null) return
            instance = IntroSoundPlayer(context)
        }

        /**
         * 设置 V10 引擎声浪开关。由配置变更（广播）或初始化时调用。
         * 开关打开时 native 层静音 introSound + 轮询开场信号；
         * 关闭时停止播放 + 恢复游戏原生音量。
         */
        fun setEnabled(enabled: Boolean) {
            instance?.setEnabledInternal(enabled)
        }

        /**
         * 销毁 IntroSoundPlayer。释放 MediaPlayer + 删除缓存文件。
         */
        fun destroy() {
            instance?.destroyInternal()
            instance = null
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var enabled = false
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private var soundFile: File? = null
    private val pollRunnable: Runnable = Runnable { poll() }

    init {
        extractSoundFile()
        // 启动轮询（每 500ms 检查一次开场信号）
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    /**
     * 从模块 APK 的 assets 资源中提取 V10 MP3 到缓存目录。
     * 优先用 APK 绝对路径 + ZipFile 解压（游戏进程下最可靠），
     * ClassLoader.getResourceAsStream 作兜底。
     */
    private fun extractSoundFile() {
        try {
            val apkPath = NativeBridge.resolveModuleApkPath(context)
            if (apkPath != null) {
                val entryFound = extractFromApk(apkPath)
                if (entryFound) {
                    Log.i(TAG, "IntroSoundPlayer: extracted via APK path to ${soundFile?.absolutePath}")
                    return
                }
                Log.w(TAG, "IntroSoundPlayer: $APK_ENTRY_PATH not found in APK ($apkPath), trying ClassLoader")
            } else {
                Log.w(TAG, "IntroSoundPlayer: cannot resolve module APK path, trying ClassLoader")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "IntroSoundPlayer: APK extract failed, trying ClassLoader", e)
        }
        // 兜底：ClassLoader 资源流
        try {
            val cl = NativeBridge::class.java.classLoader
            if (cl == null) {
                Log.w(TAG, "IntroSoundPlayer: ClassLoader null")
                return
            }
            val inputStream = cl.getResourceAsStream(RAW_RESOURCE_PATH)
            if (inputStream == null) {
                Log.w(TAG, "IntroSoundPlayer: resource not found in ClassLoader ($RAW_RESOURCE_PATH)")
                return
            }
            inputStream.use { copyToCache(it) }
            Log.i(TAG, "IntroSoundPlayer: extracted via ClassLoader to ${soundFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "IntroSoundPlayer: ClassLoader extract failed", e)
        }
    }

    /** 从模块 APK 直接解压 assets/f1_v10_sound.mp3 到缓存。返回是否成功。 */
    private fun extractFromApk(apkPath: String): Boolean {
        return try {
            val found = ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(APK_ENTRY_PATH) ?: run {
                    Log.w(TAG, "IntroSoundPlayer: APK has no entry $APK_ENTRY_PATH")
                    return false
                }
                zip.getInputStream(entry).use { copyToCache(it) }
                true
            }
            found
        } catch (e: Throwable) {
            Log.e(TAG, "IntroSoundPlayer: ZipFile open failed ($apkPath)", e)
            false
        }
    }

    private fun copyToCache(inputStream: java.io.InputStream) {
        val tempFile = File(context.cacheDir, CACHE_FILE_NAME)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        soundFile = tempFile
    }

    private fun setEnabledInternal(enabled: Boolean) {
        this.enabled = enabled
        try {
            if (NativeBridge.isAvailable) {
                NativeBridge.setV10Sound(enabled)
            }
        } catch (_: Throwable) {}
        if (!enabled) {
            stopSound()
        }
        Log.i(TAG, "IntroSoundPlayer: enabled=$enabled")
    }

    private fun poll() {
        try {
            // 每次轮询都尝试补提取——与 MusicPlayer 一致的兜底策略。
            if (soundFile == null && enabled) {
                extractSoundFile()
            }
            if (!enabled || soundFile == null) {
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                return
            }
            if (!NativeBridge.isAvailable) {
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                return
            }

            // 检测开场动画是否已开始（one-shot：返回即清零）
            val introStarted = NativeBridge.isIntroStarted()
            if (introStarted && !isPlaying) {
                startSound()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "IntroSoundPlayer: poll failed", e)
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun startSound() {
        try {
            var mp = mediaPlayer
            if (mp == null) {
                val file = soundFile ?: return
                mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    isLooping = false  // 单次播放，播完即停
                    prepare()
                    setOnCompletionListener {
                        stopSound()
                        Log.i(TAG, "IntroSoundPlayer: playback completed")
                    }
                }
                mediaPlayer = mp
            }
            if (!mp.isPlaying) {
                mp.start()
                isPlaying = true
                Log.i(TAG, "IntroSoundPlayer: started playing V10 engine sound")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "IntroSoundPlayer: start failed", e)
        }
    }

    private fun stopSound() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.reset()
            }
            isPlaying = false
            Log.i(TAG, "IntroSoundPlayer: stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "IntroSoundPlayer: stop failed", e)
        }
    }

    private fun destroyInternal() {
        handler.removeCallbacks(pollRunnable)
        stopSound()
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            soundFile?.delete()
        } catch (_: Throwable) {}
        soundFile = null
        Log.i(TAG, "IntroSoundPlayer: destroyed")
    }
}