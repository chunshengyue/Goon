package com.example.goon.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

data class ImageInput(val mimeType: String, val base64: String, val byteCount: Int)

fun prepareImageInput(context: Context, uri: Uri): ImageInput {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片。" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val decoded = resolver.openInputStream(uri).use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("无法解码图片。")
    val scale = minOf(1f, 1280f / maxOf(decoded.width, decoded.height))
    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    if (bitmap !== decoded) decoded.recycle()
    try {
        var quality = 85
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 10
        } while (bytes.size > 1_500_000 && quality >= 45)
        require(bytes.size <= 1_500_000) { "图片压缩后仍超过 1.5 MB。" }
        return ImageInput("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size)
    } finally {
        bitmap.recycle()
    }
}
