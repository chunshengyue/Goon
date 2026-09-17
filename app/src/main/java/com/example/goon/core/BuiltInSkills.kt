package com.example.goon.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Product-owned guidance packs. They are prompt resources, not executable code. */
object BuiltInSkills {
    data class Skill(val id: String, val title: String, val summary: String, val body: String)

    /**
     * 从 assets/skills/<id>/ 原样装载的第三方 skill。
     *
     * 为什么原样搬而不是自己改写：这类 skill 是社区反复迭代出来的（有的还有 P0/P1/P2 分级与版本号），
     * 自己总结一版等于把别人的经验压成我们的偏见。所以只做两件事——**原样装载** + 在 SOURCE.md
     * 里记录上游、commit 与许可，平台差异单独写在平台说明里，不改进原文一个字。
     */
    private class Vendored(val skill: Skill, val files: Map<String, String>)

    private val vendored = linkedMapOf<String, Vendored>()
    private var installed = false

    /** 迭代顺序决定索引里的先后，通用设计 skill 要排在最前面。 */
    private val vendoredOrder = listOf("no-ai-slop", "avoid-ai-design")

    /** 在 App 启动时调一次；重复调用无副作用。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val assets = context.applicationContext.assets
        for (id in vendoredOrder) {
            val files = linkedMapOf<String, String>()
            runCatching {
                val names = assets.list("skills/$id").orEmpty().toMutableList()
                assets.list("skills/$id/references").orEmpty().forEach { names += "references/$it" }
                for (name in names) {
                    if (!name.endsWith(".md") || name == "SOURCE.md") continue
                    val text = assets.open("skills/$id/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                    if (name == "SKILL.md") files["SKILL.md"] = text else files[name] = text
                }
            }
            val skill = files["SKILL.md"] ?: continue
            val name = frontmatter(skill, "name") ?: id
            val description = frontmatter(skill, "description") ?: ""
            val extra = files.keys.filter { it != "SKILL.md" }
            val body = buildString {
                append(skill.trim())
                if (extra.isNotEmpty()) {
                    append("\n\n---\n本技能随包附带的完整清单（原文，未改写）：")
                    append(extra.joinToString("、") { "`$it`" })
                    append("。需要时用 read_skill 的 file 参数按名读取，例如 read_skill(id=\"$id\", file=\"")
                    append(extra.first())
                    append("\")。")
                }
                append("\n\n（来源与许可见 assets/skills/$id/SOURCE.md 与 LICENSE；正文按上游原样引入。）")
            }
            vendored[id] = Vendored(Skill(id, name, description.take(200), body), files)
        }
    }

    private fun frontmatter(markdown: String, key: String): String? {
        val end = markdown.indexOf("\n---", 3)
        if (!markdown.startsWith("---") || end < 0) return null
        return markdown.substring(3, end).lineSequence()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(":")
            ?.trim()
    }

    private val skills = listOf(
        Skill("product-ui", "产品 UI", "把需求拆成可扫描、可操作、可恢复的移动端界面。", """
            先明确用户、主任务和成功状态，再设计页面。一个页面只保留一个主要动作层级。
            必须覆盖 loading、空数据、错误、权限拒绝、成功反馈和网络/离线状态；不要用解释段落代替状态。
            使用真实可操作控件、稳定的 data-testid/id、清晰的表单标签和触摸尺寸。移动端优先，避免横向溢出和内容遮挡。
            复杂页面拆成可复用的 header、navigation、list、form、dialog、empty-state 等小模块，保持 DOM 和 CSS 结构可读。
        """.trimIndent()),
        Skill("visual-design", "视觉审美", "选择有记忆点的视觉方向并把它执行完整。", """
            开始前先选择一个明确方向：极简、编辑、工业、自然、复古、奢华或其他与产品场景相符的方向，避免默认模板风格。
            使用 CSS variables 管理色彩、间距、圆角、阴影和字体层级。颜色服务于内容层级，不要堆渐变、紫色背景、无意义的玻璃卡片或装饰性光球。
            为标题、正文、辅助文字和数字选择有层次的字体组合；避免所有页面都使用同一种默认字体和相同卡片布局。
            页面必须有一个可识别的视觉记忆点。动效只强调进入、状态变化和反馈，尊重 prefers-reduced-motion。
            优先用真实上下文图片、内联 SVG 或项目资源；找不到资源时使用有语义的占位，不声称存在未提供的图片。
        """.trimIndent()),
        Skill("interaction", "交互设计", "让每个动作、状态变化和失败路径都可理解。", """
            所有按钮都应有明确动作、禁用状态和完成反馈；表单要有输入校验、错误位置和可继续路径。
            列表、筛选、排序、分页和导航要保留用户上下文；删除、覆盖和清空需要确认或可撤销。
            使用事件委托或模块化事件绑定，避免重复监听。状态变化应有单一来源，刷新后需要保留的内容写入 localStorage 并处理损坏数据。
            键盘、触摸和读屏语义不能依赖颜色或 hover。焦点、active、pressed、selected、loading 都要可见。
        """.trimIndent()),
        // 上游 Anthropic frontend-design 原文（逐字保留，未改写）。
        // 这里不再追加自造的建议：设计判断交给随包引入的社区 skill（no-ai-slop / avoid-ai-design），
        // 我们只负责运行时事实与审计契约。
        Skill("frontend-design", "前端视觉实现", "把设计意图落成有辨识度的 HTML/CSS，而不是默认模板。", """
            本技能指导创作有辨识度、production 级的前端界面，避免通用的 "AI slop" 审美。用真实可运行的代码实现，
            并对美学细节与创意选择投入异常的关注。

            用户会给出前端需求：一个组件、页面、应用或界面，也可能带上用途、受众或技术约束。

            ## Design Thinking

            动手之前先理解语境，并承诺一个明确的（BOLD）审美方向：
            - **Purpose**：这个界面解决什么问题？给谁用？
            - **Tone**：选一个极端——极端极简、极繁混乱、复古未来、有机自然、奢华精致、玩具般的俏皮、杂志编辑风、粗野主义、
              装饰几何、柔和粉彩、工业实用……（可作灵感，但要选一个真正贴合的方向）
            - **Constraints**：技术约束（框架、性能、可访问性）。
            - **Differentiation**：什么让它令人难忘？别人会记住的那一件事是什么？

            **关键**：选一个清晰的概念方向，并精确执行。大胆的极繁与克制的极简都可以——关键在于**意图性**，而不是强度。

            然后实现可运行的代码，要求：production 级且真正能用；视觉上引人注目、令人记住；与明确的审美观点一致；每一处细节都打磨过。

            ## Frontend Aesthetics Guidelines

            - **Typography**：选择好看、独特、有趣的字体；避免 Arial、Inter 这类通用选择；用一个有个性的显示字体搭配精致的正文字体。
            - **Color & Theme**：承诺一套统一的审美；用 CSS variables 保持一致；有主导色 + 锐利强调色，胜过怯生生的平均分布色板。
            - **Motion**：动效用于效果与微交互；HTML 优先纯 CSS；把力气用在关键时刻——一次编排好的页面入场（错开延迟）
              比散落各处的微交互更令人愉悦。
            - **Spatial Composition**：反常规的布局、不对称、重叠、斜向流动、打破网格的元素；要么大留白，要么有控制的密度。
            - **Backgrounds & Visual Details**：制造氛围与景深，而不是默认纯色；加入与整体审美一致的纹理与效果。

            绝不要使用通用的 AI 审美：被用滥的字体系列（Inter、Roboto、Arial、系统字体）、陈词滥调的配色（尤其是白底紫色渐变）、
            可预测的布局与组件模式、以及缺乏语境特征的模板化设计。

            创造性地诠释，做出真正为这个语境而设计的意外选择。每一次设计都不应该一样：在浅色/深色、
            不同字体、不同审美之间变化，**不要跨次收敛到同一批"安全"选择**。

            **重要**：实现复杂度要与审美愿景匹配。极繁需要大量动效与细节；极简或精致需要克制、精确，以及对间距、
            字体和微妙细节的仔细关注。优雅来自把愿景执行好。
        """.trimIndent()),
        Skill("app-interface-design", "移动 App 界面", "按移动端操作习惯组织页面、导航与反馈。", """
            页面骨架固定三层：顶部栏（标题 + 返回/主操作）、可滚动内容区、底部操作区或标签栏。内容区滚动，其余两层固定。
            主导航二选一：底部标签栏（2–5 个平级入口，图标 + 文字，当前项用主色）或顶部标题 + 返回的层级导航。
            不要同时用底部标签栏和汉堡菜单表达同级导航；不要用底部标签栏做只有 1 个入口的页面。
            列表项结构统一为「左：主信息（标题 + 次要说明）／右：数值或状态」，点击整行而不是只点文字；行高不小于 56px。
            表单：标签在上方、输入框占满宽度、错误信息紧贴输入框下方并说明如何修正；提交按钮在底部操作区，校验失败时聚焦第一个错误项。
            状态反馈必须可见：加载用骨架屏或进度条（超过 300ms 才显示），成功用 toast 或行内提示，破坏性操作要二次确认且说明后果。
            空状态要给出下一步动作，不要只写「暂无数据」。离线、无权限、失败都要有各自的文案和出口。
            安全区：顶部内容避开状态栏，底部固定元素避开手势条，输入框聚焦时不被键盘遮挡。
            触控优先：不要依赖 hover 表达信息，手势（长按、滑动）必须同时提供可见按钮替代。
            数字、金额、进度使用等宽字体或 tabular-nums 对齐，避免数字跳动造成布局抖动。
        """.trimIndent()),
        Skill("motion-design", "动效设计", "用克制的运动表达层级、因果与状态变化。", """
            动效只服务于三件事：说明元素从哪来（进入）、发生了什么（状态变化）、操作是否生效（反馈）。装饰性动画一律不加。
            时长按距离与层级取：微反馈 100–150ms，控件状态 150–200ms，面板/页面切换 220–320ms，超过 400ms 会显得迟钝。
            缓动区分方向：进入用 ease-out（快起慢停），退出用 ease-in，位移与尺寸变化用 cubic-bezier(0.2, 0, 0, 1)，避免默认 linear 和 ease-in-out 滥用。
            只动 transform 和 opacity，不动 width/height/top/left，避免触发布局与重绘；需要展开收起时用 grid-template-rows 或 max-height 过渡。
            隔层错开：列表项进入用 animation-delay 递增 20–40ms，超过 8 项就不要再错开。
            交互反馈：按下时缩放 0.97 或降低亮度，100ms 内响应；加载用不确定进度条或骨架屏而不是无限旋转按钮。
            弹层要有明确的进入与退出，背景遮罩同步淡入；关闭后焦点回到触发元素。
            必须写 @media (prefers-reduced-motion: reduce) 分支：关闭位移与缩放，只保留 ≤100ms 的透明度变化。
            动画不能阻塞交互：加 pointer-events 与 will-change 要克制，动画结束后清理临时类名，避免长时间驻留合成层。
            用 CSS transition/animation 实现即可，不要引入动画库；复杂序列用 Web Animations API（element.animate），不要用 setInterval 逐帧改样式。
        """.trimIndent()),
        Skill("project-engineering", "项目工程", "让模型生成的文件可读、可继续修改、可回滚。", """
            默认使用 index.html、styles/、logic/、assets/，按职责拆文件；文件名和相对引用稳定，避免把整个应用塞进一个 HTML。
            先读取现有文件再 patch；小修改用精确替换，避免无关重写。不要引入 npm、CDN 或不必要框架，当前运行时没有构建步骤。
            用 manifest 声明名称、入口、版本和权限。不要把 API Key、Cookie、设备数据或真实凭据写入项目。
            每轮修改都应保留可运行版本；先保存草稿，验证通过后再 finish。保持用户已有功能，除非请求明确要求移除。
        """.trimIndent()),
        Skill("verification", "验证工具", "用真实运行反馈证明功能，而不是凭代码外观宣布完成。", """
            最后一次文件修改后必须 run_preview。验证主路径，而不是只断言标题：输入、点击、筛选、导航、保存和错误提示至少覆盖用户核心需求。
            使用稳定选择器和短小的 click/type/assertText/assertCount/assertVisible 步骤；发现 console、资源、断言或溢出问题就继续修复。
            预览失败时向用户报告具体错误和下一步，不要把静态语法通过当成运行成功。没有证据不能声称已完成。
            视觉质量需要同时检查手机宽度、内容层级、空状态和加载状态；自动化断言不能替代必要的截图或人工视觉检查。
        """.trimIndent()),
        Skill("runtime-safety", "运行安全", "保持小程序权限、资源和执行边界可审计。", """
            当前项目默认离线，只使用本地 HTML/CSS/JS、localStorage 和内联资源。禁止 CDN、外部 import、iframe、worker、eval、任意下载和未声明网络。
            不伪造支付、账号、定位、相机、地图、上传、多人同步或后台持续执行；缺少宿主能力时明确降级为本地模拟。
            文件路径必须是项目相对路径，权限只由 manifest 声明。任何未来 Bridge 都应有能力名、输入输出 schema、用户可见权限和可取消错误。
        """.trimIndent())
    )

    /**
     * 平台适用性。skill 是提示资源，不增加运行权限；但有些 skill 假设的能力在 Android 宿主上并不存在，
     * 因此必须能对用户明确回答「能不能用、有没有参考价值」，而不是笼统说「已加载」。
     */
    private val platformNotes = mapOf(
        "no-ai-slop" to ("partial" to "反 AI 味的通用清单，绝大多数条目直接适用；但它假设可以加载 Web 字体、可以用图库照片——本项目离线，字体只能用系统字体栈，所以「不要用默认字体」要读成「不要让整页只有一种字体、要有角色分工」。"),
        "avoid-ai-design" to ("partial" to "审计 + 重写流程与我们的 run_preview 天然吻合（它自己就要求「先渲染再判断」）；Tailwind/shadcn 的类名条目要按等价的原生 CSS 理解。"),
        "product-ui" to ("usable" to "直接影响小程序的信息结构与状态覆盖，纯 HTML/CSS/JS 即可落地。"),
        "visual-design" to ("usable" to "决定色彩、字体层级与视觉记忆点，可完全落地。"),
        "interaction" to ("usable" to "表单校验、列表状态、确认与撤销都能在本地实现。"),
        "frontend-design" to ("usable" to "纯 CSS 与内联 SVG，不依赖任何外部资源，可完全落地。"),
        "app-interface-design" to ("usable" to "移动端骨架、安全区与触控尺寸都能落地。"),
        "motion-design" to ("usable" to "CSS transition/animation 与 Web Animations API 均可用。"),
        "project-engineering" to ("usable" to "文件拆分与相对引用属于项目组织规则，直接适用。"),
        "verification" to ("partial" to "预览只支持同步 DOM 断言；异步请求、多页导航等待、游戏帧循环和真实网络请求无法验证，这部分只有部分参考价值。"),
        "runtime-safety" to ("usable" to "隔离边界规则直接适用；但它提到的宿主 Bridge、相机、定位、网络等能力当前并未开放，遇到这类需求只能降级或明确拒绝。")
    )

