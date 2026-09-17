package com.example.goon.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class UserIntent { CHAT, SEARCH, CREATE_MINI_APP, MODIFY_MINI_APP, INSPECT_MINI_APP, CLARIFY, UNKNOWN }
enum class IntentConfidenceKind { RULE_SIGNAL, MODEL_ESTIMATE, CALIBRATED_PROBABILITY }
enum class IntentRisk { NONE, READ_ONLY, WRITE, DESTRUCTIVE }

/** Cheap, deterministic signals collected before intent understanding. They never grant capability. */
data class IntentPrecheck(
    val inputLength: Int,
    val isEmpty: Boolean = false,
    val unrecognized: Boolean = false,
    val tooLong: Boolean = false,
    val isConsultation: Boolean = false,
    val negatedWrite: Boolean = false,
    val destructive: Boolean = false,
    val promptInjection: Boolean = false,
    val signals: List<String> = emptyList(),
    val blockedReason: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("inputLength", inputLength); put("isEmpty", isEmpty); put("unrecognized", unrecognized); put("tooLong", tooLong)
        put("isConsultation", isConsultation); put("negatedWrite", negatedWrite)
        put("destructive", destructive); put("promptInjection", promptInjection)
        put("signals", JSONArray(signals)); put("blockedReason", blockedReason ?: JSONObject.NULL)
    }
}

object IntentRulePrecheck {
    const val MAX_INPUT_LENGTH = 8_000

    private val consultationPattern = Regex("(怎么|如何|为什么|能不能|可不可以|是否可以|教我|解释|介绍|什么是|只是想问|想了解|原理|how|why|can you explain|is it possible)")
    /**
     * 创建类表达。中文的口语动词是**开放集合**（做 / 搭 / 整 / 搞 / 弄 / 来 / 写…），
     * 早期只认「做一个小程序」这个连写短语，于是「做一个外卖软件」整句漏判成闲聊。
     * 这里作为唯一来源，`IntentRouter` 直接复用，避免两处词表各自漂移。
     */
    val CREATE_EXPRESSIONS = Regex("(创建|新建|建一个|建个|做一个|做个|做一款|搭一个|搭个|搭建|整一个|弄个|弄一个|搞个|搞一个|来一个|来个|写一个|写个|安排一个|开发一个|生成一个|create|build|make)")
    private val writePattern = Regex("(创建|新建|做一个|做个|做一款|搭一个|搭个|搭建|整一个|弄个|搞个|来一个|写一个|开发一个|生成一个|修改|改成|设置为|增加|删除|覆盖|批量替换|批量修改|清空|调整|修复|优化|改一下|create|build|make|edit|update|delete|overwrite|bulk replace)")
    private val destructivePattern = Regex("(删除|覆盖|批量替换|批量修改|清空|删除项目|overwrite|bulk replace|wipe|remove all)")
    /**
     * 只是"提到"破坏性动作、并不是要执行它的写法。
     *
     * 为什么需要：用户说「创建一个清单，**可以**添加、勾选**和删除物品**」时，破坏性词是**功能描述**，
     * 不是要删东西。旧实现只看词表，于是这类创建请求被判成 DESTRUCTIVE 并直接弹澄清——实测
     * 评测用例 `create-checklist` 连续三次都在 1.5 秒内失败，一次都没真正执行（见
     * `docs/problems_and_solutions.md` 待处理第 1 条）。
     *
     * 判据两条，命中任一即视为"描述功能"：
     * 1. 前面紧邻能力/并列标记（可以、能、支持、包含、以及、和、/、…）——是在列举功能；
     * 2. 宾语是内容条目（物品、条目、记录、卡片、任务、数据项、内容）而不是项目本身——删的是数据项，不是应用。
     */
    private val destructiveAsFeature = Regex(
        "(可以|可|能|支持|包含|带有|允许|以及|和|与|、|/)\\s*[^，。；;]{0,6}(删除|清空|移除|覆盖|delete|remove)" +
            "|(删除|清空|移除|移除掉|remove|delete)[^，。；;]{0,4}(物品|条目|记录|卡片|任务|数据项|内容|列表项|待办)"
    )

