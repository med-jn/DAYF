package app.dayf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Keeps a cheap virtual display alive, but only encodes a frame when grab() is called.
 * No frame is produced, sent or paid for unless something asks for it.
 */
class ScreenGrabber(private val ctx: Context, private val projection: MediaProjection) {
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var w = 0
    private var h = 0
    @Volatile var latest: ByteArray? = null
        private set

    @Suppress("DEPRECATION")
    fun start() {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        w = minOf(dm.widthPixels, 720)
        h = (dm.heightPixels.toLong() * w / dm.widthPixels).toInt()
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = projection.createVirtualDisplay(
            "dayf", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )
    }

    /** Returns a JPEG of the current screen (or the last one if the screen has not changed). */
    @Synchronized
    fun grab(): ByteArray? {
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            try {
                val j = toJpeg(img)
                if (j != null) latest = j
            } finally {
                img.close()
            }
        }
        return latest
    }

    private fun toJpeg(img: Image): ByteArray? {
        return try {
            val p = img.planes[0]
            val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(p.buffer)
            val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
            val bos = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 60, bos)
            if (crop !== bmp) crop.recycle()
            bmp.recycle()
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        try {
            vd?.release()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
    }
}
