package com.example.goon.core

import org.json.JSONArray
import org.json.JSONObject

enum class AgentDelivery { STEER, FOLLOW_UP }

/**
 * 当前模型的可用输入窗口（token）。
 *
 * 只用于**给人看**的分母（App 脚注里的「上下文 x / y」）和解释压缩闸门的位置，不参与决策——
 * 决策用的是 [AgentLoopBudget.compactAtTokens] 与 [AgentLoopBudget.maxContextTokens]。
 * 换模型时三个数一起改（`deepseek-flash` 是 128k）。
 */
const val MODEL_CONTEXT_WINDOW_TOKENS = 128_000

enum class AgentTerminalReason {
    COMPLETED, CANCELLED, MODEL_ERROR, TOOL_ERROR, BUDGET_EXCEEDED,
    TIMEOUT, CONTEXT_LIMIT, LOOP_DETECTED, VERSION_CONFLICT, USER_STOPPED, UNKNOWN,

    /**
     * 降级交付：任务没跑到"完全验证通过"，但**已经有可用的产物**，于是交付它并把未完成的部分说清楚。
     *
     * 为什么需要：实测 4 次失败里有 2 次是撞轮次预算、2 次是单次输出被截断，**两次都把已写好的文件
     * 一起扔掉了**——用户拿到的是一句"草稿已保留"，而不是一个能打开的东西。能力上限（pass@3=100%）
     * 已经证明做得出，缺的就是这条"兜底交付"的路。
     */
    DEGRADED_DELIVERY
}

data class AgentLoopBudget(
    /**
     * 轮次上限。**按任务规模分档，并且可以在有进展时弹性延长**（见 [TaskScale] 与 [extensionTurns]）。
     *
     * 演进过程：24 → 40 → 分档。前两次都是"整体抬上限"，但实测暴露出两个问题：
     * ① 「改一个颜色」和「从零写 6 个文件」共用同一个数字——前者永远用不完，后者常常不够；
     * ② 复测里新建类任务耗时 513–648 秒，已经贴到 15 分钟的时间预算上限，再抬就有长尾失控风险。
     * 所以改成：**基础额度看规模，超出部分看进展**——有实际改动就延长，停滞就按原额度收口。
     */
    var maxTurns: Int = 40,
    val maxToolsPerTurn: Int = 16,
    var maxToolCalls: Int = 128,
    /**
     * 上下文预算换成 **token 口径**。
     *
     * 原来按字符数封顶（500k 字符）有两个问题：① 中英文混排时 500k 字符对应的 token 数能差一倍，
     * 封顶线其实是浮动的；② 撞线即终止，而那时往往只是**历史太长**，任务本身还好好的。
     * 现在分两档：超过 [compactAtTokens] 先压缩历史再继续，只有压缩后仍超过 [maxContextTokens] 才终止。
     *
     * 数值取自 provider 的可用输入窗口（128k）留出输出与工具结果的余量；换模型时改这两个常量即可。
     *
     * 闸门比的是 **provider 实际计费的输入 token**，[ContextTokens] 只是它还没回报时的兜底：
     * 决定"会不会撑爆窗口"的是对方收到的 token，不是本地按字符估出来的数。实测中本地估算会低估
     * 1.5–3 倍（见 docs/problems_and_solutions.md 第 37 条），只靠估算的闸门等于没有闸门。
     */
    val compactAtTokens: Int = 60_000,
    val maxContextTokens: Int = 110_000,
    var maxElapsedMs: Long = 15 * 60 * 1000L,
    val maxDuplicateToolCalls: Int = 3,
    /** 最多延长几次。每次 +[extensionTurns] 轮 / +[extensionMs]。 */
    val maxExtensions: Int = 2,
    val extensionTurns: Int = 8,
    val extensionMs: Long = 4 * 60 * 1000L
) {
    /**
     * 按任务规模给基础额度。
     *
     * 依据是实测分布（`docs/eval_report_final.md`）：
     * - **新建**：从零写多个文件 + 预览 + 修复，实测 178–648 秒、多次逼近 40 轮 → 给最大基础额度；
     * - **修改**：在已有项目上改样式/文案，实测 300–391 秒且 3/3 通过、从未撞上限 → 中等额度即可；
     * - **轻量**：拒绝类/边界类（说明能力边界、不需要写文件）→ 小额度，撞上就该收口。
     */
    fun forScale(scale: TaskScale): AgentLoopBudget = when (scale) {
        TaskScale.CREATE -> copy(maxTurns = 32, maxElapsedMs = 12 * 60 * 1000L, maxToolCalls = 160)
        TaskScale.MODIFY -> copy(maxTurns = 20, maxElapsedMs = 8 * 60 * 1000L, maxToolCalls = 96)
    }
}

