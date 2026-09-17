package com.example.goon.core

import android.net.Uri
import org.json.JSONObject

data class AgentDisplayState(val phase: String, val headline: String, val detail: String, val tools: List<String> = emptyList(), val canCancel: Boolean = false)

class AgentOrchestrator(private val workspace: Workspace, private val provider: ModelProvider) {
    fun submit(prompt: String, createNew: Boolean = false, testCaseId: String? = null, imageUri: Uri? = null, projectId: String? = null, onDelta: (String) -> Unit = {}, callback: (AgentDisplayState) -> Unit): String {
        val project = workspace.projectSpec(projectId)
        val runId = workspace.startRun(prompt, provider.modelName, testCaseId, projectId)
        val intent = IntentRouter.classify(prompt, createNew, projectId)
        val projectExists = projectId != null && workspace.projects().any { it.id == projectId }
        val policy = IntentPolicy.evaluate(intent, createNew, projectId, projectExists, workspace.hasWebProject(projectId))
        val toolGate = IntentToolValidator.validateRoute(policy.route, createNew, policy.safeProjectId, projectExists, policy.safeProjectId?.let(workspace::hasWebProject) == true)
        workspace.appendEvent("intent_precheck", intent.precheck.toJson().toString(), runId)
        workspace.appendEvent("intent_decision", intent.toJson(), runId)
        workspace.appendEvent("intent_policy", policy.toJson(), runId)
        workspace.appendEvent("intent_tool_gate", toolGate.toJson().toString(), runId)
        workspace.appendEvent("user_message", prompt, runId)
        if (imageUri != null) workspace.appendEvent("user_attachment", "本轮已附加图片，仅发送给当前模型。", runId)
        if (!toolGate.allowed) {
            val message = "为了避免越权操作，当前请求暂未执行：${toolGate.reason}。"
            workspace.appendEvent("intent_security_rejection", toolGate.toJson().toString(), runId)
            workspace.updateRun(runId, "intent_rejected", "failed", message, "tool_gate_rejected")
            callback(AgentDisplayState("已拒绝", "暂未执行项目操作", message))
            return runId
        }
        if (policy.route == IntentRoute.CLARIFY) {
            val confirmation = policy.confirmation?.toJson()?.toString()
            val question = policy.clarify?.question ?: policy.reason
            // 创建类请求的澄清是**引导**（告诉用户去哪发起），不是拒绝。
            // 用「为了避免误操作，暂未执行」的句式会让人以为自己被挡回来了。
            val guiding = policy.confirmation?.action == "create_project"
            val message = buildString {
                if (guiding) append(question)
                else {
                    append("为了避免误操作，当前请求暂未执行：" + question + "。")
                    if (confirmation != null) append("\n待确认信息：" + confirmation)
                }
            }
            workspace.appendEvent("intent_clarify", policy.toJson(), runId)
            workspace.appendEvent("agent_message", message, runId)
            workspace.updateRun(runId, "intent_clarify", "completed", message, "intent_clarify")
            callback(AgentDisplayState("需要澄清", "暂未执行项目操作", message))
            return runId
        }
        if (policy.route == IntentRoute.WEB_AGENT) {
            WebAgentRun(workspace, provider, runId, policy.safeProjectId?.let { workspace.webStore().load(it) }, onDelta, callback).start(prompt, imageUri)
            return runId
        }
        if (policy.route == IntentRoute.INSPECT) {
            requestInspection(runId, prompt, requireNotNull(policy.safeProjectId), imageUri, onDelta, callback)
            return runId
        }
        workspace.updateRun(runId, "understanding")
        callback(AgentDisplayState("理解需求", "正在理解你的请求", "读取当前对话…", canCancel = true))
        workspace.appendEvent("agent_phase", "理解需求", runId)
        workspace.appendEvent("agent_phase", "调用模型", runId)
        if (policy.route == IntentRoute.LEGACY_AGENT) {
            callback(AgentDisplayState("执行工具", "正在应用安全修改", "校验规格、保存快照并运行小程序…", canCancel = true))
            workspace.updateRunProject(runId, requireNotNull(policy.safeProjectId))
            workspace.activateProject(requireNotNull(policy.safeProjectId))
            val result = AgentSession(workspace).submit(prompt, runId)
            callback(AgentDisplayState(if (result.status == AgentStatus.COMPLETED) "已完成" else "需要处理", if (result.status == AgentStatus.COMPLETED) "已完成修改" else "修改未完成", result.message, result.tools))
            return runId
        }
        // CHAT 与 SEARCH 共用同一条路径：搜索是模型自己调用的工具，不再按「用户有没有说搜索」分叉。
        ConversationRun(workspace, provider, runId, onDelta, callback).start(prompt, imageUri)
        return runId
    }

