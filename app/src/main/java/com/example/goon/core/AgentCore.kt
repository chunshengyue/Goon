package com.example.goon.core

import org.json.JSONObject

enum class AgentStatus { IDLE, REQUESTING_MODEL, EXECUTING_TOOL, CANCELLING, CANCELLED, COMPLETED, FAILED }
enum class ProductTool { READ_PROJECT, PATCH_PROJECT, RUN_APP, INSPECT_APP, CHECKPOINT }

private fun ProductTool.displayName(): String = when (this) {
    ProductTool.READ_PROJECT -> "读取项目"
    ProductTool.PATCH_PROJECT -> "修改规格"
    ProductTool.RUN_APP -> "运行小程序"
    ProductTool.INSPECT_APP -> "检查结果"
    ProductTool.CHECKPOINT -> "保存版本"
}

data class AgentMessage(
    val text: String,
    val fromUser: Boolean,
    val at: Long = System.currentTimeMillis(),
    val isPlan: Boolean = false,
    val isAttachment: Boolean = false,
    val attachmentUri: String? = null,
    val runId: String? = null,
    val toolName: String? = null,
    val appResult: AppResult? = null,
    /** 该条回复发出时的会话累计用量脚注；仅助手消息有。 */
    val usage: String? = null
)
data class AppResult(
    val projectId: String, val name: String, val schemaVersion: Int, val pages: Int, val components: Int,
    val runtime: String = "spec", val files: Int = 0,
    /** Web 结果附带的项目快照，供对话内直接渲染预览；其他运行时为 null。 */
    val project: WebProject? = null
)
data class ToolResult(val tool: ProductTool, val succeeded: Boolean, val summary: String)
data class AgentResult(val status: AgentStatus, val message: String, val tools: List<String>)
data class AgentProposal(
    val updatedSpec: ApplicationSpec?,
    val failure: String? = null,
    val message: String = "",
    val plan: List<String> = emptyList(),
    val isConversation: Boolean = false
)

class ToolRegistry(private val workspace: Workspace) {
    fun readProject() = ToolResult(ProductTool.READ_PROJECT, true, "已读取 ${workspace.spec().name}。")
    fun checkpoint(label: String): ToolResult { workspace.createCheckpoint(label); return ToolResult(ProductTool.CHECKPOINT, true, "已保存 $label。") }
    fun patchProject(spec: ApplicationSpec, reason: String): ToolResult {
        val error = SpecValidator.validate(spec).firstOrNull()
        if (error != null) return ToolResult(ProductTool.PATCH_PROJECT, false, error.humanMessage)
        workspace.saveSpec(spec, "Agent applied: $reason")
        return ToolResult(ProductTool.PATCH_PROJECT, true, "已更新小程序规格。")
    }
    fun runApp(): ToolResult {
        val error = SpecValidator.validate(workspace.spec()).firstOrNull()
        return if (error == null) ToolResult(ProductTool.RUN_APP, true, "小程序运行时已就绪。") else ToolResult(ProductTool.RUN_APP, false, error.humanMessage)
    }
    fun inspectApp(): ToolResult {
        val spec = workspace.spec(); val error = SpecValidator.validate(spec).firstOrNull()
        return if (error == null) { val items = workspace.items(); ToolResult(ProductTool.INSPECT_APP, true, "页面 ${spec.pages.firstOrNull()?.id ?: "none"}；${spec.pages.sumOf { it.components.size }} 个组件；${items.size} 条本地数据。") }
        else ToolResult(ProductTool.INSPECT_APP, false, "${error.errorCode}: ${error.humanMessage}")
    }
}

open class LocalDemoProvider {
    open fun propose(prompt: String, current: ApplicationSpec): AgentProposal {
        val normalized = prompt.trim()
        if (normalized.isEmpty()) return AgentProposal(null, "请描述你想创建或修改的内容。")
        val lower = normalized.lowercase()
        val updated = when {
            lower.contains("创建") || lower.contains("做一个") || lower.contains("全新") -> {
                val name = extractValue(normalized) ?: "新小程序"
                ApplicationSpec.checklist(ApplicationSpec.blankProjectId(), name.take(18), name.take(28))
            }
            lower.contains("标题") || lower.contains("title") -> {
                val title = extractValue(normalized) ?: return AgentProposal(null, "可以这样说：把标题改为旅行准备。")
                current.copy(title = title, pages = current.pages.mapIndexed { index, page -> if (index == 0) page.copy(title = title, components = page.components.map { if (it.type == "hero") it.copy(text = title) else it }) else page })
            }
            lower.contains("颜色") || lower.contains("color") || lower.contains("主题") -> current.copy(accent = when { lower.contains("红") || lower.contains("coral") -> "coral"; lower.contains("紫") || lower.contains("violet") -> "violet"; lower.contains("绿") || lower.contains("green") -> "green"; else -> "blue" })
            else -> return AgentProposal(null, "本地模式只能创建基础清单或修改标题、颜色。配置模型后可以从零生成更多类型的小程序。")
        }
        return AgentProposal(updated)
    }
    private fun extractValue(prompt: String): String? = listOf("：", ":", "为", "成").firstNotNullOfOrNull { marker -> prompt.substringAfter(marker, "").trim().takeIf { it.isNotBlank() } }
}

