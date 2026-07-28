package tools.alamobile.mod.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tools.alamobile.mod.NativeBridge
import tools.alamobile.mod.config.ModConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Dual-zone vertical pedal overlay.
 *
 * The touch area is split vertically around [ModConfig.Settings.pedalTransition].
 * - Top half: throttle. Finger at the top => full throttle; near the transition
 *   line (inside the deadzone) => zero throttle.
 * - Bottom half: brake. Finger at the bottom => full brake; near the transition
 *   line (inside the deadzone) => zero brake.
 *
 * The mapping curve can be linear, quadratic, or exponential.
 */
class PedalOverlayView(
    context: Context,
    private val settings: ModConfig.Settings = ModConfig.Settings(
        enableControlReplacement = true,
        enableAutoDrs = true,
        showOverlay = true,
        disableAutoGear = false,
        enableUnlock = false,
        pedalDeadzone = 0.05f,
        pedalTransition = 0.5f,
        pedalCurve = ModConfig.PedalCurve.LINEAR
    )
) : View(context) {

    companion object {
        private const val TAG = "AlaMobileTool"

        /**
         * Shared IPC state between PedalOverlayView and GearShiftView.
         * Both views write to the same file, so we use shared static fields
         * to avoid one view clobbering the other's data.
         */
        @Volatile
        var sharedThrottle = 0f
        @Volatile
        var sharedBrake = 0f
        /**
         * Monotonically increasing counter for shift commands.
         * Odd values = shift up, even values = shift down.
         * Native detects the *change* in value to fire a one-shot command.
         */
        @Volatile
        var sharedShiftCmd = 0

        @Volatile
        var ipcFile: File? = null

        private var raf: RandomAccessFile? = null
        // Reused buffer — no per-frame allocation
        private val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        /**
         * Atomically write current shared state to the IPC file as 12 bytes
         * of binary data (float + float + int, little-endian).
         *
         * Uses RandomAccessFile.seek(0) + write() to overwrite in-place.
         * Unlike File.writeText() which deletes and recreates the file
         * (causing ENOENT races and filesystem metadata churn), this just
         * overwrites the existing bytes — a single pwrite() syscall, no
         * filesystem operations, no sync, no GC pressure, no heat.
         */
        fun flushToIpc() {
            try {
                val file = ipcFile ?: return
                if (raf == null) {
                    if (!file.exists()) file.createNewFile()
                    raf = RandomAccessFile(file, "rw")
                    Log.i(TAG, "IPC file opened: ${file.absolutePath}")
                }
                val r = raf!!
                r.seek(0)
                buffer.clear()
                buffer.putFloat(sharedThrottle)
                buffer.putFloat(sharedBrake)
                buffer.putInt(sharedShiftCmd)
                r.write(buffer.array(), 0, 12)
            } catch (_: Exception) {
                // Silently ignore — file may not be writable yet
            }
        }
    }

    private val throttlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 255, 0)
    }
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 0, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var throttle = 0f
    private var brake = 0f

    init {
        ipcFile = File(context.cacheDir, "ala_input.dat")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height * settings.pedalTransition
        val throttleHeight = centerY * throttle
        val brakeHeight = (height - centerY) * brake

        canvas.drawRect(0f, centerY - throttleHeight, width.toFloat(), centerY, throttlePaint)
        canvas.drawRect(0f, centerY, width.toFloat(), centerY + brakeHeight, brakePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateValues(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                throttle = 0f
                brake = 0f
                updateNativeValues()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateValues(y: Float) {
        val height = this.height.toFloat()
        if (height <= 0f) return

        val t = (y / height).coerceIn(0f, 1f)
        val transition = settings.pedalTransition.coerceIn(0.1f, 0.9f)
        val deadzone = settings.pedalDeadzone.coerceIn(0f, 0.5f)

        if (t <= transition) {
            val raw = if (transition <= 0f) 0f else 1f - (t / transition)
            throttle = applyCurve(applyDeadzone(raw, deadzone))
            brake = 0f
        } else {
            val raw = if (transition >= 1f) 0f else (t - transition) / (1f - transition)
            throttle = 0f
            brake = applyCurve(applyDeadzone(raw, deadzone))
        }

        updateNativeValues()
        invalidate()
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        if (deadzone <= 0f) return value
        if (value <= deadzone) return 0f
        return (value - deadzone) / (1f - deadzone)
    }

    private fun applyCurve(value: Float): Float {
        val exponent = when (settings.pedalCurve) {
            ModConfig.PedalCurve.LINEAR -> 1f
            ModConfig.PedalCurve.QUADRATIC -> 2f
            ModConfig.PedalCurve.EXPONENTIAL -> 2.5f
        }
        val result = value.coerceIn(0f, 1f).pow(exponent)
        return result.coerceIn(0f, 1f)
    }

    private fun updateNativeValues() {
        // Update shared state (preserves shiftCmd from GearShiftView)
        sharedThrottle = throttle
        sharedBrake = brake

        // Flush to IPC file — works for ALL builds (original + coexistence)
        flushToIpc()

        // Also try direct JNI — fast path for original build
        if (NativeBridge.isAvailable) {
            try {
                NativeBridge.setThrottle(throttle)
                NativeBridge.setBrake(brake)
            } catch (_: Throwable) {}
        }
    }
}
