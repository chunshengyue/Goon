package com.example.goon.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP（Model Context Protocol）客户端。
 *
 * 与项目里已有的 [McpToolRegistry] 的区别：那个只是"长得像 MCP"的本地注册表（描述符 + 处理函数都在进程内），
 * 用来固定工具接口的形状；这里是真的**跨进程协议客户端**——按 JSON-RPC 2.0 说话，工具由远端服务声明。
 *
 * 为什么只做 HTTP 传输（不做 stdio）：Android 没有可靠的子进程模型，stdio 型 MCP server 落不了地；
 * 而 Streamable HTTP / 旧版 SSE 都是主流形态（GitHub、Sentry、Notion 等官方 server 都提供）。
 *
 * 协议要点（照 2025-06-18 规范实现）：
 * - 请求 `Accept: application/json, text/event-stream`；响应**可能是 JSON，也可能是 SSE 帧**，两种都要解。
 * - `initialize` 之后服务端可能回 `Mcp-Session-Id` 响应头，后续每个请求都要带上。
 * - 再发一条 `notifications/initialized` 通知（没有 id，不需要响应）才算握手完成。
 * - 工具结果在 `result.content[]` 里，是**内容块数组**而不是字符串；文本块取 text，其它类型只回类型名。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transport: String = "http",
    val authHeader: String? = null,
    val enabled: Boolean = true
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("transport", transport).put("authHeader", authHeader ?: "").put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject) = McpServerConfig(
            id = json.getString("id"), name = json.optString("name", json.getString("id")),
            url = json.getString("url"), transport = json.optString("transport", "http"),
            authHeader = json.optString("authHeader").takeIf { it.isNotBlank() },
            enabled = json.optBoolean("enabled", true)
        )
    }
}

class McpException(message: String) : RuntimeException(message)

class McpClient(private val config: McpServerConfig) {
    private val ids = AtomicInteger(0)
    private var sessionId: String? = null

    /** 握手：initialize + notifications/initialized。返回服务端信息用于展示（名字/版本/协议版本）。 */
    fun initialize(): JSONObject {
        val result = request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("clientInfo", JSONObject().put("name", "goon").put("version", "1.0"))
        ).getJSONObject("result")
        notify("notifications/initialized", JSONObject())
        return result
    }

    fun listTools(): List<McpToolDescriptor> {
        val result = request("tools/list", JSONObject()).optJSONObject("result") ?: return emptyList()
        val tools = result.optJSONArray("tools") ?: return emptyList()
        return (0 until tools.length()).mapNotNull { index ->
            val tool = tools.optJSONObject(index) ?: return@mapNotNull null
            val name = tool.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpToolDescriptor(name, tool.optString("description"), tool.optJSONObject("inputSchema") ?: JSONObject())
        }
    }

    /** 调用远端工具，把内容块数组压成一段文本。 */
    fun callTool(name: String, arguments: JSONObject): McpToolResult {
        val response = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        if (response.has("error")) {
            return McpToolResult(JSONObject().put("error", response.getJSONObject("error").optString("message", "MCP 调用失败")), true)
        }
        val result = response.optJSONObject("result") ?: JSONObject()
        val text = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> text.append(block.optString("text"))
                    "image", "audio" -> text.append("[${block.optString("type")} 内容，${block.optString("mimeType")}，未回传]")
                    "resource" -> text.append("[resource ${block.optJSONObject("resource")?.optString("uri").orEmpty()}]")
                    else -> text.append("[${block.optString("type")} 内容]")
                }
                if (index < content.length() - 1) text.append('\n')
            }
        }
        if (text.isEmpty()) {
            val structured = result.optJSONObject("structuredContent")
            if (structured != null) text.append(structured.toString())
        }
        return McpToolResult(
            JSONObject().put("server", config.id).put("tool", name).put("text", text.toString().take(MAX_RESULT_CHARS)),
            result.optBoolean("isError", false)
        )
    }

    private fun notify(method: String, params: JSONObject) {
        // 通知不需要响应，失败也不该让整次调用失败（有的服务端对未知方法不实现）。
        runCatching { send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)) }
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = ids.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val response = send(body)
        if (response.optInt("id", -1) != id) throw McpException("MCP 响应 id 与请求不匹配（可能是不兼容的 server）。")
        return response
    }

    private fun send(body: JSONObject): JSONObject {
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            config.authHeader?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", it) }
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val status = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            if (status >= 400) {
                val detail = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText().take(300)
                }.orEmpty()
                throw McpException("MCP 服务端返回 HTTP $status。${detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}")
            }
            val payload = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            when {
                contentType.contains("text/event-stream") -> parseSse(payload)
                else -> JSONObject(payload)
            }
        } catch (error: McpException) {
            throw error
        } catch (error: Exception) {
            throw McpException("MCP 请求失败：${error.message.orEmpty().take(160)}")
        } finally {
            connection.disconnect()
        }
    }

    /** SSE 帧：`event: message` + `data: {json}`。取最后一条带 result/error 的 JSON。 */
    private fun parseSse(payload: String): JSONObject {
        var found: JSONObject? = null
        payload.lineSequence().filter { it.startsWith("data:") }.forEach { line ->
            val data = line.removePrefix("data:").trim()
            if (!data.startsWith("{")) return@forEach
            runCatching { JSONObject(data) }.getOrNull()?.let { if (it.has("result") || it.has("error") || it.has("id")) found = it }
        }
        return found ?: throw McpException("MCP 的 SSE 响应里没有可解析的 JSON-RPC 消息。")
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_RESULT_CHARS = 8000
    }
}

