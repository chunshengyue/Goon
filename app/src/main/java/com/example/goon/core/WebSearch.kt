package com.example.goon.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class SearchResult(val title: String, val url: String, val snippet: String, val published: String? = null) {
    fun toJson() = JSONObject().apply { put("title", title); put("url", url); put("snippet", snippet); put("published", published ?: JSONObject.NULL); put("domain", Uri.parse(url).host ?: "") }
}

object WebSearch {
    /**
     * 搜索工具的 schema。聊天路径与小程序 Agent 共用同一份，避免两处描述漂移——
     * 这两个入口对「搜索」应该是同一种能力，不是一个功能两个实现。
     */
    fun toolSchema(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", "search_web")
            .put("description", "联网搜索并返回来源摘要；不能打开任意 URL，也不能直接请求网络。query 由你决定：要组合成能命中原始资料的关键词，不要把整句口语当查询词发出去；结果不相关或为空时就改写关键词重搜，不要放弃。domain 用于把结果限定到某个站点，是尽力而为：宿主在本地按域名过滤，站点没被收录时会返回 0 条而不是无关结果——这时不要带 domain 重试。")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string"))
                    .put("count", JSONObject().put("type", "integer"))
                    .put("domain", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("query"))
                .put("additionalProperties", false)))

    /** 搜索结果首页（无路径或仅根路径）几乎没有信息量，属于搜索引擎兜底噪声。 */
    private fun isLowValue(result: SearchResult): Boolean {
        val path = runCatching { Uri.parse(result.url).path.orEmpty() }.getOrDefault("")
        return path.isBlank() || path == "/"
    }

    fun search(query: String, count: Int = 5, domain: String? = null): List<SearchResult> {
        val clean = query.trim()
        require(clean.length in 2..600) { "搜索词需为 2-600 个字符。" }
        require(clean.split(Regex("\\s+")).size <= 75) { "搜索词最多 75 个单词。" }
        val safeCount = count.coerceIn(1, 10)
        val boundedDomain = domain?.trim()?.takeIf { it.isNotBlank() }?.also { require(it.matches(Regex("[A-Za-z0-9.-]{1,120}"))) { "域名格式无效。" } }
        val finalQuery = if (boundedDomain == null) clean else "$clean site:$boundedDomain"
        val endpoint = "https://www.bing.com/search?format=rss&count=$safeCount&q=" + Uri.encode(finalQuery)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"; connection.connectTimeout = 8_000; connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml;q=0.9")
        connection.setRequestProperty("User-Agent", "Goon/1.0 (local agent search)")
        val code = connection.responseCode
        require(code in 200..299) { "搜索服务返回 HTTP $code。" }
        val results = connection.inputStream.use { stream -> parse(stream).take(safeCount) }
        val usable = results.filterNot(::isLowValue).ifEmpty { results }
        if (boundedDomain == null) return usable
        // cn.bing 的 RSS 会忽略 site:，原样返回全网结果（实测 8 个域名全部如此）。
        // 若不在本地过滤，模型会以为「已限定站点」，把无关来源当成该平台的资料——
        // 这种静默失效比查不到更危险，所以宁可在站点确实没收录时返回空。
        return usable.filter { matchesDomain(it.url, boundedDomain) }
    }

    /** 域名匹配按后缀对齐「.」边界，避免 notbilibili.com 被 bilibili.com 命中。 */
    private fun matchesDomain(url: String, domain: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        val target = domain.lowercase()
        return host == target || host.endsWith(".$target")
    }

    private fun parse(stream: java.io.InputStream): List<SearchResult> {
        val parser = Xml.newPullParser().apply { setInput(stream, StandardCharsets.UTF_8.name()) }
        val output = mutableListOf<SearchResult>(); var event = parser.eventType; var tag = ""; var title = ""; var url = ""; var snippet = ""; var published: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> tag = parser.name
                XmlPullParser.TEXT -> when (tag) { "title" -> title = parser.text.trim(); "link" -> url = parser.text.trim(); "description" -> snippet = parser.text.trim(); "pubDate" -> published = parser.text.trim() }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    if (title.isNotBlank() && url.startsWith("https://")) output += SearchResult(title.take(240), url.take(2000), snippet.take(600), published?.take(80))
                    title = ""; url = ""; snippet = ""; published = null
                }
            }
            event = parser.next()
        }
        return output
    }
}
