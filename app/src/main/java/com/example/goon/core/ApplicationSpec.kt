package com.example.goon.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val CURRENT_SCHEMA_VERSION = 4

data class ApplicationSpec(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val name: String,
    val title: String,
    val description: String = "",
    val accent: String = "blue",
    val theme: String = "light",
    val initialState: Map<String, String> = emptyMap(),
    val pages: List<AppPage>,
    val sourceDsl: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion); put("projectId", projectId); put("name", name); put("title", title)
        put("description", description); put("accent", accent); put("theme", theme); put("initialState", JSONObject(initialState))
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
        if (sourceDsl.isNotBlank()) put("sourceDsl", sourceDsl)
    }.toString()

    companion object {
        fun fromJson(value: String): ApplicationSpec {
            val json = JSONObject(value)
            return when (json.optInt("schemaVersion", 1)) {
                1 -> migrateV1(json)
                2 -> migrateV2(json)
                3 -> migrateV3(json)
                else -> parseV4(json)
            }
        }

        private fun parseV4(json: JSONObject): ApplicationSpec {
            val state = json.optJSONObject("initialState") ?: JSONObject()
            val pages = json.optJSONArray("pages") ?: JSONArray()
            return ApplicationSpec(
                schemaVersion = json.optInt("schemaVersion", -1), projectId = json.optString("projectId"), name = json.optString("name"),
                title = json.optString("title"), description = json.optString("description"), accent = json.optString("accent", "blue"),
                theme = json.optString("theme", "light"), initialState = state.keys().asSequence().associateWith { state.optString(it) },
                pages = List(pages.length()) { AppPage.fromJson(pages.getJSONObject(it)) }, sourceDsl = json.optString("sourceDsl")
            )
        }

        fun starter() = checklist("local-checklist", "我的清单", "今天要做什么？")
        fun blankProjectId(): String = "app-${UUID.randomUUID().toString().take(8)}"

        fun checklist(projectId: String, name: String, title: String) = ApplicationSpec(
            projectId = projectId, name = name, title = title, description = "轻轻记下，逐项完成。", theme = "light",
            initialState = mapOf("newItem" to ""), pages = listOf(
                AppPage("home", title, components = listOf(
                    AppComponent("screen", "column", style = ComponentStyle(gap = 14, padding = 2), children = listOf(
                        AppComponent("hero", "hero", text = title),
                        AppComponent("intro", "text", text = "把今天想做的事情放在这里。", style = ComponentStyle(foreground = "muted")),
                        AppComponent("input", "input", label = "新事项", binding = "newItem", placeholder = "输入后点击添加"),
                        AppComponent("add", "button", label = "添加事项", action = "add_item", binding = "newItem"),
                        AppComponent("progress", "progress", label = "完成进度"), AppComponent("list", "list")
                    ))
                ))
            )
        )

        private fun migrateV1(json: JSONObject): ApplicationSpec = checklist(
            json.optString("projectId", "local-checklist"), json.optString("name", "我的清单"), json.optString("title", "今天要做什么？")
        ).copy(accent = when (json.optString("accent")) { "coral" -> "coral"; "ocean" -> "blue"; else -> "green" })

        private fun migrateV2(json: JSONObject): ApplicationSpec = migrateV3(json)

        private fun migrateV3(json: JSONObject): ApplicationSpec {
            val state = json.optJSONObject("initialState") ?: JSONObject()
            val legacyPages = json.optJSONArray("pages") ?: JSONArray()
            return ApplicationSpec(
                projectId = json.optString("projectId"), name = json.optString("name"), title = json.optString("title"),
                description = json.optString("description"), accent = json.optString("accent", "blue"), theme = "dark",
                initialState = state.keys().asSequence().associateWith { state.optString(it) },
                pages = List(legacyPages.length()) { index ->
                    val page = AppPage.fromJson(legacyPages.getJSONObject(index))
                    page.copy(components = listOf(AppComponent("screen-$index", "column", style = ComponentStyle(gap = 12), children = page.components)))
                }
            )
        }
    }
}

data class AppPage(val id: String, val title: String, val tabLabel: String = "", val components: List<AppComponent>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); if (tabLabel.isNotBlank()) put("tabLabel", tabLabel)
        put("components", JSONArray().apply { components.forEach { put(it.toJson()) } })
    }
    companion object {
        fun fromJson(json: JSONObject): AppPage {
            val components = json.optJSONArray("components") ?: JSONArray()
            return AppPage(json.optString("id"), json.optString("title"), json.optString("tabLabel"), List(components.length()) { AppComponent.fromJson(components.getJSONObject(it)) })
        }
    }
}

