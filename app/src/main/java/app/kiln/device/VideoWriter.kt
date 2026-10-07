package app.kiln.device

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes bitmaps into an H.264 MP4 with the platform encoder (no library): each frame is
 * converted to YUV 4:2:0 and written into the codec's input image. Used to record QA runs on the
 * hidden test display. Not thread-safe; one writer per recording.
 */
class VideoWriter(private val file: File, width: Int, height: Int, private val fps: Int = 4) {
    // Encoders want even dimensions.
    val w = width and 1.inv()
    val h = height and 1.inv()
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 2).coerceAtMost(4_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun add(frame: Bitmap) {
        val bmp = if (frame.width == w && frame.height == h) frame else Bitmap.createScaledBitmap(frame, w, h, true)
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val img = codec.getInputImage(idx) ?: return
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            val (yP, uP, vP) = img.planes
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yP.buffer.put(y * yP.rowStride + x * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uP.buffer.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u.toByte())
                    vP.buffer.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v.toByte())
                }
            }
            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, frames++ * 1_000_000L / fps, 0)
        }
        drain(false)
    }

    /** Finish the file; returns it, or null if nothing was recorded. */
    fun finish(): File? {
        runCatching {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        }
        runCatching { codec.stop() }; codec.release()
        val ok = track >= 0
        runCatching { if (ok) muxer.stop() }; runCatching { muxer.release() }
        return if (ok && frames > 0) file else { file.delete(); null }
    }

    private fun drain(end: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
