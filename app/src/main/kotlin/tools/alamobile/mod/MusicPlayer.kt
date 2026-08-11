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
 * 主菜单音乐替换播放器。在游戏进程内运行，负责：
 * 1. 从模块 APK 的 assets 资源中提取 F1 MP3 到缓存目录
 * 2. 轮询 [NativeBridge.isInMainMenu] 检测是否在主菜单
 * 3. 在主菜单时播放 MP3（循环），离开主菜单时暂停
 * 4. 通过 [NativeBridge.setMusicReplace] 同步 mute 游戏原生音乐
 *
 * MP3 提取方式：优先拿模块 APK 绝对路径后用 ZipFile 直接解压 assets/
 * 条目（[NativeBridge.resolveModuleApkPath]）。游戏进程的 ClassLoader
 * getResourceAsStream 在 LSPosed/NPatch 下常找不到资源——模块 ClassLoader 的
 * dexElements[].path 指向优化后的 dex 而非原 APK（M23 真机"静音不播放"根因）；
 * ClassLoader 路径仅作最后兜底。
 *
 * 为什么放 assets 而不是 res/raw：R8 资源缩减（isMinifyEnabled=true）会把
 * res/raw 里的 mp3 重命名为短随机名（如 res/sL.mp3），导致按名字解压失败。
 * assets/ 下的文件 R8 不重命名，ZipFile 按固定路径读取稳定可靠。
 *
 * 生命周期：AlaMobileModule.doPackageReadyDeferred 延迟 15s 后创建，
 * 随游戏进程存活。
 */
class MusicPlayer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AlaMobileTool"
        private const val APK_ENTRY_PATH = "assets/f1_music.mp3"
        private const val RAW_RESOURCE_PATH = "assets/f1_music.mp3"
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
     * 优先用 APK 绝对路径 + ZipFile 解压（游戏进程下最可靠），
     * ClassLoader.getResourceAsStream 作兜底。
     */
    private fun extractMusicFile() {
        try {
            // 优先：模块 APK 绝对路径 + ZipFile 直接解压 raw 条目。
            // 游戏进程 ClassLoader 的 dexElements[].path 是优化后的 dex 路径，
            // getResourceAsStream 找不到 APK 内的 raw 资源（M23 静音不播放根因）。
            val apkPath = NativeBridge.resolveModuleApkPath(context)
            if (apkPath != null) {
                val entryFound = extractFromApk(apkPath)
                if (entryFound) {
                    Log.i(TAG, "MusicPlayer: extracted via APK path to ${musicFile?.absolutePath}")
                    return
                }
                Log.w(TAG, "MusicPlayer: $APK_ENTRY_PATH not found in APK ($apkPath), trying ClassLoader")
            } else {
                Log.w(TAG, "MusicPlayer: cannot resolve module APK path, trying ClassLoader")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: APK extract failed, trying ClassLoader", e)
        }
        // 兜底：ClassLoader 资源流（部分环境下可用）
        try {
            val cl = NativeBridge::class.java.classLoader
            if (cl == null) {
                Log.w(TAG, "MusicPlayer: ClassLoader null")
                return
            }
            val inputStream = cl.getResourceAsStream(RAW_RESOURCE_PATH)
            if (inputStream == null) {
                Log.w(TAG, "MusicPlayer: resource not found in ClassLoader ($RAW_RESOURCE_PATH)")
                return
            }
            inputStream.use { copyToCache(it) }
            Log.i(TAG, "MusicPlayer: extracted via ClassLoader to ${musicFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: ClassLoader extract failed", e)
        }
    }

    /** 从模块 APK 直接解压 raw/f1_music.mp3 到缓存。返回是否成功。 */
    private fun extractFromApk(apkPath: String): Boolean {
        return try {
            val found = ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(APK_ENTRY_PATH) ?: run {
                    Log.w(TAG, "MusicPlayer: APK has no entry $APK_ENTRY_PATH (entries assets/: ${zip.entries().asSequence().filter { it.name.startsWith("assets") }.map { it.name }.toList()})")
                    return false
                }
                zip.getInputStream(entry).use { copyToCache(it) }
                true
            }
            found
        } catch (e: Throwable) {
            Log.e(TAG, "MusicPlayer: ZipFile open failed ($apkPath)", e)
            false
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
            // 每次轮询都尝试补提取——M23 里 MusicPlayer.init 在 native 完全就绪前
            // 跑，resolveModuleApkPath 的 Context 拿不到模块包（Android 11+ 可见性）
            // 时可能失败。等游戏进主菜单（musicFile 仍 null）再重试，命中
            // ConfigReceiver 改配置时 ClassLoader 已稳定、APK 路径可解析的场景。
            if (musicFile == null && enabled) {
                extractMusicFile()
            }
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
