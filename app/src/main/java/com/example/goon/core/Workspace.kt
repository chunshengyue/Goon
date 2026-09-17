package com.example.goon.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class ChecklistItem(val id: String, val text: String, val done: Boolean = false, val note: String = "")
data class Checkpoint(val id: String, val label: String, val createdAt: Long, val specJson: String, val isUsable: Boolean)
data class WorkspaceEvent(val id: String, val at: Long, val kind: String, val message: String, val conversationId: String?, val projectId: String?, val runId: String?)
data class SessionSnapshot(val status: String, val message: String, val updatedAt: Long)
data class ProjectSummary(val id: String, val name: String, val description: String, val updatedAt: Long)
data class ConversationSummary(val id: String, val title: String, val preview: String, val updatedAt: Long)

/**
 * 会话累计用量。附带 `runsWithoutCacheData`，因为历史运行可能没上报 `cached_tokens`——
 * 把那些运行当成「零命中」会把命中率稀释成看着精确、实则错误的数字（见第 17 条）。
 */
data class ConversationUsage(
    val calls: Int, val promptTokens: Int, val cachedTokens: Int,
    val completionTokens: Int, val reasoningTokens: Int, val runsWithoutCacheData: Int,
    /**
     * 这次运行**最后一次请求**实际带了多少上下文 token（provider 真实回报）。
     *
     * 与上面的累计量是两个口径：累计是"这一轮到现在一共喂进去多少"（按 O(N²) 涨，能到百万级），
     * 这个数是"此刻上下文窗口里占了多少"（决定压缩闸门什么时候开）。0 表示没埋点。
     */
    val contextTokens: Int = 0
) {
    val uncachedPromptTokens: Int get() = (promptTokens - cachedTokens).coerceAtLeast(0)
}
data class AgentRun(
    val id: String, val conversationId: String, val projectId: String?, val prompt: String, val model: String,
    val testCaseId: String?, val status: String, val phase: String, val startedAt: Long, val completedAt: Long?,
    val attempts: Int, val modelMs: Long?, val promptTokens: Int?, val completionTokens: Int?,
    /** prompt_tokens 中命中前缀缓存的部分；与未命中部分单价不同，必须分开记录。 */
    val cachedTokens: Int?, val reasoningTokens: Int?, val httpStatus: Int?, val errorCode: String?, val message: String?
)

class Workspace(context: Context) {
    companion object {
        const val ACTION_CHANGED = "com.example.goon.WORKSPACE_CHANGED"

        /** 超过这个时长还停在 running 的运行视为僵尸。比单次任务上限（15 分钟）留出余量。 */
        private const val STALE_RUN_MS = 20 * 60 * 1000L

        /** 进程内已经没有活动运行、且超过这个时长的孤儿记录：启动即失败、进程被杀都属于这一类。 */
        private const val ORPHAN_GRACE_MS = 60 * 1000L
    }

    private val appContext = context.applicationContext
    private val database = WorkspaceDatabase(appContext)
    private val state = appContext.getSharedPreferences("goon_workspace_state", Context.MODE_PRIVATE)

    fun context(): Context = appContext
    fun webStore(): WebMiniAppStore = WebMiniAppStore(appContext)
    fun hasWebProject(projectId: String?): Boolean = projectId?.let(webStore()::has) == true
    fun saveWebProject(manifest: WebMiniAppManifest, files: Map<String, String>, event: String = "保存 Web 小程序", expectedRevision: String? = null): String {
        require(projectSpec(manifest.id) == null) { "不能覆盖旧格式项目。" }
        val revision = webStore().save(manifest, files, expectedRevision)
        state.edit().putString("active_project", manifest.id).apply()
        appendEvent("web_project", event)
        appContext.sendBroadcast(Intent(ACTION_CHANGED).setPackage(appContext.packageName))
        return revision
    }

    init {
        migrateLegacyPreferences()
        ensureConversation(currentConversationId())
        repairConversationTitles()
    }