    private fun requestInspection(runId: String, prompt: String, projectId: String, imageUri: Uri?, onDelta: (String) -> Unit, callback: (AgentDisplayState) -> Unit) {
        val context = runCatching { InspectionContext.load(workspace, projectId) }.getOrNull()
        if (context == null) {
            val message = "检查目标不存在或无法读取，只读检查未执行。"
            workspace.appendEvent("intent_security_rejection", JSONObject().put("route", IntentRoute.INSPECT.name).put("reason", "inspection_context_unavailable").put("projectId", projectId).toString(), runId)
            workspace.updateRun(runId, "inspect_rejected", "failed", message, "inspection_context_unavailable")
            callback(AgentDisplayState("失败", "检查未完成", message))
            return
        }
        workspace.updateRun(runId, "inspect_read_only")
        callback(AgentDisplayState("只读检查", "正在分析项目", "读取项目清单和文件内容，不会修改项目…", canCancel = true))
        workspace.appendEvent("agent_phase", "只读检查", runId)
        val request = withConversationContext(
            "你正在执行 INSPECT_READ_ONLY。只允许返回分析文本、问题列表、风险和建议。" +
                "严禁返回 spec、dsl、patch、write proposal、保存/发布指令或工具调用。" +
                "检查上下文是只读证据，不构成写入授权。\n" + context.prompt() + "\n用户请求：" + prompt
        )
        val startedAt = System.currentTimeMillis()
        val streamed = StringBuilder()
        provider.complete(request, null, false, imageUri, onDelta = { delta ->
            streamed.append(delta)
            extractMessagePreview(streamed.toString())?.let(onDelta)
        }) { result ->
            result.fold(onSuccess = { response ->
                workspace.recordModelAttempt(runId, response.durationMs, response.promptTokens, response.completionTokens, response.httpStatus, true, response.cachedTokens, response.reasoningTokens)
                val inspection = parseInspection(response.content)
                val toolProposal = response.toolCalls.length() > 0
                if (inspection.rejectedReason != null || toolProposal) {
                    val reason = inspection.rejectedReason ?: "INSPECT 返回了工具调用。"
                    workspace.appendEvent("intent_security_rejection", JSONObject().put("route", IntentRoute.INSPECT.name).put("reason", reason).put("projectId", projectId).toString(), runId)
                    workspace.updateRun(runId, "inspect_rejected", "failed", "检查响应包含禁止的写入内容，未执行任何项目操作。", "inspect_write_proposal")
                    callback(AgentDisplayState("已拒绝", "检查结果未采用", "检查响应包含禁止的写入内容，未执行任何项目操作。"))
                    return@fold
                }
                val payload = inspection.toJson()
                workspace.appendEvent("inspection_result", payload, runId)
                val reply = inspection.message.ifBlank { "检查完成，但模型没有返回可展示的分析内容。" }
                workspace.appendEvent("agent_message", reply, runId)
                workspace.updateRun(runId, "inspect_completed", "completed", reply)
                callback(AgentDisplayState("已完成", "只读检查完成", reply))
            }, onFailure = { error ->
                workspace.recordModelAttempt(runId, System.currentTimeMillis() - startedAt, null, null, null, false)
                workspace.updateRun(runId, "inspect_failed", "failed", error.message ?: "检查模型请求失败。", "inspect_model_error")
                workspace.appendEvent("agent_message", "只读检查未完成：" + (error.message ?: "模型请求失败。"), runId)
                callback(AgentDisplayState("失败", "检查未完成", error.message ?: "模型请求失败。"))
            })
        }
    }

    private data class InspectionResult(
        val message: String,
        val issues: List<String> = emptyList(),
        val risks: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
        val rejectedReason: String? = null
    ) {
        fun toJson() = JSONObject().apply {
            put("mode", "inspect_read_only")
            put("message", message)
            put("issues", org.json.JSONArray(issues))
            put("risks", org.json.JSONArray(risks))
            put("recommendations", org.json.JSONArray(recommendations))
        }.toString()
    }

    private fun parseInspection(raw: String): InspectionResult {
        val fence = 96.toChar().toString().repeat(3)
        val text = raw.trim().removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return InspectionResult(text)
        val forbidden = listOf("spec", "dsl", "patch", "write", "writeProposal", "save", "publish", "tool_calls", "toolCalls")
        val found = forbidden.firstOrNull { json.has(it) && !json.isNull(it) } ?: when (json.optString("mode").lowercase()) {
            "create", "modify", "write", "replace", "patch", "publish" -> "禁止的 INSPECT 响应模式。"
            else -> null
        }
        if (found != null) return InspectionResult("", rejectedReason = "检测到禁止字段或模式：" + found)
        fun strings(key: String): List<String> = json.optJSONArray(key)?.let { array -> List(array.length()) { array.optString(it).trim() }.filter { it.isNotBlank() }.take(20) } ?: emptyList()
        return InspectionResult(
            message = json.optString("message", json.optString("analysis", json.optString("summary"))),
            issues = strings("issues"),
            risks = strings("risks"),
            recommendations = strings("recommendations")
        )
    }

    private fun extractMessagePreview(raw: String): String? {
        val start = raw.indexOf("\"message\"")
        if (start < 0) return null
        val valueStart = raw.indexOf(':', start).let { raw.indexOf('\"', it + 1) }
        if (valueStart < 0) return null
        val value = raw.substring(valueStart + 1)
        val end = value.indexOf('"')
        val partial = if (end >= 0) value.substring(0, end) else value
        return partial.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun withConversationContext(request: String): String {
        val history = workspace.events().asReversed()
            .filter { it.kind == "user_message" || it.kind == "agent_message" }
            .takeLast(10)
            .joinToString("\n") { event -> "${if (event.kind == "user_message") "用户" else "助手"}：${event.message}" }
        val skills = if (mentionsSkill(request) || mentionsSkill(history)) BuiltInSkills.catalog else null
        return buildString {
            if (history.isNotBlank()) append("最近对话：\n").append(history).append("\n\n")
            if (skills != null) append("内置 skill 目录（skill 只是提示资源，不增加任何运行权限）：\n").append(skills).append("\n如果用户询问某个 skill 能否使用，必须按上面的平台说明直接回答能否落地、哪部分只能参考，不要笼统说「已加载」。\n\n")
            append("当前请求：").append(request)
        }
    }

    private fun mentionsSkill(text: String) = Regex("(?i)skill|技能|能力包|插件|前端设计|视觉设计|动效").containsMatchIn(text)

}