    /**
     * 功能清单写法：能力标记后面跟着动作动词（「可以添加、勾选和删除」「支持新增与删除」）。
     *
     * 这类句子在描述"这个应用能做什么"，不是在命令宿主去改一个已存在的项目。旧实现会让
     * `删除` 同时命中「修改」词表，于是**一个纯创建请求被算成"创建 + 修改"的混合任务**，
     * 策略层据此要求用户确认执行顺序和范围——用户看到的是一句莫名其妙的澄清。
     */
    val featureListing = Regex(
        "(可以|可|能|支持|包含|带有|允许|以及|和|与|、|/)\\s*[^，。；;]{0,8}(删除|清空|移除|增加|新增|添加|修改|调整|覆盖|删除项)"
    )
    private val negatedPattern = Regex("(不想|不要|不用|无需|只是想问|仅仅想了解|别|不要真的)\\s*(帮我|让我)?\\s*(创建|新建|修改|改成|删除|覆盖|批量替换|批量修改|清空|增加|调整|修复|优化|改一下|create|edit|update|overwrite|delete)")
    private val injectionPattern = Regex("(ignore\\s+(all\\s+|any\\s+|previous\\s+|above\\s+)?(instructions?|rules?)|忽略(之前|上面|先前).*(指令|规则)|(?:绕过|跳过).*(确认|审批|policy|安全)|(?:system\\s+prompt|系统提示词).*(泄露|显示|输出|告诉|reveal|show|print)|(?:api\\s*key|token|cookie|密钥).*(读取|泄露|显示|输出|告诉|give me)|(?:读取|泄露|显示|输出|告诉|give me|reveal).*(?:api\\s*key|token|cookie|密钥|secret))")

    fun inspect(prompt: String): IntentPrecheck {
        val raw = prompt.trim()
        val text = raw.lowercase(Locale.ROOT)
        val isEmpty = text.isBlank()
        val unrecognized = raw.isNotBlank() && raw.none { it.isLetterOrDigit() }
        val tooLong = raw.length > MAX_INPUT_LENGTH
        val isConsultation = consultationPattern.containsMatchIn(text)
        val negatedWrite = negatedPattern.containsMatchIn(text)
        // 提到破坏性词 ≠ 要执行破坏性操作。只有**不是功能描述**时才算真破坏性。
        val destructive = destructivePattern.containsMatchIn(text) && !destructiveAsFeature.containsMatchIn(text)
        val destructiveMentioned = destructivePattern.containsMatchIn(text)
        val promptInjection = injectionPattern.containsMatchIn(text)
        val signals = buildList {
            if (isEmpty) add("empty_input")
            if (unrecognized) add("unrecognized_input")
            if (tooLong) add("input_too_long")
            if (isConsultation) add("consultation_language")
            if (negatedWrite) add("negated_write")
            if (destructive) add("destructive_action")
            if (destructiveMentioned && !destructive) add("destructive_wording_as_feature")
            if (promptInjection) add("prompt_injection")
            if (writePattern.containsMatchIn(text)) add("write_language")
        }
        val blockedReason = when {
            isEmpty -> "请求为空"
            unrecognized -> "请求无法理解"
            tooLong -> "请求超过 ${MAX_INPUT_LENGTH} 个字符"
            promptInjection -> "请求包含可能绕过安全边界或泄露敏感信息的指令"
            else -> null
        }
        return IntentPrecheck(raw.length, isEmpty, unrecognized, tooLong, isConsultation, negatedWrite, destructive, promptInjection, signals, blockedReason)
    }
}

data class IntentConfirmation(
    val action: String,
    val target: String,
    val risk: IntentRisk,
    val consequence: String,
    val requiredConfirmation: Boolean = true
) {
    fun toJson() = JSONObject().apply {
        put("action", action); put("target", target); put("risk", risk.name)
        put("consequence", consequence); put("requiredConfirmation", requiredConfirmation)
    }
}