    fun spec(): ApplicationSpec {
        check(!hasWebProject(activeProjectId())) { "Web 项目必须使用文件运行时。" }
        return projectSpec(activeProjectId())
            ?: latestSpec()
            ?: ApplicationSpec.starter().also { saveSpec(it, "创建默认小程序") }
    }

    fun activeProjectId(): String? {
        val id = state.getString("active_project", null) ?: return null
        if (webStore().has(id)) return id
        val exists = database.readableDatabase.query("project", arrayOf("project_id"), "project_id = ?", arrayOf(id), null, null, null, "1").use(Cursor::moveToFirst)
        return id.takeIf { exists } ?: run { state.edit().remove("active_project").apply(); null }
    }

    private fun legacyProjects(): List<ProjectSummary> = database.readableDatabase.query("project", arrayOf("project_id", "spec_json", "updated_at"), null, null, null, null, "updated_at DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val parsed = runCatching { ApplicationSpec.fromJson(cursor.getString(1)) }.getOrNull() ?: continue
                add(ProjectSummary(cursor.getString(0), parsed.name, parsed.description, cursor.getLong(2)))
            }
        }
    }

    fun projects(): List<ProjectSummary> = (legacyProjects() + webStore().projects()).sortedByDescending { it.updatedAt }

    fun activateProject(projectId: String) {
        if (projects().any { it.id == projectId }) {
            state.edit().putString("active_project", projectId).apply()
            appendEvent("project_selected", projectId)
        }
    }

    fun deleteProject(projectId: String): Boolean {
        if (projects().none { it.id == projectId }) return false
        if (webStore().has(projectId)) {
            webStore().delete(projectId)
            android.os.Handler(android.os.Looper.getMainLooper()).post { android.webkit.WebStorage.getInstance().deleteOrigin(WebMiniAppStore.origin(projectId)) }
        }
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("items", "project_id = ?", arrayOf(projectId))
            database.writableDatabase.delete("runtime_state", "project_id = ?", arrayOf(projectId))
            database.writableDatabase.delete("checkpoints", "project_id = ?", arrayOf(projectId))
            database.writableDatabase.delete("project", "project_id = ?", arrayOf(projectId))
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }
        if (state.getString("active_project", null) == projectId) {
            val next = projects().firstOrNull()?.id
            state.edit().apply { if (next == null) remove("active_project") else putString("active_project", next) }.apply()
        }
        appendEvent("project_deleted", "已删除小程序 $projectId")
        return true
    }

    fun saveSpec(spec: ApplicationSpec, event: String) {
        require(!hasWebProject(spec.projectId)) { "不能用旧规格覆盖 Web 项目。" }
        require(SpecValidator.validate(spec).isEmpty()) { "Cannot save an invalid application specification." }
        database.writableDatabase.insertWithOnConflict("project", null, ContentValues().apply {
            put("project_id", spec.projectId); put("spec_json", spec.toJson()); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        state.edit().putString("active_project", spec.projectId).apply()
        spec.initialState.forEach { (key, value) -> if (runtimeValue(key) == null) setRuntimeValue(key, value) }
        appendEvent("project", event)
    }

    fun items(): List<ChecklistItem> = database.readableDatabase.query("items", arrayOf("item_id", "text", "done", "note"), "project_id = ?", arrayOf(spec().projectId), null, null, "created_at ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(ChecklistItem(cursor.getString(0), cursor.getString(1), cursor.getInt(2) == 1, cursor.getString(3))) }
    }

    fun saveItems(items: List<ChecklistItem>) {
        val projectId = spec().projectId
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("items", "project_id = ?", arrayOf(projectId))
            items.forEachIndexed { index, item -> database.writableDatabase.insertOrThrow("items", null, ContentValues().apply {
                put("item_id", item.id); put("project_id", projectId); put("text", item.text); put("done", if (item.done) 1 else 0); put("note", item.note); put("created_at", index.toLong())
            }) }
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }
    }

    fun runtimeValue(key: String): String? = database.readableDatabase.query("runtime_state", arrayOf("value"), "project_id = ? AND state_key = ?", arrayOf(spec().projectId, key), null, null, null).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun setRuntimeValue(key: String, value: String) {
        database.writableDatabase.insertWithOnConflict("runtime_state", null, ContentValues().apply { put("project_id", spec().projectId); put("state_key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun checkpoints(): List<Checkpoint> = database.readableDatabase.query("checkpoints", arrayOf("checkpoint_id", "label", "created_at", "spec_json", "usable"), "project_id = ?", arrayOf(spec().projectId), null, null, "created_at DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Checkpoint(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getInt(4) == 1)) }
    }

    fun createCheckpoint(label: String, usable: Boolean = true): Checkpoint {
        val current = spec()
        val point = Checkpoint(UUID.randomUUID().toString(), label, System.currentTimeMillis(), current.toJson(), usable)
        database.writableDatabase.insertOrThrow("checkpoints", null, ContentValues().apply {
            put("checkpoint_id", point.id); put("project_id", current.projectId); put("label", point.label); put("created_at", point.createdAt); put("spec_json", point.specJson); put("usable", if (point.isUsable) 1 else 0)
        })
        database.writableDatabase.execSQL("DELETE FROM checkpoints WHERE checkpoint_id NOT IN (SELECT checkpoint_id FROM checkpoints WHERE project_id = ? ORDER BY created_at DESC LIMIT 20) AND project_id = ?", arrayOf(current.projectId, current.projectId))
        appendEvent("checkpoint", "保存版本：$label")
        return point
    }

    fun restore(checkpoint: Checkpoint) {
        saveSpec(ApplicationSpec.fromJson(checkpoint.specJson), "恢复 ${checkpoint.label}")
        appendEvent("checkpoint", "已恢复到 ${checkpoint.label}")
    }

    fun conversations(): List<ConversationSummary> = database.readableDatabase.rawQuery(
        """SELECT c.conversation_id, c.title, c.updated_at,
            COALESCE((SELECT e.message FROM events e WHERE e.conversation_id = c.conversation_id AND e.kind IN ('user_message','agent_message') ORDER BY e.created_at DESC, e.rowid DESC LIMIT 1), '')
            FROM conversations c ORDER BY c.updated_at DESC""".trimIndent(), null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(ConversationSummary(cursor.getString(0), cursor.getString(1), cursor.getString(3), cursor.getLong(2))) } }

    fun newConversation(): String = UUID.randomUUID().toString().also {
        state.edit().putString("conversation_id", it).apply()
        ensureConversation(it)
        saveSession("idle", "新对话")
    }

    fun activateConversation(conversationId: String) {
        if (conversations().any { it.id == conversationId }) {
            state.edit().putString("conversation_id", conversationId).apply()
            saveSession("idle", "已切换对话")
        }
    }

    fun events(conversationId: String = currentConversationId()): List<WorkspaceEvent> = database.readableDatabase.query(
        "events", arrayOf("event_id", "created_at", "kind", "message", "conversation_id", "project_id", "run_id"),
        "conversation_id = ?", arrayOf(conversationId), null, null, "created_at DESC, rowid DESC"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(WorkspaceEvent(cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getStringOrNull(4), cursor.getStringOrNull(5), cursor.getStringOrNull(6))) }
    }

    fun appendEvent(kind: String, message: String, runId: String? = null) {
        database.writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            // 事件是运行 trace 与评测 artifact 的来源，截断会让验证报告无法复现；只做内存与体积保护。
            put("event_id", UUID.randomUUID().toString()); put("created_at", System.currentTimeMillis()); put("kind", kind); put("message", message.take(64_000))
            put("conversation_id", runId?.let { run(it)?.conversationId } ?: currentConversationId()); put("project_id", if (runId == null) activeProjectId() else run(runId)?.projectId); put("run_id", runId)
        })
        database.writableDatabase.execSQL("DELETE FROM events WHERE run_id IS NULL AND event_id NOT IN (SELECT event_id FROM events WHERE run_id IS NULL ORDER BY created_at DESC LIMIT 160)")
        appContext.sendBroadcast(Intent(ACTION_CHANGED).setPackage(appContext.packageName))
    }

    fun startRun(prompt: String, model: String, testCaseId: String? = null, projectId: String? = null): String {
        sweepStaleRuns()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        touchConversation(prompt, now)
        database.writableDatabase.insertOrThrow("agent_run", null, ContentValues().apply {
            put("run_id", id); put("conversation_id", currentConversationId()); put("project_id", projectId); put("prompt", prompt.take(2_000)); put("model", model.take(120)); put("test_case_id", testCaseId); put("status", "running"); put("phase", "started"); put("started_at", now); put("model_attempts", 0)
        })
        return id
    }

    /**
     * 清理僵尸运行。
     *
     * 进程被杀、启动即失败、回调抛异常，都会留下一条永远停在 `running` 的记录。危害不只是数据脏：
     * **后续运行会被判成「会话被占用」而拒绝或跳过**——实测评测驱动就因为一条僵尸运行整条用例被跳过。
     * 所以每次开新运行前先扫一遍，超时的标记为失败并写明原因。
     */
    private fun sweepStaleRuns() {
        runCatching {
            val values = ContentValues().apply {
                put("status", "failed"); put("phase", "abandoned")
                put("message", "运行未正常结束（进程中断或启动失败），已自动标记为失败。")
                put("error_code", "abandoned")
                put("completed_at", System.currentTimeMillis())
            }
            val now = System.currentTimeMillis()
            // 判据一：单纯超时（进程里可能还在跑，只是太久没结束）。
            database.writableDatabase.update("agent_run", values, "status = ? AND started_at < ?",
                arrayOf("running", (now - STALE_RUN_MS).toString()))
            // 判据二（更准）：进程里已经没有对应的活动运行，说明这条记录是孤儿——启动即失败、
            // 进程被杀、回调抛异常都会造成它。只给 1 分钟宽限，避免误杀刚启动还没登记的运行。
            val orphans = database.writableDatabase.rawQuery(
                "SELECT run_id, conversation_id FROM agent_run WHERE status = ? AND started_at < ?",
                arrayOf("running", (now - ORPHAN_GRACE_MS).toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            for ((runId, conversationId) in orphans) {
                if (WebAgentRun.isRunning(conversationId) || ConversationRun.isActive(conversationId)) continue
                database.writableDatabase.update("agent_run", ContentValues(values), "run_id = ?", arrayOf(runId))
            }
        }
    }

    fun updateRun(runId: String, phase: String, status: String = "running", message: String? = null, errorCode: String? = null) {
        database.writableDatabase.update("agent_run", ContentValues().apply {
            put("phase", phase); put("status", status); if (message != null) put("message", message.take(800)); if (errorCode != null) put("error_code", errorCode.take(80))
            if (status in setOf("completed", "failed", "cancelled")) {
                put("completed_at", System.currentTimeMillis())
                run(runId)?.projectId?.let(::projectSpec)?.let { put("after_spec_hash", specHash(it)) }
            }
        }, "run_id = ?", arrayOf(runId))
    }

    fun updateRunProject(runId: String, projectId: String) {
        database.writableDatabase.update("agent_run", ContentValues().apply { put("project_id", projectId) }, "run_id = ?", arrayOf(runId))
    }

    fun recordModelAttempt(
        runId: String, durationMs: Long?, promptTokens: Int?, completionTokens: Int?, httpStatus: Int?, succeeded: Boolean,
        cachedTokens: Int? = null, reasoningTokens: Int? = null
    ) {
        database.writableDatabase.execSQL(
            "UPDATE agent_run SET model_attempts = model_attempts + 1, model_ms = COALESCE(model_ms, 0) + COALESCE(?, 0), " +
                "prompt_tokens = CASE WHEN ? IS NULL THEN prompt_tokens ELSE COALESCE(prompt_tokens, 0) + ? END, " +
                "completion_tokens = CASE WHEN ? IS NULL THEN completion_tokens ELSE COALESCE(completion_tokens, 0) + ? END, " +
                "cached_tokens = CASE WHEN ? IS NULL THEN cached_tokens ELSE COALESCE(cached_tokens, 0) + ? END, " +
                "reasoning_tokens = CASE WHEN ? IS NULL THEN reasoning_tokens ELSE COALESCE(reasoning_tokens, 0) + ? END, " +
                "http_status = ?, phase = ? WHERE run_id = ?",
            arrayOf(durationMs, promptTokens, promptTokens, completionTokens, completionTokens,
                cachedTokens, cachedTokens, reasoningTokens, reasoningTokens,
                httpStatus, if (succeeded) "model_returned" else "model_failed", runId)
        )
    }

    fun runs(limit: Int = 40): List<AgentRun> = database.readableDatabase.query("agent_run", null, null, null, null, null, "started_at DESC", limit.toString()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toRun()) }
    }

    fun run(id: String): AgentRun? = database.readableDatabase.query("agent_run", null, "run_id = ?", arrayOf(id), null, null, null, "1").use { cursor -> if (cursor.moveToFirst()) cursor.toRun() else null }

    /**
     * 会话累计用量，按运行时间累加，返回 runId → 截至该次运行的累计值。
     * 逐条累计而不是只给总数，是为了让历史回复上的脚注固定不变——否则新消息一进来，旧回复的数字也会跟着涨。
     */
    fun conversationUsage(conversationId: String): Map<String, ConversationUsage> =
        contextTokensByRun(conversationId).let { contexts ->
        database.readableDatabase.query(
            "agent_run",
            arrayOf("run_id", "model_attempts", "prompt_tokens", "cached_tokens", "completion_tokens", "reasoning_tokens"),
            "conversation_id = ?", arrayOf(conversationId), null, null, "started_at ASC, rowid ASC"
        ).use { cursor ->
            val output = LinkedHashMap<String, ConversationUsage>()
            var calls = 0; var prompt = 0; var cached = 0; var completion = 0; var reasoning = 0; var withoutCache = 0
            while (cursor.moveToNext()) {
                calls += cursor.getInt(1)
                if (!cursor.isNull(2)) prompt += cursor.getInt(2)
                if (cursor.isNull(3)) withoutCache++ else cached += cursor.getInt(3)
                if (!cursor.isNull(4)) completion += cursor.getInt(4)
                if (!cursor.isNull(5)) reasoning += cursor.getInt(5)
                val runId = cursor.getString(0)
                output[runId] = ConversationUsage(calls, prompt, cached, completion, reasoning, withoutCache, contexts[runId] ?: 0)
            }
            output
        }
        }

    /**
     * 每次运行的**末次**上下文大小，取自 `agent_usage` 埋点。
     *
     * 不新建列：埋点本来就要为评测和排查留下 provider 真数，脚注只是把它读出来给用户看，
     * 多一个数据源就多一处会和真相对不上的地方。
     */
    private fun contextTokensByRun(conversationId: String): Map<String, Int> =
        database.readableDatabase.query(
            "events", arrayOf("run_id", "message"), "kind = ? AND conversation_id = ?",
            arrayOf("agent_usage", conversationId), null, null, "rowid ASC"
        ).use { cursor ->
            val output = HashMap<String, Int>()
            while (cursor.moveToNext()) {
                val runId = cursor.getString(0) ?: continue
                val tokens = runCatching { JSONObject(cursor.getString(1)).optInt("promptTokens") }.getOrDefault(0)
                if (tokens > 0) output[runId] = tokens
            }
            output
        }

    fun runArtifact(id: String): String? {
        val run = run(id) ?: return null
        val events = database.readableDatabase.query("events", arrayOf("event_id", "created_at", "kind", "message"), "run_id = ?", arrayOf(id), null, null, "created_at ASC, rowid ASC").use { cursor ->
            JSONArray().apply { while (cursor.moveToNext()) put(JSONObject().put("id", cursor.getString(0)).put("at", cursor.getLong(1)).put("kind", cursor.getString(2)).put("message", cursor.getString(3))) }
        }
        return JSONObject().apply {
            put("run", run.toJson()); put("events", events); put("finalSpec", run.projectId?.let(::projectSpec)?.toJson())
            val result = (0 until events.length()).map { events.getJSONObject(it) }.lastOrNull { it.optString("kind") == "app_result" }
                ?.let { runCatching { JSONObject(it.getString("message")) }.getOrNull() }
            if (result?.optString("runtime") == "web" && run.projectId != null) {
                put("webRevision", result.optString("revision"))
                put("finalWebProject", runCatching { webStore().readRevision(run.projectId, result.getString("revision")).toJson() }.getOrNull() ?: JSONObject.NULL)
            }
        }.toString()
    }

    fun session(): SessionSnapshot? = database.readableDatabase.query("agent_session", arrayOf("status", "message", "updated_at"), "session_id = ?", arrayOf("current"), null, null, null).use { cursor -> if (cursor.moveToFirst()) SessionSnapshot(cursor.getString(0), cursor.getString(1), cursor.getLong(2)) else null }

    fun saveSession(status: String, message: String) {
        database.writableDatabase.insertWithOnConflict("agent_session", null, ContentValues().apply { put("session_id", "current"); put("status", status); put("message", message.take(500)); put("updated_at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun displayTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

    fun currentConversationId(): String {
        val existing = state.getString("conversation_id", null)
        if (existing != null) return existing
        return UUID.randomUUID().toString().also { state.edit().putString("conversation_id", it).apply() }
    }

    private fun ensureConversation(id: String) {
        val now = System.currentTimeMillis()
        database.writableDatabase.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("conversation_id", id); put("title", "新对话"); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        database.writableDatabase.update("events", ContentValues().apply { put("conversation_id", id) }, "conversation_id IS NULL", null)
    }

    private fun touchConversation(prompt: String, at: Long) {
        val id = currentConversationId()
        ensureConversation(id)
        val current = conversations().firstOrNull { it.id == id }
        database.writableDatabase.update("conversations", ContentValues().apply {
            if (current?.title in setOf("新对话", "历史对话")) put("title", conversationTitle(prompt))
            put("updated_at", at)
        }, "conversation_id = ?", arrayOf(id))
    }

    private fun repairConversationTitles() {
        conversations().filter { it.title in setOf("新对话", "历史对话") && it.preview.isNotBlank() }.forEach { conversation ->
            val firstPrompt = database.readableDatabase.query("events", arrayOf("message"), "conversation_id = ? AND kind = 'user_message'", arrayOf(conversation.id), null, null, "created_at ASC, rowid ASC", "1").use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (!firstPrompt.isNullOrBlank()) database.writableDatabase.update("conversations", ContentValues().apply { put("title", conversationTitle(firstPrompt)) }, "conversation_id = ?", arrayOf(conversation.id))
        }
    }

    private fun conversationTitle(prompt: String): String {
        val firstLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val concise = firstLine
            .replace(Regex("^(请帮我|帮我|请|我想让你|我想|能否|可以)\\s*"), "")
            .substringBefore('。').substringBefore('？').substringBefore('?').substringBefore('！').substringBefore('!')
            .trim(' ', '，', ',', '：', ':')
        return concise.ifBlank { firstLine }.take(24).ifBlank { "新对话" }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun Cursor.toRun() = AgentRun(
        id = getString(getColumnIndexOrThrow("run_id")),
        conversationId = getString(getColumnIndexOrThrow("conversation_id")),
        projectId = getColumnIndex("project_id").takeIf { it >= 0 && !isNull(it) }?.let(::getString),
        prompt = getString(getColumnIndexOrThrow("prompt")),
        model = getString(getColumnIndexOrThrow("model")),
        testCaseId = getColumnIndex("test_case_id").takeIf { it >= 0 && !isNull(it) }?.let(::getString),
        status = getString(getColumnIndexOrThrow("status")),
        phase = getString(getColumnIndexOrThrow("phase")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        completedAt = getColumnIndex("completed_at").takeIf { it >= 0 && !isNull(it) }?.let(::getLong),
        attempts = getInt(getColumnIndexOrThrow("model_attempts")),
        modelMs = getColumnIndex("model_ms").takeIf { it >= 0 && !isNull(it) }?.let(::getLong),
        promptTokens = getColumnIndex("prompt_tokens").takeIf { it >= 0 && !isNull(it) }?.let(::getInt),
        completionTokens = getColumnIndex("completion_tokens").takeIf { it >= 0 && !isNull(it) }?.let(::getInt),
        cachedTokens = getColumnIndex("cached_tokens").takeIf { it >= 0 && !isNull(it) }?.let(::getInt),
        reasoningTokens = getColumnIndex("reasoning_tokens").takeIf { it >= 0 && !isNull(it) }?.let(::getInt),
        httpStatus = getColumnIndex("http_status").takeIf { it >= 0 && !isNull(it) }?.let(::getInt),
        errorCode = getColumnIndex("error_code").takeIf { it >= 0 && !isNull(it) }?.let(::getString),
        message = getColumnIndex("message").takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    )

    private fun AgentRun.toJson() = JSONObject().apply {
        put("id", id); put("conversationId", conversationId); put("projectId", projectId); put("prompt", prompt); put("model", model); put("testCaseId", testCaseId); put("status", status); put("phase", phase); put("startedAt", startedAt); put("completedAt", completedAt); put("modelAttempts", attempts); put("modelMs", modelMs); put("promptTokens", promptTokens); put("completionTokens", completionTokens); put("cachedTokens", cachedTokens); put("reasoningTokens", reasoningTokens); put("httpStatus", httpStatus); put("errorCode", errorCode); put("message", message)
    }

    private fun specHash(spec: ApplicationSpec): String = spec.toJson().hashCode().toUInt().toString(16)

    private fun latestSpec(): ApplicationSpec? = database.readableDatabase.query("project", arrayOf("spec_json"), null, null, null, null, "updated_at DESC", "1").use { cursor ->
        if (!cursor.moveToFirst()) null else runCatching { ApplicationSpec.fromJson(cursor.getString(0)) }.getOrNull()?.also { state.edit().putString("active_project", it.projectId).apply() }
    }

    fun projectSpec(projectId: String?): ApplicationSpec? {
        if (projectId == null) return null
        return database.readableDatabase.query("project", arrayOf("spec_json"), "project_id = ?", arrayOf(projectId), null, null, null, "1").use { cursor ->
        if (!cursor.moveToFirst()) null else runCatching { ApplicationSpec.fromJson(cursor.getString(0)) }.getOrNull()
        }
    }

    private fun migrateLegacyPreferences() {
        val legacy = appContext.getSharedPreferences("goon_workspace", Context.MODE_PRIVATE)
        val hasProject = database.readableDatabase.rawQuery("SELECT 1 FROM project LIMIT 1", null).use(Cursor::moveToFirst)
        val legacySpec = legacy.getString("spec", null) ?: return
        if (hasProject) return
        val migrated = runCatching { ApplicationSpec.fromJson(legacySpec) }.getOrNull() ?: return
        saveSpec(migrated, "迁移旧版小程序")
        runCatching {
            val itemsJson = JSONArray(legacy.getString("items", "[]"))
            saveItems(List(itemsJson.length()) { index -> itemsJson.getJSONObject(index).let { ChecklistItem(it.getString("id"), it.getString("text"), it.optBoolean("done"), it.optString("note")) } })
        }
        appendEvent("migration", "工作区已迁移到本地数据库")
    }
}

private class WorkspaceDatabase(context: Context) : SQLiteOpenHelper(context, "goon_workspace.db", null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE project (project_id TEXT PRIMARY KEY, spec_json TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE items (item_id TEXT PRIMARY KEY, project_id TEXT NOT NULL, text TEXT NOT NULL, done INTEGER NOT NULL, note TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE checkpoints (checkpoint_id TEXT PRIMARY KEY, project_id TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL, spec_json TEXT NOT NULL, usable INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE events (event_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL, conversation_id TEXT, project_id TEXT, run_id TEXT)")
        db.execSQL("CREATE TABLE agent_session (session_id TEXT PRIMARY KEY, status TEXT NOT NULL, message TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE runtime_state (project_id TEXT NOT NULL, state_key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(project_id, state_key))")
        db.execSQL("CREATE TABLE conversations (conversation_id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE agent_run (run_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, project_id TEXT, prompt TEXT NOT NULL, model TEXT NOT NULL, test_case_id TEXT, status TEXT NOT NULL, phase TEXT NOT NULL, started_at INTEGER NOT NULL, completed_at INTEGER, model_attempts INTEGER NOT NULL, model_ms INTEGER, prompt_tokens INTEGER, completion_tokens INTEGER, cached_tokens INTEGER, reasoning_tokens INTEGER, http_status INTEGER, error_code TEXT, message TEXT, after_spec_hash TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS agent_session (session_id TEXT PRIMARY KEY, status TEXT NOT NULL, message TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE items ADD COLUMN project_id TEXT NOT NULL DEFAULT 'local-checklist'")
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN project_id TEXT NOT NULL DEFAULT 'local-checklist'")
            db.execSQL("CREATE TABLE runtime_state (project_id TEXT NOT NULL, state_key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(project_id, state_key))")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE events ADD COLUMN conversation_id TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN project_id TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN run_id TEXT")
            db.execSQL("CREATE TABLE agent_run (run_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, project_id TEXT NOT NULL, prompt TEXT NOT NULL, model TEXT NOT NULL, status TEXT NOT NULL, phase TEXT NOT NULL, started_at INTEGER NOT NULL, completed_at INTEGER, model_attempts INTEGER NOT NULL, model_ms INTEGER, http_status INTEGER, error_code TEXT, message TEXT, after_spec_hash TEXT)")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE agent_run ADD COLUMN prompt_tokens INTEGER")
            db.execSQL("ALTER TABLE agent_run ADD COLUMN completion_tokens INTEGER")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE agent_run ADD COLUMN test_case_id TEXT")
        if (oldVersion < 7) {
            db.execSQL("CREATE TABLE conversations (conversation_id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO conversations SELECT conversation_id, '历史对话', MAX(created_at) FROM events WHERE conversation_id IS NOT NULL GROUP BY conversation_id")
            db.execSQL("ALTER TABLE agent_run RENAME TO agent_run_v6")
            db.execSQL("CREATE TABLE agent_run (run_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, project_id TEXT, prompt TEXT NOT NULL, model TEXT NOT NULL, test_case_id TEXT, status TEXT NOT NULL, phase TEXT NOT NULL, started_at INTEGER NOT NULL, completed_at INTEGER, model_attempts INTEGER NOT NULL, model_ms INTEGER, prompt_tokens INTEGER, completion_tokens INTEGER, http_status INTEGER, error_code TEXT, message TEXT, after_spec_hash TEXT)")
            db.execSQL("INSERT INTO agent_run SELECT run_id, conversation_id, project_id, prompt, model, test_case_id, status, phase, started_at, completed_at, model_attempts, model_ms, prompt_tokens, completion_tokens, http_status, error_code, message, after_spec_hash FROM agent_run_v6")
            db.execSQL("DROP TABLE agent_run_v6")
        }
        // 缓存命中与推理 token 是判断真实成本的前提：prompt_tokens 里含命中缓存的部分，
        // 只看总数会把「重发但命中」误判成「重发且全价」，二者的单价并不相同。
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE agent_run ADD COLUMN cached_tokens INTEGER")
            db.execSQL("ALTER TABLE agent_run ADD COLUMN reasoning_tokens INTEGER")
        }
    }
}
