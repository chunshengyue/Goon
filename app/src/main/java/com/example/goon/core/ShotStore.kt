package com.example.goon.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File

/**
 * 预览截图存放区。
 *
 * 为什么单独一块：项目工作区是纯 UTF-8 文本（单文件 512 KiB、合计 2 MiB），截图是二进制，
 * 放进去会同时破坏两件事——校验会拒（扩展名白名单里没有图片格式），而且每张图都会跟着项目
 * 一起进版本与上下文。所以截图落在独立目录，trace 里只记引用、不写图片内容（可能含用户数据）。
 *
 * 格式取舍（同一张 1114×1505 真机截图实测，见 docs/prd_next_phase.md 第 25 节）：
 *   无损 WebP 412 KB ／ PNG 450 KB ／ JPEG q90 213 KB ／ JPEG q80 150 KB ／ 有损 WebP q90 110 KB
 * 结论：**有损 WebP q90 最小**（比无损 WebP 小 73%、比 JPEG q90 小 49%），而且 2× 放大后
 * 中文文字边缘干净、没有 JPEG 那种块状伪影。初版用了无损 WebP，是选错了——它比 JPEG 还大。
 * WEBP_LOSSY / WEBP_LOSSLESS 都要 API 30，本项目 minSdk = 24，低版本退回 JPEG q90。
 */
object ShotStore {
    private const val QUALITY = 90

    fun webpSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    fun extension(): String = if (webpSupported()) "webp" else "jpg"

    private val format: Bitmap.CompressFormat
        get() = if (webpSupported()) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG

    /** key 形如 draft-&lt;runId&gt; 或 pub-&lt;projectId&gt;-&lt;revision&gt;；同 key 覆盖，聊天里要的是"现在长什么样"。 */
    fun file(context: Context, key: String): File =
        File(context.applicationContext.filesDir, "shots/$key.${extension()}")

    fun exists(context: Context, key: String): Boolean = file(context, key).let { it.exists() && it.length() > 0 }

    /**
     * 写入。不 recycle 传入的 bitmap：调用方（UI）还要用它显示，
     * 在这里 recycle 会让紧接着的渲染直接崩。
     */
    fun write(context: Context, key: String, bitmap: Bitmap): File? {
        val target = file(context, key).apply { parentFile?.mkdirs() }
        val ok = runCatching { target.outputStream().use { bitmap.compress(format, QUALITY, it) } }.getOrDefault(false)
        return if (ok) target else null
    }

    /** 草稿发布后转存为版本截图，失败不阻断发布。 */
    fun promote(context: Context, runId: String, projectId: String, revision: String): File? {
        val draftKey = "draft-$runId"
        if (!exists(context, draftKey)) return null
        val target = file(context, "pub-$projectId-$revision")
        return runCatching { file(context, draftKey).copyTo(target, overwrite = true) }.getOrNull()
    }

    /** 每个项目只留最近几版截图，避免无限增长。 */
    fun prune(context: Context, projectId: String, keep: Int = 3) {
        runCatching {
            val prefix = "pub-$projectId-"
            File(context.applicationContext.filesDir, "shots").listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}
