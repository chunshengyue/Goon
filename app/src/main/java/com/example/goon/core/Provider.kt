package com.example.goon.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 单次输出触顶（`finish_reason == length`）。
 *
 * 为什么单独建类型：它和"真的失败"不是一回事——这一轮没有产生任何修改，把写入拆小重来即可。
 * 调用方据此把它当**可恢复错误**处理，而不是终止整轮任务（实测 4 次评测失败里有 2 次是它，
 * 代价是已经写好的文件被一起丢弃）。
 */
class TruncatedModelOutput(message: String) : IllegalStateException(message)

data class ProviderSettings(val baseUrl: String = "https://api.openai.com/v1", val model: String = "gpt-4o-mini", val apiKey: String = "") {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

data class ModelResponse(
    val content: String, val model: String, val durationMs: Long, val httpStatus: Int,
    val promptTokens: Int?, val completionTokens: Int?,
    /** prompt_tokens 里命中前缀缓存的量与推理 token；缓存部分单价更低，必须分开统计。 */
    val cachedTokens: Int? = null, val reasoningTokens: Int? = null,
    val toolCalls: JSONArray = JSONArray()
)

class SecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("goon_secrets", Context.MODE_PRIVATE)
    private val alias = "goon_provider_key"

    fun readSettings(): ProviderSettings = ProviderSettings(
        baseUrl = preferences.getString("base_url", ProviderSettings().baseUrl) ?: ProviderSettings().baseUrl,
        model = preferences.getString("model", ProviderSettings().model) ?: ProviderSettings().model,
        apiKey = decrypt(preferences.getString("api_key", null))
    )

    fun saveSettings(settings: ProviderSettings) {
        preferences.edit().putString("base_url", settings.baseUrl.trim()).putString("model", settings.model.trim()).putString("api_key", encrypt(settings.apiKey.trim())).apply()
    }

    fun clearKey() { preferences.edit().remove("api_key").apply() }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP); val iv = bytes.copyOfRange(0, 12); val payload = bytes.copyOfRange(12, bytes.size)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }.doFinal(payload).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
    }
}

interface ModelProvider {
    val modelName: String
    fun turn(messages: JSONArray, tools: JSONArray, system: String, onDelta: (String) -> Unit = {}, callback: (Result<ModelResponse>) -> Unit) { callback(Result.failure(IllegalStateException("Provider 不支持工具循环。"))) }
    fun cancel() {}
    fun complete(prompt: String, project: ApplicationSpec?, createNew: Boolean, imageUri: Uri? = null, onDelta: (String) -> Unit = {}, callback: (Result<ModelResponse>) -> Unit)
}

class OpenAiCompatibleProvider(private val settings: ProviderSettings, private val context: Context, private val executor: ExecutorService = Executors.newSingleThreadExecutor()) : ModelProvider {
    override val modelName: String get() = settings.model

