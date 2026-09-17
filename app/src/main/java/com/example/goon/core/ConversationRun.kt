package com.example.goon.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话路径：带工具的模型循环。
 *
 * 搜索是**模型自己调用的工具**，不是宿主的前置步骤。宿主用正则拼查询词只能剥掉语气词，
 * 不可能知道「海克斯大乱斗」是专有名词、该补「胜率」这类检索词；实测把整句口语原样发给
 * 搜索引擎会退化成只匹配里面最泛的词（详见 `docs/problems_and_solutions.md` 第 20 条）。
 *
 * 因此这里不再按「用户有没有说搜索」分叉：工具一直可用，由模型决定搜不搜、搜几次。
 * 宿主只负责两件事——封顶（轮次与搜索次数）和记录 trace。
 */
class ConversationRun(
    private val workspace: Workspace,
    private val provider: ModelProvider,
    private val runId: String,
    private val onDelta: (String) -> Unit,
    private val callback: (AgentDisplayState) -> Unit
) {
    companion object {
        /**
         * 进程内活动运行登记表。
         *
         * 为什么需要：运行记录写在数据库里，进程被杀或启动即失败时会留下一条永远 `running` 的行，
         * 之后所有新运行都会被判成"会话被占用"。光靠 `started_at` 判断不够——正常的长任务也会很久，
         * 而僵尸可能才刚产生。**"进程里没有对应的活动对象"才是僵尸的准确判据**。
         */
        private val active = java.util.concurrent.ConcurrentHashMap<String, ConversationRun>()
        fun isActive(conversationId: String): Boolean = active.containsKey(conversationId)
        // 搜索由模型发起，但轮次必须由宿主封顶，否则「一直搜下去」会失控。
        private const val MAX_MODEL_TURNS = 4
        private const val MAX_SEARCH_CALLS = 4
        private const val MAX_PAGE_READS = 3
        /** 一轮对话最多挂多少个 MCP 工具。挂太多会让 schema 占满上下文，也降低模型选对工具的概率。 */
        private const val MAX_MCP_TOOLS = 24
        private const val MAX_MCP_CALLS = 6
        private const val HISTORY_MESSAGES = 10
        private val TOOLS = JSONArray().apply { put(WebSearch.toolSchema()); put(WebPageReader.toolSchema()) }
        private val SKILL_HINT = Regex("(?i)skill|技能|能力包|插件|前端设计|视觉设计|动效")

        private fun system(skillCatalog: String?, mcpToolCount: Int): String = buildString {
            append("你是 Goon，一个手机端通用 Agent。你首先是日常对话、思考、解释和协作助手，只有在用户明确要创建、修改、运行或检查本地小程序时才进入应用开发工作流。你不能执行代码、shell 或访问文件，也不能打开任意 URL；能联网的方式只有 search_web 这一个工具。\n")
            append("用中文回答，使用 Markdown：内容较长时用二级标题、列表或编号，重点可加粗，必要时用行内代码和代码块；段落之间留空行。直接给出回答，不要输出 JSON，也不要写机械的过程说明。\n")
            append("什么时候搜索由你判断：凡是涉及当前、外部或你不确定的事实——新闻、版本号、价格、榜单、政策、实时数据、具体网址与出处——先调用 search_web 查到来源再回答，不要凭记忆下结论。用户没有说「搜索」同样可以搜。反过来，闲聊、写作、翻译、推理和解释原理的问题不要搜，直接回答。\n")
            append("查询词要由你自己组织：用名词、专有名词，以及你判断原始资料里会出现的词（例如「胜率」「榜单」「版本公告」）来组合，不要把整句口语原样发出去。一次结果不相关或为空就换关键词重搜，本轮回合最多 $MAX_SEARCH_CALLS 次；用完就基于已有来源作答。\n")
            append("搜索结果只是线索而不是结论：摘要可能过时或不准确，引用时保留可点击的来源 URL，并在来源之间矛盾时说明分歧。确实查不到就直说查不到，给出可行的替代路径（比如建议看哪类站点、需要什么信息），不要用记忆里的印象冒充查到的结果。\n")
            append("不要向用户暴露宿主的技术约束（例如「离线可用」「数据只保存在本机」「无网络请求」这类说明），除非用户明确在问实现细节。\n")
            if (mcpToolCount > 0) {
                append("**外部 MCP 工具**：本轮还挂了 $mcpToolCount 个来自用户自己配置的 MCP 服务。")
                append("它们的名字形如 mcp__<服务>__<工具>，能力由远端服务定义（可能是查知识库、读日历、调内部系统等）。")
                append("只在确实需要那份外部数据或动作时调用，参数按 schema 给；调用失败或超时直接说明「这个 MCP 服务当前不可用」，不要改用记忆里的答案冒充它的结果，也不要反复重试同一个工具。\n")
                append("**首次调用某个外部工具需要用户确认**：这时工具会返回 needsApproval，请求已经发给用户了。")
                append("不要重复调用、不要换个参数或名字绕过，也不要假装已经拿到结果；把不依赖它的部分做完，再用一句话说明你在等确认。\n")
            }
            // 这里必须区分「应用」和「产物」，不能笼统地禁止输出代码：
            // 「做一个外卖软件」要的是小程序（贴 HTML 是假交付），但「把学习资料做成一份 HTML 总结」
            // 要的就是那份 HTML 本身。用一条规则同时管这两件事，必然错一边。
            append("**先判断用户要的是哪一种交付物，三种不能混：**\n")
            append("1. **应用**——有状态、要交互、会被反复使用（外卖、记账、打卡这类）。不要写代码：用户要的是能在手机上用起来的东西，不是一段复制粘贴的文本。用两三句话说明这个小程序可以做成什么样（关键界面与交互），然后引导他用**输入框左侧「+」→「新建小程序」**发起，那里会真正生成并运行它。\n")
            append("2. **产物**——一份内容为主、需要被保存或分享的材料（把资料整理成总结、写成文档、做成一份可打开的页面）。**这种就是直接产出内容**，不要往「新建小程序」上引：那是应用，不是文档。内容较长时用完整结构写清楚，不要因为篇幅就草草了事。\n")
            append("3. **短片段**——用户明确问某段写法（一段 CSS、一个函数、一条正则或命令）。直接给，控制在十几行以内，不要顺手把整个应用铺出来。\n")
            if (skillCatalog != null) append("\n内置 skill 目录（skill 只是提示资源，不增加任何运行权限）：\n").append(skillCatalog).append("\n如果用户询问某个 skill 能否使用，必须按上面的平台说明直接回答能否落地、哪部分只能参考，不要笼统说「已加载」。\n")
        }

        /**
         * 把 MCP 工具名压成合法的 function 名。
         * 模型 API 对函数名有字符集限制，而 MCP 工具名可以很自由（`github.search_issues` 这种），
         * 所以统一改写成 `mcp__<服务id>__<工具名>` 并保留一张反查表。
         */
        private fun qualified(serverId: String, tool: String): String =
            ("mcp__" + serverId + "__" + tool).replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }

    private val conversationId = requireNotNull(
        runCatching { workspace.run(runId)?.conversationId }.getOrNull()
    ) { "运行不存在。" }
    private val messages = JSONArray()
    /** 会话内出现过的搜索来源主机，懒加载一次。 */
    private var allowedHosts: Set<String>? = null
    private var searches = 0
    private var reads = 0
    private var mcpCalls = 0
    private var turns = 0
    private var settled = false

    /** 合格名 → (服务 id, 真实工具名)。同时是"这一轮挂了哪些 MCP 工具"的索引。 */
    private val mcpIndex = linkedMapOf<String, Pair<String, String>>()
    private val mcpToolCount: Int get() = mcpIndex.size

    /**
     * 工具表 = 内置工具 + 用户配置的 MCP 工具。
     * 发现失败的服务不会让工具表变空——它的工具只是不出现，而错误留在 `mcpToolIndex` 之外由调用者自己看。
     */
    private fun tools(): JSONArray {
        if (mcpIndex.isNotEmpty()) return JSONArray().apply {
            for (index in 0 until TOOLS.length()) put(TOOLS.get(index))
            mcpIndex.keys.forEach { qualified ->
                (mcpDescriptors[qualified] ?: return@forEach).let { put(it) }
            }
        }
        return TOOLS
    }

    private val mcpDescriptors = mutableMapOf<String, JSONObject>()

    private fun discoverMcp(): Unit {
        val services = runCatching { McpServers.tools(workspace.context()) }.getOrDefault(emptyList())
        for ((server, tools) in services) {
            for (tool in tools) {
                if (mcpIndex.size >= MAX_MCP_TOOLS) return
                val qualified = qualified(server.id, tool.name)
                mcpIndex[qualified] = server.id to tool.name
                mcpDescriptors[qualified] = JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", qualified)
                    .put("description", "[MCP/${server.name}] " + tool.description.take(600))
                    .put("parameters", if (tool.inputSchema.length() == 0) JSONObject().put("type", "object").put("properties", JSONObject()) else tool.inputSchema))
            }
        }
    }
    private var streamed = StringBuilder()

    fun start(prompt: String, imageUri: Uri?) {
        active[conversationId] = this
        // MCP 工具发现放在这一轮开始时做一次：远端可能不可用，失败只表现为"没有这些工具"。
        discoverMcp()
        // 本轮自己的 user_message 事件已经写过，重建历史时要排除，否则当前提问会出现两次。
        val history = workspace.events(conversationId).asReversed()
            .filter { it.runId != runId && (it.kind == "user_message" || it.kind == "agent_message") }
            .takeLast(HISTORY_MESSAGES)
        history.forEach { messages.put(JSONObject().put("role", if (it.kind == "user_message") "user" else "assistant").put("content", it.message)) }
        val content: Any = if (imageUri == null) prompt else runCatching { prepareImageInput(workspace.context(), imageUri) }
            .map { image ->
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}").put("detail", "low")))
            }
            .getOrElse { error ->
                workspace.appendEvent("agent_message", "图片无法读取，本轮只按文字处理：" + (error.message ?: "未知错误"), runId)
                prompt
            }
        messages.put(JSONObject().put("role", "user").put("content", content))
        val mentioned = SKILL_HINT.containsMatchIn(prompt) || SKILL_HINT.containsMatchIn(history.joinToString { it.message })
        step(if (mentioned) BuiltInSkills.catalog else null)
    }

    private fun step(skillCatalog: String?) {
        if (settled) return
        if (turns >= MAX_MODEL_TURNS) {
            finish("本轮工具调用已达上限，我先按现有来源回答。")
            return
        }
        turns++
        streamed = StringBuilder()
        callback(
            if (searches == 0) AgentDisplayState("思考中", "正在理解你的请求", "读取当前对话…")
            else AgentDisplayState("联网搜索", "正在整理来源", "已完成 $searches 次搜索，正在归纳…")
        )
        val startedAt = System.currentTimeMillis()
        provider.turn(messages, tools(), system(skillCatalog, mcpToolCount), onDelta = { delta ->
            if (!settled) {
                streamed.append(delta)
                onDelta(streamed.toString())
            }
        }) { result ->
            result.fold(onSuccess = { response ->
                workspace.recordModelAttempt(runId, response.durationMs, response.promptTokens, response.completionTokens, response.httpStatus, true, response.cachedTokens, response.reasoningTokens)
                val assistant = JSONObject().put("role", "assistant").put("content", response.content.ifBlank { JSONObject.NULL })
                if (response.toolCalls.length() > 0) assistant.put("tool_calls", response.toolCalls)
                messages.put(assistant)
                if (response.toolCalls.length() == 0) finish(response.content)
                else execute(response.toolCalls, 0, skillCatalog)
            }, onFailure = { error ->
                workspace.recordModelAttempt(runId, System.currentTimeMillis() - startedAt, null, null, null, false)
                fail(error.message ?: "请检查网络或 Provider 配置。")
            })
        }
    }

    private fun execute(calls: JSONArray, index: Int, skillCatalog: String?) {
        if (settled) return
        if (index == calls.length()) {
            step(skillCatalog)
            return
        }
        val call = calls.optJSONObject(index)
        if (call == null || call.optString("id").isBlank() || call.optJSONObject("function") == null) {
            fail("模型返回的工具调用格式无效。")
            return
        }
        val function = call.getJSONObject("function")
        val name = function.getString("name")
        callback(AgentDisplayState("联网搜索", "正在查找资料", if (searches == 0) "搜索最新资料…" else "换个关键词再查一次…"))
        val output = runCatching { runTool(name, function.optString("arguments")) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "工具执行失败。") }
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", output.toString()))
        execute(calls, index + 1, skillCatalog)
    }

    /** 失败也要写 trace：否则用户只看到模型说「没查到」，分不清是没有资料还是搜索本身挂了。 */
    private fun runTool(name: String, rawArguments: String): JSONObject {
        if (name.startsWith("mcp__")) return callMcp(name, rawArguments)
        if (name == "fetch_page") return readPage(rawArguments)
        require(name == "search_web") { "不支持的工具：$name。" }
        if (searches >= MAX_SEARCH_CALLS) {
            return JSONObject().put("ok", false).put("error", "本次已经搜索 $MAX_SEARCH_CALLS 次，请直接基于已有来源作答，或说明查不到。")
        }
        val args = runCatching { JSONObject(rawArguments) }.getOrElse { JSONObject() }
        val query = args.optString("query").trim()
        require(query.isNotBlank()) { "search_web 需要 query。" }
        val count = if (args.has("count")) args.optInt("count", 5) else 5
        val domain = args.optString("domain").takeIf { it.isNotBlank() }
        searches++
        val results = runCatching { WebSearch.search(query, count, domain) }.getOrElse { error ->
            val reason = error.message ?: "未知错误"
            workspace.appendEvent("web_search", JSONObject().put("ok", false).put("query", query.take(600)).put("domain", domain ?: JSONObject.NULL).put("error", reason.take(240)).toString(), runId)
            return JSONObject().put("ok", false).put("error", "搜索失败：$reason")
        }
        workspace.appendEvent("web_search", JSONObject()
            .put("ok", true).put("query", query.take(600)).put("domain", domain ?: JSONObject.NULL)
            .put("count", results.size)
            .put("results", JSONArray().apply { results.forEach { put(it.toJson()) } }).toString(), runId)
        return JSONObject().put("ok", true).put("query", query).put("count", results.size)
            .put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
            .also { if (results.isEmpty()) it.put("note", "没有结果。换更短、更具体的关键词重搜，或直接说明查不到；不要用同一个查询词重试。") }
    }

    /**
     * 调用远端 MCP 工具。
     *
     * 独立计数与封顶：MCP 是用户自己的服务，可能很慢或已经下线，不能让它在同一轮里被反复重试
     * 把预算吃光。失败一律转成模型能读懂的文本，同时写 trace——出问题时要能分清"服务不可用"
     * 和"模型没用这个工具"。
     */
    private fun callMcp(qualifiedName: String, rawArguments: String): JSONObject {
        val target = mcpIndex[qualifiedName]
            ?: return JSONObject().put("ok", false).put("error", "没有这个 MCP 工具：$qualifiedName（可能是本轮没有发现到，或服务已停用）。")
        if (mcpCalls >= MAX_MCP_CALLS) {
            return JSONObject().put("ok", false).put("error", "本次对话已经调用 MCP 工具 $MAX_MCP_CALLS 次，请基于已有结果作答。")
        }
        val args = runCatching { JSONObject(rawArguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val (serverId, tool) = target
        val scopeKey = "$serverId::$tool"
        // 闸门：外部工具的第一次调用必须先拿到用户授权。拒绝不是错误而是**给模型的信息**——
        // 它该换个做法或直说做不到，而不是改个名字再试。
        if (!ToolApproval.granted(workspace.context(), scopeKey)) {
            val entry = ToolApproval.request(scopeKey, "$serverId · $tool", JSONObject()
                .put("arguments", args.toString().take(600)).put("runId", runId).put("conversationId", conversationId))
            workspace.appendEvent("mcp_approval_request", entry.toString(), runId)
            return JSONObject().put("ok", false).put("needsApproval", true).put("approvalId", scopeKey)
                .put("error", "调用 $serverId 的 $tool 需要用户确认，请求已发出。")
                .put("note", "在用户确认之前不要重复调用它，也不要换个名字绕过去。先把不依赖它的部分做完，然后用一两句话说明你在等这个确认。")
        }
        ToolApproval.consume(workspace.context(), scopeKey)
        // 「超时不等于未执行」：先登记执行日志，再决定是重放、拦截、还是真的发出去。
        val argsHash = ToolExecutionLog.argsHash(args.toString())
        val entryKey = serverId + "::" + tool
        val start = ToolExecutionLog.begin(workspace.context(), entryKey, argsHash)
        if (start is ToolExecutionLog.Start.Replay) {
            workspace.appendEvent("mcp_call", JSONObject()
                .put("server", serverId).put("tool", tool).put("arguments", args.toString().take(600))
                .put("replayed", true).put("content", start.summary.take(600)).toString(), runId)
            return JSONObject().put("ok", true).put("server", serverId).put("tool", tool).put("text", start.summary)
                .put("replayed", true)
                .put("note", "这次调用在 ${(System.currentTimeMillis() - start.at) / 1000} 秒前已经成功执行过，参数完全相同，因此直接返回上次结果，没有重复执行。")
        }
        val entryId = (start as ToolExecutionLog.Start.Fresh).id
        // 上一次同参数的调用超时过：服务端可能已经执行成功，这时自动重试会造成第二次副作用。
        if (ToolExecutionLog.uncertain(workspace.context(), entryKey, argsHash) != null) {
            ToolExecutionLog.finish(workspace.context(), entryId, ToolExecutionLog.UNKNOWN, "同参数的上一次调用未确认，本次未执行", 0)
            workspace.appendEvent("mcp_call", JSONObject()
                .put("server", serverId).put("tool", tool).put("arguments", args.toString().take(600))
                .put("blocked", "previous_unknown").toString(), runId)
            return JSONObject().put("ok", false).put("needsConfirmation", true)
                .put("error", serverId + " 的 " + tool + " 上一次同样参数的调用超时，无法确认服务端是否已经执行。为避免重复执行，本次没有发出。")
                .put("note", "不要直接重试。先用一两句话向用户说明这次操作可能已经生效；等用户确认后再明确重试。其余不依赖它的部分可以继续做。")
        }
        mcpCalls++
        val startedAt = System.currentTimeMillis()
        val result = runCatching { McpServers.call(workspace.context(), serverId, tool, args) }
            .getOrElse { error -> McpToolResult(JSONObject().put("error", error.message.orEmpty().take(200)), true) }
        val elapsed = System.currentTimeMillis() - startedAt
        val errorText = result.content.optString("error")
        // 连接类错误 ⇒ unknown（可能已执行）；服务端明确报错 ⇒ failed（确实没生效，可重试）。
        val uncertain = Regex("(?i)timeout|timed out|connect|reset|refused|unreachable").containsMatchIn(errorText)
        ToolExecutionLog.finish(
            workspace.context(), entryId,
            when {
                result.isError && uncertain -> ToolExecutionLog.UNKNOWN
                result.isError -> ToolExecutionLog.FAILED
                else -> ToolExecutionLog.SUCCEEDED
            },
            if (result.isError) errorText else result.content.optString("text"),
            elapsed
        )
        workspace.appendEvent("mcp_call", JSONObject()
            .put("server", serverId).put("tool", tool).put("arguments", args.toString().take(600))
            .put("isError", result.isError).put("uncertain", result.isError && uncertain)
            .put("content", result.content.toString().take(1200)).toString(), runId)
        return if (result.isError) {
            JSONObject().put("ok", false).put("error", errorText.take(400))
                .put("note", if (uncertain) "这次调用超时，**无法确认服务端是否已经执行**。不要把超时当成失败直接重试——那可能造成第二次副作用；先向用户说明，等确认后再试。"
                    else "这是外部 MCP 服务返回的失败。不要反复重试同一个调用；可以换个参数试一次，或直接告诉用户这个服务当前不可用。")
        } else {
            JSONObject().put("ok", true).put("server", serverId).put("tool", tool).put("text", result.content.optString("text"))
        }
    }

    /**
     * 读取页面。白名单只认本次会话 `search_web` 返回过的主机——
     * 这样模型不能把这里当成任意出网通道，只能沿着已经浮出来的线索往下读。
     */
    private fun readPage(rawArguments: String): JSONObject {
        if (reads >= MAX_PAGE_READS) {
            return JSONObject().put("ok", false).put("error", "本次已经读取 $MAX_PAGE_READS 个页面，请基于已有内容作答。")
        }
        val args = runCatching { JSONObject(rawArguments) }.getOrElse { JSONObject() }
        val url = args.optString("url").trim()
        require(url.isNotBlank()) { "fetch_page 需要 url。" }
        val host = WebPageReader.hostOf(url)
        require(host.isNotBlank()) { "地址无效。" }
        if (allowedHosts == null) allowedHosts = WebPageReader.allowedHosts(workspace, conversationId)
        require(host in requireNotNull(allowedHosts)) { "只能读取本次搜索返回过的站点。$host 不在本次的搜索结果里，请先搜索该站点。" }
        reads++
        callback(AgentDisplayState("读取页面", "正在打开来源页", host))
        val maxChars = if (args.has("maxChars")) args.optInt("maxChars", WebPageReader.DEFAULT_CHARS) else WebPageReader.DEFAULT_CHARS
        val output = runCatching { WebPageReader.read(url, maxChars) }.getOrElse { error ->
            val reason = error.message ?: "未知错误"
            workspace.appendEvent("web_fetch", JSONObject().put("ok", false).put("url", url.take(600)).put("error", reason.take(240)).toString(), runId)
            return JSONObject().put("ok", false).put("url", url).put("error", "读取失败：$reason")
        }
        workspace.appendEvent("web_fetch", JSONObject()
            .put("ok", true).put("url", url.take(600)).put("title", output.optString("title"))
            .put("chars", output.optInt("chars")).put("truncated", output.optBoolean("truncated")).toString(), runId)
        return output
    }

    private fun finish(content: String?) {
        if (settled) return
        settled = true
        active.remove(conversationId)
        val reply = content?.takeIf { it.isNotBlank() } ?: "我还没有足够信息来回答这个问题。"
        workspace.appendEvent("agent_message", reply, runId)
        workspace.updateRun(runId, "chat_completed", "completed", reply)
        callback(AgentDisplayState("已完成", "已回应", reply))
    }

    private fun fail(reason: String) {
        active.remove(conversationId)
        if (settled) return
        settled = true
        workspace.updateRun(runId, "model_failed", "failed", reason, "model_error")
        workspace.appendEvent("agent_message", "本轮没有完成：$reason", runId)
        callback(AgentDisplayState("失败", "请求未完成", reason))
    }
}
