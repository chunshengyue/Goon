package com.example.goon.debug

import com.example.goon.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 机器可判定的断言。判定全部在 bridge 内完成，且每条失败都必须给出原因，
 * 否则出现回归时无法定位被破坏的是哪一条期望。
 */
data class EvaluationAssertion(val type: String, val params: JSONObject = JSONObject()) {
    fun toJson() = JSONObject().put("type", type).put("params", params)

    companion object {
        fun fromJson(value: JSONObject) = EvaluationAssertion(value.getString("type"), value.optJSONObject("params") ?: JSONObject())
    }
}

/** 从一次运行的 artifact 里取出断言需要的证据，避免每条断言各自解析。 */
private class ArtifactView(val raw: String) {
    val json: JSONObject? = runCatching { JSONObject(raw) }.getOrNull()
    val events: List<JSONObject> = json?.optJSONArray("events")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
    val run: JSONObject? = json?.optJSONObject("run")

    private fun message(kind: String): JSONObject? = events.lastOrNull { it.optString("kind") == kind }
        ?.optString("message")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    val terminal: JSONObject? = message("agent_terminal")
    val appResult: JSONObject? = message("app_result")
    val verification: JSONObject? = message("web_verification")
    val webProject: JSONObject? = json?.optJSONObject("finalWebProject")?.takeIf { it.length() > 0 }
    val spec: JSONObject? = json?.optJSONObject("finalSpec")?.takeIf { it.length() > 0 }
    val toolKinds: List<String> = events.map { it.optString("kind") }.filter { it.startsWith("tool_") }
    val kinds: Set<String> = events.map { it.optString("kind") }.toSet()

    fun projectFiles(): Map<String, String> {
        val files = webProject?.optJSONObject("files") ?: return emptyMap()
        return files.keys().asSequence().associateWith { files.optString(it) }
    }
}

/** 项目内容摘要：文件内容或规格一变摘要就变，用于判断「修改是不是真的发生」。 */
fun projectDigests(workspace: Workspace): JSONObject {
    val store = workspace.webStore()
    return JSONObject().apply {
        workspace.projects().forEach { project ->
            val text = if (store.has(project.id)) {
                store.load(project.id)?.let { loaded ->
                    loaded.manifest.toJson().toString() + "\n" + loaded.files.toSortedMap().entries.joinToString("\n") { "// ${it.key}\n${it.value}" }
                }.orEmpty()
            } else {
                workspace.projectSpec(project.id)?.toJson()?.toString().orEmpty()
            }
            put(project.id, sha256(text))
        }
    }
}

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

object EvaluationJudge {
    const val ARTIFACT_ENDPOINT_PREFIX = "/test/runs/"