data class IntentTask(
    val intent: UserIntent,
    val entities: Map<String, String> = emptyMap(),
    val negated: Boolean = false,
    val requiresWrite: Boolean = false,
    val risk: IntentRisk = IntentRisk.NONE,
    val evidence: String = ""
)

data class IntentDecision(
    val intent: UserIntent,
    val confidence: Double,
    val entities: Map<String, String> = emptyMap(),
    val reason: String,
    val tasks: List<IntentTask> = emptyList(),
    val ambiguities: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val requiresWrite: Boolean = false,
    val confidenceKind: IntentConfidenceKind = IntentConfidenceKind.RULE_SIGNAL,
    val isConsultation: Boolean = false,
    val precheck: IntentPrecheck = IntentPrecheck(0)
) {
    fun toJson() = JSONObject().apply {
        put("intent", intent.name); put("confidence", confidence); put("confidenceKind", confidenceKind.name)
        put("entities", JSONObject(entities)); put("reason", reason); put("requiresConfirmation", requiresConfirmation); put("requiresWrite", requiresWrite)
        put("isConsultation", isConsultation); put("precheck", precheck.toJson())
        put("ambiguities", JSONArray(ambiguities)); put("tasks", JSONArray().apply { tasks.forEach { task -> put(JSONObject().apply { put("intent", task.intent.name); put("entities", JSONObject(task.entities)); put("negated", task.negated); put("requiresWrite", task.requiresWrite); put("risk", task.risk.name); put("evidence", task.evidence) }) } })
    }.toString()
}