    /**
     * 必须按需计算，不能用 val 缓存。
     *
     * 这里踩过一次坑：最初写成 `private val byId = ...`，而 Kotlin 的 object 属性在类初始化时就求值一次——
     * 那时 `install()` 还没被调用，`vendored` 是空的，于是随包引入的 skill 永远进不了索引（接口返回 9 个而非 11 个）。
     */
    private fun registry(): LinkedHashMap<String, Skill> = linkedMapOf<String, Skill>().apply {
        vendoredOrder.forEach { id -> vendored[id]?.let { put(id, it.skill) } }
        skills.forEach { put(it.id, it) }
    }
    private fun applicability(id: String) = platformNotes[id]?.first ?: "usable"
    private fun platformNote(id: String) = platformNotes[id]?.second ?: ""
    fun allDocuments(): List<RagDocument> = registry().values.map { RagDocument(it.id, it.title, it.body, "builtin://skills/${it.id}") }
    val summary: String get() = registry().values.joinToString("\n") { "- ${it.id}: ${it.summary}" }
    val catalog: String get() = registry().values.joinToString("\n") { "- ${it.id}（${it.title}，${applicability(it.id)}）：${it.summary}${platformNote(it.id).let { note -> if (note.isBlank()) "" else " 平台说明：" + note }}" }

