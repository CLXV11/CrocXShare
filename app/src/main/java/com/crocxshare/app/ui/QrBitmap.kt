package com.crocxshare.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders pairing payloads as an on-screen QR code. */
object QrBitmap {
    fun encode(content: String, sizePx: Int = 720): ImageBitmap? {
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (y in 0 until sizePx) {
                for (x in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF14231A.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
