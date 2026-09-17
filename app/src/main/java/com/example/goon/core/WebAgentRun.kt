package com.example.goon.core

import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

    /** 一次运行最多展开几个 skill 全文（理由见 read_skill 分支：实测模型会把 9 个 skill 全读一遍）。 */
    private const val SKILL_READ_LIMIT = 2

    /** 单次输出触顶的重试上限：给模型两次"拆小重写"的机会，再不行就走降级交付。 */
    private const val MAX_TRUNCATION_RETRIES = 2

/** 单次 search_files 的扫描上限：超时宁可少给结果，也不能把一次工具调用卡成"应用没反应"。 */
private const val SCAN_DEADLINE_MS = 1500L

/** 超长行（压缩过的 JS/CSS）跳过：命中信息价值低，而正则回溯成本最高。 */
private const val MAX_SCAN_LINE = 4000

/** Native tool calls operate on one private draft; only finish publishes a verified revision. */
class WebAgentRun(
    private val workspace: Workspace, private val provider: ModelProvider, private val runId: String,
    private val original: WebProject?, private val onDelta: (String) -> Unit,
    private val callback: (AgentDisplayState) -> Unit,
    private val recovered: JSONObject? = null
) {
    private val id = recovered?.getString("projectId") ?: original?.manifest?.id ?: ApplicationSpec.blankProjectId()
    private val files = recovered?.getJSONObject("files")?.let { json -> json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap() } ?: original?.files?.toMutableMap() ?: linkedMapOf()
    private var manifest = recovered?.getJSONObject("manifest")?.let { WebMiniAppManifest.fromJson(it.toString()) } ?: original?.manifest ?: WebMiniAppManifest(id, "新小程序")
    /** 整个对话历史。压缩历史时会整体替换（org.json 的 JSONArray 没有 clear），所以是 var。 */
    private var messages = JSONArray()
    private var turns = recovered?.optInt("turns", 0) ?: 0
    private var edits = recovered?.optInt("edits", 0) ?: 0
    private var verified = recovered?.optInt("verified", -1) ?: -1
    private var hasPlan = recovered?.optBoolean("hasPlan", false) ?: false
    private var emptyTurns = 0
    private var totalToolCalls = recovered?.optInt("totalToolCalls", 0) ?: 0
    private var duplicateToolCalls = 0
    private var modelErrors = 0
    private var toolErrors = 0
    private var lastToolSignature = ""
    private val startedAt = System.currentTimeMillis()
    /**
     * 预算按任务规模给基础额度：新建要从零写多个文件，修改是在已有项目上动手——两者共用同一个数字的
     * 结果是"小的永远用不完、大的常常不够"（实测新建类多次逼近 40 轮）。超出基础额度后，只有在有
     * **实际进展**时才会弹性延长，见 [checkBudget]。
     */
    private val budget = AgentLoopBudget().forScale(if (original == null) TaskScale.CREATE else TaskScale.MODIFY)
    /** 已延长次数；以及上次延长时的改动数，用来判断是不是真的在往前走。 */
    private var extensionsUsed = 0
    private var lastExtensionEdits = 0
    /**
     * 「一次都还没动过手」的宽限是否已用掉。
     *
     * 实测 `modify-theme` 有一次跑成：20 轮、20 次工具调用、`edits: 0`——一直在读文件和搜样式，
     * 一次都没写。它撞上 MODIFY 的 20 轮额度后拿不到延长（延长要求「自上次延长以来有新的改动」），
     * 直接降级交付。规则本身没错（额度该给在收敛的任务），但它对「启动慢」的运行没有出口，
     * 所以给这种形态**一次**无条件延长；只给一次，且只给「还没动过手」的运行。
     */
    private var explorationGraceUsed = false
    private val graph = AgentGraph.start(runId, requireNotNull(workspace.run(runId)).conversationId)
    private val rag = RagIndex.builtIn()
    private val mcp = McpToolRegistry(emptyList(), emptyMap())
    @Volatile private var stopped = false
    private val draft = AtomicFile(File(workspace.context().filesDir, "web_drafts/$runId.json"))

    private var lastCompactedChars = 0
    /**
     * provider 上一次回报的输入 token（含 system 与 tools）。
     *
     * 这是唯一可信的"真实上下文有多大"信号：本地估算实测低估 1.5–3 倍，曾经导致 60k 的压缩闸门
     * 在 400+ 次调用里一次都没触发过。0 表示还没有真数可用，此时退回纯估算。
     */
    private var lastPromptTokens = 0
    /**
     * 估算器的自校准系数 = provider 真实值 / 本地纯估算，取指数滑动平均。
     *
     * 为什么不继续手调系数：对着孤立样本量出来的 chars/token（中文散文 1.8、整篇 HTML 2.8）在真实
     * 报文上并不成立——真实报文是 JSON 转义过的代码 + 审计 JSON + 中文混排，实测只有 1.24–1.45，
     * 于是代码密集时估算低估一倍以上（实测估算 31,204 / 真实 61,689）。与其继续猜，不如让它在运行中
     * 自己对齐：用每次的真实回报修正，两轮之后偏差就在 10% 内。
     */
    private var calibration = 1.0
    /** 上一次压缩前那一轮的真实上下文；>0 表示有未结算的压缩效果待确认。 */
    private var pendingCompactReal = 0
    /** 上次压缩是否真的减小了请求。压不动就别再压，见 [compactionWorthwhile]。 */
    private var lastCompactEffective = true
    private var lastCompactedParts = 0
    /** 上一次预览失败的指纹，用于识别"改了但失败项没变"的停滞。 */
    private var lastVerifySignature = ""
    /** 上一次预览的（版本+步骤）指纹与结果，用于拦掉完全重复的预览。 */
    private var lastPreviewSignature = ""
    private var lastPreviewResult: JSONObject? = null
    /** 结构化笔记与最近一次验证摘要：压缩历史时要用它们把"当前状态"带过去。 */
    private var compactNotes = ""
    /** 因单次输出触顶而重试的次数。超过上限说明"拆小"这条路走不通，再考虑降级交付。 */
    private var truncations = 0
    private var planSteps = ""
    private var lastVerifySummary: String? = null
    /** 本次运行已经展开过的 skill。上限见 SKILL_READ_LIMIT：读 skill 的每一轮都会把全文带进后续所有轮次。 */
    private val skillsRead = mutableSetOf<String>()

    companion object {
        // 最近若干条消息保持原样，确保当前轮的调用与结果完整；更早的大块内容才会被省略。
        private const val COMPACT_KEEP_RECENT_MESSAGES = 10
        /**
         * 历史压缩时保留最近几个"完整交换"（一个交换 = assistant 的 tool_calls + 它对应的所有 tool 结果）。
         * 按交换而不是按条数切，是为了不把 tool_calls 和它的结果拆散——拆散会让下一次请求变成非法对话。
         */
        private const val COMPACT_KEEP_EXCHANGES = 6
        private const val COMPACT_INLINE_CHARS = 1_200

        /**
         * 压缩的目标水位：压到闸门的百分之多少就停手。
         *
         * 不设目标时会"能删多少删多少"——实测一次把 60k 压到 18.8k（丢掉 26 条消息、约 85% 的上下文），
         * 模型会忘记刚做过什么，于是反复重写同一批文件。60% 是"留够余量"和"少丢信息"之间的折中。
         */
        private const val COMPACT_TARGET_PERCENT = 60
        // 单次 read_file 返回给模型的上限；文件仍在工作区，翻页即可读完。
        private const val READ_FILE_MAX_CHARS = 6_000
        private val active = ConcurrentHashMap<String, WebAgentRun>()
        fun isRunning(conversationId: String): Boolean = active.containsKey(conversationId)
        fun draftRun(workspace: Workspace): AgentRun? = workspace.runs().firstOrNull {
            it.conversationId == workspace.currentConversationId() && it.status != "completed" &&
                File(workspace.context().filesDir, "web_drafts/${it.id}.json").isFile
        }
        /**
         * 读取未发布草稿，供界面渲染「实时预览」。
         * 草稿在每次工具结果后都会落盘，因此界面不需要额外的推送通道就能看到项目长出来。
         */
        fun draftProject(workspace: Workspace, runId: String): WebProject? = runCatching {
            val draft = AtomicFile(File(workspace.context().filesDir, "web_drafts/$runId.json"))
            val json = JSONObject(draft.openRead().bufferedReader().use { it.readText() })
            val manifest = WebMiniAppManifest.fromJson(json.getJSONObject("manifest").toString())
            val fileJson = json.getJSONObject("files")
            val files = fileJson.keys().asSequence().associateWith { fileJson.getString(it) }
            if (files.isEmpty() || files[manifest.entry].isNullOrBlank()) null else WebProject(manifest, files)
        }.getOrNull()
        fun resume(workspace: Workspace, provider: ModelProvider, previousRunId: String, onDelta: (String) -> Unit, callback: (AgentDisplayState) -> Unit): String {
            require(previousRunId.matches(Regex("[a-f0-9-]{36}")))
            val previous = requireNotNull(workspace.run(previousRunId))
            require(previous.conversationId == workspace.currentConversationId() && !isRunning(previous.conversationId)) { "会话不匹配或仍有运行中的任务。" }
            val draftFile = AtomicFile(File(workspace.context().filesDir, "web_drafts/$previousRunId.json"))
            val recovered = JSONObject(draftFile.openRead().bufferedReader().use { it.readText() })
            val id = recovered.getString("projectId")
            val original = workspace.webStore().load(id)
            require(recovered.getString("baseRevision") == (original?.revision ?: "")) { "可用项目版本已改变，请重新读取后修改。" }
            val nextRun = workspace.startRun("继续：${previous.prompt}", provider.modelName, projectId = original?.manifest?.id)
            workspace.appendEvent("user_message", "继续上次未完成的小程序任务", nextRun)
            val run = WebAgentRun(workspace, provider, nextRun, original, onDelta, callback, recovered)
            run.start("恢复上次任务：${previous.prompt}。草稿已恢复，请先读取现有文件，继续实现和验证。", null)
            return nextRun
        }
        fun cancel(conversationId: String) { active[conversationId]?.cancel() }
        fun steer(conversationId: String, text: String): Boolean = enqueue(conversationId, AgentDelivery.STEER, text)
        fun followUp(conversationId: String, text: String): Boolean = enqueue(conversationId, AgentDelivery.FOLLOW_UP, text)
        private fun enqueue(conversationId: String, delivery: AgentDelivery, text: String): Boolean = active[conversationId]?.let { run ->
            if (run.stopped || text.isBlank()) false else {
                run.enqueue(delivery, text.trim())
                true
            }
        } ?: false
        private fun tool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) =
            JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)))
        private fun string() = JSONObject().put("type", "string")
        val tools: JSONArray get() = JSONArray().apply {
            put(tool("list_skills", "列出可用的 UI、审美、交互、工程和验证 skill。", JSONObject()))
            put(tool("read_skill", "读取一个内置 skill 的完整规范。部分 skill 还随包附带参考文件（完整清单、方向库等），用 file 参数按名读取，例如 file=\"ai-tells-catalog.md\"。", JSONObject().put("id", string()).put("file", string()), listOf("id")))
            put(WebSearch.toolSchema())
            put(tool("retrieve_context", "从内置产品 skill 中检索与当前任务相关的指导，返回匹配来源。", JSONObject().put("query", string()).put("limit", JSONObject().put("type", "integer")), listOf("query")))
            put(tool("update_plan", "先记录本次页面、功能、实现和验证计划。", JSONObject().put("steps", JSONObject().put("type", "array").put("items", string())), listOf("steps")))
            put(tool("list_files", "列出当前项目文件和清单。", JSONObject()))
            put(tool("search_files", "在所有项目文件里查找，默认按**字面文本**、大小写不敏感。用途是**定位改动点**：改样式、改函数、改文案之前先找到在第几行，再用 read_file 的 startLine/endLine 精读那一小段，最后 patch_file。比逐篇 read_file 翻页省得多，也能避免凭记忆猜 patch 的 old 内容。\n参数：pattern（必填）；regex=true 时按正则匹配（Java 语法，请写能走向确定的那类表达式，别写嵌套量词——用 (a+)+ 这种会被判超时并返回错误）；ignoreCase（默认 true）；wholeWord；glob（按路径通配筛文件，如 *.css 或 styles/*）；path（路径子串，与 glob 同时给时两边都要满足）；before/after（各 0–3 行上下文，用于判断这段是不是你要改的地方）；filesOnly=true 只回文件名；maxResults（默认 30，上限 60）。返回 matches 里每条含 path/line/text，带上下文时另有 before/after；总数、命中文件数、是否被截断单独回传。", JSONObject()
                .put("pattern", string()).put("regex", JSONObject().put("type", "boolean")).put("ignoreCase", JSONObject().put("type", "boolean"))
                .put("wholeWord", JSONObject().put("type", "boolean")).put("glob", string()).put("path", string())
                .put("before", JSONObject().put("type", "integer")).put("after", JSONObject().put("type", "integer"))
                .put("filesOnly", JSONObject().put("type", "boolean")).put("maxResults", JSONObject().put("type", "integer")), listOf("pattern")))
            put(tool("read_file", "按行区间读取文件内容（manifest.json 只读）。不传区间时返回整个文件，但单次返回有长度上限，超出会截断并提示翻页；读取大文件请显式指定 startLine/endLine。", JSONObject().put("path", string()).put("startLine", JSONObject().put("type", "integer")).put("endLine", JSONObject().put("type", "integer")), listOf("path")))
            put(tool("write_file", "创建或完整写入草稿中的一个文本文件，不发布。", JSONObject().put("path", string()).put("content", string()), listOf("path", "content")))
            put(tool("patch_file", "精确替换唯一匹配的旧文本；不匹配会返回错误。", JSONObject().put("path", string()).put("old", string()).put("new", string()), listOf("path", "old", "new")))
            put(tool("delete_file", "删除草稿文件。", JSONObject().put("path", string()), listOf("path")))
            put(tool("list_assets", "列出宿主素材库里的全部图片素材（名字 + 体积）。做界面之前先看有没有现成立绘/图标可用，别用 emoji 或纯色块硬凑。", JSONObject()))
            put(tool("configure_app", "设置名称、HTML 入口与要用的素材清单。assets 里每一步都要写素材库里的**完整文件名**（含扩展名），只有列进去的素材才能在小程序里通过 assets/文件名 引用。", JSONObject().put("name", string()).put("entry", string()).put("assets", JSONObject().put("type", "array").put("items", string())), listOf("name", "entry")))
            put(tool("run_preview", "在隔离 WebView 中按**设备真实分辨率**加载草稿：检查 JS/资源错误、横向溢出，按 steps 执行交互，跑完再截一张预览图（尺寸即设备像素）。审计分四类：布局（点击命中测试、固定底栏遮挡、超宽元素、小点击目标）、交互（每次 click 都会比较点击前后的整页指纹，点下去没有任何 DOM 变化的按钮直接判失败；每次 click 后面两步内必须有断言，否则判失败）、可读性（文字与它背后实际底色的 WCAG 对比度，不足 4.5:1 / 大字号 3:1 判失败）、设计（圆角档数、装饰性伪元素、背景渐变或纹理、阴影是否与配色同源、卡片叠卡片、字体与字距、是否有并列结构——整页单列纵向堆叠会被判流水账）。只有一种圆角 + 零装饰 + 无渐变纹理会被判定为默认模板风格并失败。必须包含至少一个 assertText/assertCount/assertVisible，且每次点击都要有断言跟着。返回的 layout 字段含视口尺寸、被遮挡元素的 rect、遮挡者的 rect 与 position/z-index/pointer-events、超宽元素样例、小点击目标尺寸；contrast 字段列出对比度不足的文字及其前景色与实际底色；consoleMessages 是页面自己的 console.error，只作参考、不影响通过与否。不要为了量尺寸在页面代码里加 console 日志或改 document.title。", JSONObject().put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties",
                JSONObject().put("action", string()).put("selector", string()).put("text", string()).put("count", JSONObject().put("type", "integer"))).put("required", JSONArray(listOf("action", "selector"))).put("additionalProperties", false))), listOf("steps")))
            put(tool("finish", "仅在当前文件版本通过预览和交互断言后，原子保存整个项目与版本并交付。", JSONObject().put("message", string()), listOf("message")))
        }
        val SYSTEM = """
你是 Goon 综合助手的小程序开发 Agent。当前用户明确授权创建或修改一个本地 Web 小程序。
通过工具持续工作：读取现有文件、制定计划、写入/精确修改文件、运行预览、根据真实错误修复、验证后 finish。
工具结果由宿主执行返回。不要只回复一个规格或代码块，不要假装工具已执行。一个用户请求可用多次模型调用。
小程序使用标准 HTML/CSS/JavaScript，支持 DOM、模块、Flex/Grid、动画、SVG、表单、列表筛选、路由与 localStorage。不要使用旧 DSL 或 ApplicationSpec。
所有文件都在项目工作区，入口默认 index.html；引用本地 CSS/JS/JSON/SVG 相对路径。页面加入 viewport meta，适配手机屏幕，控件有可访问标签与稳定 ID。
无需 npm、构建工具或框架。CSS/JS 自由实现完整交互，不受固定组件白名单限制。使用 localStorage 保存用户数据，明确处理空状态、错误、表单校验。
小程序运行时完全离线：不使用 CDN、外部网络、iframe、worker、eval、远程 import、宿主桥接、相机或定位。这是实现约束，不要在界面上宣传它——不要出现「离线可用」「数据只保存在本机」「无网络请求」「本机浏览器」这类技术说明，用户要看到的是一个正常产品，不是一份能力声明。Agent 可以按用户需求调用受控 search_web 获取当前资料，但不能把搜索结果变成小程序的隐式网络请求。可以使用内联 SVG 和 data 图片；真实支付/账号/地图等不能假装实现。
界面质量是这个项目的核心要求。下面的界面硬规则已经内联，不要再 read_skill 取它们。
这是本地离线小程序，**不要用 search_web**：它查的是外部实时资料，对写一个本地记录类应用没有帮助，只会白花轮次。只有用户明确要求「查一下现在的行情/榜单/政策」这类外部事实时才搜。
界面质量是这个项目的核心要求，**判断标准以随包引入的社区 skill 为准，不要自己另立一套**：
- 写样式之前先 `read_skill` 取 `no-ai-slop`（通用反 AI 味清单，含"这一版不要重复上一版的套路"这条要求）；
- 要选视觉方向时，读 `avoid-ai-design` 的 `references/aesthetic-directions.md`（7 个方向），挑**一个**并在 plan 里点名，说明它在这页上的具体做法；
- `read_skill` 还支持 `file` 参数读随包附带的清单原文（例如 `avoid-ai-design` 的 `references/ai-tells-catalog.md`）。
- update_plan 里要能看出你选了哪个方向、以及这条方向在本页的四个具体落点（字体角色、配色立场、布局骨架、一处标志性细节）。方向选完每一轮改样式前回看一次——一旦发现自己在"某个区块单独决定颜色/圆角/字号"，就是偏离了方向。
如果用户给了参考截图或素材，**先描述它的设计语言**再动手；不要照抄内容，把它当风格锚点。
必须避免的默认模板风格：满屏同款圆角阴影卡片、紫色渐变背景、发光球体、emoji 当图标、所有页面都用同一种字体与同一种卡片布局、居中的大段说明文字。每个页面至少有一个明确的视觉记忆点，并且只做一个、做彻底。
判"默认模板"用的是**一致性**，不是装饰量：为凑设计感而堆渐变、伪元素、圆角值同样是被否的方向。同一条判据的细节见下面 uiRules 的审计契约。
布局必须经得起真机检验：固定底栏不得遮挡正文或按钮；可点击元素必须真的能被点到；内容不得超出一屏宽度；点击目标不小于 44x44。run_preview 会做命中测试与遮挡审计，被遮挡或底栏超限会直接判定失败。
**素材**：动手写界面之前先 `list_assets` 看素材库里有什么，有合适的立绘/图标就用真图，别用 emoji 或纯色块硬凑。要用的素材必须在 `configure_app` 的 assets 里逐个列出**完整文件名**，然后在小程序里用相对路径 `assets/文件名` 引用（同源，不需要网络，也不要内联成 data URI——那样会把图片塞进项目文件、每轮重新计进上下文）。没列进 assets 的素材不会被发放。
布局失败时不要往页面里加 console 日志、也不要用 document.title 输出调试信息来量尺寸：run_preview 的 layout 字段已经给出视口尺寸、每个被遮挡元素的 rect、遮挡者的 rect / position / z-index / pointer-events、超宽元素样例和小点击目标尺寸。直接按这些数字改 CSS，一次改到位再预览，不要在页面上留任何调试代码。
先 update_plan，再读写文件；一次编辑只处理相关文件。修改保留用户不要求改变的功能。调用 configure_app 设置实际名称。
run_preview steps 支持 click、type、assertText、assertCount、assertVisible；只支持同步 DOM 交互，预览使用临时存储来源，不修改真实用户数据。
每次 click 后面两步内必须跟一条断言：断言要针对这次点击应该产生的结果（弹层出现就 assertVisible 那个弹层，数据变化就 assertText 那个数字），不要断言一个跟点击无关的标题——按钮点不动、断言却通过，是最坏的验证。
用户反馈过的真实故障：点「记 1 抽」没有任何弹窗。原因就是断言只查了一个统计数字。按钮的反馈要同步插入 DOM 后再播动画，异步插入会让断言不稳定。
验证应覆盖用户核心需求，不能只断言无关标题。最后一次修改后必须再次 run_preview，包含至少一条断言。预览失败则继续修复。finish 返回前不能宣称完成。
用简短自然中文说明当前实际进展，代码通过工具写入。只在需要用户信息或能力不可用时说明具体阻碍。
**说话的方式**：面向用户的文字（进度说明、计划步骤、finish 的 message）只写人话——做了什么、用户能拿它干什么、哪里还没做到。
不要出现 CSS 属性名与取值、毫秒数、选择器、工具参数名、审计字段名（rgba(…)、240–280ms、pointer-events、fixedCoverage、transform/opacity 这类）。
这些是给你自己看的实现细节：用户看不出好坏，只会觉得啰嗦、看不懂、像在念代码。要谈视觉就直接说感受与意图，
例如「纸面质感」「印章式按钮」「上下楼层的层次」「翻页时轻轻抬起」，而不是「用 12px 圆角加 8% 透明黑阴影」。
同样地，报告验证结果时说「预览通过，没有遮挡和看不清的文字」，不要贴 passed/assertions/layoutProblems 这些字段。
改样式、改函数、改文案之前，先用 search_files 定位在第几行，再用 read_file 的 startLine/endLine 精读那一小段，最后 patch_file——不要凭记忆猜 patch 的 old 内容，那会导致补丁失败或整篇重写，两者都是最贵的恢复路径。
为了控制上下文成本，较早的工具参数与工具结果会被省略成占位说明；文件内容始终以工作区为准，需要查看或确认时用 search_files / read_file / list_files / run_preview 重新获取，不要凭记忆假设旧内容。
""".trimIndent() + "\n\n" + BuiltInSkills.uiRules + "\n\n" + BuiltInSkills.prompt
    }
    private val queue = AgentMessageQueue(recovered)
    private val conversationId = requireNotNull(workspace.run(runId)).conversationId

    fun start(prompt: String, image: Uri?) {
        if (active.putIfAbsent(conversationId, this) != null) { end("failed", "本会话已有运行中的任务，请追加要求或停止它。"); return }
        try {
        val restoredMessages = recovered?.optJSONArray("messages")
        if (restoredMessages != null) for (index in 0 until restoredMessages.length()) messages.put(restoredMessages.getJSONObject(index))
        else {
            val history = workspace.events(conversationId).asReversed().filter { it.runId != runId && it.kind in setOf("user_message", "agent_message") }.takeLast(10)
            history.forEach { messages.put(JSONObject().put("role", if (it.kind == "user_message") "user" else "assistant").put("content", it.message)) }
        }
        val text = "当前项目 ID：$id\n用户要求：$prompt\n现有文件：${files.keys.joinToString()}"
        val content: Any = if (image == null) text else prepareImageInput(workspace.context(), image).let {
            JSONArray().put(JSONObject().put("type", "text").put("text", text)).put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${it.mimeType};base64,${it.base64}")))
        }
        messages.put(JSONObject().put("role", "user").put("content", content))
        graph.move(GraphNode.RETRIEVE_CONTEXT, "用户请求已进入状态图")
        workspace.appendEvent("graph_state", graph.checkpoint().toString(), runId)
        saveDraft()
        next()
        } catch (error: Exception) { end("failed", "任务启动失败：${error.message.orEmpty().take(120)}") }
    }
    private fun enqueue(delivery: AgentDelivery, text: String) {
        queue.enqueue(delivery, text)
        workspace.appendEvent("user_message", text, runId)
        saveDraft()
        callback(AgentDisplayState("开发小程序", if (delivery == AgentDelivery.STEER) "已收到调整" else "已排队后续任务", text, canCancel = true))
    }

    private fun saveDraft() {
        val value = JSONObject()
            .put("projectId", id).put("baseRevision", original?.revision ?: "")
            .put("manifest", manifest.toJson()).put("files", JSONObject(files))
            .put("messages", compactMessages()).put("steering", queue.snapshot().getJSONArray("steering")).put("followUps", queue.snapshot().getJSONArray("followUps"))
            .put("turns", turns).put("edits", edits).put("verified", verified).put("hasPlan", hasPlan)
            .put("totalToolCalls", totalToolCalls).put("graph", graph.checkpoint())
        val stream = draft.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); draft.finishWrite(stream) }
        catch (error: Exception) { draft.failWrite(stream); throw error }
    }
    private fun next() {
        if (stopped) return
        if (!checkBudget()) return
        val steering = queue.drainSteering()
        if (steering.isNotEmpty()) {
            graph.move(GraphNode.HUMAN_INPUT, "收到 steering 消息")
            steering.forEach { messages.put(JSONObject().put("role", "user").put("content", it)); verified = -1 }
            graph.move(GraphNode.ACT, "继续当前任务")
        }
        var requestContext = requestMessages()
        // 闸门用**自校准后**的估算，并取「估算」与「provider 上一轮真实回报」的较大值：
        // 真数只在拿到过一次响应之后才有，估算负责补上这一轮的增量。
        var sentEstimate = ContextTokens.of(requestContext, SYSTEM, tools)
        var requestTokens = maxOf((sentEstimate * calibration).roundToInt(), lastPromptTokens)
        var compactedNow = false
        var droppedMessages = 0
        if (requestTokens > budget.compactAtTokens && compactionWorthwhile(requestTokens)) {
            val before = requestTokens
            pendingCompactReal = maxOf(lastPromptTokens, before)
            droppedMessages = compactHistory()
            requestContext = requestMessages()
            sentEstimate = ContextTokens.of(requestContext, SYSTEM, tools)
            // 压缩后旧的真数已经不代表这一轮了，必须丢掉重算；否则会拿着过期的大数字连压多次。
            lastPromptTokens = 0
            requestTokens = (sentEstimate * calibration).roundToInt()
            compactedNow = true
            workspace.appendEvent("agent_compact", JSONObject()
                .put("turn", turns + 1).put("droppedMessages", droppedMessages)
                .put("tokensBefore", before).put("tokensAfter", requestTokens)
                .put("notes", compactNotes.length).toString(), runId)
        }
        val requestChars = requestContext.toString().length
        workspace.appendEvent("agent_context", JSONObject().put("turn", turns + 1).put("messages", requestContext.length())
            .put("requestChars", requestChars).put("requestTokens", requestTokens)
            .put("compactedParts", lastCompactedParts).put("compactedChars", lastCompactedChars)
            .put("historyCompacted", compactedNow).put("droppedMessages", droppedMessages)
            .put("elidedRatio", if (lastCompactedChars == 0) 0.0 else lastCompactedChars.toDouble() / (requestChars + lastCompactedChars)).toString(), runId)
        if (requestTokens > budget.maxContextTokens) {
            end("failed", "上下文压缩后仍达到上限（约 $requestTokens token），草稿已保存，可继续任务。", AgentTerminalReason.CONTEXT_LIMIT)
            return
        }
        saveDraft()
        turns++
        graph.move(GraphNode.ACT, "开始第 ${turns} 轮模型与工具交互")
        workspace.appendEvent("graph_state", graph.checkpoint().toString(), runId)
        callback(AgentDisplayState("开发小程序", "正在继续开发", "第 $turns 轮 · ${files.size} 个项目文件", canCancel = true))
        val started = System.currentTimeMillis()
        var streamed = ""
        provider.turn(requestContext, tools, SYSTEM, onDelta = { delta -> if (!stopped) { streamed += delta; onDelta(streamed) } }) { result ->
            if (!stopped) {
                result.fold(onSuccess = { response ->
                    workspace.recordModelAttempt(runId, response.durationMs, response.promptTokens, response.completionTokens, response.httpStatus, true, response.cachedTokens, response.reasoningTokens)
                    // 对账用的真实用量：把「provider 报的」和「本地估的」记在一起，才能在下一次回归里
                    // 立刻看出估算又偏了多少，而不是等到会话累计数字吓人时再猜。
                    if (response.promptTokens != null) {
                        lastPromptTokens = response.promptTokens
                        // 校准本地估算：样本要够大（否则 SYSTEM 与大块固定开销会主导比值），
                        // 比值也要落在合理区间，避免一次异常回报把系数带跑。
                        if (sentEstimate > 5_000) {
                            val ratio = (response.promptTokens.toDouble() / sentEstimate).coerceIn(0.4, 5.0)
                            calibration = calibration * 0.6 + ratio * 0.4
                        }
                        // 结算上一次压缩：真实减小不到 10% 就判「压不动」。
                        if (pendingCompactReal > 0) {
                            val ratio = response.promptTokens.toDouble() / pendingCompactReal
                            lastCompactEffective = ratio <= 0.9
                            workspace.appendEvent("agent_compact_result", JSONObject()
                                .put("turn", turns).put("before", pendingCompactReal).put("after", response.promptTokens)
                                .put("ratio", ratio).put("effective", lastCompactEffective).toString(), runId)
                            pendingCompactReal = 0
                        }
                    }
                    workspace.appendEvent("agent_usage", JSONObject().put("turn", turns)
                        .put("promptTokens", response.promptTokens ?: JSONObject.NULL)
                        .put("cachedTokens", response.cachedTokens ?: JSONObject.NULL)
                        .put("completionTokens", response.completionTokens ?: JSONObject.NULL)
                        .put("estimateTokens", requestTokens).put("requestChars", requestChars)
                        .put("messages", requestContext.length()).toString(), runId)
                    val message = JSONObject().put("role", "assistant").put("content", response.content.ifBlank { JSONObject.NULL })
                    if (response.toolCalls.length() > 0) message.put("tool_calls", response.toolCalls)
                    messages.put(message)
                    if (response.content.isNotBlank()) workspace.appendEvent("agent_message", response.content, runId)
                    saveDraft()
                    if (response.toolCalls.length() == 0) {
                        val followUp = queue.pollFollowUp()
                        if (followUp != null) {
                            messages.put(JSONObject().put("role", "user").put("content", followUp))
                            verified = -1
                            saveDraft()
                            next()
                            return@fold
                        }
                        emptyTurns++
                        if (emptyTurns >= 2) { end("failed", response.content.ifBlank { "模型未返回可执行工具，请检查当前模型是否支持 tool calling。" }, AgentTerminalReason.MODEL_ERROR); return@fold }
                        messages.put(JSONObject().put("role", "user").put("content", "请继续通过工具推进；完成需调用 finish，不能以文字结束开发。"))
                        next()
                    } else if (response.toolCalls.length() > budget.maxToolsPerTurn) end("failed", "单轮工具调用超过 ${budget.maxToolsPerTurn} 个，草稿已保留。", AgentTerminalReason.BUDGET_EXCEEDED)
                    else { emptyTurns = 0; execute(response.toolCalls, 0) }
                }, onFailure = {
                    modelErrors++
                    workspace.recordModelAttempt(runId, System.currentTimeMillis() - started, null, null, null, false)
                    if (it is TruncatedModelOutput && truncations < MAX_TRUNCATION_RETRIES) {
                        // 单次输出触顶是**可恢复**的：这一轮没有产生任何修改，把写入拆小重来即可。
                        // 旧实现直接终止整轮，实测 4 次失败里有 2 次是它，代价是已写好的文件被一起丢弃。
                        truncations++
                        messages.put(JSONObject().put("role", "user").put("content",
                            "上一条回复因为**单次输出超过上限被截断**，本次没有产生任何修改。请把这次写入拆小再来：\n" +
                                "1) 先用 write_file 只写结构骨架（HTML 骨架 / CSS 变量与主要区块 / JS 的入口与空函数），单次 content 控制在 8000 字符以内；\n" +
                                "2) 再用多次 patch_file 分批补内容，每次只补一段；\n" +
                                "3) 不要重发已经写好的整篇文件。"))
                        saveDraft()
                        next()
                        return@fold
                    }
                    end("failed", "模型请求失败：${it.message.orEmpty().take(160)}。草稿已保留，未执行模拟替代。", AgentTerminalReason.MODEL_ERROR)
                })
            }
        }
    }
    private fun execute(calls: JSONArray, index: Int) {
        if (stopped) return
        if (!checkBudget()) return
        if (index == calls.length()) { next(); return }
        val call = calls.optJSONObject(index)
        if (call == null || call.optString("id").isBlank() || call.optJSONObject("function") == null) { end("failed", "模型工具调用格式无效，草稿已保留。"); return }
        val function = call.getJSONObject("function")
        val name = function.getString("name")
        val signature = "$name:${function.optString("arguments")}".take(4000)
        if (signature == lastToolSignature) duplicateToolCalls++ else duplicateToolCalls = 0
        lastToolSignature = signature
        if (duplicateToolCalls >= budget.maxDuplicateToolCalls) {
            end("failed", "检测到重复工具调用，已停止以避免循环。草稿已保留。", AgentTerminalReason.LOOP_DETECTED)
            return
        }
        totalToolCalls++
        fun result(output: JSONObject) {
            if (stopped) return
            if (!output.optBoolean("ok", true)) toolErrors++
            graph.move(GraphNode.OBSERVE, "工具 $name 返回结果")
            workspace.appendEvent("tool_$name", JSONObject().put("toolCallId", call.optString("id")).put("round", turns).put("ok", output.optBoolean("ok", true)).put("summary", output.optString("error", name)).toString(), runId)
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", output.toString()))
            saveDraft()
            execute(calls, index + 1)
        }
        callback(AgentDisplayState("开发小程序", "正在执行 $name", "第 $turns 轮", canCancel = true))
        runCatching {
            val args = JSONObject(function.getString("arguments"))
            when (name) {
                "list_skills" -> result(JSONObject().put("skills", BuiltInSkills.listJson()))
                "read_skill" -> {
                    val skill = requireNotNull(BuiltInSkills.read(args.getString("id"))) { "skill 不存在。" }
                    val file = args.optString("file")
                    if (file.isNotBlank()) {
                        val content = requireNotNull(BuiltInSkills.readFile(skill.id, file)) {
                            "skill ${skill.id} 里没有 $file。可读文件见 read_skill 返回的 files 字段。"
                        }
                        result(JSONObject().put("id", skill.id).put("file", file).put("body", content))
                        return
                    }
                    // 超限不抛错、只回一条提示：抛错会变成一次失败，模型下一轮还得再试，反而更贵。
                    if (skill.id !in skillsRead && skillsRead.size >= SKILL_READ_LIMIT) {
                        result(JSONObject().put("ok", false).put("skillsRead", JSONArray(skillsRead.toList()))
                            .put("message", "本次运行已经展开 $SKILL_READ_LIMIT 个 skill（${skillsRead.joinToString()}），不再展开新的。界面硬规则已经内联在系统提示里，直接按它执行；确实需要别的规范时请说明是哪一条、为什么要，不要继续读 skill。"))
                    } else {
                        skillsRead += skill.id
                        result(requireNotNull(BuiltInSkills.detailJson(skill.id)).put("skillsRead", JSONArray(skillsRead.toList())))
                    }
                }
                "search_web" -> {
                    val query = args.getString("query")
                    val count = if (args.has("count")) args.optInt("count", 5) else 5
                    val domain = args.optString("domain").takeIf { it.isNotBlank() }
                    val results = WebSearch.search(query, count, domain)
                    workspace.appendEvent("web_search", JSONObject().put("query", query.take(600)).put("count", results.size).put("results", JSONArray().apply { results.forEach { put(it.toJson()) } }).toString(), runId)
                    result(JSONObject()
                        .put("ok", true).put("query", query)
                        .put("domain", domain ?: JSONObject.NULL)
                        .put("count", results.size)
                        .put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
                        .also { if (domain != null && results.isEmpty()) it.put("note", "限定 $domain 后没有结果：Bing 的 site: 过滤在当前网络下不生效，且返回结果里没有该域名的页面。该站点多半未被 Bing 收录，请改用不带 domain 的搜索，或直接说明查不到。") })
                }
                "retrieve_context" -> {
                    val hits = rag.retrieve(args.getString("query"), if (args.has("limit")) args.optInt("limit", 4) else 4)
                    result(JSONObject().put("ok", true).put("hits", JSONArray().apply { hits.forEach { put(it.toJson().put("text", it.document.text.take(1800))) } }))
                }
                "update_plan" -> {
                    val steps = args.getJSONArray("steps")
                    require(steps.length() in 1..10)
                    hasPlan = true
                    // 留一份最新计划：历史被压缩时，计划必须跟着"当前状态"带过去，否则模型会丢掉方向。
                    planSteps = (0 until steps.length()).joinToString("\n") { "${it + 1}. ${steps.optString(it)}" }
                    workspace.appendEvent("agent_plan", (0 until steps.length()).joinToString("\n") { steps.getString(it) }, runId)
                    result(JSONObject().put("ok", true))
                }
                "list_files" -> result(JSONObject().put("manifest", manifest.toJson()).put("files", JSONArray(files.keys.toList())))
                "search_files" -> result(searchFiles(args))
                "list_assets" -> result(JSONObject().put("ok", true).put("assets", JSONArray().apply {
                    AssetStore.list(workspace.context()).forEach { put(JSONObject().put("name", it.name).put("bytes", it.bytes)) }
                }).also { if (it.getJSONArray("assets").length() == 0) it.put("note", "素材库是空的，请用 emoji 视觉占位并在 message 里说明。") })
                "read_file" -> {
                    val path = WebMiniAppStore.path(args.getString("path"))
                    // 文件正文是当前最大的「新增 token」来源：整篇返回一个 19KB 的 CSS 就是几千 token，
                    // 而且每轮都要重发。这里强制按行区间读取并设上限，需要更多内容再翻页。
                    val full = if (path == "manifest.json") manifest.toJson().toString() else requireNotNull(files[path]) { "文件不存在。" }
                    val lines = full.lines()
                    val start = (if (args.has("startLine")) args.optInt("startLine", 1) else 1).coerceIn(1, lines.size.coerceAtLeast(1))
                    val end = (if (args.has("endLine")) args.optInt("endLine", lines.size) else lines.size).coerceIn(start, lines.size.coerceAtLeast(1))
                    val slice = lines.subList(start - 1, end).joinToString("\n")
                    val capped = slice.take(READ_FILE_MAX_CHARS)
                    result(JSONObject().put("ok", true).put("path", path)
                        .put("startLine", start).put("endLine", end).put("totalLines", lines.size).put("totalChars", full.length)
                        .put("content", capped)
                        .put("truncated", capped.length < slice.length)
                        .put("note", if (capped.length < slice.length) "内容已按上限截断，请用 startLine/endLine 分段读取剩余部分。" else "如需其他部分请用 startLine/endLine 指定区间。"))
                }
                "configure_app", "write_file", "patch_file", "delete_file" -> {
                    require(hasPlan) { "请先 update_plan。" }
                    var note: String? = null
                    if (name == "configure_app") {
                        // assets 只接受素材库里真实存在的名字：写错就在这一步当场报出来，
                        // 而不是等预览时才发现图 404——那种失败的定位成本高得多。
                        val declared = args.optJSONArray("assets")?.let { array -> List(array.length()) { array.getString(it) } } ?: manifest.assets
                        val missing = declared.filterNot { AssetStore.exists(workspace.context(), it) }
                        require(missing.isEmpty()) { "素材库里没有这些素材，请先用 list_assets 核对文件名：${missing.take(5).joinToString()}" }
                        val candidate = manifest.copy(name = args.getString("name"), entry = WebMiniAppStore.path(args.getString("entry")), assets = declared.distinct())
                        require(candidate.name.isNotBlank() && candidate.name.length <= 80 && candidate.entry.endsWith(".html"))
                        manifest = candidate
                    } else {
                        val path = WebMiniAppStore.path(args.getString("path"))
                        require(path != "manifest.json") { "清单请用 configure_app 更新。" }
                        val candidate = files.toMutableMap()
                        when (name) {
                            "delete_file" -> require(candidate.remove(path) != null) { "文件不存在。" }
                            "write_file" -> {
                                val content = args.getString("content")
                                note = files[path]?.let { rewriteNote(it, content) }
                                candidate[path] = content
                            }
                            else -> {
                                val previous = requireNotNull(candidate[path]) { "文件不存在。" }
                                val old = args.getString("old")
                                require(old.isNotEmpty()) { "old 不能为空；请提供文件中确实存在的片段。" }
                                val first = previous.indexOf(old)
                                require(first >= 0) { "旧文本在 $path 中找不到。${patchHint(previous, old)}" }
                                require(previous.indexOf(old, first + 1) < 0) { "旧文本在 $path 中出现多次，无法唯一确定位置。请扩大 old 的范围（多带一两行上下文）使其唯一，不要改用整篇 write_file。" }
                                candidate[path] = previous.replaceFirst(old, args.getString("new"))
                            }
                        }
                        // Validate partial drafts with a temporary entry; never persist the placeholder.
                        val validationFiles = candidate.toMutableMap().apply { putIfAbsent(manifest.entry, "<!doctype html><title>draft</title>") }
                        WebMiniAppStore.validate(manifest, validationFiles)
                        files.clear(); files.putAll(candidate)
                    }
                    edits++; verified = -1; saveDraft()
                    result(JSONObject().put("ok", true).put("files", files.size).put("draftVersion", edits).apply { note?.let { put("note", it) } })
                }
                "run_preview" -> {
                    WebMiniAppStore.validate(manifest, files)
                    val steps = args.getJSONArray("steps")
                    require(steps.length() in 1..40 && (0 until steps.length()).any { steps.getJSONObject(it).optString("action").startsWith("assert") }) {
                        if (steps.length() > 40) "你写了 ${steps.length()} 步，超过 40 步上限。请拆成几轮聚焦的验证（每轮只验证一条主链路），不要一次把所有路径塞进去。"
                        else "需要至少一条核心交互断言（assertText / assertCount / assertVisible）。"
                    }
                    // 每次点击后面必须紧跟一条断言。用户报过「点了记 1 抽完全没反应」，
                    // 而当时的 steps 只断言了一个数字——按钮是死的，断言却通过了。
                    val unverified = (0 until steps.length()).filter { index ->
                        steps.getJSONObject(index).optString("action") == "click" &&
                            (index + 1 until minOf(steps.length(), index + 3)).none { steps.getJSONObject(it).optString("action").startsWith("assert") }
                    }
                    require(unverified.isEmpty()) {
                        "第 ${unverified.joinToString("、") { (it + 1).toString() }} 步是 click，后面两步内没有断言：这次点击是否真的生效无从判断。每次 click 之后紧跟一条 assertVisible（弹层出现）或 assertText/assertCount（内容变化），否则按钮坏了也看不出来。"
                    }
                    val version = edits
                    // 同样的文件 + 同样的步骤必然得到同样的结果，重跑只白花一轮。
                    // 实测一次任务里 6 轮预览有 2 组是完全重复的。
                    val previewSignature = "$version:${steps.toString().hashCode()}"
                    val cached = lastPreviewResult
                    if (previewSignature == lastPreviewSignature && cached != null) {
                        result(JSONObject().put("ok", cached.optBoolean("passed")).put("report", cached)
                            .put("note", "自上次预览以来没有任何文件改动，步骤也完全相同，因此直接返回上次的结果。要改变结果请先改文件，或换成不同的步骤来缩小问题范围。"))
                        return
                    }
                    WebPreview.verify(workspace.context(), WebProject(manifest, files.toMap()), steps, shotKey = "draft-$runId") { report ->
                        if (!stopped) {
                            if (report.optBoolean("passed") && version == edits) verified = version
                            // 压缩历史时要能带上"上次验证到什么程度"，否则模型会重跑已经通过的验证。
                            lastVerifySummary = buildString {
                                append(if (report.optBoolean("passed")) "通过" else "未通过")
                                append("（断言 ${report.optJSONArray("assertions")?.length() ?: 0} 条）")
                                report.optJSONArray("layoutProblems")?.let { if (it.length() > 0) append("，问题：${it.optString(0).take(200)}") }
                            }
                            workspace.appendEvent("web_verification", report.toString(), runId)
                            val compact = compactReport(report)
                            // 停滞检测：连续两次预览失败且**失败项一模一样**，说明中间的修改没有触及真正的问题。
                            // 这时最该做的是换方向（先核对断言、或换一条断言重跑），而不是继续改同一处——
                            // 实测一次任务里 6 轮预览有 5 次失败，每次失败后模型要花 3–5 轮去修。
                            if (!report.optBoolean("passed")) {
                                val signature = compact.optJSONArray("failedAssertions").toString() + "|" + compact.optJSONArray("pageFailures").toString()
                                if (signature == lastVerifySignature) compact.put("stalled", true)
                                    .put("stallNote", "这次失败的项与上一次完全相同，说明上次的修改没有触及真正的原因。不要再改同一处：先逐条核对断言是否适用于当前视图（尤其是选择器只在某个页面存在的情况），或把它换成一条更稳的断言重跑，确认到底是断言错还是页面错。")
                                lastVerifySignature = signature
                            } else {
                                lastVerifySignature = ""
                            }
                            result(JSONObject().put("ok", report.optBoolean("passed")).put("report", compact))
                            lastPreviewSignature = previewSignature
                            lastPreviewResult = compact
                        }
                    }
                }
                "finish" -> {
                    require(hasPlan && verified == edits && edits > 0) { "必须完成计划、修改文件，并让当前版本通过 run_preview。" }
                    require(!queue.hasSteering()) { "用户追加了 steering 要求，请继续处理。" }
                    val followUp = queue.pollFollowUp()
                    if (followUp != null) {
                        messages.put(JSONObject().put("role", "user").put("content", followUp))
                        verified = -1
                        result(JSONObject().put("ok", false).put("error", "检测到 follow-up 要求，先继续处理后续请求，再次验证后才能 finish。"))
                    } else {
                        WebMiniAppStore.validate(manifest, files)
                        val revision = synchronized(this) {
                            check(!stopped) { "任务已停止。" }
                            workspace.saveWebProject(manifest, files, "Agent 已验证 Web 项目", original?.revision ?: "")
                        }
                        workspace.updateRunProject(runId, id)
                        draft.delete()
                        graph.move(GraphNode.COMPLETE, "验证通过并完成发布")
                        end("completed", args.getString("message"), AgentTerminalReason.COMPLETED)
                        // **产物卡片必须排在最后**：它是这次运行的交付物，用户要能一眼看到、直接点开，
                        // 而不是让收尾那句总结把它压到中间去（用户反馈过「每次都要去中间翻找入口」）。
                        // 所以先写终态消息，再落 app_result 事件。
                        ShotStore.promote(workspace.context(), runId, id, revision)?.let { ShotStore.prune(workspace.context(), id) }
                        workspace.appendEvent("app_result", JSONObject().put("projectId", id).put("name", manifest.name).put("runtime", "web").put("revision", revision).put("files", files.size).put("pages", files.keys.count { it.endsWith(".html") }).toString(), runId)
                    }
                }
                else -> error("未知工具：$name")
            }
        }.onFailure { result(JSONObject().put("ok", false).put("error", it.message ?: "工具执行失败。")) }
    }
    private fun checkBudget(): Boolean {
        val turnsHit = turns >= budget.maxTurns
        val toolsHit = totalToolCalls >= budget.maxToolCalls
        val timeHit = System.currentTimeMillis() - startedAt >= budget.maxElapsedMs
        if (!turnsHit && !toolsHit && !timeHit) return true
        val limit = when {
            turnsHit -> "已达到本次 ${budget.maxTurns} 轮预算"
            toolsHit -> "已达到本次工具调用预算"
            else -> "任务超过时间预算"
        }
        // 弹性延长：只有在**真的在往前走**时才给更多额度。
        // 判据是"自上次延长以来有新的文件改动"，或"当前版本已经通过一次完整预览"——
        // 这两件事都说明继续跑有机会收敛，而不是在原地打转。停滞的用旧额度收口（走降级交付）。
        val progressed = edits > lastExtensionEdits || (verified >= 0 && verified == edits)
        // 一次都还没动过手：不是「停滞」，是「还没开始动手」。给它一次宽限，否则这类运行没有任何出口。
        if (edits == 0 && !explorationGraceUsed && extensionsUsed < budget.maxExtensions) {
            explorationGraceUsed = true
            extensionsUsed++
            budget.maxTurns += budget.extensionTurns
            budget.maxToolCalls += budget.extensionTurns * 3
            budget.maxElapsedMs += budget.extensionMs
            workspace.appendEvent("agent_budget_extended", JSONObject()
                .put("count", extensionsUsed).put("trigger", limit).put("turns", turns).put("edits", edits)
                .put("maxTurns", budget.maxTurns).put("maxElapsedMs", budget.maxElapsedMs)
                .put("reason", "本次还没有任何文件改动（还在探索），给一次宽限").toString(), runId)
            return true
        }
        if (progressed && extensionsUsed < budget.maxExtensions) {
            extensionsUsed++
            lastExtensionEdits = edits
            budget.maxTurns += budget.extensionTurns
            budget.maxToolCalls += budget.extensionTurns * 3
            budget.maxElapsedMs += budget.extensionMs
            workspace.appendEvent("agent_budget_extended", JSONObject()
                .put("count", extensionsUsed).put("trigger", limit).put("turns", turns).put("edits", edits)
                .put("maxTurns", budget.maxTurns).put("maxElapsedMs", budget.maxElapsedMs)
                .put("reason", "自上次延长以来有新的改动，说明还在收敛").toString(), runId)
            return true
        }
        val why = if (progressed) "（已延长 $extensionsUsed 次，额度用尽）" else "（自上次延长以来没有新的改动，判定为没有进展）"
        return degradeOrFail(limit + why, if (timeHit) AgentTerminalReason.TIMEOUT else AgentTerminalReason.BUDGET_EXCEEDED)
    }

    /**
     * 撞预算时的**降级交付**。
     *
     * 旧行为是一句"草稿已保留"然后判失败——用户拿不到任何能打开的东西。但实测 data 显示：
     * 4 次失败里 2 次是撞轮次预算，而能力上限（pass@3 = 100%）证明这些任务**是做得出的**，
     * 只是这一轮没跑完。已经写好的文件是真实产出，把它交出去并**写清哪些没验证**，
     * 比扔掉更有用，也更诚实。
     *
     * 交付条件（缺一不可，避免把半成品当成品）：
     * - 工作区里有文件，且 manifest 声明的入口文件存在（至少能打开）；
     * - 有过至少一次通过预览的版本（`verified == edits`）→ 标为"已验证的部分"；
     *   否则明确标注"未通过预览验证"。
     *
     * 什么都没有时仍然按失败处理——不能把"什么都没做出来"包装成交付。
     */
    private fun degradeOrFail(limit: String, reason: AgentTerminalReason): Boolean {
        val entryPresent = files.containsKey(manifest.entry)
        if (files.isEmpty() || !entryPresent) {
            end("failed", "$limit。草稿已保留（没有可交付的文件）。", reason)
            return false
        }
        val verifiedNow = verified >= 0 && verified == edits
        val revision = runCatching {
            synchronized(this) {
                if (stopped) return true
                workspace.saveWebProject(manifest, files, "Agent 降级交付（$limit）", original?.revision ?: "")
            }
        }.getOrNull()
        if (revision == null) {
            end("failed", "$limit，且保存草稿失败。", reason)
            return false
        }
        workspace.updateRunProject(runId, id)
        val gap = buildString {
            append("**未完成的部分**：")
            append(if (verifiedNow) "已通过一次完整预览验证。" else "**没有通过预览验证**，交互可能有问题。")
            lastVerifySummary?.takeIf { it.isNotBlank() && !verifiedNow }?.let { append("最近一次验证：").append(it).append("。") }
            if (lastVerifySignature.isNotBlank() && !verifiedNow) append(" 上次未通过的项：").append(lastVerifySignature.take(200)).append("。")
            append(" 你可以继续追加要求（例如「把没验证的地方修一下」），我会在现有版本上接着做。")
        }
        ShotStore.promote(workspace.context(), runId, id, revision)?.let { ShotStore.prune(workspace.context(), id) }
        workspace.appendEvent("app_result", JSONObject().put("projectId", id).put("name", manifest.name).put("runtime", "web")
            .put("revision", revision).put("files", files.size).put("pages", files.keys.count { it.endsWith(".html") })
            .put("degraded", true).toString(), runId)
        draft.delete()
        graph.move(GraphNode.COMPLETE, "降级交付（$limit）")
        end("completed", "已经做出可用版本并交付（$limit 触发降级交付）：${manifest.name}。$gap", AgentTerminalReason.DEGRADED_DELIVERY)
        return false
    }
    private fun compactMessages(): JSONArray = JSONArray().apply {
        val first = (messages.length() - 80).coerceAtLeast(0)
        for (index in first until messages.length()) {
            val message = JSONObject(messages.getJSONObject(index).toString())
            if (message.has("content") && !message.isNull("content")) message.put("content", message.optString("content").take(20_000))
            put(message)
        }
    }

    /**
     * 组装真正发给模型的上下文。
     *
     * 无状态 Chat Completions 每轮都要重发完整历史，而历史里最大的一块是**模型自己写的文件全文**——
     * 它以工具参数的形式永久留在消息中，每轮重发一次，于是总量按 O(N²) 增长：实测一次 20 轮的任务里
     * 工具参数占上下文的 79%（单条 write_file 参数可达 47KB）。这里把「最近若干条之外」的大块内容
     * 换成占位说明；项目工作区里始终是最新内容，模型需要时用 read_file 重新读取即可。
     */
    /**
     * 给模型看的预览报告：只留结论与需要修的问题。
     *
     * 完整报告（含 controls 明细与逐条断言）仍原样写入 `web_verification` 事件，供评测与排查使用；
     * 但它是当前最大的「新增 token」来源之一，而模型真正需要的只是「过没过、哪里没过」。
     */
    /**
     * 跨文件定位。
     *
     * 存在的理由不是「检索知识」，而是**定位改动点**：项目里的标识符是精确的，模型缺的只是
     * 「这个样式/函数/文案在第几行」。没有它，模型只能靠猜 patch 的锚点（实测一次任务里 19 次
     * patch 反复失败），或者整篇 read_file 翻页——而 `patchHint` 里那个只在单文件里找第一处匹配的
     * 实现，本来就是这件事的残废版。
     *
     * 只做**字面匹配**，不支持正则：模型要的是精确定位，而正则会引入回溯爆炸的风险面
     * （2 MiB 语料配一个病态模式足以卡死工具循环）。语料规模小，直接线性扫，不需要索引。
     */
    /**
     * 就地压缩历史：把"早期的一整段交换"替换成一条结构化笔记。
     *
     * 关键约束：**assistant 的 tool_calls 与随后的 tool 结果必须同生共死**。只删一边，下一次请求
     * 会被 provider 判为非法对话（"tool 消息没有对应的 tool_call"），而且这个错误发生在模型侧、
     * 报错信息很难定位。所以这里按「交换」为单位切分，而不是按消息个数。
     *
     * 保留什么：任务原文（第一条 user）、当前计划、文件清单与大小、最近一次验证结论、待解决项，
     * 以及最近 [COMPACT_KEEP_EXCHANGES] 个完整交换。丢掉的是**已经被后续动作取代的中间过程**——
     * 它们的内容在工作区里仍然是最新的，需要细节时 search_files / read_file 一句话就能取回来。
     *
     * 返回被丢掉的消息条数，供 trace 与 UI 展示"这次压缩省了多少"。
     */
    /**
     * 这次还值不值得压缩。
     *
     * 实测过一个很难看的循环：闸门值来自上一轮真数（约 61k）> 60k 触发压缩，而压缩删掉的是
     * **已经被 [requestMessages] 省略成占位符的旧交换**——真实请求 60,777 → 61,689，一点没变小，
     * 却把中段历史改写了一遍，前缀缓存整段失效、命中率从 93% 掉到 40%，然后下一轮又触发……
     * 长任务里就这样每轮白压一次，一个运行压了 8–13 次。
     *
     * 所以规则是：上一次压缩真实减小不到 10%，就暂时不再压（只记一次事件说明原因）；
     * 但真逼近硬上限（90%）时照压不误——宁可砸缓存，也不能撞窗口。
     */
    private fun compactionWorthwhile(tokens: Int): Boolean =
        lastCompactEffective || tokens > budget.maxContextTokens * 9 / 10

    private fun compactHistory(): Int {
        val exchanges = HistoryCompactor.group(messages)
        if (exchanges.size <= COMPACT_KEEP_EXCHANGES + 1) return 0
        val task = exchanges.firstOrNull()?.firstOrNull { it.optString("role") == "user" }?.optString("content").orEmpty()
        compactNotes = buildNotes(task)
        // 压到"够用"而不是"能删多少删多少"：从"留最少"往上试，取第一个能压到目标以下的最小删除量。
        val target = budget.compactAtTokens * COMPACT_TARGET_PERCENT / 100
        var keep = COMPACT_KEEP_EXCHANGES
        var rebuilt = HistoryCompactor.rebuild(exchanges, keep, compactNotes)
        while (keep < exchanges.size - 1) {
            val next = HistoryCompactor.rebuild(exchanges, keep + 1, compactNotes)
            if (!HistoryCompactor.pairingOk(next)) break
            if (estimateWithKeep(exchanges, keep + 1) > target) break
            keep++
            rebuilt = next
        }
        if (!HistoryCompactor.pairingOk(rebuilt)) {
            // 宁可这次不压缩（退回原历史），也不能发一段非法对话出去——那会让整轮任务以难以定位的
            // 远端报错结束。这里保留原历史，让上层按"压缩没生效"继续走预算判定。
            workspace.appendEvent("agent_compact", JSONObject().put("ok", false).put("reason", "压缩后 tool_calls 与结果不再配对，已放弃本次压缩").toString(), runId)
            return 0
        }
        messages = rebuilt
        return exchanges.dropLast(keep).sumOf { it.size }
    }

    /**
     * 试算"保留 [keep] 个交换"时真正会发出去的上下文有多大。
     *
     * 必须算**省略后**的形态：`requestMessages()` 会把旧的 write_file 全文换成占位说明，实测能省掉一大半，
     * 按未省略的原史试算会把水位抬得虚高，等于没压。这里临时借用 [messages] 只是为了复用同一段省略逻辑，
     * 试算完立刻还原——`next()` 随后那次 `requestMessages()` 会把计数器重算成真实值。
     */
    private fun estimateWithKeep(exchanges: List<List<JSONObject>>, keep: Int): Int {
        val saved = messages
        return try {
            messages = HistoryCompactor.rebuild(exchanges, keep, compactNotes)
            ContextTokens.of(requestMessages(), SYSTEM, tools)
        } catch (error: Exception) {
            Int.MAX_VALUE
        } finally {
            messages = saved
        }
    }

    /** 结构化笔记：只写"现在处于什么状态"，不重复过程。 */
    private fun buildNotes(task: String): String = buildString {
        append("【上下文压缩】更早的对话过程已从这里移除（内容在工作区里都是最新的）。这是当前状态：\n")
        append("任务原文：").append(task.take(1200)).append('\n')
        if (planSteps.isNotBlank()) append("计划：\n").append(planSteps.take(1500)).append('\n')
        append("文件（共 ${files.size} 个，路径 + 字符数）：")
        append(files.entries.joinToString("、") { "${it.key}(${it.value.length})" }.take(1200)).append('\n')
        append("文件改动次数：$edits；已通过验证的版本：" + if (verified >= 0 && verified == edits) "是（当前版本）" else "否").append('\n')
        lastVerifySummary?.let { append("最近一次预览验证：$it\n") }
        if (lastVerifySignature.isNotBlank()) append("上次未通过的项：").append(lastVerifySignature.take(400)).append('\n')
        append("需要任何文件内容时用 search_files / read_file / list_files 重新读取，不要凭记忆假设；文件内容永远以工作区为准。")
    }

    /**
     * 项目内检索（宿主侧 grep）。
     *
     * 设计要点，每条都有原因：
     * - **默认字面匹配**：模型最常做的是"找这个选择器/函数名在哪一行"，正则不是默认需求，而字面匹配没有回溯风险。
     * - **正则要防 ReDoS**：`(a+)+` 这类嵌套量词在长行上会指数级回溯，把工具调用卡死。这里不用"拒绝某些写法"这种
     *   猜谜做法，而是给待匹配的字符序列套一层**截止时间**：匹配过程中每读一个字符都检查是否超时，超时就中断并返回
     *   可操作的错误。Java 的 Matcher 会不断调用 `charAt`，所以这个包装真的能打断灾难性回溯（实测见文档）。
     * - **上下文行**：只回命中行时，模型常常要看"这段到底属于哪个规则"，于是又去 read_file 翻。before/after 一次给全更省。
     * - **glob 与 path 同时生效**：glob 回答"哪类文件"，path 回答"在哪个目录"，两者是交集，符合直觉。
     * - **返回里带 truncated / matchedFiles**：让模型能判断"是只有这些，还是被截断了"，避免它把截断当成全集。
     */
    private fun searchFiles(args: JSONObject): JSONObject {
        val pattern = args.getString("pattern")
        require(pattern.isNotBlank()) { "pattern 不能为空。" }
        require(pattern.length <= 200) { "pattern 过长（${pattern.length} 字符，上限 200）。" }
        val useRegex = args.optBoolean("regex", false)
        val ignoreCase = args.optBoolean("ignoreCase", true)
        val wholeWord = args.optBoolean("wholeWord", false)
        val pathFilter = args.optString("path").takeIf { it.isNotBlank() }
        val glob = args.optString("glob").takeIf { it.isNotBlank() }
        val before = args.optInt("before", 0).coerceIn(0, 3)
        val after = args.optInt("after", 0).coerceIn(0, 3)
        val filesOnly = args.optBoolean("filesOnly", false)
        val limit = args.optInt("maxResults", 30).coerceIn(1, 60)

        val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val literal = if (wholeWord) "\\b${Regex.escape(pattern)}\\b" else null
        val regex = Regex(
            when {
                wholeWord && useRegex -> "(?:$pattern)\\b"
                literal != null -> literal
                useRegex -> pattern
                else -> Regex.escape(pattern)
            },
            flags
        )
        val globRegex = glob?.let { Regex("^" + it.split("/").joinToString("/") { part ->
            part.split("*").joinToString("[^/]*") { Regex.escape(it) }
        } + "$", setOf(RegexOption.IGNORE_CASE)) }

        val matches = JSONArray()
        val matchedFiles = linkedSetOf<String>()
        var total = 0
        var timedOut = 0
        val deadline = System.currentTimeMillis() + SCAN_DEADLINE_MS
        for (path in files.keys.sorted()) {
            if (System.currentTimeMillis() > deadline) { timedOut = -1; break }
            if (pathFilter != null && !path.contains(pathFilter)) continue
            if (globRegex != null && !globRegex.matches(path)) continue
            val lines = files.getValue(path).lines()
            lines.forEachIndexed { index, line ->
                if (line.length > MAX_SCAN_LINE) return@forEachIndexed
                val hit = runCatching { regex.containsMatchIn(Deadline(line, deadline)) }
                    .getOrElse { if (it is ScanTimeout) { timedOut++; false } else throw it }
                if (!hit) return@forEachIndexed
                total++
                matchedFiles += path
                if (filesOnly) return@forEachIndexed
                if (matches.length() >= limit) return@forEachIndexed
                matches.put(JSONObject().put("path", path).put("line", index + 1).put("text", line.trim().take(240)).apply {
                    if (before > 0) put("before", JSONArray().apply {
                        (maxOf(0, index - before) until index).forEach { put(lines[it].trim().take(200)) }
                    })
                    if (after > 0) put("after", JSONArray().apply {
                        (index + 1..minOf(lines.lastIndex, index + after)).forEach { put(lines[it].trim().take(200)) }
                    })
                })
            }
        }
        val truncated = total > matches.length()
        return JSONObject().put("ok", timedOut == 0).put("pattern", pattern)
            .put("mode", if (useRegex) "regex" else "literal").put("matches", matches)
            .put("files", JSONArray(matchedFiles.toList()))
            .put("totalMatches", total).put("matchedFiles", matchedFiles.size).put("truncated", truncated)
            .apply { if (timedOut != 0) put("timedOut", true) }
            .also {
                it.put("note", when {
                    timedOut != 0 -> "正则匹配超时（已中断，避免把工具调用卡死）。请把表达式改写成不会指数级回溯的形式：避免 (a+)+ / (.*)* 这类嵌套量词，用 [^\\s]* 或更具体的字面片段；也可以用字面模式先定位大概位置。"
                    filesOnly && matchedFiles.isNotEmpty() -> "这些文件里有命中。挑一个用 read_file 精读，或去掉 filesOnly 看行号。"
                    matches.length() == 0 -> if (useRegex) "没有匹配。先确认表达式本身（可以用更短的正则试一次），或改回字面匹配搜一个确定出现的片段。"
                        else "没有匹配。换个更短、更确定的字面文本再搜（例如只搜选择器或函数名的一半）；确实需要按模式找时再开 regex=true。"
                    truncated -> "命中 $total 处，只返回前 ${matches.length()} 处。用更具体的 pattern、glob 或 path 缩小范围，再用 read_file 的 startLine/endLine 精读，然后 patch_file。"
                    else -> "定位到行号后，用 read_file 的 startLine/endLine 精读该区间，再用 patch_file 修改。不要凭记忆猜 patch 的 old 内容。"
                })
            }
    }

    /** 匹配超时：由 [Deadline] 在匹配过程中抛出，用于中断灾难性回溯。 */
    private class ScanTimeout : RuntimeException("scan timeout")

    /**
     * 带截止时间的字符序列。Matcher 每次取字符都会经过这里，所以超时能在**匹配进行中**生效——
     * 这是不被 ReDoS 卡死的唯一可靠做法（在循环外面检查时间没用，卡住的是单次 match 调用）。
     */
    private class Deadline(private val text: String, private val until: Long) : CharSequence {
        private var checked = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if (++checked % 2048 == 0 && System.currentTimeMillis() > until) throw ScanTimeout()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
    }

    /**
     * 补丁失败时的可操作诊断。
     *
     * 只回一句「不匹配，请重新读取」时，模型最省事的恢复方式是整篇重写——实测那正是上下文里
     * 最大的两块新增内容（一次 16–18k 字符）。给出最接近的行号和内容，模型才能改对参数。
     */
    private fun patchHint(previous: String, old: String): String {
        val lines = previous.lines()
        val probe = old.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40).orEmpty()
        if (probe.isBlank()) return "该文件共 ${lines.size} 行。"
        val index = lines.indexOfFirst { it.contains(probe) }
        return if (index >= 0) "最接近的是第 ${index + 1} 行：「${lines[index].trim().take(80)}」。请据此校正 old 后重试；要确认上下文用 read_file 的 startLine/endLine 读该行附近，不要改用整篇 write_file。"
        else "该文件共 ${lines.size} 行，未找到相近内容；请先用 search_files 按字面文本定位真实内容，再用 read_file 的 startLine/endLine 读该区间后重试，不要改用整篇 write_file。"
    }

    /** 整篇重写且改动很小时给出提示：这是当前最大的新增 token 来源。 */
    private fun rewriteNote(previous: String, content: String): String? {
        if (previous == content) return "本次写入与现有内容完全相同，文件没有变化。"
        val newLines = content.lines()
        if (newLines.size < 20) return null
        val oldLines = previous.lines().toHashSet()
        val unchanged = newLines.count { it in oldLines }
        val ratio = unchanged.toDouble() / newLines.size
        return if (ratio >= 0.85) "本次整篇重写有 ${(ratio * 100).toInt()}% 的行与现有内容相同。这类小改动请改用 patch_file，否则整个文件每轮都会重新计入上下文。" else null
    }

    private fun compactReport(report: JSONObject): JSONObject {
        val assertions = report.optJSONArray("assertions") ?: JSONArray()
        val failed = JSONArray()
        for (index in 0 until assertions.length()) {
            val item = assertions.optJSONObject(index) ?: continue
            if (!item.optBoolean("passed", true)) failed.put(item)
        }
        // 布局问题的「被谁挡住、挡在哪个坐标」必须原样带给模型：只给一句"3 个元素被遮挡"它只能猜，
        // 实测会退化成用 document.title / console.error 自行打点，把 24 轮预算烧光。
        val layout = report.optJSONObject("layout")?.let { source ->
            val blocked = source.optJSONArray("blocked") ?: JSONArray()
            JSONObject()
                .put("viewport", source.optJSONArray("viewport") ?: JSONArray())
                .put("blocked", JSONArray().apply { for (index in 0 until minOf(blocked.length(), 8)) put(blocked.getJSONObject(index)) })
                .put("blockedCount", blocked.length())
                .put("fixedCoverage", source.optDouble("fixedCoverage", 0.0))
                .put("fixedTags", source.optJSONArray("fixedTags") ?: JSONArray())
                .put("overflowCount", source.optInt("overflowCount"))
                .put("overflowSamples", source.optJSONArray("overflowSamples") ?: JSONArray())
                .put("smallTargets", source.optJSONArray("smallTargets") ?: JSONArray())
        }
        val consoleMessages = report.optJSONArray("consoleMessages") ?: JSONArray()
        // 把失败**分成两类**并各自给出行动指引。这是实测出来的最大浪费来源：
        // 模型自己写的断言不适用于当前视图时，它会默认「页面错了」，回头去翻代码——一次假失败
        // 实测烧掉 8 轮。断言是模型自己写的，它必须知道那条也可能错。
        val pageFailures = JSONArray().apply {
            val layoutProblems = report.optJSONArray("layoutProblems") ?: JSONArray()
            for (index in 0 until layoutProblems.length()) put(layoutProblems.getString(index))
            val errors = report.optJSONArray("errors") ?: JSONArray()
            for (index in 0 until errors.length()) put(errors.getString(index))
            val designIssues = report.optJSONArray("designIssues") ?: JSONArray()
            for (index in 0 until designIssues.length()) put(designIssues.getString(index))
        }
        val guidance = buildString {
            if (failed.length() > 0) append("有 ${failed.length()} 条**你自己写的断言**失败。先确认断言本身是否适用于当前视图（选择器是否只存在于某个页面、时机是否太早、期望值是否写反），再决定改页面——不要一看到失败就去翻代码修页面。")
            if (pageFailures.length() > 0) {
                if (isNotEmpty()) append(" ")
                append("另有 ${pageFailures.length()} 项**页面本身的问题**（在 layout / errors / designIssues 里），这些才该改页面。")
            }
            if (isEmpty()) append("失败原因既不在断言也不在审计项里，请查看 errors 与 reason 字段。")
        }
        return JSONObject()
            .put("passed", report.optBoolean("passed"))
            .put("title", report.optString("title"))
            .put("nodes", report.optInt("nodes"))
            .put("horizontalOverflow", report.optBoolean("horizontalOverflow"))
            .put("errors", report.optJSONArray("errors") ?: JSONArray())
            .put("consoleMessages", JSONArray().apply { for (index in 0 until minOf(consoleMessages.length(), 5)) put(consoleMessages.getString(index)) })
            .put("layoutProblems", report.optJSONArray("layoutProblems") ?: JSONArray())
            .put("layout", layout ?: JSONObject.NULL)
            .put("designIssues", report.optJSONArray("designIssues") ?: JSONArray())
            .put("assertionCount", assertions.length())
            .put("failedAssertions", failed)
            .put("pageFailures", pageFailures)
            .put("failureGuidance", guidance)
            .put("controlCount", (report.optJSONArray("controls") ?: JSONArray()).length())
    }

    private fun requestMessages(): JSONArray {
        // 压缩过的历史只影响"发出去的内容"，messages 本身保留完整结构，见 compactHistory()。
        val keepFrom = (messages.length() - COMPACT_KEEP_RECENT_MESSAGES).coerceAtLeast(0)
        // 先算出每个文件**最后一次**被完整写入的位置。只有被后续写入取代的旧内容才省略：
        // 如果连最新内容也拿掉，模型看不到自己写了什么，就会整篇重写——实测写文件次数会从 4 次涨到 10 次，
        // 总轮数从 16 涨到 24 并撞上预算上限，反而更贵。
        val lastWrite = mutableMapOf<String, Int>()
        for (index in 0 until messages.length()) {
            val calls = messages.optJSONObject(index)?.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                val function = calls.optJSONObject(callIndex)?.optJSONObject("function") ?: continue
                if (function.optString("name") != "write_file") continue
                val path = runCatching { JSONObject(function.optString("arguments")).optString("path") }.getOrDefault("")
                if (path.isNotBlank()) lastWrite[path] = index
            }
        }
        lastCompactedChars = 0
        lastCompactedParts = 0
        return JSONArray().apply {
            for (index in 0 until messages.length()) {
                val original = messages.optJSONObject(index) ?: continue
                if (index >= keepFrom) {
                    put(JSONObject(original.toString()))
                    continue
                }
                val message = JSONObject(original.toString())
                message.optJSONArray("tool_calls")?.let { calls ->
                    for (callIndex in 0 until calls.length()) {
                        val function = calls.optJSONObject(callIndex)?.optJSONObject("function") ?: continue
                        if (function.optString("name") != "write_file") continue
                        val arguments = function.optString("arguments")
                        if (arguments.length <= COMPACT_INLINE_CHARS) continue
                        val path = runCatching { JSONObject(arguments).optString("path") }.getOrDefault("")
                        // 该文件的最终内容后来又被写过，这份才是可以安全丢弃的旧副本。
                        if (lastWrite[path] == index) continue
                        lastCompactedChars += arguments.length
                        lastCompactedParts++
                        function.put("arguments", JSONObject().put("path", path)
                            .put("note", "这是 $path 的旧版本（原 ${arguments.length} 字符），已被后续写入取代，故从上下文省略；文件在工作区里是最新的。").toString())
                    }
                }
                if (message.optString("role") == "tool") {
                    val content = message.optString("content")
                    if (content.length > COMPACT_INLINE_CHARS) {
                        val ok = runCatching { JSONObject(content).optBoolean("ok", true) }.getOrDefault(true)
                        lastCompactedChars += content.length
                        lastCompactedParts++
                        message.put("content", JSONObject().put("ok", ok)
                            .put("note", "第 ${index + 1} 条历史里的工具结果（原 ${content.length} 字符）已省略；需要重新确认请再次调用对应工具。").toString())
                    }
                }
                put(message)
            }
        }
    }
    private fun cancel() = synchronized(this) { provider.cancel(); end("cancelled", "已停止。未发布的修改保留在草稿中。", AgentTerminalReason.CANCELLED) }
    @Synchronized private fun end(status: String, message: String, reason: AgentTerminalReason = when (status) {
        "completed" -> AgentTerminalReason.COMPLETED
        "cancelled" -> AgentTerminalReason.CANCELLED
        else -> AgentTerminalReason.UNKNOWN
    }) {
        if (stopped) return
        stopped = true
        active.remove(conversationId, this)
        if (reason == AgentTerminalReason.CANCELLED) graph.move(GraphNode.CANCELLED, reason.name)
        else if (reason != AgentTerminalReason.COMPLETED) graph.move(GraphNode.FAILED, reason.name)
        workspace.appendEvent("graph_state", graph.checkpoint().toString(), runId)
        workspace.updateRun(runId, status, status, message, reason.name)
        workspace.appendEvent("agent_terminal", AgentLoopMetrics(turns, totalToolCalls, edits, modelErrors, toolErrors, duplicateToolCalls, System.currentTimeMillis() - startedAt, reason).toJson().toString(), runId)
        if (status != "completed") saveDraft()
        workspace.appendEvent("agent_message", message, runId)
        callback(AgentDisplayState(if (status == "completed") "已完成" else "需要处理", if (status == "completed") "小程序已保存" else "任务已停止", message))
    }
}