    /**
     * 索引：只给 id + 一句话。放在系统提示里，模型不必先花一轮 list_skills 才知道有什么。
     */
    val prompt: String = """
可用 skill 索引（要全文才 read_skill，按需取，不是清单）：
$summary
""".trimIndent()

    /**
     * 界面硬规则：这些数值与禁止项由宿主审计强制，和 skill 全文无关，所以直接内联进系统提示。
     *
     * 为什么不放在 skill 里让模型读：实测过一次运行里 read_skill 被调用 9 次（把 9 个 skill 全读了），
     * 每次调用都占一轮、而且读到的全文之后每一轮都要重发。设计规则是每个界面任务的公共前提，
     * 内联一次反而更省——而且不会因为模型没挑对 skill 就完全没有规则可依。
     */
    val uiRules: String = """
这一节只写**这个运行时的硬事实与审计契约**，不写设计主张——设计判断一律按随包引入的社区 skill 执行
（`no-ai-slop`、`avoid-ai-design`、`frontend-design`），它们比我们自己总结的更完整、也更少偏见。

运行时事实（这些不是建议，是限制）：
A) 完全离线：不能外链字体、图片、脚本；字体只能用系统字体栈，图片只能用素材库里已声明的素材或用户给的图片。
   所以"换个独特字体"在本项目里做不到——差异要靠字重、字号、字距，以及衬线/等宽/无衬线的角色分工来做。
B) 运行时无构建步骤、无框架；用标准 HTML/CSS/JS。没有 worker、没有 wasm、没有外部请求。
C) 预览截图是**离屏软件渲染**，`mix-blend-mode` 画不出来（会显示成一块生图）。要叠素材用 opacity + filter。

审计契约（run_preview 会真的判，不是风格建议）：
1) 每个 `click` 后面两步内必须有断言；点下去整页 HTML 没有任何变化的按钮直接判失败（反馈要同步插进 DOM 再播动画）。
2) 文字与它背后的实际底色算 WCAG 对比度，正文 <4.5:1（留 0.5 余量）判为看不清。
3) 可点击元素不能被遮挡，不能超出屏幕宽度，点击目标 ≥44×44；固定底栏不得压住正文。
4) 被祖先 opacity 藏起来的可点击元素会被点名：`display:none`/`visibility:hidden` 不算，只把 opacity 置 0 的会继续吃点击。
5) 判定"默认模板"用的是**一致性**，不是装饰量。三条：清一色圆角卡片且首屏没有别的结构骨架；字号最大/最小 <1.8 倍；
   不同圆角值 ≥4 种。**没有任何一条是"装饰不够多"**——为凑设计感而堆渐变、伪元素、圆角值同样是被否的方向。
""".trimIndent()