class AgentSession(private val workspace: Workspace, private val provider: LocalDemoProvider = LocalDemoProvider()) {
    var status: AgentStatus = restoreStatus()
        private set
    private var cancellationRequested = false

    fun cancel() {
        if (status == AgentStatus.REQUESTING_MODEL || status == AgentStatus.EXECUTING_TOOL) {
            cancellationRequested = true
            status = AgentStatus.CANCELLING
            workspace.saveSession(status.name.lowercase(), "Cancellation requested")
            workspace.appendEvent("agent_status", "cancelling")
        }
    }

    fun submit(prompt: String, runId: String = workspace.startRun(prompt, "local-demo", projectId = workspace.activeProjectId()), recordUser: Boolean = true, completionMessage: String? = null): AgentResult {
        cancellationRequested = false
        if (recordUser) workspace.appendEvent("user_message", prompt, runId)
        transitionTo(AgentStatus.REQUESTING_MODEL, "正在请求模型", runId)
        val proposal = provider.propose(prompt, workspace.spec())
        if (proposal.failure != null || proposal.updatedSpec == null) return fail(proposal.failure ?: "No change was proposed.", emptyList(), runId)
        if (cancellationRequested) return cancelled(runId)
        transitionTo(AgentStatus.EXECUTING_TOOL, "正在执行结构化工具", runId)
        val tools = ToolRegistry(workspace)
        val results = listOf(tools.readProject(), tools.checkpoint("修改前"), tools.patchProject(proposal.updatedSpec, prompt), tools.runApp(), tools.inspectApp())
        results.forEach { result -> workspace.appendEvent("tool_${result.tool.name.lowercase()}", result.summary, runId) }
        val failure = results.firstOrNull { !it.succeeded }
        if (failure != null) return fail(failure.summary, results, runId)
        if (cancellationRequested) return cancelled(runId)
        val finalMessage = completionMessage?.trim().takeUnless { it.isNullOrBlank() } ?: "小程序已更新，并保存了可恢复版本。"
        tools.checkpoint("Agent 修改完成"); transitionTo(AgentStatus.COMPLETED, finalMessage, runId); workspace.appendEvent("agent_message", finalMessage, runId)
        val resultSpec = workspace.spec()
        workspace.appendEvent("app_result", JSONObject().apply {
            put("projectId", resultSpec.projectId); put("name", resultSpec.name)
            put("schemaVersion", resultSpec.schemaVersion); put("pages", resultSpec.pages.size); put("components", resultSpec.componentCount())
        }.toString(), runId)
        return AgentResult(status, finalMessage, results.map { it.tool.displayName() })
    }

    private fun transitionTo(next: AgentStatus, message: String, runId: String) {
        status = next; workspace.saveSession(next.name.lowercase(), message); workspace.appendEvent("agent_status", next.name.lowercase(), runId)
        val final = when (next) { AgentStatus.COMPLETED -> "completed"; AgentStatus.FAILED -> "failed"; AgentStatus.CANCELLED -> "cancelled"; else -> "running" }
        workspace.updateRun(runId, next.name.lowercase(), final, message)
    }
    private fun cancelled(runId: String): AgentResult { transitionTo(AgentStatus.CANCELLED, "已在下一步开始前取消。", runId); workspace.appendEvent("agent_message", "已在下一步开始前取消。", runId); return AgentResult(status, "已在下一步开始前取消。", emptyList()) }
    private fun fail(message: String, results: List<ToolResult>, runId: String): AgentResult { transitionTo(AgentStatus.FAILED, message, runId); workspace.appendEvent("agent_message", message, runId); return AgentResult(status, message, results.map { it.tool.displayName() }) }
    private fun restoreStatus(): AgentStatus {
        val snapshot = workspace.session() ?: return AgentStatus.IDLE
        return when (snapshot.status) {
            AgentStatus.REQUESTING_MODEL.name.lowercase(), AgentStatus.EXECUTING_TOOL.name.lowercase(), AgentStatus.CANCELLING.name.lowercase() -> {
                workspace.saveSession(AgentStatus.FAILED.name.lowercase(), "上次任务在完成前中断，未重复执行工具。")
                workspace.appendEvent("agent_status", "recovered_interrupted_session")
                AgentStatus.FAILED
            }
            else -> runCatching { AgentStatus.valueOf(snapshot.status.uppercase()) }.getOrDefault(AgentStatus.IDLE)
        }
    }
}
