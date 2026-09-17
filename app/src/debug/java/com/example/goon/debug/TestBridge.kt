package com.example.goon.debug

import android.content.Context
import android.util.AtomicFile
import com.example.goon.core.AgentOrchestrator
import com.example.goon.core.ApplicationSpec
import com.example.goon.core.BuiltInSkills
import com.example.goon.core.GraphNode
import com.example.goon.core.UserIntent
import com.example.goon.core.OpenAiCompatibleProvider
import com.example.goon.core.ProviderSettings
import com.example.goon.core.SecretStore
import com.example.goon.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Debug-only localhost bridge. Reach it from the desktop through `adb forward`. */
class TestBridge private constructor() {
    companion object {
        private val running = AtomicBoolean(false)
        private val workers = Executors.newCachedThreadPool()

        @JvmStatic
        fun start(context: Context) {
            if (!running.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            // 评测可能在任何界面交互之前就打接口，这里也保证社区 skill 已装载（install 是幂等的）。
            com.example.goon.core.BuiltInSkills.install(appContext)
            Thread {
                ServerSocket(8765, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                    while (running.get()) runCatching { server.accept() }.getOrNull()?.let { socket -> workers.execute { socket.use { handle(appContext, it) } } }
                }
            }.apply { isDaemon = true; name = "goon-debug-bridge" }.start()
        }

        private fun handle(context: Context, socket: Socket) {
            socket.soTimeout = 20_000
            val request = readRequest(socket) ?: return
            if (request.method == "GET" && request.path == "/test/health") {
                writeResponse(socket, json(200, JSONObject().put("status", "ok").put("bridge", "debug-localhost").put("webRuntime", 1)))
                return
            }
            val workspace = Workspace(context)
            val response = if (request.path != "/test/health" && request.headers["x-goon-test-token"] != token(context)) {
                json(401, JSONObject().put("error", "unauthorized"))
            } else when {
                request.method == "GET" && request.path == "/test/health" -> json(200, JSONObject().put("status", "ok").put("bridge", "debug-localhost"))
                request.method == "GET" && request.path == "/test/runs" -> {
                    val runs = JSONArray().apply { workspace.runs().forEach { put(it.toJson()) } }
                    json(200, JSONObject().put("runs", runs))
                }
                request.method == "GET" && request.path == "/test/projects" -> {
                    val projects = JSONArray().apply { workspace.projects().forEach { project -> put(JSONObject().put("id", project.id).put("name", project.name).put("description", project.description).put("updatedAt", project.updatedAt)) } }
                    json(200, JSONObject().put("activeProjectId", workspace.activeProjectId() ?: JSONObject.NULL).put("projects", projects))
                }
                request.method == "GET" && request.path == "/test/summary" -> json(200, summary(workspace))
                request.method == "GET" && request.path == "/test/cases" -> json(200, JSONObject().put("cases", EvaluationCases.asJson()))
                request.method == "GET" && request.path == "/test/baseline" -> json(200, baseline(workspace))
                request.method == "GET" && request.path == "/test/skills" -> skills(request)
                request.method == "GET" && request.path == "/test/mcp" -> mcp(context, "{}")
                request.method == "POST" && request.path == "/test/mcp" -> mcp(context, request.body)
                request.method == "POST" && request.path == "/test/context" -> contextProbe(request.body)
                request.method == "GET" && request.path == "/test/approvals" -> approvals(context, "{}")
                request.method == "POST" && request.path == "/test/approvals" -> approvals(context, request.body)
                request.method == "GET" && request.path == "/test/executions" -> executions(context, "{}")
                request.method == "POST" && request.path == "/test/executions" -> executions(context, request.body)
                request.method == "GET" && request.path == "/test/architecture" -> architecture()
                request.method == "GET" && request.path == "/test/intent/cases" -> json(200, JSONObject().put("cases", IntentEvaluationCases.asJson()))
                request.method == "GET" && request.path == "/test/intent/corrections" -> json(200, JSONObject().put("corrections", readIntentCorrections(context)))
                request.method == "POST" && request.path == "/test/intent/evaluate" -> json(200, IntentEvaluationCases.evaluate())
                request.method == "POST" && request.path == "/test/intent/batch" -> classifyIntentBatch(request.body, workspace)
                request.method == "POST" && request.path == "/test/intent/corrections" -> saveIntentCorrection(context, request.body)
                request.method == "POST" && request.path == "/test/intent" -> classifyIntent(request.body, workspace)
                request.method == "GET" && request.path.startsWith("/test/runs/") -> {
                    val artifact = workspace.runArtifact(request.path.removePrefix("/test/runs/"))
                    if (artifact == null) json(404, JSONObject().put("error", "run_not_found")) else Response(200, artifact)
                }
                request.method == "POST" && request.path.startsWith("/test/projects/") && request.path.endsWith("/activate") -> {
                    val projectId = request.path.removePrefix("/test/projects/").removeSuffix("/activate")
                    if (workspace.projects().none { it.id == projectId }) json(404, JSONObject().put("error", "project_not_found"))
                    else { workspace.activateProject(projectId); json(200, JSONObject().put("activeProjectId", projectId)) }
                }
                request.method == "POST" && request.path == "/test/spec" -> saveSpec(workspace, request.body)
                request.method == "POST" && request.path == "/test/dsl" -> compileDsl(workspace, request.body)
                request.method == "POST" && request.path == "/test/web" -> webProject(context, workspace, request.body)
                request.method == "POST" && request.path == "/test/agent-control" -> agentControl(context, workspace, request.body)
                request.method == "POST" && request.path.startsWith("/test/cases/") && request.path.endsWith("/run") -> runCase(context, request.path.removePrefix("/test/cases/").removeSuffix("/run"))
                request.method == "POST" && request.path.startsWith("/test/cases/") && request.path.endsWith("/judge") -> judgeCase(context, request.path.removePrefix("/test/cases/").removeSuffix("/judge"), request.body)
                request.method == "POST" && request.path == "/test/provider" -> saveProvider(context, request.body)
                request.method == "POST" && request.path == "/test/provider-fault" -> providerFault(request.body)
                request.method == "POST" && request.path == "/test/runs" -> startRun(context, request.body)
                else -> json(404, JSONObject().put("error", "not_found"))
            }
            writeResponse(socket, response)
        }

        private fun saveProvider(context: Context, body: String): Response = runCatching {
            val input = JSONObject(body)
            val settings = ProviderSettings(input.optString("baseUrl"), input.optString("model"), input.optString("apiKey"))
            require(settings.isConfigured) { "baseUrl、model 和 apiKey 都不能为空。" }
            SecretStore(context).saveSettings(settings)
            json(200, JSONObject().put("saved", true).put("model", settings.model))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_provider").put("message", it.message ?: "invalid request")) }

        private fun skills(request: Request): Response {
            return skillsInner(request)
        }

        /**
         * MCP 的调试入口：不经过模型就能验证「配置 → 发现工具 → 调用」这条链路。
         * 面试里这也是个考点——外部工具接不进来时，要能把它和模型分离开单独测。
         */
        private fun mcp(context: Context, body: String): Response = runCatching {
            val input = body.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val store = com.example.goon.core.McpServers
            when (input.optString("action", "list")) {
                "add" -> {
                    val config = com.example.goon.core.McpServerConfig(
                        id = input.getString("id"), name = input.optString("name", input.getString("id")),
                        url = input.getString("url"), transport = input.optString("transport", "http"),
                        authHeader = input.optString("authHeader").takeIf { it.isNotBlank() }
                    )
                    store.add(context, config)
                    json(200, JSONObject().put("added", config.id).put("servers", store.load(context).size))
                }
                "remove" -> {
                    store.remove(context, input.getString("id"))
                    json(200, JSONObject().put("removed", input.getString("id")).put("servers", store.load(context).size))
                }
                "call" -> {
                    val args = input.optJSONObject("arguments") ?: JSONObject()
                    val result = store.call(context, input.getString("server"), input.getString("tool"), args)
                    json(200, JSONObject().put("isError", result.isError).put("content", result.content))
                }
                else -> {
                    val refresh = input.optString("action") == "refresh"
                    val discovered = store.tools(context, refresh = refresh)
                    json(200, JSONObject().put("servers", JSONArray().apply {
                        discovered.forEach { (server, tools) ->
                            put(JSONObject().put("id", server.id).put("name", server.name).put("url", server.url)
                                .put("enabled", server.enabled).put("toolCount", tools.size)
                                .put("tools", JSONArray().apply { tools.forEach { tool -> put(JSONObject().put("name", tool.name).put("description", tool.description.take(160))) } }))
                        }
                    }))
                }
            }
        }.getOrElse { json(400, JSONObject().put("error", "invalid_mcp").put("message", it.message ?: "MCP 操作失败")) }

        private fun skillsInner(request: Request): Response {
            return skillsBody(request)
        }

        /**
         * 上下文工程的独立验证入口：喂一段消息历史，返回 token 估算、压缩后的 token、丢掉多少条，
         * 以及**压缩后 tool_calls 与 tool 结果是否仍然配对**。
         *
         * 为什么值得单独做：压缩是唯一会"改历史"的操作，出错方式是远端报一句看不懂的格式错误。
         * 有了这个入口，不发一次真实模型请求就能把不变量测掉。
         */
        private fun contextProbe(body: String): Response = runCatching {
            json(200, contextProbeInner(body))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_context").put("message", it.message ?: "上下文检查失败")) }

        /**
         * 审批的调试入口：列待确认 / 列已授权 / 允许 / 拒绝 / 撤销。
         * 审批是"安全性"功能，必须能被独立验证——否则只能靠人去点界面猜它有没有生效。
         */
        private fun approvals(context: Context, body: String): Response = runCatching {
            return approvalsInner(context, body)
        }.getOrElse { json(400, JSONObject().put("error", "invalid_approval").put("message", it.message ?: "审批操作失败")) }

        /**
         * 执行日志的调试入口：验证「超时不等于未执行」的三条语义。
         *
         * 用法：`{"action":"start","key":"echo::send","args":"{\"text\":\"hi\"}"}` 连续调用两次，
         * 第二次会拿到 replayed；把第一次标成 unknown 之后再调，会被拦成 needsConfirmation。
         */
        private fun executions(context: Context, body: String): Response = runCatching {
            val input = body.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val log = com.example.goon.core.ToolExecutionLog
            when (input.optString("action", "list")) {
                "clear" -> { log.clear(context); json(200, JSONObject().put("cleared", true)) }
                "start" -> {
                    val key = input.getString("key")
                    val hash = log.argsHash(input.optString("args", "{}"))
                    when (val start = log.begin(context, key, hash)) {
                        is com.example.goon.core.ToolExecutionLog.Start.Replay ->
                            json(200, JSONObject().put("replayed", true).put("summary", start.summary).put("at", start.at))
                        is com.example.goon.core.ToolExecutionLog.Start.Fresh -> json(200, JSONObject().put("replayed", false).put("id", start.id)
                            .put("uncertainBefore", log.uncertain(context, key, hash) != null))
                    }
                }
                "finish" -> {
                    log.finish(context, input.getLong("id"), input.getString("status"), input.optString("summary", ""), input.optLong("ms", 0))
                    json(200, JSONObject().put("ok", true))
                }
                else -> json(200, JSONObject().put("executions", log.all(context)))
            }
        }.getOrElse { json(400, JSONObject().put("error", "invalid_execution").put("message", it.message ?: "执行日志操作失败")) }

        private fun approvalsInner(context: Context, body: String): Response = runCatching {
            val input = body.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val store = com.example.goon.core.ToolApproval
            when (input.optString("action", "list")) {
                "allow" -> {
                    val scope = input.optString("scope", com.example.goon.core.ToolApproval.ONCE)
                    val grants = store.grant(context, input.getString("id"), scope, input.optString("label", ""))
                    store.resolve(input.getString("id"))
                    json(200, JSONObject().put("allowed", input.getString("id")).put("scope", scope).put("grants", grants))
                }
                "deny" -> {
                    store.resolve(input.getString("id"))
                    json(200, JSONObject().put("denied", input.getString("id")))
                }
                "revoke" -> {
                    store.revoke(context, input.optString("id").takeIf { it.isNotBlank() })
                    json(200, JSONObject().put("revoked", input.optString("id", "all")).put("grants", store.list(context)))
                }
                else -> json(200, JSONObject().put("pending", store.pending()).put("grants", store.list(context)))
            }
        }.getOrElse { json(400, JSONObject().put("error", "invalid_approval").put("message", it.message ?: "审批操作失败")) }

        private fun contextProbeInner(body: String): JSONObject {
            val input = JSONObject(body)
            val messages = input.optJSONArray("messages") ?: JSONArray()
            val keep = input.optInt("keep", 6).coerceIn(1, 50)
            val notes = input.optString("notes", "【上下文压缩】更早的过程已移除，文件以工作区为准。")
            val before = com.example.goon.core.ContextTokens.of(messages)
            val groups = com.example.goon.core.HistoryCompactor.group(messages)
            val rebuilt = com.example.goon.core.HistoryCompactor.rebuild(groups, keep, notes)
            val after = com.example.goon.core.ContextTokens.of(rebuilt)
            return JSONObject()
                .put("exchanges", groups.size).put("keptExchanges", minOf(keep, groups.size))
                .put("messagesBefore", messages.length()).put("messagesAfter", rebuilt.length())
                .put("droppedMessages", messages.length() - (rebuilt.length() - 1))
                .put("tokensBefore", before).put("tokensAfter", after)
                .put("pairingOkBefore", com.example.goon.core.HistoryCompactor.pairingOk(messages))
                .put("pairingOkAfter", com.example.goon.core.HistoryCompactor.pairingOk(rebuilt))
                .put("textTokensSample", com.example.goon.core.ContextTokens.of(input.optString("sample", "中文一百个字大约是一百个 token，English words are cheaper.")))
        }

        private fun skillsBody(request: Request): Response {
            val id = request.query["id"]
            val skill = id?.let(com.example.goon.core.BuiltInSkills::read)
            // file 参数用来核验随包引入的参考清单能不能真的读到（不加参数时只回 SKILL.md）。
            val file = request.query["file"]
            return if (id != null && skill == null) json(404, JSONObject().put("error", "skill_not_found"))
            else if (skill != null && file != null) {
                val body = com.example.goon.core.BuiltInSkills.readFile(skill.id, file)
                if (body == null) json(404, JSONObject().put("error", "skill_file_not_found"))
                else json(200, JSONObject().put("id", skill.id).put("file", file).put("length", body.length).put("head", body.take(400)))
            } else if (skill != null) json(200, JSONObject().put("skill", JSONObject().put("id", skill.id).put("title", skill.title).put("summary", skill.summary).put("body", skill.body)))
            else json(200, JSONObject().put("skills", com.example.goon.core.BuiltInSkills.listJson()))
        }

        private fun architecture(): Response = json(200, JSONObject().apply {
            put("intentRouter", JSONArray(UserIntent.values().map { it.name }))
            put("intentPipeline", JSONArray(listOf("rule_precheck", "intent_understanding", "policy_gate", "tool_validation", "execute_observe_audit")))
            put("intentUnderstanding", "deterministic_rules; model_candidate_not_enabled")
            put("rag", JSONObject().put("documents", BuiltInSkills.allDocuments().size).put("strategy", "lexical_replaceable_index"))
            put("mcp", JSONObject().put("name", "goon-local").put("version", "1.0").put("resources", JSONArray(listOf("goon://skills", "goon://conversation/context"))).put("prompts", JSONArray(listOf("mini-app-build", "research-with-citations"))))
            put("graphNodes", JSONArray(GraphNode.values().map { it.name }))
            put("search", "host-bounded")
        })

        private fun classifyIntent(body: String, workspace: Workspace): Response = runCatching {
            classifyIntent(JSONObject(body), workspace)
        }.fold(onSuccess = { json(200, it) }, onFailure = { json(400, JSONObject().put("error", "invalid_intent").put("message", it.message ?: "invalid intent request")) })

        private fun classifyIntentBatch(body: String, workspace: Workspace): Response = runCatching {
            val inputs = JSONObject(body).getJSONArray("samples")
            require(inputs.length() in 1..500) { "samples 数量必须为 1-500。" }
            JSONObject().put("results", JSONArray().apply {
                repeat(inputs.length()) { index -> put(classifyIntent(inputs.getJSONObject(index), workspace).put("id", inputs.getJSONObject(index).optString("id", index.toString()))) }
            })
        }.fold(onSuccess = { json(200, it) }, onFailure = { json(400, JSONObject().put("error", "invalid_intent_batch").put("message", it.message ?: "invalid intent batch")) })

        private fun classifyIntent(input: JSONObject, workspace: Workspace): JSONObject {
            val projectId = input.optString("projectId").takeIf { it.isNotBlank() }
            val projectExists = if (input.has("projectExists")) input.optBoolean("projectExists") else projectId != null && workspace.projects().any { it.id == projectId }
            val isWebProject = if (input.has("isWebProject")) input.optBoolean("isWebProject") else workspace.hasWebProject(projectId)
            val createNew = input.optBoolean("createNew")
            val decision = com.example.goon.core.IntentRouter.classify(input.optString("prompt"), createNew, projectId)
            val policy = com.example.goon.core.IntentPolicy.evaluate(decision, createNew, projectId, projectExists, isWebProject)
            val toolGate = com.example.goon.core.IntentToolValidator.validateRoute(policy.route, createNew, policy.safeProjectId, projectExists, isWebProject)
            return JSONObject().put("decision", JSONObject(decision.toJson())).put("policy", JSONObject(policy.toJson())).put("toolValidation", toolGate.toJson())
        }

        @Synchronized
        private fun saveIntentCorrection(context: Context, body: String): Response = runCatching {
            val input = JSONObject(body)
            val expectedIntent = UserIntent.valueOf(input.getString("expectedIntent"))
            val expectedRoute = input.optString("expectedRoute").takeIf { it.isNotBlank() }?.let { com.example.goon.core.IntentRoute.valueOf(it) }
            val text = redactIntentText(input.getString("text").trim()).take(8_000)
            require(text.isNotBlank()) { "text 不能为空。" }
            val correction = JSONObject()
                .put("id", input.optString("id", UUID.randomUUID().toString()).take(120))
                .put("text", text)
                .put("expectedIntent", expectedIntent.name)
                .put("expectedRoute", expectedRoute?.name ?: JSONObject.NULL)
                .put("notes", input.optString("notes").take(500))
                .put("recordedAt", System.currentTimeMillis())
            val current = readIntentCorrections(context)
            val next = JSONArray().apply {
                val first = (current.length() - 498).coerceAtLeast(0)
                for (index in first until current.length()) put(current.getJSONObject(index))
                put(correction)
            }
            val file = AtomicFile(File(context.filesDir, "intent_corrections.json"))
            val stream = file.startWrite()
            try { stream.write(next.toString().toByteArray(StandardCharsets.UTF_8)); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
            json(200, JSONObject().put("saved", true).put("count", next.length()).put("correction", correction))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_intent_correction").put("message", it.message ?: "invalid correction")) }

        private fun readIntentCorrections(context: Context): JSONArray {
            val file = AtomicFile(File(context.filesDir, "intent_corrections.json"))
            return runCatching { file.openRead().use { JSONArray(it.readBytes().toString(StandardCharsets.UTF_8)) } }.getOrDefault(JSONArray())
        }

        private fun redactIntentText(value: String): String = value
            .replace(Regex("(?i)(api[_ -]?key|authorization|token|cookie|secret)\\s*[:=]\\s*[^\\s,;]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/-]+"), "Bearer [REDACTED]")

        private fun saveSpec(workspace: Workspace, body: String): Response = runCatching {
            val spec = ApplicationSpec.fromJson(body)
            workspace.saveSpec(spec, "debug test spec")
            json(200, JSONObject().put("saved", true).put("projectId", spec.projectId).put("schemaVersion", spec.schemaVersion))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_spec").put("message", it.message ?: "invalid spec")) }

        private fun compileDsl(workspace: Workspace, body: String): Response = runCatching {
            val input = JSONObject(body)
            val source = input.optString("source")
            require(source.isNotBlank()) { "source 不能为空。" }
            val result = com.example.goon.core.MiniAppDsl.compile(source, input.optString("projectId").takeIf { it.isNotBlank() } ?: ApplicationSpec.blankProjectId())
            if (result.spec == null) return@runCatching json(400, JSONObject().put("error", "invalid_dsl").put("errors", JSONArray(result.errors)))
            if (input.optBoolean("save")) workspace.saveSpec(result.spec, "debug DSL compile")
            json(200, JSONObject().put("compiled", true).put("saved", input.optBoolean("save")).put("spec", JSONObject(result.spec.toJson())).put("errors", JSONArray()))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_dsl").put("message", it.message ?: "invalid DSL")) }

        private fun agentControl(context: Context, workspace: Workspace, body: String): Response = runCatching {
            val input = JSONObject(body)
            val action = input.getString("action")
            val conversation = workspace.currentConversationId()
            when (action) {
                "cancel" -> com.example.goon.core.WebAgentRun.cancel(conversation)
                "steer" -> require(com.example.goon.core.WebAgentRun.steer(conversation, input.getString("text"))) { "没有运行中的任务。" }
                "follow_up" -> require(com.example.goon.core.WebAgentRun.followUp(conversation, input.getString("text"))) { "没有运行中的任务。" }
                "resume" -> {
                    val settings = SecretStore(context).readSettings()
                    require(settings.isConfigured)
                    val provider = OpenAiCompatibleProvider(settings, context)
                    val id = com.example.goon.core.WebAgentRun.resume(workspace, provider, input.getString("runId"), {}) {
                        if (!it.canCancel) provider.shutdown()
                    }
                    return@runCatching json(202, JSONObject().put("runId", id))
                }
                // 对话路径以前只能从界面点出来，导致"审批/ MCP 这些只在对话里生效的东西"没法自动化验证。
                // 这个动作把它变成可调用的入口：不需要点界面，也不需要跑评测。
                "chat" -> {
                    val settings = SecretStore(context).readSettings()
                    require(settings.isConfigured) { "Provider 未配置。" }
                    val provider = OpenAiCompatibleProvider(settings, context)
                    val id = com.example.goon.core.AgentOrchestrator(workspace, provider).submit(
                        input.getString("text"), onDelta = {}
                    ) { /* 调试入口不关 provider：对话路径的状态回调里 canCancel 一开始就是 false，
                         照搬界面那套"不能取消就关掉"会立刻关掉自己。这里靠进程结束回收。 */ }
                    return@runCatching json(202, JSONObject().put("runId", id))
                }
                else -> error("未知运行操作。")
            }
            json(200, JSONObject().put("ok", true))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_control").put("message", it.message)) }

        private fun webProject(context: Context, workspace: Workspace, body: String): Response = runCatching {
            val input = JSONObject(body)
            val action = input.getString("action")
            val store = workspace.webStore()
            if (action == "get") {
                val project = requireNotNull(store.load(input.getString("projectId"))) { "项目不存在。" }
                return@runCatching json(200, project.toJson().put("revisions", JSONArray().apply { store.revisions(project.manifest.id).forEach { put(JSONObject().put("id", it.id).put("createdAt", it.createdAt)) } }))
            }
            if (action == "restore") return@runCatching json(200, JSONObject().put("revision", store.restore(input.getString("projectId"), input.getString("revision"))))
            val manifest = com.example.goon.core.WebMiniAppManifest.fromJson(input.getJSONObject("manifest").toString())
            val fileJson = input.getJSONObject("files")
            val files = fileJson.keys().asSequence().associateWith { fileJson.getString(it) }
            com.example.goon.core.WebMiniAppStore.validate(manifest, files)
            if (action == "save") {
                val expected = input.optString("expectedRevision")
                val revision = workspace.saveWebProject(manifest, files, "接口验证 Web 项目", expected)
                return@runCatching json(200, JSONObject().put("saved", true).put("revision", revision))
            }
            require(action == "preview") { "未知 Web 操作。" }
            val steps = input.optJSONArray("steps") ?: JSONArray()
            require(steps.length() <= 40)
            val latch = java.util.concurrent.CountDownLatch(1)
            var report = JSONObject().put("passed", false).put("reason", "预览接口超时。")
            val shot = input.optString("shot").takeIf { it.isNotBlank() }
            com.example.goon.core.WebPreview.verify(context, com.example.goon.core.WebProject(manifest, files), steps, shotKey = shot) { report = it; latch.countDown() }
            latch.await(18, java.util.concurrent.TimeUnit.SECONDS)
            json(200, report)
        }.getOrElse { json(400, JSONObject().put("error", "invalid_web_project").put("message", it.message ?: "Web 项目请求失败。")) }

        /** 评测用的模型故障注入开关；只影响 bridge 进程，不改动用户真实 Provider 配置。 */
        private fun providerFault(body: String): Response = runCatching {
            val input = body.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val mode = input.optString("mode").takeIf { it.isNotBlank() }
            DebugProviderFault.mode = mode
            json(200, JSONObject().put("mode", mode ?: JSONObject.NULL))
        }.getOrElse { json(400, JSONObject().put("error", "invalid_fault").put("message", it.message ?: "invalid fault")) }

        private fun startRun(context: Context, body: String): Response = runCatching {
            val input = JSONObject(body)
            val prompt = input.optString("prompt").trim()
            require(prompt.isNotBlank()) { "prompt 不能为空。" }
            startRun(context, prompt, input.optBoolean("createNew"), null, input.optString("projectId").takeIf { it.isNotBlank() })
        }.getOrElse { json(400, JSONObject().put("error", "invalid_run").put("message", it.message ?: "invalid request")) }

        private fun runCase(context: Context, id: String): Response = runCatching {
            val case = requireNotNull(EvaluationCases.find(id)) { "test case not found" }
            require(case.prompt.isNotBlank() || case.allowEmptyPrompt) { "该用例需要由桌面端显式构造空输入测试。" }
            startRun(context, case.prompt, case.createNew, case.id, if (case.createNew) null else Workspace(context).activeProjectId(), case.allowEmptyPrompt)
        }.getOrElse { json(400, JSONObject().put("error", "invalid_case").put("message", it.message ?: "invalid request")) }

        private fun judgeCase(context: Context, id: String, body: String): Response = runCatching {
            val case = requireNotNull(EvaluationCases.find(id)) { "test case not found" }
            val input = body.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val workspace = Workspace(context)
            val runId = input.optString("runId").takeIf { it.isNotBlank() }
                ?: workspace.runs().firstOrNull { it.testCaseId == id }?.id
                ?: error("需要提供 runId，或先运行该用例。")
            EvaluationJudge.judge(workspace, case, runId, input.optJSONObject("baseline"), input.optJSONObject("judgeBaseline"))
        }.fold(onSuccess = { json(200, it) }, onFailure = { json(400, JSONObject().put("error", "invalid_judgment").put("message", it.message ?: "judgment failed")) })

        private fun baseline(workspace: Workspace): JSONObject = JSONObject()
            .put("capturedAt", System.currentTimeMillis())
            .put("activeProjectId", workspace.activeProjectId() ?: JSONObject.NULL)
            .put("projects", JSONObject().apply { workspace.projects().forEach { put(it.id, it.updatedAt) } })
            .put("digests", projectDigests(workspace))

        private fun startRun(context: Context, prompt: String, createNew: Boolean, testCaseId: String?, projectId: String? = null, allowEmptyPrompt: Boolean = false): Response {
            val settings = SecretStore(context).readSettings()
            require(settings.isConfigured) { "请先设置 Provider。" }
            require(prompt.isNotBlank() || allowEmptyPrompt) { "prompt 不能为空。" }
            val base = OpenAiCompatibleProvider(settings, context)
            val fault = DebugProviderFault.mode
            val provider = if (fault == null) base else DebugProviderFault.provider(settings.model, fault)
            val workspace = Workspace(context)
            require(projectId == null || workspace.projects().any { it.id == projectId }) { "指定项目不存在。" }
            val runId = AgentOrchestrator(workspace, provider).submit(prompt, createNew, testCaseId, projectId = if (createNew) null else projectId) { state ->
                if (state.phase in setOf("已完成", "失败", "需要确认", "离线完成", "需要处理")) base.shutdown()
            }
            return json(202, JSONObject().put("runId", runId).put("testCaseId", testCaseId))
        }

        private fun readRequest(socket: Socket): Request? {
            val input = BufferedInputStream(socket.getInputStream())
            val headerBytes = ArrayList<Byte>()
            var matched = 0
            while (headerBytes.size < 16_384) {
                val next = input.read()
                if (next < 0) return null
                headerBytes.add(next.toByte())
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    next == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            val headers = headerBytes.toByteArray().toString(StandardCharsets.ISO_8859_1).split("\r\n")
            val requestLine = headers.firstOrNull()?.split(" ") ?: return null
            if (requestLine.size < 2) return null
            val headerMap = headers.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).lowercase() to line.substring(it + 1).trim() } }.toMap()
            val length = headerMap["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..3_000_000) return null
            val bodyBytes = input.readNBytes(length)
            val target = requestLine[1]
            val query = target.substringAfter('?', "").split('&').mapNotNull { pair -> pair.indexOf('=').takeIf { it > 0 }?.let { pair.substring(0, it) to java.net.URLDecoder.decode(pair.substring(it + 1), "UTF-8") } }.toMap()
            return Request(requestLine[0], target.substringBefore('?'), bodyBytes.toString(StandardCharsets.UTF_8), headerMap, query)
        }

        private fun token(context: Context): String {
            val file = File(context.filesDir, "debug_bridge_token")
            if (file.exists()) return file.readText(StandardCharsets.UTF_8)
            return UUID.randomUUID().toString().also { file.writeText(it, StandardCharsets.UTF_8) }
        }

        private fun writeResponse(socket: Socket, response: Response) {
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().use { output ->
                output.write("HTTP/1.1 ${response.status} ${if (response.status < 400) "OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write(bytes)
            }
        }

        private fun json(status: Int, value: JSONObject) = Response(status, value.toString())
        private fun summary(workspace: Workspace): JSONObject {
            val runs = workspace.runs()
            val completed = runs.count { it.status == "completed" }
            val terminal = runs.filter { it.status in setOf("completed", "failed", "cancelled") }
            val durations = runs.mapNotNull { it.modelMs }.sorted()
            val tokenRuns = runs.filter { it.promptTokens != null || it.completionTokens != null }
            val latestCases = runs.filter { it.testCaseId != null }.groupBy { it.testCaseId }.mapValues { (_, caseRuns) -> caseRuns.maxBy { it.startedAt } }.values
            val latestCaseTerminal = latestCases.filter { it.status in setOf("completed", "failed", "cancelled") }
            fun percentile(value: Double): Long? = durations.takeIf { it.isNotEmpty() }?.let { it[(value * (it.lastIndex)).toInt()] }
            return JSONObject().apply {
                put("runs", runs.size); put("caseRuns", runs.count { it.testCaseId != null }); put("completed", completed); put("failed", runs.count { it.status == "failed" }); put("running", runs.count { it.status == "running" }); put("terminalSuccessRate", if (terminal.isEmpty()) JSONObject.NULL else completed.toDouble() / terminal.size)
                put("latestCaseRuns", latestCases.size); put("latestCaseCompleted", latestCases.count { it.status == "completed" }); put("latestCaseSuccessRate", if (latestCaseTerminal.isEmpty()) JSONObject.NULL else latestCases.count { it.status == "completed" }.toDouble() / latestCaseTerminal.size)
                val promptTotal = tokenRuns.sumOf { it.promptTokens ?: 0 }
                // 命中率只能在「上报过 cached_tokens」的运行上计算：埋点之前的历史运行没有这个字段，
                // 混进来会被当成 0 命中，把整体命中率稀释成没有意义的数字。
                val cachedRuns = runs.filter { it.cachedTokens != null }
                val cachedPromptTotal = cachedRuns.sumOf { it.promptTokens ?: 0 }
                val cachedTotal = cachedRuns.sumOf { it.cachedTokens ?: 0 }
                put("modelAttempts", runs.sumOf { it.attempts }); put("tokenUsageRuns", tokenRuns.size)
                put("promptTokens", if (tokenRuns.isEmpty()) JSONObject.NULL else promptTotal)
                put("completionTokens", if (tokenRuns.isEmpty()) JSONObject.NULL else tokenRuns.sumOf { it.completionTokens ?: 0 })
                put("cachedTokenRuns", cachedRuns.size)
                put("cachedTokens", if (cachedRuns.isEmpty()) JSONObject.NULL else cachedTotal)
                put("reasoningTokens", if (tokenRuns.isEmpty()) JSONObject.NULL else tokenRuns.sumOf { it.reasoningTokens ?: 0 })
                // 只有未命中缓存的输入是全价；把这两项分开，才能判断「重发历史」到底是贵还是便宜。
                put("cachedPromptTokens", if (cachedRuns.isEmpty()) JSONObject.NULL else cachedPromptTotal)
                put("promptTokensUncached", if (cachedRuns.isEmpty()) JSONObject.NULL else (cachedPromptTotal - cachedTotal).coerceAtLeast(0))
                put("cacheHitRate", if (cachedPromptTotal <= 0) JSONObject.NULL else cachedTotal.toDouble() / cachedPromptTotal)
                put("modelMsP50", percentile(0.5)); put("modelMsP95", percentile(0.95))
            }
        }
        private fun com.example.goon.core.AgentRun.toJson() = JSONObject().apply {
            put("id", id); put("conversationId", conversationId); put("projectId", projectId); put("prompt", prompt); put("model", model); put("testCaseId", testCaseId); put("status", status); put("phase", phase); put("startedAt", startedAt); put("completedAt", completedAt); put("modelAttempts", attempts); put("modelMs", modelMs); put("promptTokens", promptTokens); put("completionTokens", completionTokens); put("cachedTokens", cachedTokens); put("reasoningTokens", reasoningTokens); put("httpStatus", httpStatus); put("errorCode", errorCode); put("message", message)
            // 进程里是否真的还有对应的运行对象。调用方（评测驱动）判断"会话是否被占用"必须以这个为准：
            // 只看 status 会把僵尸行（进程被杀/启动即失败留下的 running）误判成占用，实测让整轮用例被跳过。
            put("active", com.example.goon.core.WebAgentRun.isRunning(conversationId) || com.example.goon.core.ConversationRun.isActive(conversationId))
        }
    }
}

private data class Request(val method: String, val path: String, val body: String, val headers: Map<String, String>, val query: Map<String, String> = emptyMap())
private data class Response(val status: Int, val body: String)