/** 任务规模。判据必须是**起跑前就能确定**的，不能指望模型自报。 */
enum class TaskScale {
    /** 新建项目：`original == null`。 */
    CREATE,

    /** 在已有项目上修改。 */
    MODIFY
}

/**
 * 粗略 token 估算。
 *
 * 为什么不用真 tokenizer：那需要按模型加载词表（体积与依赖都不划算）。但"粗略"不等于可以拍脑袋——
 * 这里的系数是**拿真 provider 量出来的**（`deepseek-flash`，2026-09，方法见 docs/problems_and_solutions.md 第 37 条）：
 *
 * | 内容 | 实测 chars/token | 说明 |
 * | --- | --- | --- |
 * | 通顺中文 | 1.7–1.8 | 常用词能合并 |
 * | **随机中文** | **0.67** | 不可合并，1 字 ≈ 1.5 token |
 * | 英文散文 | 5.7 | |
 * | 真实小程序 HTML/JS | 2.8 | 标签、类名、十六进制色值密集 |
 *
 * 所以旧系数（中日韩 1 token/字、其余 0.25 token/字符）两头都不对：中文**高估**，代码**低估**，
 * 而后者才是这个项目的上下文大头。现在改成对两类都取偏保守（宁可高估）的一侧：
 * 中日韩 1 token/字，其余 0.3 token/字符（≈ 3.3 chars/token）。
 *
 * 这组系数**只是起点，不是答案**：真实报文（JSON 转义过的代码 + 审计 JSON + 中文混排）实测只有
 * 1.24–1.45 chars/token，比上表任何一类都密，代码密集时会低估一倍以上。因此调用方会拿 provider
 * 的真实回报做**运行时自校准**（[WebAgentRun] 的 `calibration`），这里的常数只负责给出第一个量级。
 */
object ContextTokens {
    private const val MESSAGE_OVERHEAD = 6

    fun of(text: String): Int {
        var cjk = 0
        for (char in text) {
            val code = char.code
            // 0x3000–0x303F 的中文标点与 0xFF00–0xFFEF 的全角字符（，。；：（）等）同样按 1 token 计：
            // 它们在中英混排里占比不小，按 ASCII 折算（1/4 token）会低估整段预算。
            if (code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF) cjk++
        }
        // 其余字符按 0.3 token/字符计（整数运算，避免浮点误差）：真实 HTML/JS 实测 2.8 chars/token，
        // 取 3.3 是故意的保守侧——低估会漏掉压缩时机，高估只是提前压缩一次历史。
        return cjk + (text.length - cjk) * 3 / 10
    }

    /**
     * 整条请求的估算，必须和真正发出去的东西一一对应。
     *
     * 只算 messages 是不够的：system 提示词与 tools schema 每轮都随请求重发，实测约 2–3k token/次，
     * 在 40 轮的任务里就是十万量级。把它们一起算进来，估算口径才和 provider 的 prompt_tokens 对齐。
     */
    fun of(messages: JSONArray, system: String = "", tools: JSONArray? = null): Int {
        var total = if (system.isEmpty()) 0 else of(system)
        if (tools != null) total += of(tools.toString())
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            total += MESSAGE_OVERHEAD
            total += of(message.optString("content"))
            message.optJSONArray("tool_calls")?.let { calls ->
                for (callIndex in 0 until calls.length()) {
                    val function = calls.optJSONObject(callIndex)?.optJSONObject("function") ?: continue
                    total += of(function.optString("name")) + of(function.optString("arguments"))
                }
            }
        }
        return total
    }
}