object IntentRouter {
    fun classify(prompt: String, createMode: Boolean = false, projectId: String? = null): IntentDecision {
        val raw = prompt.trim()
        val text = raw.lowercase(Locale.ROOT)
        val precheck = IntentRulePrecheck.inspect(raw)
        fun normalized(decision: IntentDecision) = decision.copy(precheck = precheck, isConsultation = precheck.isConsultation)
        if (precheck.isEmpty || precheck.unrecognized) return normalized(IntentDecision(UserIntent.UNKNOWN, 0.0, reason = precheck.blockedReason ?: "请求无法理解", ambiguities = listOf("缺少可理解的用户目标")))
        if (precheck.tooLong) return normalized(IntentDecision(UserIntent.CLARIFY, 0.0, reason = precheck.blockedReason ?: "请求过长", ambiguities = listOf("input_too_long")))
        if (precheck.promptInjection) return normalized(IntentDecision(UserIntent.CLARIFY, 0.1, reason = precheck.blockedReason ?: "请求不安全", ambiguities = listOf("safety_injection"), requiresConfirmation = true))
        val question = precheck.isConsultation
        val executionQuestion = Regex("(能不能|可不可以|是否可以|可以.*(创建|新建|修改|改成|删除|增加|调整|修复|优化)|can you (create|edit|update|delete))").containsMatchIn(text)
        val negatedWrite = precheck.negatedWrite
        val create = IntentRulePrecheck.CREATE_EXPRESSIONS.containsMatchIn(text)
        val modifyWords = listOf("修改", "改成", "设置为", "增加", "删除", "覆盖", "批量替换", "批量修改", "清空", "调整", "修复", "优化", "改一下", "edit", "update", "delete", "overwrite", "bulk replace")
        // 纯创建请求 + 没有目标项目 + 动作词出现在功能清单里 ⇒ 这些动词是在描述应用能力，
        // 不该被算成一个"要修改某个项目"的任务（否则会触发"混合任务"澄清，把创建请求挡在门外）。
        val featureWordingOnly = create && projectId == null && IntentRulePrecheck.featureListing.containsMatchIn(text)
        val modify = modifyWords.any(text::contains) && !featureWordingOnly
        val inspect = listOf("运行", "预览", "检查", "测试", "打开", "看看", "有问题", "inspect", "preview").any(text::contains)
        val search = listOf("搜索", "查一下", "查找", "查一查", "最新", "新闻", "资料", "官网", "价格", "政策", "search", "latest", "news").any(text::contains)
        val destructive = precheck.destructive
        val tasks = mutableListOf<IntentTask>()
        val contentEntities = extractContentEntities(raw)
        if (search && !negatedWrite) tasks += IntentTask(UserIntent.SEARCH, evidence = "出现联网资料请求")
        if (create && !negatedWrite) tasks += IntentTask(UserIntent.CREATE_MINI_APP, entities = contentEntities, requiresWrite = true, risk = IntentRisk.WRITE, evidence = "出现创建请求")
        if (modify && !negatedWrite && !question) tasks += IntentTask(UserIntent.MODIFY_MINI_APP, entities = contentEntities + (projectId?.let { mapOf("projectId" to it) } ?: emptyMap()), requiresWrite = true, risk = if (destructive) IntentRisk.DESTRUCTIVE else IntentRisk.WRITE, evidence = if (destructive) "出现破坏性修改请求" else "出现执行修改请求")
        if (inspect && !question && (projectId != null || !search)) tasks += IntentTask(UserIntent.INSPECT_MINI_APP, projectId?.let { mapOf("projectId" to it) } ?: emptyMap(), risk = IntentRisk.READ_ONLY, evidence = "出现运行或检查请求")
        if (negatedWrite) return normalized(IntentDecision(UserIntent.CHAT, 0.88, reason = "检测到否定表达，禁止把关键词当作写入授权", tasks = listOf(IntentTask(UserIntent.CHAT, negated = true, evidence = "否定句"))))
        if (createMode) return normalized(IntentDecision(UserIntent.CREATE_MINI_APP, 0.99, reason = "用户通过新建入口明确授权", tasks = (tasks + IntentTask(UserIntent.CREATE_MINI_APP, requiresWrite = true, risk = IntentRisk.WRITE, evidence = "显式入口")).distinctBy { it.intent }, requiresWrite = true))
        if (executionQuestion && (create || modify)) {
            val requested = (tasks + if (modify) IntentTask(UserIntent.MODIFY_MINI_APP, projectId?.let { mapOf("projectId" to it) } ?: emptyMap(), requiresWrite = true, risk = IntentRisk.WRITE, evidence = "疑问句中包含修改动作") else IntentTask(UserIntent.CREATE_MINI_APP, requiresWrite = true, risk = IntentRisk.WRITE, evidence = "疑问句中包含创建动作")).distinctBy { it.intent }
            return normalized(IntentDecision(if (modify) UserIntent.MODIFY_MINI_APP else UserIntent.CREATE_MINI_APP, 0.62, entities = requested.flatMap { it.entities.entries }.associate { it.key to it.value }, reason = "请求同时表达疑问和执行动作", tasks = requested, ambiguities = listOf("这是咨询能力，还是要实际执行修改"), requiresConfirmation = true, requiresWrite = true))
        }
        if (question) return normalized(IntentDecision(UserIntent.CHAT, 0.88, reason = "请求是咨询/解释，不是执行授权", tasks = listOf(IntentTask(UserIntent.CHAT, evidence = "疑问句"))) )
        if (tasks.any { it.intent == UserIntent.CREATE_MINI_APP } && projectId == null) {
            return normalized(IntentDecision(UserIntent.CREATE_MINI_APP, 0.74, reason = "创建小程序需要用户明确进入创建工作流", tasks = tasks, ambiguities = listOf("是否要进入新建小程序工作流"), requiresConfirmation = true, requiresWrite = true))
        }
        if (tasks.any { it.intent == UserIntent.MODIFY_MINI_APP } && projectId == null) {
            return normalized(IntentDecision(UserIntent.MODIFY_MINI_APP, 0.74, reason = "修改请求缺少目标项目", tasks = tasks, ambiguities = listOf("要修改哪个小程序"), requiresConfirmation = true, requiresWrite = true))
        }
        if (tasks.size > 1 && tasks.any { it.requiresWrite }) {
            return normalized(IntentDecision(UserIntent.CLARIFY, 0.68, entities = tasks.flatMap { it.entities.entries }.associate { it.key to it.value }, reason = "请求包含多个动作且至少一个会写入项目", tasks = tasks, ambiguities = listOf("需要确认多个动作的执行顺序和写入范围"), requiresConfirmation = true, requiresWrite = true))
        }
        val primary = tasks.firstOrNull { it.intent == UserIntent.MODIFY_MINI_APP }?.intent
            ?: tasks.firstOrNull { it.intent == UserIntent.INSPECT_MINI_APP }?.intent
            ?: tasks.firstOrNull()?.intent
        if (primary != null) return normalized(IntentDecision(primary, 0.84, tasks = tasks, entities = tasks.flatMap { it.entities.entries }.associate { it.key to it.value }, reason = "规则与入口上下文共同判断", requiresWrite = tasks.any { it.requiresWrite }))
        return normalized(IntentDecision(UserIntent.CHAT, 0.72, reason = "未发现需要工具的明确动作", tasks = listOf(IntentTask(UserIntent.CHAT, evidence = "默认信息回答"))))
    }

