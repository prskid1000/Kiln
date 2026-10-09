package app.kiln.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import java.io.File
import java.io.ByteArrayOutputStream

/**
 * An invisible display the agent tests apps on, so it never takes over the user's screen.
 *
 * Kiln owns it (a private virtual display needs no permission) and reads its frames from an
 * ImageReader. Warden — running as shell, which may launch onto any display — starts the app
 * there (`am start --display`), and input goes to it with `input -d`. The UI tree comes from
 * `uiautomator dump --display`. The user's own Run button still uses the real screen.
 */
class TestDisplay(private val context: Context, private val size: Triple<Int, Int, Int>? = null) {
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread by lazy { HandlerThread("kiln-test-display").apply { start() } }
    var width = 0; private set
    var height = 0; private set
    var densityDpi = 0; private set

    /** The display's id, creating it on first use at the real screen's size and density. */
    @Synchronized
    fun id(): Int {
        vd?.let { return it.display.displayId }
        val m = context.resources.displayMetrics
        // Full real size (not the app window): match the phone the app will run on.
        val real = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        width = size?.first ?: real.width(); height = size?.second ?: real.height()
        densityDpi = size?.third ?: m.densityDpi
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val d = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "kiln-test", width, height, size?.third ?: m.densityDpi, r.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, null, Handler(thread.looper))
        reader = r; vd = d
        return d.display.displayId
    }

    /**
     * What the display shows now, full size. A still screen produces no new frames, so the last
     * frame read is kept and returned: it is still what's showing. Null before anything drew.
     */
    suspend fun bitmap(wait: Boolean = true): Bitmap? = withContext(Dispatchers.IO) { frameLock.withLock { frame(wait) } }

    /** One reader at a time: the recorder, the preview and screenshots each holding a frame overran maxImages (2) and threw. */
    private val frameLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun frame(wait: Boolean): Bitmap? {
        val r = reader ?: return null
        fun latest() = runCatching { r.acquireLatestImage() }.getOrNull()
        var img = latest()
        var tries = 0
        while (img == null && wait && lastFrame == null && tries++ < 20) { delay(100); img = latest() }
        img ?: return lastFrame
        // Always close the image: with maxImages = 2, two leaked frames would end all captures.
        return try {
            val plane = img.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            // Some GPUs don't pad the last row: copy only the rows the buffer actually holds.
            val rows = minOf(height, plane.buffer.remaining() / plane.rowStride)
            val padded = Bitmap.createBitmap(stride, rows, Bitmap.Config.ARGB_8888)
            plane.buffer.limit(plane.buffer.position() + rows * plane.rowStride)
            padded.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(padded, 0, 0, width, rows).also { lastFrame = it }
        } catch (e: Exception) { lastFrame } finally { img.close() }
    }

    /** The current frame as PNG, scaled to [maxSide] on the long edge. */
    suspend fun capture(maxSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        val full = bitmap() ?: return@withContext null
        val k = maxSide.toFloat() / maxOf(full.width, full.height)
        val out = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).toInt(), (full.height * k).toInt(), true) else full
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Volatile private var lastFrame: Bitmap? = null

    /** Record what the display shows (about 4 fps, ≤ 720 px tall) until [Recording.stop]. */
    fun record(file: File, scope: kotlinx.coroutines.CoroutineScope): Recording {
        id()
        val k = minOf(1f, 720f / height)
        // Even sizes, multiples of 16: some hardware encoders reject odd ones.
        val writer = VideoWriter(file, ((width * k).toInt() / 16 * 16).coerceAtLeast(16), ((height * k).toInt() / 16 * 16).coerceAtLeast(16), fps = 4)
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) { bitmap(wait = false)?.let { runCatching { writer.add(it) } }; delay(250) }
        }
        return Recording(job, writer)
    }

    class Recording internal constructor(private val job: kotlinx.coroutines.Job, private val writer: VideoWriter) {
        /** Stops and returns the MP4, or null if nothing was captured. */
        suspend fun stop(): File? { job.cancelAndJoin(); return withContext(Dispatchers.IO) { writer.finish() } }
    }

    @Synchronized
    fun release() { vd?.release(); reader?.close(); vd = null; reader = null; lastFrame = null }
}
