package com.example.goon.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 受控的页面读取：把「搜到的那条线索」变成「能用的正文片段」。
 *
 * 为什么要有它：`search_web` 只回标题与摘要，那是**发现入口**，不是内容。查数字、条款、榜单这类
 * 问题时，摘要里永远没有答案。但也**不能把整页塞进上下文**——实测一个数据站首页的可见正文就有
 * 16,631 字，一次就能吃掉辛苦压下来的上下文预算（见 `docs/prd_next_phase.md` 15.4、13.6）。
 *
 * 所以这里做三件事：只取正文、去掉导航与脚本、按上限截断。返回的是片段，不是文档。
 */
object WebPageReader {
    /**
     * 读取工具的 schema。和 `search_web` 一样放在能力实现旁边，供聊天与小程序 Agent 共用。
     */
    fun toolSchema(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", "fetch_page")
            .put("description", "读取一个网页的正文片段。只能读取 search_web 本次会话真正返回过的站点，不能凭构造的地址访问其它地方。返回的是抽取后的正文（已去掉脚本、样式与导航），不是原始 HTML；超过上限会被截断。用它拿摘要里没有的具体数字、榜单、条款、版本号。maxChars 控制回传长度，默认 6000、上限 12000；不确定就直接用默认值。")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("url", JSONObject().put("type", "string"))
                    .put("maxChars", JSONObject().put("type", "integer")))
                .put("required", JSONArray().put("url"))
                .put("additionalProperties", false)))

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    /** 下载阶段的上限：够覆盖普通文章页，又不会被超大页面拖死。 */
    private const val MAX_DOWNLOAD_BYTES = 2_000_000
    const val MAX_CHARS = 12_000
    const val DEFAULT_CHARS = 6_000
    const val MIN_CHARS = 500
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun read(rawUrl: String, maxChars: Int = DEFAULT_CHARS): JSONObject {
        val url = rawUrl.trim()
        require(url.startsWith("https://") || url.startsWith("http://")) { "只支持 http/https 地址。" }
        require(url.length <= 2000) { "地址过长。" }
        val limit = maxChars.coerceIn(MIN_CHARS, MAX_CHARS)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "页面返回 HTTP $code。" }
            val type = connection.contentType.orEmpty()
            require(type.contains("html", true) || type.contains("text/plain", true)) { "不是可读的网页内容（$type）。" }
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (out.size() < MAX_DOWNLOAD_BYTES) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray().toString(StandardCharsets.UTF_8)
            }
            val title = extractTitle(body)
            val text = toPlainText(body)
            require(text.isNotBlank()) { "页面没有可提取的正文，可能是需要 JavaScript 渲染的站点。" }
            val truncated = text.length > limit
            return JSONObject()
                .put("ok", true)
                .put("url", url)
                .put("title", title.take(200))
                .put("chars", text.length)
                .put("truncated", truncated)
                .put("text", if (truncated) text.take(limit) else text)
                .also { if (truncated) it.put("note", "正文共 ${text.length} 字，已截断到 $limit 字。需要后面部分请带上更具体的 maxChars 或换更精确的来源页。") }
        } finally {
            connection.disconnect()
        }
    }

    /** 白名单判定：只允许读取本次会话里 `search_web` 真正返回过的主机。 */
    fun allowedHosts(workspace: Workspace, conversationId: String): Set<String> {
        val hosts = mutableSetOf<String>()
        workspace.events(conversationId).filter { it.kind == "web_search" }.forEach { event ->
            val results = runCatching { JSONObject(event.message).optJSONArray("results") }.getOrNull() ?: return@forEach
            for (index in 0 until results.length()) {
                val host = results.optJSONObject(index)?.optString("domain").orEmpty()
                if (host.isNotBlank()) hosts += host.lowercase()
            }
        }
        return hosts
    }

    fun hostOf(url: String): String = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")

    private fun extractTitle(html: String): String {
        val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
        return decodeEntities(match?.groupValues?.get(1).orEmpty()).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 只保留正文：丢掉脚本、样式、内联资源与注释。
     * 这是启发式而不是真正的阅读模式，所以宁可少给——多给的噪声会把上下文和模型注意力一起吃掉。
     */
    private fun toPlainText(html: String): String {
        var text = html
        text = Regex("(?s)<!--.*?-->").replace(text, " ")
        for (tag in listOf("script", "style", "noscript", "svg", "iframe", "template", "head")) {
            text = Regex("(?is)<$tag\\b[^>]*>.*?</$tag>").replace(text, " ")
        }
        // 块级元素与换行标签补空格，避免相邻文本粘连成假词。
        text = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|nav|ul|ol|table)\\b[^>]*>").replace(text, " ")
        text = Regex("<[^>]+>").replace(text, " ")
        text = decodeEntities(text)
        return text.replace(Regex("[\\t\\u00a0]+"), " ").replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{2,}"), "\n").replace(Regex(" {2,}"), " ").trim()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "times" to "×", "laquo" to "«", "raquo" to "»"
    )

    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);").replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", true) -> body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
            }
        }
    }

    private fun codePointToString(code: Int): String =
        if (code in 0x20..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else " "
}