    private fun extractContentEntities(prompt: String): Map<String, String> {
        val value = Regex("(?:标题|名称|命名|改成|设置为|叫做)\\s*[:：]?\\s*[‘'「『\\\"“]?([^，。！？!?\\\"”」』']{1,80})").find(prompt)?.groupValues?.get(1)?.trim().orEmpty()
        return if (value.isBlank()) emptyMap() else mapOf("value" to value, "title" to value)
    }
}

enum class IntentRoute { CHAT, SEARCH, WEB_AGENT, LEGACY_AGENT, INSPECT, CLARIFY }

data class ClarifyPayload(
    val question: String,
    val missing: List<String> = emptyList(),
    val action: String? = null,
    val target: String? = null,
    val risk: IntentRisk = IntentRisk.NONE,
    val consequence: String = "",
    val requiredConfirmation: Boolean = false,
    val canResume: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("question", question); put("missing", JSONArray(missing)); put("action", action ?: JSONObject.NULL)
        put("target", target ?: JSONObject.NULL); put("risk", risk.name); put("consequence", consequence)
        put("requiredConfirmation", requiredConfirmation); put("canResume", canResume)
    }
}

data class IntentPolicyDecision(
    val route: IntentRoute,
    val reason: String,
    val safeProjectId: String? = null,
    val confirmation: IntentConfirmation? = null,
    val clarify: ClarifyPayload? = null
) {
    fun toJson() = JSONObject().apply {
        put("route", route.name); put("reason", reason); put("safeProjectId", safeProjectId ?: JSONObject.NULL)
        put("confirmation", confirmation?.toJson() ?: JSONObject.NULL); put("clarify", clarify?.toJson() ?: JSONObject.NULL)
    }.toString()
}