    private val SECRET_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_-]{24,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{24,}")
    )

    fun judge(workspace: Workspace, case: EvaluationCase, runId: String, baseline: JSONObject?, judgeBaseline: JSONObject? = null): JSONObject {
        if (case.status == "pending") {
            return JSONObject().put("caseId", case.id).put("title", case.title).put("suite", case.suite)
                .put("runId", runId).put("passed", false).put("pending", true)
                .put("checks", JSONArray().put(JSONObject().put("type", "status").put("passed", false).put("reason", "该用例的期望还不能机器判定，需要补场景（取消、失败注入或桌面端构造输入）。")))
                .put("failures", JSONArray())
        }
        val artifact = workspace.runArtifact(runId)
        if (artifact == null) {
            return JSONObject().put("caseId", case.id).put("runId", runId).put("passed", false)
                .put("checks", JSONArray()).put("failures", JSONArray().put(JSONObject().put("type", "artifact").put("reason", "运行 artifact 不可读，可能是运行记录已被清理或 bridge 中断。")))
        }
        val view = ArtifactView(artifact)
        val checks = JSONArray()
        val failures = JSONArray()
        fun record(type: String, passed: Boolean, reason: String = "") {
            checks.put(JSONObject().put("type", type).put("passed", passed).apply { if (reason.isNotBlank()) put("reason", reason) })
            if (!passed) failures.put(JSONObject().put("type", type).put("reason", reason))
        }
        if (case.assertions.isEmpty()) {
            record("assertions", false, "该用例尚未定义机器可判定的断言，判定结果不可信。")
        }
        case.assertions.forEach { assertion ->
            val outcome = evaluate(workspace, assertion, view, baseline, judgeBaseline, case)
            record(assertion.type, outcome[0] as Boolean, outcome[1] as String)
        }
        return JSONObject().put("caseId", case.id).put("title", case.title).put("suite", case.suite)
            .put("runId", runId).put("passed", failures.length() == 0)
            .put("checks", checks).put("failures", failures)
            .put("artifactEndpoint", "$ARTIFACT_ENDPOINT_PREFIX$runId")
    }

    /** 返回「是否通过」与失败原因，原因必须能直接定位问题。 */
    private fun evaluate(workspace: Workspace, assertion: EvaluationAssertion, view: ArtifactView, baseline: JSONObject?, judgeBaseline: JSONObject?, case: EvaluationCase): Array<Any> {
        val params = assertion.params
        return when (assertion.type) {
            "artifact" -> {
                val required = params.optJSONArray("requires")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val missing = required.filter { it !in view.kinds }
                val requiresAny = params.optJSONArray("requiresAny")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val messages = view.events.filter { it.optString("kind") == "agent_message" }.joinToString("\n") { it.optString("message") }
                val containsAny = params.optJSONArray("messageContainsAny")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val notContains = params.optJSONArray("messageNotContains")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val hitNotContains = notContains.filter { messages.contains(it) }
                when {
                    view.json == null -> fail("artifact 不是合法 JSON。")
                    missing.isNotEmpty() -> fail("artifact 缺少事件：${missing.joinToString()}。")
                    requiresAny.isNotEmpty() && requiresAny.none { it in view.kinds } -> fail("artifact 缺少任一必需事件：${requiresAny.joinToString()}。")
                    containsAny.isNotEmpty() && containsAny.none { messages.contains(it) } -> fail("回复没有说明受限能力，未包含任何提示词：${containsAny.joinToString()}。")
                    hitNotContains.isNotEmpty() -> fail("回复出现越权声明：${hitNotContains.joinToString()}。")
                    params.optBoolean("noSecrets", true) && SECRET_PATTERNS.any { it.containsMatchIn(view.raw) } -> fail("artifact 命中疑似凭据，日志脱敏未生效。")
                    else -> pass()
                }
            }
            "terminal" -> {
                val reasons = params.optJSONArray("reasons")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val statuses = params.optJSONArray("statuses")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                // 澄清、拒绝等路由不会写 agent_terminal，终止原因落在 run.error_code 上。
                val actualReason = view.terminal?.optString("terminalReason").orEmpty().ifBlank { view.run?.optString("errorCode").orEmpty() }
                val actualStatus = view.run?.optString("status").orEmpty()
                when {
                    view.terminal == null && actualReason.isBlank() -> fail("artifact 既没有 agent_terminal 事件也没有运行终止记录，无法判断终止原因。")
                    reasons.isNotEmpty() && actualReason !in reasons -> fail("终止原因为 $actualReason，期望 ${reasons.joinToString()}。")
                    statuses.isNotEmpty() && actualStatus !in statuses -> fail("运行状态为 $actualStatus，期望 ${statuses.joinToString()}。")
                    else -> pass()
                }
            }
            "project" -> {
                // 创建类请求现在统一走 Web 路径，旧 DSL 只在修改既有旧项目时可达成；
                // 因此按「产生了可运行项目」判定，不把内部路由写进期望，避免用例与实现耦合。
                val webSource = view.webProject
                val source = webSource ?: view.spec
                val isWeb = webSource != null
                if (source == null) return fail("没有可判定的项目产物（finalWebProject 与 finalSpec 都为空）。")
                val files = view.projectFiles()
                val entry = if (isWeb) source.optJSONObject("manifest")?.optString("entry").orEmpty() else ""
                val minFiles = params.optInt("minFiles", if (isWeb) 1 else 0)
                val maxFiles = params.optInt("maxFiles", Int.MAX_VALUE)
                val nameContains = params.optString("nameContains")
                val name = if (isWeb) source.optJSONObject("manifest")?.optString("name").orEmpty() else source.optString("name")
                when {
                    isWeb && files.isEmpty() -> fail("项目没有文件。")
                    isWeb && params.optBoolean("entryHtml", true) && files[entry]?.isNotBlank() != true -> fail("缺少 HTML 入口 $entry。")
                    !isWeb && params.optBoolean("requireWeb", false) -> fail("期望产出 Web 文件项目，但只得到旧 DSL 规格。")
                    files.size < minFiles -> fail("文件数 ${files.size} 少于期望的 $minFiles。")
                    files.size > maxFiles -> fail("文件数 ${files.size} 超过期望的 $maxFiles。")
                    nameContains.isNotBlank() && !name.contains(nameContains) -> fail("项目名为「$name」，未包含「$nameContains」。")
                    else -> pass()
                }
            }
            "fileContent" -> {
                val path = params.optString("path")
                val content = view.projectFiles()[path]
                val contains = params.optJSONArray("contains")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val notContains = params.optJSONArray("notContains")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val missing = contains.filter { content?.contains(it) != true }
                val present = notContains.filter { content?.contains(it) == true }
                when {
                    content == null -> fail("项目里没有文件 $path。")
                    missing.isNotEmpty() -> fail("$path 缺少内容：${missing.joinToString()}。")
                    present.isNotEmpty() -> fail("$path 出现了不该有的内容：${present.joinToString()}。")
                    else -> pass()
                }
            }
            "projectContains" -> {
                val files = view.projectFiles()
                val text = if (files.isNotEmpty()) files.entries.joinToString("\n") { "// ${it.key}\n${it.value}" } else view.spec?.toString().orEmpty()
                if (text.isBlank()) return fail("没有可检查的项目内容。")
                val contains = params.optJSONArray("contains")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val notContains = params.optJSONArray("notContains")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val minFiles = params.optInt("minFiles", 0)
                val missing = contains.filter { !text.contains(it) }
                val present = notContains.filter { text.contains(it) }
                val fileCount = if (files.isNotEmpty()) files.size else 1
                when {
                    fileCount < minFiles -> fail("项目只有 $fileCount 个文件，少于期望的 $minFiles 个。")
                    missing.isNotEmpty() -> fail("项目内容缺少：${missing.joinToString()}。")
                    present.isNotEmpty() -> fail("项目内容出现不该有的：${present.joinToString()}。")
                    else -> pass()
                }
            }
            "controls" -> {
                val report = view.verification ?: return fail("artifact 没有 web_verification 事件，控件摘要不可用。")
                val controls = report.optJSONArray("controls")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
                val tags = controls.map { it.optString("tag").lowercase() }
                val tagsAll = params.optJSONArray("tags")?.let { array -> (0 until array.length()).map { array.getString(it).lowercase() } }.orEmpty()
                val tagsAny = params.optJSONArray("tagsAny")?.let { array -> (0 until array.length()).map { array.getString(it).lowercase() } }.orEmpty()
                val minControls = params.optInt("minControls", 0)
                val missingTags = tagsAll.filter { it !in tags }
                when {
                    controls.size < minControls -> fail("页面只有 ${controls.size} 个可交互控件，少于期望的 $minControls 个。")
                    missingTags.isNotEmpty() -> fail("页面缺少控件类型：${missingTags.joinToString()}。")
                    tagsAny.isNotEmpty() && tagsAny.none { it in tags } -> fail("页面缺少任一必需控件类型：${tagsAny.joinToString()}。")
                    else -> pass()
                }
            }
            "boundary" -> {
                val patterns = params.optJSONArray("forbiddenPatterns")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val hits = mutableListOf<String>()
                view.projectFiles().forEach { (name, content) ->
                    patterns.forEach { pattern -> if (runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content) }.getOrDefault(false)) hits += "$name 命中 /$pattern/" }
                }
                val manifest = view.webProject?.optJSONObject("manifest")
                val permissions = manifest?.optJSONArray("permissions")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
                val allowed = params.optJSONArray("permissions")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val network = manifest?.optJSONArray("network")?.length() ?: 0
                val extra = if (allowed.isEmpty()) emptyList() else permissions.filter { it !in allowed }
                when {
                    hits.isNotEmpty() -> fail("越界内容：${hits.take(3).joinToString()}。")
                    params.optBoolean("networkEmpty", true) && manifest != null && network > 0 -> fail("manifest 声明了网络能力。")
                    extra.isNotEmpty() -> fail("manifest 权限超出允许范围：${extra.joinToString()}。")
                    else -> pass()
                }
            }
            "isolation" -> {
                if (baseline == null) return fail("缺少运行前的项目快照，无法判断隔离性。")
                val before = baseline.optJSONObject("projects") ?: JSONObject()
                val current = workspace.projects().associate { it.id to it.updatedAt }
                val created = current.keys.filter { !before.has(it) }
                // 新建用例里 app_result 缺失（例如运行失败）时，只把唯一新建的项目视为目标，其余仍算越权。
                val target = params.optString("projectId").takeIf { it.isNotBlank() }
                    ?: view.appResult?.optString("projectId")?.takeIf { it.isNotBlank() }
                    ?: created.singleOrNull().orEmpty()
                val unexpectedCreated = created.filter { it != target }
                val touched = before.keys().asSequence().filter { before.optLong(it) != current[it] }.toList().filter { it != target }
                when {
                    unexpectedCreated.isNotEmpty() -> fail("创建了非目标项目：${unexpectedCreated.joinToString()}。")
                    touched.isNotEmpty() -> fail("写入了非目标项目：${touched.joinToString()}。")
                    else -> pass()
                }
            }
            "noProject" -> when {
                view.webProject != null -> fail("请求本应不产出项目，但生成了 Web 项目「${view.webProject.optJSONObject("manifest")?.optString("name")}」。")
                view.spec != null -> fail("请求本应不产出项目，但写入了旧格式规格。")
                else -> pass()
            }
            "interaction" -> {
                val report = view.verification ?: return fail("artifact 没有 web_verification 事件，交互未被验证。")
                val assertions = report.optJSONArray("assertions")?.length() ?: 0
                val errors = report.optJSONArray("errors")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val minAssertions = params.optInt("minAssertions", 1)
                when {
                    params.optBoolean("passed", true) && !report.optBoolean("passed") -> fail("预览未通过：${report.optString("reason").ifBlank { "见 artifact 中的断言明细" }}。")
                    assertions < minAssertions -> fail("只有 $assertions 条交互断言，少于期望的 $minAssertions。")
                    params.optBoolean("noErrors", true) && errors.isNotEmpty() -> fail("预览存在错误：${errors.take(2).joinToString()}。")
                    params.optBoolean("noOverflow", true) && report.optBoolean("horizontalOverflow") -> fail("页面存在横向溢出。")
                    else -> pass()
                }
            }
            "modified" -> {
                val snapshot = judgeBaseline ?: baseline ?: return fail("缺少运行前的项目内容快照，无法判断项目是否真的被修改。")
                val before = snapshot.optJSONObject("digests") ?: return fail("运行前快照缺少项目内容摘要，无法判断项目是否真的被修改。")
                val digests = projectDigests(workspace)
                val target = params.optString("projectId").takeIf { it.isNotBlank() }
                    ?: view.appResult?.optString("projectId")?.takeIf { it.isNotBlank() }
                    ?: before.keys().asSequence().firstOrNull { before.optString(it).isNotBlank() && before.optString(it) != digests.optString(it) }
                when {
                    target.isNullOrBlank() -> fail("没有识别到被修改的目标项目。")
                    !digests.has(target) -> fail("目标项目 $target 不存在。")
                    before.optString(target).isBlank() -> fail("目标项目 $target 在运行前不存在，无法判断修改是否发生。")
                    before.optString(target) == digests.optString(target) -> fail("目标项目 $target 的内容与运行前一致，修改没有生效。")
                    else -> pass()
                }
            }
            "tools" -> {
                val lower = view.toolKinds.map { it.removePrefix("tool_") }
                val required = params.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val forbidden = params.optJSONArray("forbidden")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                val missing = required.filter { it !in lower }
                val used = forbidden.filter { it in lower }
                val calls = view.terminal?.optInt("toolCalls", view.toolKinds.size) ?: view.toolKinds.size
                val duplicates = view.terminal?.optInt("duplicateToolCalls", 0) ?: 0
                when {
                    missing.isNotEmpty() -> fail("没有调用必需工具：${missing.joinToString()}。")
                    used.isNotEmpty() -> fail("调用了禁止工具：${used.joinToString()}。")
                    calls < params.optInt("minCalls", 0) -> fail("工具调用 $calls 次，少于期望的 ${params.optInt("minCalls", 0)} 次。")
                    calls > params.optInt("maxCalls", Int.MAX_VALUE) -> fail("工具调用 $calls 次，超过允许的 ${params.optInt("maxCalls", Int.MAX_VALUE)} 次。")
                    duplicates > params.optInt("maxDuplicates", 0) -> fail("重复工具调用 $duplicates 次，超过允许的 ${params.optInt("maxDuplicates", 0)} 次。")
                    else -> pass()
                }
            }
            "budget" -> {
                val attempts = view.terminal?.optInt("turns", view.run?.optInt("modelAttempts", 0) ?: 0) ?: 0
                val calls = view.terminal?.optInt("toolCalls", 0) ?: 0
                val elapsed = view.terminal?.optLong("elapsedMs", 0L) ?: 0L
                val maxTurns = params.optInt("maxModelCalls", 24)
                val maxTools = params.optInt("maxToolCalls", 128)
                val maxElapsed = params.optLong("maxElapsedMs", 15 * 60 * 1000L)
                when {
                    attempts > maxTurns -> fail("模型调用 $attempts 次，超过上限 $maxTurns。")
                    calls > maxTools -> fail("工具调用 $calls 次，超过上限 $maxTools。")
                    elapsed > maxElapsed -> fail("耗时 ${elapsed}ms，超过上限 ${maxElapsed}ms。")
                    else -> pass()
                }
            }
            else -> fail("未知断言类型：${assertion.type}。")
        }
    }

    private fun pass(): Array<Any> = arrayOf(true, "")
    private fun fail(reason: String): Array<Any> = arrayOf(false, reason)
}
