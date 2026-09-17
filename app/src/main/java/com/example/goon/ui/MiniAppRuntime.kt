package com.example.goon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goon.core.AppComponent
import com.example.goon.core.ApplicationSpec
import com.example.goon.core.ChecklistItem
import com.example.goon.core.ComponentItem
import com.example.goon.core.ComponentStyle
import com.example.goon.core.SpecValidator
import com.example.goon.core.Workspace
import kotlinx.coroutines.delay
import java.util.UUID

private data class RuntimePalette(val background: Color, val surface: Color, val raised: Color, val ink: Color, val muted: Color)

@Composable
fun MiniAppRuntime(workspace: Workspace, onBack: () -> Unit, onChanged: () -> Unit) {
    val spec = workspace.spec()
    val errors = SpecValidator.validate(spec)
    val palette = paletteFor(spec)
    val values = remember(spec.projectId) { mutableStateMapOf<String, String>().apply { spec.initialState.forEach { (key, initial) -> put(key, workspace.runtimeValue(key) ?: initial) } } }
    var list by remember(spec.projectId) { mutableStateOf(workspace.items()) }
    var pageId by remember(spec.projectId) { mutableStateOf(spec.pages.firstOrNull()?.id.orEmpty()) }
    val page = spec.pages.firstOrNull { it.id == pageId } ?: spec.pages.firstOrNull()
    val accent = accentColor(spec.accent)

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(palette.surface).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = palette.ink) }
                Column(Modifier.weight(1f)) { Text(spec.name, color = palette.ink, fontWeight = FontWeight.SemiBold); Text(page?.title ?: "本地运行", style = MaterialTheme.typography.labelSmall, color = palette.muted) }
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF34A853)))
                Spacer(Modifier.size(12.dp))
            }
        },
        bottomBar = {
            if (spec.pages.size > 1) NavigationBar(containerColor = palette.surface) {
                spec.pages.forEach { item ->
                    NavigationBarItem(selected = item.id == page?.id, onClick = { pageId = item.id }, icon = { Box(Modifier.size(6.dp).clip(CircleShape).background(if (item.id == page?.id) accent else palette.muted)) }, label = { Text(item.tabLabel.ifBlank { item.title }, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    ) { padding ->
        if (errors.isNotEmpty()) RuntimeFailure(errors.first().humanMessage, errors.first().suggestedAction, palette, Modifier.padding(padding))
        else if (page != null) LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            page.components.forEach { component ->
                item(key = component.id) {
                    RuntimeNode(component, values, list, spec, accent, palette, workspace, onNavigate = { pageId = it }, updateList = { next -> list = next; onChanged() })
                }
            }
        }
    }
}

@Composable
private fun RuntimeNode(component: AppComponent, values: MutableMap<String, String>, list: List<ChecklistItem>, spec: ApplicationSpec, accent: Color, palette: RuntimePalette, workspace: Workspace, onNavigate: (String) -> Unit, updateList: (List<ChecklistItem>) -> Unit, modifier: Modifier = Modifier, fill: Boolean = true) {
    if (!isVisible(component.showWhen, values)) return
    val styled = nodeModifier(modifier, component.style, palette, accent, fill)
    when (component.type) {
        "column" -> Column(styled, verticalArrangement = Arrangement.spacedBy(component.style.gap.dp), horizontalAlignment = horizontalAlignment(component.style.align)) { component.children.forEach { RuntimeNode(it, values, list, spec, accent, palette, workspace, onNavigate, updateList) } }
        "row" -> Row(styled, horizontalArrangement = Arrangement.spacedBy(component.style.gap.dp), verticalAlignment = verticalAlignment(component.style.align)) { component.children.forEach { child -> RuntimeNode(child, values, list, spec, accent, palette, workspace, onNavigate, updateList, Modifier.weight(child.style.weight), false) } }
        "grid" -> GridNode(component, values, list, spec, accent, palette, workspace, onNavigate, updateList, styled)
        "stack" -> Box(styled, contentAlignment = boxAlignment(component.style.align)) { component.children.forEach { child -> RuntimeNode(child, values, list, spec, accent, palette, workspace, onNavigate, updateList, fill = false) } }
        "section" -> SectionNode(component, values, list, spec, accent, palette, workspace, onNavigate, updateList, styled)
        "hero" -> Text(render(component.text.ifBlank { component.label }, values), color = foreground(component.style, palette), style = MaterialTheme.typography.headlineMedium, lineHeight = 38.sp, fontWeight = FontWeight.Bold, modifier = styled)
        "text" -> Text(render(component.text.ifBlank { component.label }, values), color = foreground(component.style, palette), style = textStyle(component.style.textStyle), modifier = styled)
        "notice" -> Surface(color = styleBackground(component.style, palette, Color(0xFFFFF3D5)), shape = RoundedCornerShape(styleRadius(component.style, 10).dp), modifier = styled) { Text(render(component.text.ifBlank { component.label }, values), color = foreground(component.style.copy(foreground = if (component.style.foreground.isBlank()) "dark" else component.style.foreground), palette), modifier = Modifier.padding(14.dp)) }
        "divider" -> HorizontalDivider(color = palette.raised, modifier = styled)
        "input", "search" -> TextInput(component, values, workspace, palette, styled, component.type == "search")
        "button" -> Button(onClick = { performAction(component, values, list, spec, workspace, onNavigate, updateList) }, colors = ButtonDefaults.buttonColors(containerColor = styleBackground(component.style, palette, accent, accent), contentColor = readableOn(styleBackground(component.style, palette, accent, accent))), shape = RoundedCornerShape(styleRadius(component.style, 10).dp), modifier = styled.heightIn(min = 46.dp)) { if (component.icon.isNotBlank()) Icon(appIcon(component.icon), null, Modifier.size(18.dp)); if (component.icon.isNotBlank()) Spacer(Modifier.size(6.dp)); Text(component.label.ifBlank { "执行" }) }
        "switch" -> SwitchNode(component, values, workspace, palette, styled)
        "selector" -> SelectorNode(component, values, workspace, accent, palette, styled)
        "tag" -> Surface(color = styleBackground(component.style, palette, palette.raised), shape = RoundedCornerShape(styleRadius(component.style, 16).dp), modifier = styled) { Text(render(component.text.ifBlank { component.label }, values), color = foreground(component.style, palette), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
        "icon" -> Icon(appIcon(component.icon.ifBlank { component.value }), component.label.takeIf { it.isNotBlank() }, tint = foreground(component.style, palette), modifier = styled.size(28.dp))
        "image" -> MediaNode(component, palette, styled)
        "spacer" -> Spacer(styled.height((if (component.style.minHeight > 0) component.style.minHeight else 12).dp))
        "stat" -> StatNode(component, values, accent, palette, styled)
        "progress" -> ProgressNode(component, list, accent, palette, styled)
        "list" -> Checklist(list, palette, workspace, updateList, styled)
        "card_list", "location_board" -> InfoList(component, values, accent, palette, styled)
        "board" -> BoardNode(component, accent, palette, styled)
        "direction_pad" -> DirectionPad(component, values, workspace, accent, palette, styled)
        "timer" -> TimerNode(component, values, workspace)
    }
}

@Composable
private fun GridNode(component: AppComponent, values: MutableMap<String, String>, list: List<ChecklistItem>, spec: ApplicationSpec, accent: Color, palette: RuntimePalette, workspace: Workspace, onNavigate: (String) -> Unit, updateList: (List<ChecklistItem>) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(component.style.gap.dp)) {
        component.children.chunked(component.style.columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(component.style.gap.dp)) {
                row.forEach { child -> Box(Modifier.weight(1f)) { RuntimeNode(child, values, list, spec, accent, palette, workspace, onNavigate, updateList) } }
                repeat(component.style.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionNode(component: AppComponent, values: MutableMap<String, String>, list: List<ChecklistItem>, spec: ApplicationSpec, accent: Color, palette: RuntimePalette, workspace: Workspace, onNavigate: (String) -> Unit, updateList: (List<ChecklistItem>) -> Unit, modifier: Modifier) {
    Surface(color = styleBackground(component.style, palette, palette.surface), shape = RoundedCornerShape(styleRadius(component.style, 14).dp), modifier = modifier) {
        Column(Modifier.padding((if (component.style.padding > 0) component.style.padding else 14).dp), verticalArrangement = Arrangement.spacedBy(component.style.gap.dp)) {
            if (component.label.isNotBlank()) Text(render(component.label, values), color = foreground(component.style, palette), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            component.children.forEach { RuntimeNode(it, values, list, spec, accent, palette, workspace, onNavigate, updateList) }
        }
    }
}

@Composable
private fun TextInput(component: AppComponent, values: MutableMap<String, String>, workspace: Workspace, palette: RuntimePalette, modifier: Modifier, isSearch: Boolean) {
    OutlinedTextField(value = values[component.binding].orEmpty(), onValueChange = { values[component.binding] = it; workspace.setRuntimeValue(component.binding, it) }, label = component.label.takeIf { it.isNotBlank() }?.let { { Text(it) } }, placeholder = { Text(component.placeholder.ifBlank { if (isSearch) "搜索" else "请输入" }) }, leadingIcon = if (isSearch) ({ Icon(Icons.Rounded.Search, null) }) else null, singleLine = isSearch, maxLines = if (isSearch) 1 else 3, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.muted, unfocusedBorderColor = palette.raised, focusedTextColor = palette.ink, unfocusedTextColor = palette.ink), shape = RoundedCornerShape(styleRadius(component.style, 10).dp), modifier = modifier)
}

@Composable
private fun SwitchNode(component: AppComponent, values: MutableMap<String, String>, workspace: Workspace, palette: RuntimePalette, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(component.label.ifBlank { component.text }, color = foreground(component.style, palette), fontWeight = FontWeight.Medium); if (component.text.isNotBlank() && component.label.isNotBlank()) Text(component.text, color = palette.muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = values[component.binding].toBoolean(), onCheckedChange = { enabled -> values[component.binding] = enabled.toString(); workspace.setRuntimeValue(component.binding, enabled.toString()) })
    }
}

@Composable
private fun SelectorNode(component: AppComponent, values: MutableMap<String, String>, workspace: Workspace, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    val choices = if (component.options.isNotEmpty()) component.options else component.items.map { it.title }
    Column(modifier) {
        if (component.label.isNotBlank()) Text(component.label, color = foreground(component.style, palette), fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(choices, key = { it }) { choice ->
            val selected = values[component.binding] == choice
            Surface(color = if (selected) accent else palette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable { values[component.binding] = choice; workspace.setRuntimeValue(component.binding, choice) }) { Text(choice, color = if (selected) readableOn(accent) else palette.ink, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) }
        } }
    }
}

@Composable
private fun MediaNode(component: AppComponent, palette: RuntimePalette, modifier: Modifier) {
    val glyph = component.image.removePrefix("emoji:").ifBlank { "图片" }
    Box(modifier.aspectRatio(1.55f).clip(RoundedCornerShape(styleRadius(component.style, 12).dp)).background(styleBackground(component.style, palette, palette.raised)), contentAlignment = Alignment.Center) {
        Text(glyph, color = foreground(component.style, palette), fontSize = if (glyph.length <= 3) 42.sp else 15.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun StatNode(component: AppComponent, values: Map<String, String>, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    Surface(color = styleBackground(component.style, palette, palette.surface), shape = RoundedCornerShape(styleRadius(component.style, 12).dp), modifier = modifier) { Column(Modifier.padding(16.dp)) { Text(component.label, color = palette.muted, style = MaterialTheme.typography.labelMedium); Text(values[component.binding].orEmpty(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = foreground(component.style.copy(foreground = component.style.foreground.ifBlank { "accent" }), palette, accent), modifier = Modifier.padding(top = 4.dp)) } }
}

@Composable
private fun ProgressNode(component: AppComponent, list: List<ChecklistItem>, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    val done = list.count { it.done }; val progress = if (list.isEmpty()) 0f else done.toFloat() / list.size
    Column(modifier) { Row(Modifier.fillMaxWidth()) { Text(component.label.ifBlank { "进度" }, color = palette.ink, fontWeight = FontWeight.Medium); Spacer(Modifier.weight(1f)); Text("$done / ${list.size}", color = palette.muted) }; LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(7.dp).clip(CircleShape), color = accent, trackColor = palette.raised) }
}

@Composable
private fun InfoList(component: AppComponent, values: Map<String, String>, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    val selected = values[component.binding].orEmpty(); val visible = component.items.filter { selected.isBlank() || selected == "全部" || listOf(it.subtitle, it.meta, it.badge).any { field -> field.contains(selected) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { visible.forEachIndexed { index, item ->
        Surface(color = styleBackground(component.style, palette, palette.surface), shape = RoundedCornerShape(styleRadius(component.style, 12).dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (component.type == "location_board") Box(Modifier.size(30.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) { Text("${index + 1}", color = readableOn(accent), fontWeight = FontWeight.Bold) }
            if (item.image.isNotBlank()) Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(palette.raised), contentAlignment = Alignment.Center) { Text(item.image.removePrefix("emoji:"), fontSize = 22.sp) }
            Column(Modifier.weight(1f).padding(start = if (component.type == "location_board") 10.dp else if (item.image.isNotBlank()) 10.dp else 0.dp)) { Text(item.title, color = palette.ink, fontWeight = FontWeight.SemiBold); if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(top = 3.dp)); if (item.meta.isNotBlank()) Text(item.meta, style = MaterialTheme.typography.labelSmall, color = palette.muted, modifier = Modifier.padding(top = 3.dp)) }
            if (item.badge.isNotBlank()) Text(item.badge, color = toneColor(item.tone, accent), style = MaterialTheme.typography.labelMedium)
        } }
    } }
}

@Composable
private fun BoardNode(component: AppComponent, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) { component.items.chunked(component.style.columns).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { item -> Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(boardColor(item, accent, palette)), contentAlignment = Alignment.Center) { Text(item.image.removePrefix("emoji:").ifBlank { item.title }, fontSize = 14.sp, maxLines = 1) } }; repeat(component.style.columns - row.size) { Spacer(Modifier.weight(1f)) } } }
    }
}

@Composable
private fun DirectionPad(component: AppComponent, values: MutableMap<String, String>, workspace: Workspace, accent: Color, palette: RuntimePalette, modifier: Modifier) {
    fun set(direction: String) { values[component.binding] = direction; workspace.setRuntimeValue(component.binding, direction) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("上", Icons.Rounded.ArrowUpward, accent, palette) { set("up") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { PadButton("左", Icons.AutoMirrored.Rounded.ArrowBack, accent, palette) { set("left") }; PadButton("暂停", Icons.Rounded.Pause, accent, palette) { set("pause") }; PadButton("右", Icons.Rounded.PlayArrow, accent, palette) { set("right") } }
        PadButton("下", Icons.Rounded.ArrowDownward, accent, palette) { set("down") }
    }
}

@Composable
private fun PadButton(label: String, icon: ImageVector, accent: Color, palette: RuntimePalette, onClick: () -> Unit) {
    Surface(color = palette.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(4.dp).clickable(onClick = onClick)) { Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, label, tint = accent, modifier = Modifier.size(18.dp)); Text(label, color = palette.muted, style = MaterialTheme.typography.labelSmall) } }
}

@Composable
private fun TimerNode(component: AppComponent, values: MutableMap<String, String>, workspace: Workspace) {
    LaunchedEffect(component.id, values[component.showWhen.substringBefore("=")]) {
        while (isVisible(component.showWhen, values)) {
            delay(component.intervalMs.toLong())
            if (isVisible(component.showWhen, values)) {
                val next = ((values[component.binding]?.toIntOrNull() ?: 0) + 1).toString()
                values[component.binding] = next; workspace.setRuntimeValue(component.binding, next)
            }
        }
    }
}

@Composable
private fun Checklist(list: List<ChecklistItem>, palette: RuntimePalette, workspace: Workspace, updateList: (List<ChecklistItem>) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list.isEmpty()) Surface(color = palette.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("还没有内容", color = palette.muted, modifier = Modifier.padding(18.dp)) }
        list.forEach { item -> Surface(color = palette.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(item.done, { done -> val next = list.map { if (it.id == item.id) it.copy(done = done) else it }; workspace.saveItems(next); updateList(next) }); Text(item.text, Modifier.weight(1f), color = if (item.done) palette.muted else palette.ink); IconButton(onClick = { val next = list.filterNot { it.id == item.id }; workspace.saveItems(next); updateList(next) }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = palette.muted) } } }
        }
    }
}

private fun performAction(component: AppComponent, values: MutableMap<String, String>, list: List<ChecklistItem>, spec: ApplicationSpec, workspace: Workspace, onNavigate: (String) -> Unit, updateList: (List<ChecklistItem>) -> Unit) {
    when (component.action) {
        "add_item" -> values[component.binding].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { text -> val next = list + ChecklistItem(UUID.randomUUID().toString(), text); workspace.saveItems(next); updateList(next); values[component.binding] = ""; workspace.setRuntimeValue(component.binding, "") }
        "increment", "decrement" -> { val delta = if (component.action == "increment") 1 else -1; val next = ((values[component.binding]?.toIntOrNull() ?: 0) + delta).toString(); values[component.binding] = next; workspace.setRuntimeValue(component.binding, next) }
        "reset" -> { val next = spec.initialState[component.binding].orEmpty(); values[component.binding] = next; workspace.setRuntimeValue(component.binding, next) }
        "clear_list" -> { workspace.saveItems(emptyList()); updateList(emptyList()) }
        "navigate" -> onNavigate(component.targetPage)
        "set_value" -> { values[component.binding] = component.value; workspace.setRuntimeValue(component.binding, component.value) }
        "toggle" -> { val next = (!values[component.binding].toBoolean()).toString(); values[component.binding] = next; workspace.setRuntimeValue(component.binding, next) }
    }
}

private fun nodeModifier(modifier: Modifier, style: ComponentStyle, palette: RuntimePalette, accent: Color, fill: Boolean): Modifier {
    var value = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)
    if (style.minHeight > 0) value = value.heightIn(min = style.minHeight.dp)
    if (style.padding > 0) value = value.padding(style.padding.dp)
    if (style.background.isNotBlank()) value = value.clip(RoundedCornerShape(styleRadius(style, 10).dp)).background(styleBackground(style, palette, palette.surface, accent))
    return value
}

private fun isVisible(rule: String, values: Map<String, String>): Boolean {
    if (rule.isBlank()) return true
    val pair = rule.split("=", limit = 2)
    return if (pair.size == 2) values[pair[0].trim()] == pair[1].trim() else values[rule]?.toBoolean() == true
}

private fun render(text: String, values: Map<String, String>) = Regex("\\{\\{([a-zA-Z0-9_-]+)\\}\\}").replace(text) { values[it.groupValues[1]].orEmpty() }

private fun paletteFor(spec: ApplicationSpec) = if (spec.theme == "dark") RuntimePalette(Color(0xFF101011), Color(0xFF1C1C1E), Color(0xFF29292C), Color(0xFFF4F4F5), Color(0xFF9A9AA0)) else RuntimePalette(Color(0xFFF8F9FB), Color.White, Color(0xFFE8EBF0), Color(0xFF1A1D24), Color(0xFF737985))
private fun styleRadius(style: ComponentStyle, fallback: Int) = if (style.radius > 0) style.radius else fallback
private fun styleBackground(style: ComponentStyle, palette: RuntimePalette, fallback: Color, accent: Color = Color(0xFF356BEF)) = namedColor(style.background, palette, fallback, accent)
private fun foreground(style: ComponentStyle, palette: RuntimePalette, accent: Color = Color(0xFF356BEF)) = namedColor(style.foreground, palette, palette.ink, accent)
private fun namedColor(value: String, palette: RuntimePalette, fallback: Color, accent: Color = Color(0xFF356BEF)): Color = when (value) { "" -> fallback; "background" -> palette.background; "surface" -> palette.surface; "raised" -> palette.raised; "muted" -> palette.muted; "accent" -> accent; "dark" -> Color(0xFF2A2414); "success" -> Color(0xFF2D8A62); "danger" -> Color(0xFFD85643); else -> runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback) }
private fun readableOn(color: Color) = if (color.luminance() > 0.48f) Color(0xFF131313) else Color.White
private fun horizontalAlignment(value: String) = when (value) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
private fun verticalAlignment(value: String) = when (value) { "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top }
private fun boxAlignment(value: String) = when (value) { "center" -> Alignment.Center; "end" -> Alignment.BottomEnd; else -> Alignment.TopStart }
@Composable private fun textStyle(value: String) = when (value) { "caption" -> MaterialTheme.typography.bodySmall; "title" -> MaterialTheme.typography.titleMedium; "display" -> MaterialTheme.typography.headlineLarge; else -> MaterialTheme.typography.bodyLarge }
private fun boardColor(item: ComponentItem, accent: Color, palette: RuntimePalette) = when (item.tone) { "accent" -> accent; "success", "done" -> Color(0xFF2D8A62); "danger", "urgent" -> Color(0xFFD85643); "muted" -> palette.raised; else -> palette.surface }
private fun toneColor(tone: String, accent: Color) = when (tone) { "urgent", "danger" -> Color(0xFFD85643); "warn" -> Color(0xFFC47D16); "done", "success" -> Color(0xFF34845A); else -> accent }
private fun appIcon(name: String): ImageVector = when (name.lowercase()) { "home" -> Icons.Rounded.Home; "search" -> Icons.Rounded.Search; "cart" -> Icons.Rounded.ShoppingCart; "person" -> Icons.Rounded.Person; "location" -> Icons.Rounded.LocationOn; "favorite" -> Icons.Rounded.Favorite; "receipt" -> Icons.Rounded.ReceiptLong; "star" -> Icons.Rounded.Star; "play" -> Icons.Rounded.PlayArrow; "pause" -> Icons.Rounded.Pause; else -> Icons.Rounded.Star }

@Composable
private fun RuntimeFailure(message: String, action: String, palette: RuntimePalette, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("这个小程序暂时无法运行", color = palette.ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(message, color = palette.ink, modifier = Modifier.padding(top = 10.dp)); Text(action, color = Color(0xFFD85643), modifier = Modifier.padding(top = 8.dp)) }
}

fun accentColor(value: String): Color = when (value) { "green" -> Color(0xFF2E8B62); "coral" -> Color(0xFFE2644D); "violet" -> Color(0xFF7559D9); else -> Color(0xFF3C6FF0) }