/** Intent understands a request; policy decides which capabilities it may actually reach. */
object IntentPolicy {
    fun evaluate(decision: IntentDecision, createMode: Boolean, projectId: String?, projectExists: Boolean, isWebProject: Boolean): IntentPolicyDecision {
        fun clarify(reason: String, safeProjectId: String? = projectId.takeIf { projectExists }) = IntentPolicyDecision(
            IntentRoute.CLARIFY,
            reason,
            safeProjectId,
            confirmationFor(decision, projectId),
            clarifyFor(decision, projectId, projectExists, createMode, reason)
        )
        // Policy is the final hard gate. The main intent can never override ambiguity or risk.
        val entityKeys = decision.entities.keys + decision.tasks.flatMap { it.entities.keys }
        val unexpectedEntities = entityKeys - setOf("projectId", "title", "value")
        if (unexpectedEntities.isNotEmpty()) return clarify("理解结果包含未允许的实体：${unexpectedEntities.sorted().joinToString()}。")
        val proposedProjectIds = (listOfNotNull(decision.entities["projectId"]) + decision.tasks.mapNotNull { it.entities["projectId"] }).distinct()
        if (proposedProjectIds.any { it != projectId || !projectExists }) return clarify("理解结果中的项目标识未通过 Workspace 校验。", null)
        if (decision.ambiguities.isNotEmpty()) return clarify(decision.ambiguities.joinToString("；"))
        if (decision.requiresConfirmation) return clarify("该请求需要明确确认后才能执行。")
        val destructiveTask = decision.tasks.firstOrNull { it.risk == IntentRisk.DESTRUCTIVE }
        if (destructiveTask != null) return clarify("删除或破坏性操作需要单独确认。")
        val riskyTask = decision.tasks.firstOrNull { it.requiresWrite || it.intent == UserIntent.CREATE_MINI_APP || it.intent == UserIntent.MODIFY_MINI_APP }
        if (riskyTask != null && !createMode && (projectId == null || !projectExists)) {
            // 创建与修改要分开说：对创建请求讲「缺少目标项目」会让用户以为要先去挑一个已有项目。
            return clarify(
                if (riskyTask.intent == UserIntent.CREATE_MINI_APP) "创建小程序需要通过「新建」入口明确授权。" else "写入操作缺少明确目标项目。",
                null
            )
        }
        if (createMode && decision.tasks.size > 1 && decision.tasks.any { it.requiresWrite } && decision.tasks.any { it.intent != UserIntent.CREATE_MINI_APP }) {
            return clarify("显式创建入口不能替混合任务确认执行顺序和范围。")
        }
        if (decision.intent == UserIntent.CHAT || decision.intent == UserIntent.SEARCH || decision.intent == UserIntent.INSPECT_MINI_APP) {
            if (decision.tasks.any { it.requiresWrite || it.risk == IntentRisk.DESTRUCTIVE || it.intent == UserIntent.CREATE_MINI_APP || it.intent == UserIntent.MODIFY_MINI_APP }) return clarify("信息、搜索或检查请求不能同时获得项目写权限。")
        }
        return when (decision.intent) {
            UserIntent.CREATE_MINI_APP -> if (createMode) IntentPolicyDecision(IntentRoute.WEB_AGENT, "显式新建入口授权写入") else clarify("创建项目需要通过新建入口明确授权", null)
            UserIntent.MODIFY_MINI_APP -> if (projectId != null && projectExists && decision.requiresWrite) IntentPolicyDecision(if (isWebProject) IntentRoute.WEB_AGENT else IntentRoute.LEGACY_AGENT, "已验证目标项目且请求明确写入", projectId) else clarify("修改项目需要已验证的目标项目和写入授权", null)
            UserIntent.INSPECT_MINI_APP -> if (projectId != null && projectExists) IntentPolicyDecision(IntentRoute.INSPECT, "已验证项目，保持只读检查", projectId) else clarify("检查项目需要已验证的目标项目", null)
            UserIntent.SEARCH -> IntentPolicyDecision(IntentRoute.SEARCH, "请求明确需要外部资料")
            UserIntent.CLARIFY, UserIntent.UNKNOWN -> clarify(decision.reason.ifBlank { "需要补充任务目标" }, null)
            UserIntent.CHAT -> IntentPolicyDecision(IntentRoute.CHAT, "信息咨询不授予项目写权限")
        }
    }