/**
 * MCP 服务配置与已发现工具的缓存。
 *
 * 工具**只在被调用时才调用远端**：`tools()` 返回的是最近一次 `tools/list` 的结果。
 * 配置落盘在 `files/mcp_servers.json`，与 skill、素材分开存——外部服务的地址和 token 不该混进项目文件。
 */
object McpServers {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "mcp_servers.json")

    fun load(context: Context): List<McpServerConfig> = runCatching {
        val raw = file(context).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(McpServerConfig::fromJson) }
    }.getOrDefault(emptyList())

    fun save(context: Context, servers: List<McpServerConfig>) {
        val array = JSONArray().apply { servers.forEach { put(it.toJson()) } }
        file(context).apply { parentFile?.mkdirs() }.writeText(array.toString(), Charsets.UTF_8)
        cache.clear()
    }

    fun add(context: Context, config: McpServerConfig): List<McpServerConfig> {
        val servers = load(context).filterNot { it.id == config.id } + config
        save(context, servers)
        return servers
    }

    fun remove(context: Context, id: String): List<McpServerConfig> {
        val servers = load(context).filterNot { it.id == id }
        save(context, servers)
        return servers
    }

    private val cache = mutableMapOf<String, List<McpToolDescriptor>>()

    /** 已发现的工具（带缓存）。刷新失败不清空旧值：外部服务偶发不可用不该让工具凭空消失。 */
    fun tools(context: Context, refresh: Boolean = false): List<Pair<McpServerConfig, List<McpToolDescriptor>>> {
        return load(context).filter { it.enabled }.map { server ->
            val tools = if (!refresh && cache.containsKey(server.id)) cache.getValue(server.id)
            else runCatching { McpClient(server).run { initialize(); listTools() } }
                .onSuccess { cache[server.id] = it }
                .getOrElse { cache[server.id] ?: emptyList() }
            server to tools
        }
    }

    fun call(context: Context, serverId: String, tool: String, arguments: JSONObject): McpToolResult {
        val server = load(context).firstOrNull { it.id == serverId } ?: return McpToolResult(JSONObject().put("error", "MCP 服务不存在：$serverId"), true)
        return runCatching { McpClient(server).run { initialize(); callTool(tool, arguments) } }
            .getOrElse { McpToolResult(JSONObject().put("error", it.message.orEmpty().take(200)), true) }
    }
}
