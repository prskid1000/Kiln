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
import kotlinx.coroutines.withContext
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

    /** The display's id, creating it on first use at the real screen's size and density. */
    @Synchronized
    fun id(): Int {
        vd?.let { return it.display.displayId }
        val m = context.resources.displayMetrics
        // Full real size (not the app window): match the phone the app will run on.
        val real = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        width = size?.first ?: real.width(); height = size?.second ?: real.height()
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val d = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "kiln-test", width, height, size?.third ?: m.densityDpi, r.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, null, Handler(thread.looper))
        reader = r; vd = d
        return d.display.displayId
    }

    /** The newest frame as PNG, scaled to [maxSide] on the long edge; null if nothing has drawn yet. */
    suspend fun capture(maxSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        val r = reader ?: return@withContext null
        var img = r.acquireLatestImage()
        var tries = 0
        while (img == null && tries++ < 20) { delay(100); img = r.acquireLatestImage() }
        img ?: return@withContext lastFrame
        // Always close the image: with maxImages = 2, two leaked frames would end all captures.
        val full = try {
            val plane = img.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            // Some GPUs don't pad the last row: copy only the rows the buffer actually holds.
            val rows = minOf(height, plane.buffer.remaining() / plane.rowStride)
            val padded = Bitmap.createBitmap(stride, rows, Bitmap.Config.ARGB_8888)
            plane.buffer.limit(plane.buffer.position() + rows * plane.rowStride)
            padded.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(padded, 0, 0, width, rows)
        } catch (e: Exception) { return@withContext lastFrame } finally { img.close() }
        val k = maxSide.toFloat() / maxOf(width, height)
        val out = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).toInt(), (full.height * k).toInt(), true) else full
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { lastFrame = it }
    }

    /** A still screen produces no new frames; the last one is still what's showing. */
    private var lastFrame: ByteArray? = null

    @Synchronized
    fun release() { vd?.release(); reader?.close(); vd = null; reader = null; lastFrame = null }
}