    private fun clarifyFor(decision: IntentDecision, projectId: String?, projectExists: Boolean, createMode: Boolean, reason: String): ClarifyPayload {
        val task = decision.tasks.firstOrNull { it.risk == IntentRisk.DESTRUCTIVE || it.requiresWrite || it.intent == UserIntent.CREATE_MINI_APP || it.intent == UserIntent.MODIFY_MINI_APP }
        val confirmation = confirmationFor(decision, projectId)
        val missing = buildList {
            if (decision.precheck.tooLong) add("input_length")
            if (decision.precheck.promptInjection) add("safety_boundary")
            if (task?.requiresWrite == true && !createMode && (projectId == null || !projectExists)) add("projectId")
            if (decision.ambiguities.isNotEmpty()) addAll(decision.ambiguities)
        }.distinct()
        val question = when {
            decision.precheck.tooLong -> "请缩短请求，只保留目标、范围和必要约束。"
            decision.precheck.promptInjection -> "请重新描述用户目标，不要要求绕过安全边界或泄露凭据。"
            task?.intent == UserIntent.CREATE_MINI_APP && !createMode -> "这看起来是要做一个小程序。请用输入框左侧「+」→「新建小程序」发起，我会按你的需求生成完整界面；如果只是想了解做法，也可以直接说明。"
            task?.requiresWrite == true && !createMode && (projectId == null || !projectExists) -> "你要修改哪个已存在的小程序？"
            decision.ambiguities.isNotEmpty() -> decision.ambiguities.first()
            else -> reason
        }
        return ClarifyPayload(question, missing, confirmation?.action, confirmation?.target, confirmation?.risk ?: IntentRisk.NONE, confirmation?.consequence ?: reason, confirmation?.requiredConfirmation == true, canResume = false)
    }

    private fun confirmationFor(decision: IntentDecision, projectId: String?): IntentConfirmation? {
        val task = decision.tasks.firstOrNull { it.risk == IntentRisk.DESTRUCTIVE || it.requiresWrite || it.intent == UserIntent.CREATE_MINI_APP || it.intent == UserIntent.MODIFY_MINI_APP } ?: return null
        val action = when (task.risk) {
            IntentRisk.DESTRUCTIVE -> if (task.evidence.contains("删除")) "delete_project" else "destructive_project_action"
            else -> when (task.intent) {
                UserIntent.CREATE_MINI_APP -> "create_project"
                UserIntent.MODIFY_MINI_APP -> "modify_project"
                else -> "write_project"
            }
        }
        val target = task.entities["projectId"] ?: projectId ?: "当前小程序"
        val consequence = if (task.risk == IntentRisk.DESTRUCTIVE) "可能删除或破坏目标项目及其可用内容。" else "将修改目标项目并产生新的版本。"
        return IntentConfirmation(action, target, task.risk, consequence)
    }
}

data class IntentToolValidation(val allowed: Boolean, val reason: String, val route: IntentRoute, val tool: String) {
    fun toJson() = JSONObject().put("allowed", allowed).put("reason", reason).put("route", route.name).put("tool", tool)
}

/** Final route/tool boundary. It is intentionally stricter than intent understanding. */
object IntentToolValidator {
    fun validateRoute(route: IntentRoute, createMode: Boolean, projectId: String?, projectExists: Boolean, isWebProject: Boolean): IntentToolValidation {
        val tool = when (route) {
            IntentRoute.WEB_AGENT -> "web_agent"
            IntentRoute.LEGACY_AGENT -> "legacy_agent"
            IntentRoute.INSPECT -> "inspection"
            IntentRoute.SEARCH -> "search"
            IntentRoute.CHAT -> "chat"
            IntentRoute.CLARIFY -> "clarify"
        }
        val allowed = when (route) {
            IntentRoute.WEB_AGENT -> createMode || (projectId != null && projectExists && isWebProject)
            IntentRoute.LEGACY_AGENT -> !createMode && projectId != null && projectExists && !isWebProject
            IntentRoute.INSPECT -> projectId != null && projectExists
            IntentRoute.CHAT, IntentRoute.SEARCH, IntentRoute.CLARIFY -> true
        }
        val reason = if (allowed) "route and project capability validated" else "route, project, or runtime capability did not pass the tool gate"
        return IntentToolValidation(allowed, reason, route, tool)
    }
}