data class ComponentStyle(
    val padding: Int = 0, val gap: Int = 8, val radius: Int = 0, val columns: Int = 2, val weight: Float = 1f,
    val align: String = "start", val background: String = "", val foreground: String = "", val textStyle: String = "body", val minHeight: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (padding != 0) put("padding", padding); if (gap != 8) put("gap", gap); if (radius != 0) put("radius", radius)
        if (columns != 2) put("columns", columns); if (weight != 1f) put("weight", weight); if (align != "start") put("align", align)
        if (background.isNotBlank()) put("background", background); if (foreground.isNotBlank()) put("foreground", foreground)
        if (textStyle != "body") put("textStyle", textStyle); if (minHeight != 0) put("minHeight", minHeight)
    }
    companion object {
        fun fromJson(json: JSONObject?) = ComponentStyle(
            padding = json?.optInt("padding") ?: 0, gap = json?.optInt("gap", 8) ?: 8, radius = json?.optInt("radius") ?: 0,
            columns = json?.optInt("columns", 2) ?: 2, weight = json?.optDouble("weight", 1.0)?.toFloat() ?: 1f,
            align = json?.optString("align", "start") ?: "start", background = json?.optString("background") ?: "",
            foreground = json?.optString("foreground") ?: "", textStyle = json?.optString("textStyle", "body") ?: "body", minHeight = json?.optInt("minHeight") ?: 0
        )
    }
}

data class AppComponent(
    val id: String, val type: String, val label: String = "", val text: String = "", val binding: String = "", val action: String = "",
    val source: String = "", val placeholder: String = "", val targetPage: String = "", val value: String = "", val image: String = "", val icon: String = "",
    val showWhen: String = "", val intervalMs: Int = 0, val options: List<String> = emptyList(), val items: List<ComponentItem> = emptyList(),
    val children: List<AppComponent> = emptyList(), val style: ComponentStyle = ComponentStyle()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type)
        if (label.isNotBlank()) put("label", label); if (text.isNotBlank()) put("text", text); if (binding.isNotBlank()) put("binding", binding)
        if (action.isNotBlank()) put("action", action); if (source.isNotBlank()) put("source", source); if (placeholder.isNotBlank()) put("placeholder", placeholder)
        if (targetPage.isNotBlank()) put("targetPage", targetPage); if (value.isNotBlank()) put("value", value); if (image.isNotBlank()) put("image", image)
        if (icon.isNotBlank()) put("icon", icon); if (showWhen.isNotBlank()) put("showWhen", showWhen); if (intervalMs > 0) put("intervalMs", intervalMs)
        if (options.isNotEmpty()) put("options", JSONArray(options)); if (items.isNotEmpty()) put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        if (children.isNotEmpty()) put("children", JSONArray().apply { children.forEach { child: AppComponent -> put(child.toJson()) } })
        put("style", style.toJson())
    }
    companion object {
        fun fromJson(json: JSONObject): AppComponent {
            val options = json.optJSONArray("options") ?: JSONArray(); val items = json.optJSONArray("items") ?: JSONArray(); val children = json.optJSONArray("children") ?: JSONArray()
            return AppComponent(
                id = json.optString("id"), type = json.optString("type"), label = json.optString("label"), text = json.optString("text"), binding = json.optString("binding"),
                action = json.optString("action"), source = json.optString("source"), placeholder = json.optString("placeholder"), targetPage = json.optString("targetPage"),
                value = json.optString("value"), image = json.optString("image"), icon = json.optString("icon"), showWhen = json.optString("showWhen"), intervalMs = json.optInt("intervalMs"),
                options = List(options.length()) { options.optString(it) }, items = List(items.length()) { ComponentItem.fromJson(items.getJSONObject(it)) },
                children = List(children.length()) { fromJson(children.getJSONObject(it)) }, style = ComponentStyle.fromJson(json.optJSONObject("style"))
            )
        }
    }
}

data class ComponentItem(val id: String, val title: String, val subtitle: String = "", val meta: String = "", val badge: String = "", val tone: String = "", val image: String = "") {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); if (subtitle.isNotBlank()) put("subtitle", subtitle); if (meta.isNotBlank()) put("meta", meta)
        if (badge.isNotBlank()) put("badge", badge); if (tone.isNotBlank()) put("tone", tone); if (image.isNotBlank()) put("image", image)
    }
    companion object { fun fromJson(json: JSONObject) = ComponentItem(json.optString("id"), json.optString("title"), json.optString("subtitle"), json.optString("meta"), json.optString("badge"), json.optString("tone"), json.optString("image")) }
}

fun ApplicationSpec.componentCount(): Int = pages.sumOf { page -> page.components.sumOf { it.componentCount() } }
fun AppComponent.componentCount(): Int = 1 + children.sumOf { it.componentCount() }

data class SpecError(val errorCode: String, val humanMessage: String, val specPath: String, val recoverable: Boolean, val suggestedAction: String)

