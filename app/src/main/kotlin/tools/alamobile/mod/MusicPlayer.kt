package tools.alamobile.mod

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 主菜单音乐替换播放器。在游戏进程内运行，负责：
 * 1. 从模块 APK 的 raw 资源中提取 F1 MP3 到缓存目录
 * 2. 轮询 [NativeBridge.isInMainMenu] 检测是否在主菜单
 * 3. 在主菜单时播放 MP3（循环），离开主菜单时暂停
 * 4. 通过 [NativeBridge.setMusicReplace] 同步 mute 游戏原生音乐
 *
 * MP3 提取方式：模块 APK 的 raw 资源可以通过模块 ClassLoader 的
 * getResourceAsStream("res/raw/f1_music.mp3") 访问——这是 Android
 * BaseDexClassLoader 的默认行为，不依赖 PackageManager 包可见性。
 *
 * 生命周期：AlaMobileModule.doPackageReadyDeferred 延迟 15s 后创建，
 * 随游戏进程存活。
 */
class MusicPlayer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AlaMobileTool"
        private const val RAW_RESOURCE_PATH = "res/raw/f1_music.mp3"
        private const val CACHE_FILE_NAME = "ala_f1_music.mp3"
        private const val POLL_INTERVAL_MS = 1000L

        @Volatile
        var instance: MusicPlayer? = null
            private set

        /**
         * 初始化 MusicPlayer 单例。在游戏进程初始化路径中调用一次。
         * 提取 MP3 到缓存目录，启动轮询 timer。
         */
        fun init(context: Context) {
            if (instance != null) return
            instance = MusicPlayer(context)
        }

        /**
         * 设置替换音乐开关。由配置变更（广播）或初始化时调用。
         * 开关打开时自动开始轮询主菜单状态；关闭时停止音乐 + 恢复游戏原生音量。
         */
        fun setEnabled(enabled: Boolean) {
            instance?.setEnabledInternal(enabled)
        }

        /**
         * 销毁 MusicPlayer。释放 MediaPlayer + 删除缓存文件。
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
    private var musicFile: File? = null
    private val pollRunnable: Runnable = Runnable { poll() }

    init {
        extractMusicFile()
        // 启动轮询（每 1s 检查一次主菜单状态）
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    /**
     * 从模块 APK 的 raw 资源中提取 MP3 到缓存目录。
     * 使用 ClassLoader.getResourceAsStream 绕过包可见性限制。
     */
    private fun extractMusicFile() {
        try {
            val cl = NativeBridge::class.java.classLoader
            if (cl == null) {
                Log.w(TAG, "MusicPlayer: ClassLoader null")
                return
            }
            val inputStream = cl.getResourceAsStream(RAW_RESOURCE_PATH)
            if (inputStream == null) {
                Log.w(TAG, "MusicPlayer: resource not found in ClassLoader ($RAW_RESOURCE_PATH)")
                // 尝试备选路径（AAPT2 可能用不同路径）
                val altStream = cl.getResourceAsStream("res/raw/f1_music.mp3")
                if (altStream == null) {
                    Log.w(TAG, "MusicPlayer: alternative path also not found")
                    return
                }
                altStream.use { copyToCache(it) }
                return
            }
            inputStream.use { copyToCache(it) }
            Log.i(TAG, "MusicPlayer: extracted to ${musicFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: extract failed", e)
        }
    }

    private fun copyToCache(inputStream: java.io.InputStream) {
        val tempFile = File(context.cacheDir, CACHE_FILE_NAME)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        musicFile = tempFile
    }

    private fun setEnabledInternal(enabled: Boolean) {
        this.enabled = enabled
        try {
            if (NativeBridge.isAvailable) {
                NativeBridge.setMusicReplace(enabled)
            }
        } catch (_: Throwable) {}
        if (!enabled) {
            stopMusic()
        }
        Log.i(TAG, "MusicPlayer: enabled=$enabled")
    }

    private fun poll() {
        try {
            if (!enabled || musicFile == null) {
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                return
            }
            if (!NativeBridge.isAvailable) {
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                return
            }

            val inMenu = NativeBridge.isInMainMenu()
            if (inMenu && !isPlaying) {
                startMusic()
            } else if (!inMenu && isPlaying) {
                stopMusic()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MusicPlayer: poll failed", e)
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun startMusic() {
        try {
            var mp = mediaPlayer
            if (mp == null) {
                val file = musicFile ?: return
                mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    isLooping = true
                    prepare()
                }
                mediaPlayer = mp
            }
            if (!mp.isPlaying) {
                mp.start()
                isPlaying = true
                Log.i(TAG, "MusicPlayer: started playing F1 music")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: start failed", e)
        }
    }

    private fun stopMusic() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    mp.seekTo(0)
                }
            }
            isPlaying = false
            Log.i(TAG, "MusicPlayer: stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: stop failed", e)
        }
    }

    private fun destroyInternal() {
        handler.removeCallbacks(pollRunnable)
        stopMusic()
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            musicFile?.delete()
        } catch (_: Throwable) {}
        musicFile = null
        Log.i(TAG, "MusicPlayer: destroyed")
    }
}