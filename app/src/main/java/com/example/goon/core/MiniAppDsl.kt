package com.example.goon.core

/**
 * Small, line-oriented authoring DSL. It intentionally compiles to the existing
 * validated spec so persistence and runtime stay backward compatible.
 * Syntax: app "Name" { title "..."; text "..."; input "提示" bind newItem; button "..." action add_item bind newItem }
 */
object MiniAppDsl {
    data class Result(val spec: ApplicationSpec?, val errors: List<String>)

    fun compile(source: String, projectId: String = ApplicationSpec.blankProjectId()): Result {
        val lines = source.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        if (lines.isEmpty()) return Result(null, listOf("DSL 为空。"))
        val header = Regex("^app\\s+\\\"([^\\\"]+)\\\"\\s*\\{$").matchEntire(lines.first())
            ?: return Result(null, listOf("第一行应为 app \\\"名称\\\" {。"))
        val name = header.groupValues[1]
        val pages = mutableListOf<Pair<String, MutableList<AppComponent>>>()
        var currentPage = "home" to mutableListOf<AppComponent>()
        pages += currentPage
        val declaredState = linkedMapOf<String, String>()
        val parseErrors = mutableListOf<String>()
        var title = name
        lines.drop(1).forEachIndexed { index, line ->
            if (line == "}") return@forEachIndexed
            Regex("^page\\s+([A-Za-z0-9_-]+)\\s+\\\"([^\\\"]+)\\\"\\s*\\{$").matchEntire(line)?.let { match ->
                currentPage = match.groupValues[1] to mutableListOf()
                pages += currentPage
                return@forEachIndexed
            }
            when {
                line.startsWith("state ") -> {
                    val match = Regex("^state\\s+([A-Za-z0-9_.-]+)(?:\\s+(.+))?$").matchEntire(line)
                    if (match == null) parseErrors += "第 ${index + 2} 行 state 格式应为 state name \\\"初始值\\\"。"
                    else {
                        val key = match.groupValues[1]
                        if (declaredState.containsKey(key)) parseErrors += "第 ${index + 2} 行状态 $key 重复。"
                        else declaredState[key] = match.groupValues[2].takeIf { it.isNotBlank() }?.let(::unquote) ?: ""
                    }
                }
                line.startsWith("title ") -> title = quoted(line) ?: run { parseErrors += "第 ${index + 2} 行 title 缺少引号文本。"; return@forEachIndexed }
                line.startsWith("text ") -> quoted(line)?.let { currentPage.second += componentWithVisibility("text-$index", "text", it, line) }
                    ?: run { parseErrors += "第 ${index + 2} 行 text 缺少引号文本。" }
                line.startsWith("button ") -> {
                    val label = quoted(line) ?: run { parseErrors += "第 ${index + 2} 行 button 缺少引号文本。"; return@forEachIndexed }
                    val action = Regex("\\baction\\s+([a-z_]+)").find(line)?.groupValues?.get(1).orEmpty()
                    val binding = Regex("\\bbind(?:ing)?\\s+([A-Za-z0-9_.]+)").find(line)?.groupValues?.get(1).orEmpty()
                    if (action.isBlank()) { parseErrors += "第 ${index + 2} 行 button 缺少 action。"; return@forEachIndexed }
                    val target = Regex("\\btarget(?:Page)?\\s+([A-Za-z0-9_-]+)").find(line)?.groupValues?.get(1).orEmpty()
                    currentPage.second += componentWithVisibility("button-$index", "button", label, line).copy(action = action, binding = binding, targetPage = target)
                }
                line.startsWith("input ") -> {
                    val binding = Regex("\\bbind(?:ing)?\\s+([A-Za-z0-9_.]+)").find(line)?.groupValues?.get(1).orEmpty()
                    if (binding.isBlank()) { parseErrors += "第 ${index + 2} 行 input 缺少 bind。"; return@forEachIndexed }
                    currentPage.second += componentWithVisibility("input-$index", "input", quoted(line).orEmpty(), line).copy(binding = binding)
                }
                else -> parseErrors += "第 ${index + 2} 行不支持：$line"
            }
        }
        if (parseErrors.isNotEmpty()) return Result(null, parseErrors)
        val state = declaredState.toMutableMap().apply { pages.flatMap { it.second }.filter { it.binding.isNotBlank() }.forEach { putIfAbsent(it.binding, "") } }
        val appPages = pages.map { (id, nodes) -> AppPage(id, if (id == "home") title else id.replace('-', ' '), components = listOf(AppComponent("screen-$id", "column", children = nodes))) }
        val spec = ApplicationSpec(projectId = projectId, name = name, title = title, initialState = state, pages = appPages)
        val errors = SpecValidator.validate(spec).map { "${it.specPath}: ${it.humanMessage}" }
        return if (errors.isEmpty()) Result(spec, emptyList()) else Result(null, errors)
    }

    private fun quoted(line: String): String? = Regex("\\\"([^\\\"]*)\\\"").find(line)?.groupValues?.get(1)
    private fun unquote(value: String): String = value.trim().removeSurrounding("\\\"")
    private fun componentWithVisibility(id: String, type: String, text: String, line: String): AppComponent {
        val rule = Regex("\\bshowWhen\\s+([A-Za-z0-9_.-]+(?:=[^\\s]+)?)").find(line)?.groupValues?.get(1).orEmpty()
        return AppComponent(id, type, text = text, label = if (type == "button") text else "", showWhen = rule)
    }
}
