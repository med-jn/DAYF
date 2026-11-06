package app.dayf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenGrabber(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val intervalMs: Long,
    private val onFrame: (ByteArray) -> Unit
) {
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val ht = HandlerThread("dayf-grab")
    @Volatile var latest: ByteArray? = null
    private var last = 0L

    @Suppress("DEPRECATION")
    fun start() {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        val w = minOf(dm.widthPixels, 720)
        val h = (dm.heightPixels.toLong() * w / dm.widthPixels).toInt()
        ht.start()
        val handler = Handler(ht.looper)
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = projection.createVirtualDisplay(
            "dayf", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, handler
        )
        rd.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - last >= intervalMs) {
                        last = now
                        val jpg = toJpeg(img, w, h)
                        if (jpg != null) {
                            latest = jpg
                            onFrame(jpg)
                        }
                    }
                } finally {
                    img.close()
                }
            }
        }, handler)
    }

    private fun toJpeg(img: Image, w: Int, h: Int): ByteArray? {
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
        ht.quitSafely()
    }
}