/**
 * 历史压缩的纯逻辑部分（不依赖运行状态，便于单独验证）。
 *
 * 拆出来的唯一原因：这里有一条**静默失败**的约束——assistant 的 `tool_calls` 与随后那几条
 * `role=tool` 的结果必须同生共死。只删一边，下一次请求会被 provider 判为非法对话，报错还发生在
 * 远端、极难定位。把它做成纯函数 + [pairingOk] 断言，就能在真机上先验一遍再交给模型。
 */
object HistoryCompactor {
    /** 按「交换」切分：一条 assistant（可能带 tool_calls）+ 紧随其后的所有 tool 消息算一个整体。 */
    fun group(messages: JSONArray): MutableList<MutableList<JSONObject>> {
        val groups = mutableListOf<MutableList<JSONObject>>()
        var index = 0
        while (index < messages.length()) {
            val message = messages.optJSONObject(index) ?: run { index++; null } ?: continue
            val group = mutableListOf(message)
            val hasToolCalls = (message.optJSONArray("tool_calls")?.length() ?: 0) > 0
            index++
            if (hasToolCalls) {
                while (index < messages.length() && messages.optJSONObject(index)?.optString("role") == "tool") {
                    messages.optJSONObject(index)?.let { group += it }
                    index++
                }
            }
            groups += group
        }
        return groups
    }

    /** 保留最后 [keepLast] 个交换，前面的一律换成一条笔记消息。 */
    fun rebuild(groups: List<List<JSONObject>>, keepLast: Int, notes: String): JSONArray {
        val rebuilt = JSONArray()
        rebuilt.put(JSONObject().put("role", "user").put("content", notes))
        groups.takeLast(keepLast).forEach { group -> group.forEach { rebuilt.put(it) } }
        return rebuilt
    }

    /** 不变量：每条 `role=tool` 之前，必须存在一条带同 id 的 tool_calls。 */
    fun pairingOk(messages: JSONArray): Boolean {
        val open = mutableSetOf<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            message.optJSONArray("tool_calls")?.let { calls ->
                for (callIndex in 0 until calls.length()) {
                    calls.optJSONObject(callIndex)?.optString("id")?.takeIf { it.isNotBlank() }?.let { open += it }
                }
            }
            if (message.optString("role") == "tool" && message.optString("tool_call_id") !in open) return false
        }
        return true
    }
}

/** Pi-style delivery queues with explicit injection boundaries. */
class AgentMessageQueue(restored: JSONObject? = null) {
    private val steering = mutableListOf<String>()
    private val followUps = mutableListOf<String>()

    init {
        restored?.optJSONArray("steering")?.let { values -> for (index in 0 until values.length()) steering += values.getString(index) }
        restored?.optJSONArray("followUps")?.let { values -> for (index in 0 until values.length()) followUps += values.getString(index) }
    }

    @Synchronized fun enqueue(delivery: AgentDelivery, text: String) {
        require(text.isNotBlank()) { "追加消息不能为空。" }
        if (delivery == AgentDelivery.STEER) steering += text.trim() else followUps += text.trim()
    }

    /** Called only after the current assistant tool batch is complete. */
    @Synchronized fun drainSteering(): List<String> = steering.toList().also { steering.clear() }

    /** Called only when the Agent would otherwise become idle. */
    @Synchronized fun pollFollowUp(): String? = followUps.removeFirstOrNull()

    @Synchronized fun hasSteering(): Boolean = steering.isNotEmpty()
    @Synchronized fun snapshot(): JSONObject = JSONObject().put("steering", JSONArray(steering)).put("followUps", JSONArray(followUps))
}

data class AgentLoopMetrics(
    val turns: Int,
    val toolCalls: Int,
    val edits: Int,
    val modelErrors: Int,
    val toolErrors: Int,
    val duplicateToolCalls: Int,
    val elapsedMs: Long,
    val terminalReason: AgentTerminalReason? = null
) {
    fun toJson() = JSONObject()
        .put("turns", turns).put("toolCalls", toolCalls).put("edits", edits)
        .put("modelErrors", modelErrors).put("toolErrors", toolErrors)
        .put("duplicateToolCalls", duplicateToolCalls).put("elapsedMs", elapsedMs)
        .put("terminalReason", terminalReason?.name ?: JSONObject.NULL)
}