data class RagDocument(val id: String, val title: String, val text: String, val source: String)
data class RagHit(val document: RagDocument, val score: Double, val matchedTerms: List<String>) {
    fun toJson() = JSONObject().put("id", document.id).put("title", document.title).put("source", document.source).put("score", score).put("matchedTerms", JSONArray(matchedTerms))
}

/** Small deterministic lexical RAG index. It is a replaceable boundary for embeddings later. */
class RagIndex(documents: List<RagDocument>) {
    private val docs = documents
    fun retrieve(query: String, limit: Int = 5): List<RagHit> {
        val terms = tokenize(query).toSet()
        if (terms.isEmpty()) return emptyList()
        return docs.mapNotNull { document ->
            val haystack = tokenize("${document.title} ${document.text}").toSet()
            val matches = terms.intersect(haystack).toList()
            if (matches.isEmpty()) null else RagHit(document, matches.size.toDouble() / terms.size, matches.take(12))
        }.sortedByDescending { it.score }.take(limit.coerceIn(1, 8))
    }
    private fun tokenize(value: String): List<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()
    companion object {
        fun builtIn(): RagIndex = RagIndex(BuiltInSkills.allDocuments())
    }
}

data class McpToolDescriptor(val name: String, val description: String, val inputSchema: JSONObject)
data class McpServerManifest(val name: String, val version: String, val tools: List<McpToolDescriptor>, val resources: List<String> = emptyList(), val prompts: List<String> = emptyList()) {
    fun toJson() = JSONObject().put("name", name).put("version", version).put("tools", JSONArray().apply { tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema)) } }).put("resources", JSONArray(resources)).put("prompts", JSONArray(prompts))
}
data class McpToolResult(val content: JSONObject, val isError: Boolean = false)

/** MCP-shaped local registry: transport can be replaced by stdio/HTTP without changing tools. */
class McpToolRegistry(private val descriptors: List<McpToolDescriptor>, private val handlers: Map<String, (JSONObject) -> McpToolResult>) {
    fun listTools(): List<McpToolDescriptor> = descriptors
    fun call(name: String, input: JSONObject): McpToolResult = handlers[name]?.invoke(input) ?: McpToolResult(JSONObject().put("error", "未知 MCP 工具：$name"), true)
    fun manifest() = McpServerManifest("goon-local", "1.0", descriptors, resources = listOf("goon://skills", "goon://conversation/context"), prompts = listOf("mini-app-build", "research-with-citations"))
}

enum class GraphNode { CLASSIFY_INTENT, RETRIEVE_CONTEXT, PLAN, ACT, OBSERVE, VERIFY, HUMAN_INPUT, COMPLETE, FAILED, CANCELLED }
data class GraphState(val runId: String, val threadId: String, val node: GraphNode, val turn: Int = 0, val data: Map<String, String> = emptyMap())
data class GraphTransition(val from: GraphNode, val to: GraphNode, val reason: String)

/** LangGraph-style state machine with explicit nodes and checkpointable transitions. */
class AgentGraph(private var state: GraphState) {
    private val transitions = mutableListOf<GraphTransition>()
    fun state(): GraphState = state
    fun move(to: GraphNode, reason: String): GraphState {
        transitions += GraphTransition(state.node, to, reason)
        state = state.copy(node = to, turn = state.turn + if (to == GraphNode.ACT) 1 else 0)
        return state
    }
    fun transitions(): List<GraphTransition> = transitions.toList()
    fun checkpoint() = JSONObject().put("runId", state.runId).put("threadId", state.threadId).put("node", state.node.name).put("turn", state.turn).put("data", JSONObject(state.data)).put("transitions", JSONArray().apply { transitions.forEach { put(JSONObject().put("from", it.from.name).put("to", it.to.name).put("reason", it.reason)) } })
    companion object { fun start(runId: String, threadId: String) = AgentGraph(GraphState(runId, threadId, GraphNode.CLASSIFY_INTENT)) }
}