    @Volatile private var connectionInFlight: HttpURLConnection? = null
    @Volatile private var cancelled = false
    override fun cancel() { cancelled = true; connectionInFlight?.disconnect() }
    override fun turn(messages: JSONArray, tools: JSONArray, system: String, onDelta: (String) -> Unit, callback: (Result<ModelResponse>) -> Unit) {
        request("", null, false, null, onDelta, callback, messages, tools, system)
    }
    override fun complete(prompt: String, project: ApplicationSpec?, createNew: Boolean, imageUri: Uri?, onDelta: (String) -> Unit, callback: (Result<ModelResponse>) -> Unit) {
        request(prompt, project, createNew, imageUri, onDelta, callback)
    }
    private fun request(prompt: String, project: ApplicationSpec?, createNew: Boolean, imageUri: Uri?, onDelta: (String) -> Unit, callback: (Result<ModelResponse>) -> Unit, conversation: JSONArray? = null, toolDefinitions: JSONArray? = null, system: String = SYSTEM_PROMPT) {
        executor.execute {
            // 兼容网关会返回 503/429 等瞬时错误，一次抖动不应让整个 Agent 任务失败。
            val rawResult = attemptWithRetry(TRANSIENT_ATTEMPTS) {
                val startedAt = System.currentTimeMillis()
                check(!cancelled) { "请求已取消。" }
                require(settings.isConfigured) { "请先在设置中填写 Base URL、模型名和 API Key。" }
                val endpoint = settings.baseUrl.trimEnd('/').let {
                    when {
                        it.endsWith("/chat/completions") -> it
                        it.endsWith("/v1") -> "$it/chat/completions"
                        else -> "$it/v1/chat/completions"
                    }
                }
                val entryContext = when {
                    createNew -> "用户已明确选择创建新小程序"
                    project != null -> "用户已明确选择修改小程序：${project.name}\n当前规格：${project.toJson()}"
                    else -> "普通对话，本轮不关联任何小程序。不得创建或修改小程序"
                }
                val userContent: Any = imageUri?.let { uri ->
                    val image = prepareImageInput(context, uri)
                    JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", "入口上下文：$entryContext\n用户请求：$prompt"))
                        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}").put("detail", "low")))
                    }
                } ?: "入口上下文：$entryContext\n用户请求：$prompt"
                val request = JSONObject().apply {
                    put("model", settings.model)
                    put("temperature", 0.2)
                    put("max_tokens", if (conversation == null) 2400 else TOOL_LOOP_MAX_TOKENS)
                    put("stream", true)
                    if (toolDefinitions != null) put("tools", toolDefinitions)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        if (conversation == null) put(JSONObject().put("role", "user").put("content", userContent))
                        else for (index in 0 until conversation.length()) put(conversation.getJSONObject(index))
                    })
                }
                val requestBytes = request.toString().toByteArray(StandardCharsets.UTF_8)
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connectionInFlight = connection
                connection.requestMethod = "POST"; connection.connectTimeout = 20_000; connection.readTimeout = when {
                    conversation != null -> TOOL_LOOP_READ_TIMEOUT_MS
                    imageUri != null -> 90_000
                    else -> 45_000
                }; connection.doOutput = true
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "text/event-stream, application/json")
                connection.outputStream.use { it.write(requestBytes) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                var rawBody = ""
                var usage: JSONObject? = null
                val calls = sortedMapOf<Int, JSONObject>()
                var finishReason = ""
                val body = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    val content = StringBuilder()
                    val raw = StringBuilder()
                    // 推理内容不进入交付，但兼容网关会为每个 token 单独发一帧，字节数远高于正文。
                    // 因此：交付内容按 2 MB 计量；raw 只服务于「网关忽略 stream=true」的回退与错误信息，
                    // 一旦确认是流式就不再累积，否则单轮 SSE 可轻易超过任何固定上限并误杀正常任务。
                    var kept = 0
                    var streaming = false
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        check(!cancelled) { "请求已取消。" }
                        if (!streaming && raw.length < NON_STREAM_FALLBACK_CHARS) raw.append(line).append('\n')
                        val payload = line!!.removePrefix("data:").trim()
                        if (payload.isBlank()) continue
                        if (payload == "[DONE]") break
                        val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                        usage = chunk.optJSONObject("usage") ?: usage
                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                        if (choice != null && !choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason")
                        val fragmentCalls = choice?.optJSONObject("delta")?.optJSONArray("tool_calls")
                            ?: choice?.optJSONObject("message")?.optJSONArray("tool_calls")
                        if (fragmentCalls != null) for (index in 0 until fragmentCalls.length()) {
                            val fragment = fragmentCalls.getJSONObject(index)
                            val key = fragment.optInt("index", index)
                            val target = calls.getOrPut(key) { JSONObject().put("id", "").put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")) }
                            if (fragment.has("id")) target.put("id", fragment.getString("id"))
                            fragment.optJSONObject("function")?.let { fn ->
                                val merged = target.getJSONObject("function")
                                for (field in listOf("name", "arguments")) if (fn.has(field)) { merged.put(field, merged.optString(field) + fn.getString(field)); kept += fn.getString(field).length }
                            }
                        }
                        val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                        val complete = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                        if (delta.isNotEmpty() || complete.isNotEmpty() || fragmentCalls != null) streaming = true
                        if (delta.isNotEmpty()) { content.append(delta); onDelta(delta) }
                        if (complete.isNotEmpty()) { content.append(complete); onDelta(complete) }
                        kept += delta.length + complete.length
                        require(kept <= 2_000_000) { "模型输出超过大小限制。" }
                        if (complete.isNotEmpty()) break
                        if (choice?.has("message") == true && calls.isNotEmpty()) break
                    }
                    rawBody = raw.toString().trim()
                    if (content.isNotBlank()) content.toString() else {
                        // Some compatible gateways ignore stream=true and return one JSON response.
                        val fallback = runCatching { JSONObject(raw.toString().trim()) }.getOrNull()
                        fallback?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    }
                }
                if (code !in 200..299) {
                    val message = "模型服务返回 HTTP $code：${sanitizeError(rawBody)}"
                    if (code == 429 || code >= 500) throw TransientModelError(message) else error(message)
                }
                // 单次输出触顶：调用方要能把它和"真的失败"区分开——它可以通过"把写入拆小"重试，
                // 不该直接终止整轮任务（实测 4 次失败里有 2 次是它，代价是已写好的文件被一起丢弃）。
                if (finishReason == "length") throw TruncatedModelOutput("模型输出被截断，请缩小单次文件修改。")
                val content = body
                // 空响应按**可重试**处理：网关偶发丢帧时会整段返回空，重试一次通常就好了。
                // 旧行为是直接抛错终止整轮——实测它让一条本可以做完的用例在 51.7 秒处整轮失败、
                // 已写好的文件一起丢掉（同"输出被截断"属于同一类：抖动不该当致命错误）。
                if (content.isBlank() && calls.isEmpty()) {
                    throw TransientModelError("模型返回为空（可能是网关丢帧），请重试。")
                }
                ModelResponse(
                    content, settings.model, System.currentTimeMillis() - startedAt, code,
                    usage?.optInt("prompt_tokens")?.takeIf { it > 0 }, usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                    cachedTokens = usage?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")?.takeIf { it > 0 },
                    reasoningTokens = usage?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")?.takeIf { it > 0 },
                    toolCalls = JSONArray(calls.values.toList())
                )
            }
            val result: Result<ModelResponse> = rawResult.exceptionOrNull()?.let { error ->
                Result.failure(when {
                    error !is java.net.SocketTimeoutException -> error
                    imageUri != null -> IllegalStateException("视觉请求超时（90 秒）。当前服务可能排队较久或未启用图片模型，请确认模型支持图片输入。", error)
                    conversation != null -> IllegalStateException("模型思考超时（${TOOL_LOOP_READ_TIMEOUT_MS / 1000} 秒无响应）。可重试，或改用响应更快的模型。", error)
                    else -> error
                })
            } ?: Result.success(rawResult.getOrThrow())
            connectionInFlight?.disconnect(); connectionInFlight = null
            callback(result)
        }
    }

    fun shutdown() { cancel(); executor.shutdownNow() }

    private fun sanitizeError(body: String): String = body.replace(Regex("(?i)(api[_-]?key|authorization|token)[^,}]*"), "credential=redacted").take(240)

    companion object {
        private const val TRANSIENT_ATTEMPTS = 3
        private const val TRANSIENT_BACKOFF_MS = 2_000L
        // 推理模型把思考 token 也计入 max_tokens，工具循环需要更大的额度，否则整轮输出会被截断。
        private const val TOOL_LOOP_MAX_TOKENS = 32_000
        // 推理模型单轮可能思考上万 token，流式虽然会不断推分片，但首字节前的静默期很长；
        // 45 秒的普通对话超时会误杀工具循环，这里单独放宽。
        private const val TOOL_LOOP_READ_TIMEOUT_MS = 180_000
        // 只有「网关忽略 stream=true」时才会用到完整 body，此时不会有任何流式分片。
        private const val NON_STREAM_FALLBACK_CHARS = 2_000_000

        private class TransientModelError(message: String) : IllegalStateException(message)


        private fun Throwable.isTransient(): Boolean = when (this) {
            is TransientModelError -> true
            is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> true
            else -> this is java.io.IOException
        }

        /** 只重试瞬时故障；取消、配置错误与协议错误立即返回，避免掩盖真实问题。 */
        private fun <T> attemptWithRetry(attempts: Int, block: () -> T): Result<T> {
            var last: Result<T> = Result.failure(IllegalStateException("未执行请求。"))
            for (attempt in 1..attempts) {
                last = runCatching(block)
                val error = last.exceptionOrNull() ?: return last
                if (!error.isTransient() || attempt == attempts) return last
                runCatching { Thread.sleep(TRANSIENT_BACKOFF_MS * attempt) }
            }
            return last
        }

        private const val SYSTEM_PROMPT = """
你是 Goon，一个手机端通用 Agent。你首先是日常对话、思考、解释和协作助手；只有用户明确要创建、修改、运行或检查本地小程序时，才进入应用开发工作流。你不能执行代码、shell、任意网络请求或访问文件。用户本轮附图时，可以将其作为需求参考，但不得声称保存、上传或访问原始图片。
只返回一个 JSON 对象，不要 Markdown：{"plan":["仅执行任务时给出，最多 6 条中文步骤"],"message":"面向用户的中文回复","mode":"chat|create|replace|none","dsl":"小程序 DSL 或空字符串","spec":{兼容旧协议时的完整规格或 null}}。创建或修改小程序优先返回 dsl，不要同时返回 spec。
日常交流、分析、建议或不需要改变小程序的请求必须用 mode=chat、spec=null，并自然回答用户。此模式不应给出机械进度、工具或计划。message 支持 Markdown：请在内容较长时使用清晰的标题（##）、项目符号或编号列表、加粗重点、行内代码和必要的代码块；段落之间保留空行，避免把所有内容挤成一段。
只有入口上下文明确允许创建时才用 create；只有入口上下文提供了指定小程序及当前规格时才用 replace。普通对话即使提到小程序，也不得创建或修改项目。只有 create/replace 时才给出 plan，plan 应说明检查、修改与验证；message 要说明实际完成内容、受限能力或无法完成的原因，不能只说“已完成”。
规格 schemaVersion 必须为 4。当前 DSL 使用简单行语法：第一行 `app "名称" {`，后续每行可写 `title "标题"`、`text "文本"`、`input "提示" bind 状态名`、`button "按钮" action add_item bind 状态名`，最后用 `}` 结束。只有宿主支持的动作可以使用；编译错误会在下一轮反馈给你，必须修复后再返回。字段：projectId（3-48 位字母数字_-）、name、title、description、accent（blue|green|coral|violet）、theme（light|dark）、initialState（字符串键值）、pages（1-6 页）。
每个 page：id、title、可选 tabLabel、components。components 是可嵌套节点树，每层最多 32 个节点。基础布局组件为 column、row、grid、stack、section；只有这些容器可使用 children。grid 使用 style.columns（1-6）。
内容和交互组件：hero、text、image、icon、tag、spacer、notice、divider、input、search、button、switch、stat、progress、list、selector、card_list、location_board、board、direction_pad、timer。image 和 item.image 目前只支持 emoji: 前缀的本地视觉占位，不得声称使用了网络图片或真实素材。
每个组件字段：id、type，以及可选 label、text、binding、action、placeholder、targetPage、value、image、icon、showWhen、intervalMs、options、items、children、style。style 可使用 padding（0-48）、gap（0-32）、radius（0-32）、columns（1-6）、weight（0.1-6）、align（start|center|end）、background/foreground（background|surface|raised|muted|accent|success|danger 或十六进制颜色）、textStyle（caption|body|title|display）、minHeight（0-480）。showWhen 支持 key=true 或 key=value；不写时显示。
item 字段：id、title、可选 subtitle、meta、badge、tone（urgent|warn|done|accent|success|danger|muted）、image。board 使用 items 和 style.columns 描述棋盘或状态网格；direction_pad 将 up/left/right/down/pause 写入 binding；timer 仅在 showWhen 成立时按 intervalMs（100-10000）递增绑定的整数状态。它们是基础互动能力，不得声称实现任意游戏逻辑或后台持续运行。
动作仅限 add_item、increment、decrement、reset、clear_list、navigate、set_value、toggle。input/search/stat/selector/switch/direction_pad/timer 的 binding 必须存在于 initialState；button 必须有 action；navigate 必须使用已有 targetPage；set_value 必须提供 binding 和 value。list 默认提供本地条目的完成勾选和单项删除；card_list、location_board、board 必须提供 items。
创建主流产品时优先组合布局、图标、视觉占位、搜索、选择器、列表、表单、开关和本地状态，构建离线核心流程。不得复制品牌名称、品牌标志或受保护素材，也不得声称实现真实支付、订单履约、账号、地图、定位、相机、网络、文件上传或多人同步。create 模式必须创建完整、可用且项目 ID 唯一的新小程序；replace 模式保持 projectId 不变。无法满足时 mode=none、spec=null，并在 message 解释原因。
"""
    }
}