    fun listJson(): JSONArray = JSONArray().apply {
        registry().values.forEach {
            put(JSONObject().put("id", it.id).put("title", it.title).put("summary", it.summary)
                .put("applicability", applicability(it.id)).put("platformNote", platformNote(it.id))
                .put("files", JSONArray(vendored[it.id]?.files?.keys?.filter { name -> name != "SKILL.md" } ?: emptyList<String>())))
        }
    }
    fun read(id: String): Skill? = registry()[id]

    /** `read_skill` 的 file 参数：读随包附带的参考文件（原样返回，超长会被截断）。 */
    fun readFile(id: String, file: String): String? = vendored[id]?.files?.get(file)?.let {
        if (it.length <= MAX_REFERENCE_CHARS) it
        else it.take(MAX_REFERENCE_CHARS) + "\n\n（已截断：原文 ${it.length} 字符，这里只返回前 $MAX_REFERENCE_CHARS 字符。需要后面部分请说明你要查的具体条目。）"
    }

    private const val MAX_REFERENCE_CHARS = 24000
    fun detailJson(id: String): JSONObject? = registry()[id]?.let { skill ->
        JSONObject().put("id", skill.id).put("title", skill.title).put("body", skill.body)
            .put("applicability", applicability(skill.id)).put("platformNote", platformNote(skill.id))
            .put("files", JSONArray(vendored[skill.id]?.files?.keys?.filter { name -> name != "SKILL.md" } ?: emptyList<String>()))
    }
}
