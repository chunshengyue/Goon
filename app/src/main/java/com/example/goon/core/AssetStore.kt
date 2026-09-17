package com.example.goon.core

import android.content.Context
import java.io.File

/**
 * 素材库：二进制素材（图片等）的统一存放与读取。
 *
 * 为什么不放进项目工作区：项目文件是**纯 UTF-8 文本**（单文件 512 KiB、合计 2 MiB，扩展名白名单里
 * 没有图片格式），图片放进去既过不了校验，也会跟着每次 write_file 重新计进模型上下文——
 * 那正是 PRD 15.4 要消除的膨胀源。所以素材独立存放，小程序**靠路径引用**（`assets/名字`），
 * 由 `WebPreview.resource()` 在同源下按 manifest 声明的清单发放。
 */
object AssetStore {
    /** 单文件上限：立绘这类 20–50 KB，留出余量但不允许塞大文件。 */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    /** 素材库总量上限：手机上要能接受。 */
    const val MAX_TOTAL_BYTES = 24 * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")

    data class Asset(val name: String, val bytes: Long)

    fun root(context: Context): File = File(context.applicationContext.filesDir, "assets").apply { mkdirs() }

    /** 名字允许中文（立绘文件名就是中文），但拒绝路径穿越与隐藏文件。 */
    fun validName(name: String): Boolean {
        if (name.length !in 1..120) return false
        if (name.startsWith(".") || name.contains('/') || name.contains('\\') || name.contains("..")) return false
        return name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    fun file(context: Context, name: String): File? = if (validName(name)) File(root(context), name) else null

    fun exists(context: Context, name: String): Boolean = file(context, name)?.let { it.isFile && it.length() > 0 } == true

    fun list(context: Context): List<Asset> = root(context).listFiles()
        ?.filter { it.isFile && validName(it.name) }
        ?.sortedBy { it.name }
        ?.map { Asset(it.name, it.length()) } ?: emptyList()

    fun read(context: Context, name: String): ByteArray? =
        file(context, name)?.takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun write(context: Context, name: String, bytes: ByteArray): File? {
        if (!validName(name) || bytes.size > MAX_FILE_BYTES) return null
        val used = list(context).filter { it.name != name }.sumOf { it.bytes }
        if (used + bytes.size > MAX_TOTAL_BYTES) return null
        val target = File(root(context), name)
        return runCatching { target.writeBytes(bytes); target }.getOrNull()
    }

    fun delete(context: Context, name: String): Boolean = file(context, name)?.delete() == true

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "gif" -> "image/gif"; "svg" -> "image/svg+xml"; else -> "application/octet-stream"
    }
}