object SpecValidator {
    private val componentTypes = setOf("hero", "text", "input", "search", "button", "switch", "stat", "progress", "list", "divider", "selector", "card_list", "location_board", "notice", "column", "row", "grid", "stack", "section", "image", "icon", "tag", "spacer", "board", "direction_pad", "timer")
    private val containerTypes = setOf("column", "row", "grid", "stack", "section")
    private val actions = setOf("add_item", "increment", "decrement", "reset", "clear_list", "navigate", "set_value", "toggle")
    private val statefulTypes = setOf("input", "search", "stat", "selector", "switch", "direction_pad", "timer")

    fun validate(spec: ApplicationSpec): List<SpecError> = buildList {
        if (spec.schemaVersion != CURRENT_SCHEMA_VERSION) add(error("unsupported_schema", "这个小程序格式暂不受支持。", "schemaVersion", "恢复兼容版本。"))
        if (!spec.projectId.matches(Regex("[a-zA-Z0-9_-]{3,48}"))) add(error("invalid_project_id", "项目 ID 格式无效。", "projectId", "使用 3-48 位字母、数字、短横线或下划线。"))
        if (spec.name.isBlank() || spec.title.isBlank()) add(error("missing_title", "小程序名称和标题不能为空。", "name", "补充名称和标题。"))
        if (spec.theme !in setOf("light", "dark")) add(error("invalid_theme", "主题仅支持 light 或 dark。", "theme", "使用 light 或 dark。"))
        if (spec.pages.isEmpty() || spec.pages.size > 6) add(error("invalid_page_count", "当前宿主支持 1-6 个页面。", "pages", "调整页面数量。"))
        if (spec.pages.map { it.id }.distinct().size != spec.pages.size) add(error("duplicate_page", "页面 ID 不能重复。", "pages", "修改重复页面 ID。"))
        spec.pages.forEachIndexed { pageIndex, page -> validateNodes(page.components, "pages[$pageIndex].components", spec, this) }
    }

    private fun validateNodes(nodes: List<AppComponent>, path: String, spec: ApplicationSpec, errors: MutableList<SpecError>) {
        if (nodes.size > 32) errors += error("too_many_components", "同一层级最多 32 个组件。", path, "拆分页面或容器。")
        if (nodes.map { it.id }.distinct().size != nodes.size) errors += error("duplicate_component", "同一层级组件 ID 不能重复。", path, "修改重复组件 ID。")
        nodes.forEachIndexed { index, node ->
            val nodePath = "$path[$index]"
            if (node.type !in componentTypes) errors += error("unknown_component", "发现不支持的组件 ${node.type}。", "$nodePath.type", "使用宿主支持的组件。")
            if (node.type in containerTypes && node.children.isEmpty()) errors += error("missing_children", "${node.type} 组件需要 children。", "$nodePath.children", "加入子组件。")
            if (node.type !in containerTypes && node.children.isNotEmpty()) errors += error("unexpected_children", "${node.type} 不支持 children。", "$nodePath.children", "使用布局组件包裹子项。")
            if (node.action.isNotBlank() && node.action !in actions) errors += error("unknown_action", "发现不支持的动作 ${node.action}。", "$nodePath.action", "使用受限动作。")
            if (node.type in statefulTypes && node.binding.isBlank()) errors += error("missing_binding", "${node.type} 组件需要绑定状态。", "$nodePath.binding", "设置 binding。")
            if (node.type == "button" && node.action.isBlank()) errors += error("missing_action", "按钮需要动作。", "$nodePath.action", "设置受支持动作。")
            if (node.type == "selector" && node.options.isEmpty() && node.items.isEmpty()) errors += error("missing_options", "选择器需要 options 或 items。", "$nodePath.options", "提供选项。")
            if (node.type in setOf("card_list", "location_board", "board") && node.items.isEmpty()) errors += error("missing_items", "${node.type} 需要 items。", "$nodePath.items", "提供展示项目。")
            if (node.type == "timer" && node.intervalMs !in 100..10_000) errors += error("invalid_interval", "timer 的 intervalMs 需在 100-10000 之间。", "$nodePath.intervalMs", "设置安全的间隔。")
            if (node.action == "navigate" && node.targetPage !in spec.pages.map { it.id }) errors += error("unknown_target_page", "跳转目标页面不存在。", "$nodePath.targetPage", "使用已有页面 ID。")
            if (node.binding.isNotBlank() && node.binding !in spec.initialState && node.action !in setOf("add_item")) errors += error("unknown_binding", "组件引用了不存在的状态 ${node.binding}。", "$nodePath.binding", "在 initialState 中声明状态。")
            if (node.style.padding !in 0..48 || node.style.gap !in 0..32 || node.style.radius !in 0..32 || node.style.columns !in 1..6 || node.style.minHeight !in 0..480 || node.style.weight !in 0.1f..6f) errors += error("invalid_style", "样式数值超出安全范围。", "$nodePath.style", "调整样式数值。")
            if (node.children.isNotEmpty()) validateNodes(node.children, "$nodePath.children", spec, errors)
        }
    }

    private fun error(code: String, message: String, path: String, action: String) = SpecError(code, message, path, true, action)
}
