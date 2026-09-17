package com.example.goon.ui

import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goon.core.AppResult
import com.example.goon.core.WebPreview
import com.example.goon.core.WebProject
import com.example.goon.core.AgentDisplayState
import com.example.goon.core.AgentMessage
import com.example.goon.core.ConversationUsage
import com.example.goon.core.MODEL_CONTEXT_WINDOW_TOKENS
import com.example.goon.core.AgentOrchestrator
import com.example.goon.core.AgentSession
import com.example.goon.core.ConversationSummary
import com.example.goon.core.IntentPolicy
import com.example.goon.core.IntentRoute
import com.example.goon.core.IntentRouter
import com.example.goon.core.IntentToolValidator
import com.example.goon.core.OpenAiCompatibleProvider
import com.example.goon.core.ProjectSummary
import com.example.goon.core.ProviderSettings
import com.example.goon.core.SecretStore
import com.example.goon.core.Workspace
import com.example.goon.core.componentCount
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class Route { CHAT, APPS, RUN, PROJECT, MODEL, SKILLS, ASSETS }
private val mainHandler = Handler(Looper.getMainLooper())
// 浅色现代配色：层级靠灰阶与留白表达，强调色只用在主操作与当前状态上。
private val Blue = Color(0xFF2F6BFF)
private val Page = Color(0xFFF6F7F9)
private val SurfaceTone = Color(0xFFFFFFFF)
private val RaisedTone = Color(0xFFEDF0F4)
private val Ink = Color(0xFF14171C)
private val Muted = Color(0xFF6B7280)
private val Line = Color(0xFFE4E7EC)
private val UserBubble = Color(0xFFE7EEFF)
private val Success = Color(0xFF1E9E6A)
private val Danger = Color(0xFFD64545)
private val CodeSurface = Color(0xFFF2F4F8)

@Composable
fun AgentWorkbench() {
    val context = LocalContext.current.applicationContext
    val workspace = remember(context) { Workspace(context) }
    val secrets = remember(context) { SecretStore(context) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(Route.CHAT) }
    var revision by remember { mutableStateOf(0) }
    var createMode by remember { mutableStateOf(false) }
    var editProjectId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { revision++ } }
        val filter = IntentFilter(Workspace.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun navigate(next: Route) { route = next; scope.launch { drawer.close() } }
    fun newConversation() {
        workspace.newConversation(); revision++; createMode = false; editProjectId = null; route = Route.CHAT
        scope.launch { drawer.close() }
    }
    fun newApp() {
        revision++; createMode = true; editProjectId = null; route = Route.CHAT
        scope.launch { drawer.close() }
    }

    if (route == Route.RUN) {
        if (workspace.hasWebProject(workspace.activeProjectId())) WebMiniAppRuntime(workspace, onBack = { route = Route.APPS })
        else MiniAppRuntime(workspace, onBack = { route = Route.APPS }, onChanged = { revision++ })
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(
                conversations = workspace.conversations(), activeId = workspace.currentConversationId(),
                onNewConversation = ::newConversation, onNavigate = ::navigate,
                onConversation = { workspace.activateConversation(it); revision++; createMode = false; editProjectId = null; route = Route.CHAT; scope.launch { drawer.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = Page,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
            topBar = {
                TopBar(
                    // 标题必须把 revision 作为 key：会话标题来自数据库的普通函数调用，
                    // 不读任何 Compose 状态，否则切会话后标题会一直停留在上一个会话。
                    title = when (route) {
                        Route.CHAT -> remember(revision) { workspace.conversations().firstOrNull { it.id == workspace.currentConversationId() }?.title ?: "新对话" }
                        Route.APPS -> "小程序"
                        Route.PROJECT -> "项目与版本"
                        Route.MODEL -> "模型设置"
                        Route.SKILLS -> "技能库"
                        Route.ASSETS -> "素材库"
                        Route.RUN -> "Goon"
                    },
                    modelName = secrets.readSettings().model.ifBlank { "本地模式" },
                    onMenu = { scope.launch { drawer.open() } },
                    onApps = { route = Route.APPS }
                )
            }
        ) { padding ->
            when (route) {
                Route.CHAT -> AgentScreen(
                    workspace, secrets, revision, createMode, editProjectId,
                    clearProjectIntent = { createMode = false; editProjectId = null },
                    selectProject = { id -> createMode = false; editProjectId = id; revision++ },
                    selectCreate = { createMode = true; editProjectId = null; revision++ },
                    openProject = { id -> workspace.activateProject(id); revision++; route = Route.RUN },
                    onChanged = { revision++ },
                    modifier = Modifier.padding(padding)
                )
                Route.APPS -> MiniAppsScreen(
                    workspace = workspace,
                    openProject = { id -> workspace.activateProject(id); revision++; route = Route.RUN },
                    editProject = { id -> workspace.activateProject(id); revision++; createMode = false; editProjectId = id; route = Route.CHAT },
                    deleteProject = { id -> if (workspace.deleteProject(id)) revision++ },
                    createNew = ::newApp,
                    modifier = Modifier.padding(padding)
                )
                Route.PROJECT -> ProjectScreen(workspace, { revision++ }, Modifier.padding(padding))
                Route.MODEL -> ModelScreen(secrets, { revision++ }, Modifier.padding(padding))
                Route.SKILLS -> SkillsScreen(Modifier.padding(padding))
                Route.ASSETS -> AssetsScreen(workspace, Modifier.padding(padding))
                Route.RUN -> Unit
            }
        }
    }
}

@Composable
private fun TopBar(title: String, modelName: String, onMenu: () -> Unit, onApps: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "打开侧边栏", tint = Ink) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(modelName.ifBlank { "本地模式" }, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
            IconButton(onClick = onApps) { Icon(Icons.Rounded.Apps, "小程序", tint = Ink) }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun AppDrawer(conversations: List<ConversationSummary>, activeId: String, onNewConversation: () -> Unit, onNavigate: (Route) -> Unit, onConversation: (String) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = SurfaceTone, modifier = Modifier.fillMaxWidth(0.84f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Goon", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onNewConversation, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = RaisedTone, contentColor = Ink)) { Icon(Icons.Rounded.Add, null); Text("新对话", modifier = Modifier.padding(start = 7.dp)) }
            Spacer(Modifier.height(12.dp))
            DrawerItem("当前对话", Icons.Rounded.ChatBubbleOutline) { onNavigate(Route.CHAT) }
            DrawerItem("我的小程序", Icons.Rounded.Apps) { onNavigate(Route.APPS) }
            DrawerItem("项目与版本", Icons.Rounded.History) { onNavigate(Route.PROJECT) }
            DrawerItem("技能库", Icons.Rounded.AutoAwesome) { onNavigate(Route.SKILLS) }
            DrawerItem("素材库", Icons.Rounded.Image) { onNavigate(Route.ASSETS) }
            DrawerItem("模型设置", Icons.Rounded.Settings) { onNavigate(Route.MODEL) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
            Text("最近对话", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    NavigationDrawerItem(
                        label = { Column { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis); if (conversation.preview.isNotBlank()) Text(conversation.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.labelSmall) } },
                        selected = conversation.id == activeId, onClick = { onConversation(conversation.id) },
                        icon = { Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(20.dp)) },
                        shape = RoundedCornerShape(8.dp), colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = RaisedTone, unselectedContainerColor = Color.Transparent, selectedTextColor = Ink, unselectedTextColor = Ink, selectedIconColor = Blue, unselectedIconColor = Muted)
                    )
                }
            }
            Text("数据保存在本机", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(12.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = false, onClick = onClick, icon = { Icon(icon, null, Modifier.size(20.dp)) }, shape = RoundedCornerShape(8.dp), colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Ink, unselectedIconColor = Muted, unselectedContainerColor = Color.Transparent))
}

@Composable
private fun AgentScreen(workspace: Workspace, secrets: SecretStore, revision: Int, createMode: Boolean, editProjectId: String?, clearProjectIntent: () -> Unit, selectProject: (String) -> Unit, selectCreate: () -> Unit, openProject: (String) -> Unit, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val conversationId = workspace.currentConversationId()
    val linkedProject = workspace.projects().firstOrNull { it.id == editProjectId }
    var prompt by remember(conversationId) { mutableStateOf("") }
    var imageUri by remember(conversationId) { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> imageUri = uri }
    var showActions by remember(conversationId) { mutableStateOf(false) }
    var showMentions by remember(conversationId) { mutableStateOf(false) }
    var state by remember(conversationId) { mutableStateOf(AgentDisplayState("就绪", "准备好了", "可以交流、分析或构建应用。")) }
    var streamingReply by remember(conversationId) { mutableStateOf("") }
    var messages by remember(revision) { mutableStateOf(loadMessages(workspace)) }
    var seenCount by remember(conversationId) { mutableStateOf(-1) }
    // 带上 runId：截图按运行存放，聊天里只显示这一轮最新的那一张。
    val liveDraft by produceState<Pair<String, WebProject>?>(initialValue = null, revision) {
        value = withContext(Dispatchers.IO) {
            com.example.goon.core.WebAgentRun.draftRun(workspace)?.let { run ->
                com.example.goon.core.WebAgentRun.draftProject(workspace, run.id)?.let { run.id to it }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { seenCount = messages.size }
    LaunchedEffect(messages.size, state.phase, streamingReply.length) {
        val lastIndex = messages.lastIndex + if (state.phase == "就绪") 0 else 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    Box(modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 126.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (createMode || linkedProject != null) item {
                ProjectIntentBar(
                    label = if (createMode) "创建新小程序" else "修改 ${linkedProject?.name}",
                    onClear = clearProjectIntent
                )
            }
            if (messages.isEmpty()) item { WelcomePanel(createMode) { prompt = it } }
            // 只给「刚到达的那条」做入场动画；历史消息不重放，避免整列表闪动。
            itemsIndexed(messages) { index, message ->
                Appearing(animate = index == messages.lastIndex && seenCount in 0 until messages.size) { MessageItem(message, openProject) }
            }
            // 生成过程中就能看到界面在长出来：草稿每次工具结果都会落盘，这里按 revision 异步读取。
            liveDraft?.let { (draftRunId, draft) ->
                item(key = "live-draft") {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("实时预览 · ${draft.files.size} 个文件", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Text("未发布", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        PreviewCard(
                            shotKey = "draft-$draftRunId",
                            // 草稿每轮编辑都会变，用它作为刷新触发：图是预览流程覆盖写的，必须跟着更新。
                            version = draft.files.hashCode(),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            if (streamingReply.isNotBlank()) item(key = "streaming-reply") {
                StreamingReply(streamingReply)
            }
            if (state.phase != "就绪") item { AgentTrace(state) }
            if (state.canCancel && com.example.goon.core.WebAgentRun.isRunning(conversationId)) item { TextButton(onClick = { com.example.goon.core.WebAgentRun.cancel(conversationId) }) { Text("停止任务") } }
            if (!state.canCancel) {
                com.example.goon.core.WebAgentRun.draftRun(workspace)?.let { previous ->
                    item {
                        TextButton(onClick = {
                            val settings = secrets.readSettings()
                            if (!settings.isConfigured) { state = AgentDisplayState("需要设置", "请先配置模型", "草稿保存在本机。") }
                            else {
                                val provider = OpenAiCompatibleProvider(settings, context)
                                runCatching {
                                    com.example.goon.core.WebAgentRun.resume(workspace, provider, previous.id, { delta -> mainHandler.post { streamingReply = delta } }) { next ->
                                        mainHandler.post {
                                            state = next
                                            if (isTerminal(next)) { streamingReply = ""; messages = loadMessages(workspace); onChanged(); provider.shutdown() }
                                        }
                                    }
                                }.onFailure { provider.shutdown(); state = AgentDisplayState("需要处理", "无法继续草稿", it.message ?: "请重新读取项目。") }
                            }
                        }) { Text("继续未完成的小程序") }
                    }
                }
            }
        }
        Composer(
            value = prompt, onValueChange = { prompt = it; showMentions = it.substringAfterLast(' ', it).startsWith("@"); }, busy = state.canCancel,
            hasImage = imageUri != null, onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onClearImage = { imageUri = null },
            showActions = showActions, onToggleActions = { showActions = !showActions }, onDismissActions = { showActions = false },
            showMentions = showMentions, onDismissMentions = { showMentions = false }, projects = workspace.projects(), onSelectProject = { id -> selectProject(id); prompt = prompt.substringBeforeLast('@').trimEnd(); showMentions = false }, onSelectCreate = { selectCreate(); prompt = prompt.substringBeforeLast('@').trimEnd(); showMentions = false },
            onSelectCommand = { command -> prompt = prompt.substringBeforeLast('@').trimEnd().let { if (it.isBlank()) command else "$it $command" }; showMentions = false },
            onSend = {
                if (prompt.isBlank()) return@Composer
                if (state.canCancel) {
                    if (com.example.goon.core.WebAgentRun.steer(conversationId, prompt.trim())) { prompt = ""; onChanged() }
                    return@Composer
                }
                val request = prompt.trim(); val attachment = imageUri; prompt = ""; imageUri = null
                streamingReply = ""
                messages = messages + listOf(AgentMessage(request, true)) + attachment?.let { listOf(AgentMessage("已附加图片", true, isAttachment = true, attachmentUri = it.toString())) }.orEmpty()
                val settings = secrets.readSettings()
                val projectId = editProjectId
                state = AgentDisplayState("理解需求", if (createMode) "正在设计新小程序" else "正在理解你的请求", if (createMode || projectId != null) "读取指定项目与能力边界…" else "整理当前对话与上下文…", canCancel = true)
                if (settings.isConfigured) {
                    val provider = OpenAiCompatibleProvider(settings, context)
                    AgentOrchestrator(workspace, provider).submit(request, createMode, imageUri = attachment, projectId = projectId, onDelta = { delta ->
                        mainHandler.post { streamingReply = delta }
                    }) { next ->
                        mainHandler.post {
                            state = next
                            if (isTerminal(next)) {
                                streamingReply = ""
                                messages = loadMessages(workspace).withAttachmentAfter(request, attachment)
                                clearProjectIntent()
                                onChanged(); provider.shutdown()
                            }
                        }
                    }
                } else if (projectId != null && !workspace.hasWebProject(projectId)) {
                    val runId = workspace.startRun(request, "local-demo", projectId = projectId)
                    val projectExists = workspace.projects().any { it.id == projectId }
                    val decision = IntentRouter.classify(request, projectId = projectId)
                    val policy = IntentPolicy.evaluate(decision, false, projectId, projectExists, false)
                    val toolGate = IntentToolValidator.validateRoute(policy.route, false, policy.safeProjectId, projectExists, false)
                    workspace.appendEvent("intent_precheck", decision.precheck.toJson().toString(), runId)
                    workspace.appendEvent("intent_decision", decision.toJson(), runId)
                    workspace.appendEvent("intent_policy", policy.toJson(), runId)
                    workspace.appendEvent("intent_tool_gate", toolGate.toJson().toString(), runId)
                    workspace.appendEvent("user_message", request, runId)
                    if (policy.route == IntentRoute.LEGACY_AGENT && toolGate.allowed) {
                        workspace.activateProject(projectId)
                        val result = AgentSession(workspace).submit(request, runId, recordUser = false)
                        if (result.status.name == "COMPLETED") workspace.updateRunProject(runId, workspace.spec().projectId)
                        state = AgentDisplayState(if (result.status.name == "COMPLETED") "已完成" else "需要处理", if (result.status.name == "COMPLETED") "修改已完成" else "暂时无法完成", result.message, result.tools)
                    } else {
                        val reply = if (policy.route == IntentRoute.CLARIFY) policy.clarify?.question ?: policy.reason else "当前请求需要先配置模型，未执行项目写入。"
                        workspace.appendEvent(if (toolGate.allowed) "intent_clarify" else "intent_security_rejection", if (toolGate.allowed) policy.toJson() else toolGate.toJson().toString(), runId)
                        workspace.appendEvent("agent_message", reply, runId)
                        workspace.updateRun(runId, if (toolGate.allowed) "intent_clarify" else "intent_rejected", if (toolGate.allowed) "completed" else "failed", reply, if (toolGate.allowed) "intent_clarify" else "tool_gate_rejected")
                        state = AgentDisplayState(if (toolGate.allowed) "需要澄清" else "已拒绝", "暂未执行项目操作", reply)
                    }
                    streamingReply = ""
                    messages = loadMessages(workspace).withAttachmentAfter(request, attachment); clearProjectIntent(); onChanged()
                } else {
                    val runId = workspace.startRun(request, "未配置模型")
                    workspace.appendEvent("user_message", request, runId)
                    if (attachment != null) workspace.appendEvent("user_attachment", "本轮已附加图片。", runId)
                    val reply = "请先在模型设置中配置兼容服务，之后即可进行通用对话和任务。"
                    workspace.appendEvent("agent_message", reply, runId)
                    workspace.updateRun(runId, "provider_required", "completed", reply)
                    state = AgentDisplayState("需要设置", "尚未配置模型", reply)
                    streamingReply = ""
                    messages = loadMessages(workspace).withAttachmentAfter(request, attachment); onChanged()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ProjectIntentBar(label: String, onClear: () -> Unit) {
    Surface(color = UserBubble, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Apps, null, tint = Blue, modifier = Modifier.size(17.dp))
            Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "取消项目关联", tint = Muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * 会话用量的脚注文案。刻意把「命中缓存」和「全价输入」拆开：只有未命中部分按全价计费，
 * 只看总输入会把成本判断带偏（见 docs/problems_and_solutions.md 第 13、17 条）。
 */
private fun usageFooter(usage: ConversationUsage): String = buildString {
    append("本会话累计 · ").append(usage.calls).append(" 次调用")
    append(" · 输入 ").append(grouped(usage.promptTokens))
    append("（命中缓存 ").append(grouped(usage.cachedTokens))
    append(" / 全价 ").append(grouped(usage.uncachedPromptTokens)).append("）")
    append(" · 输出 ").append(grouped(usage.completionTokens))
    if (usage.reasoningTokens > 0) append("（推理 ").append(grouped(usage.reasoningTokens)).append("）")
    if (usage.runsWithoutCacheData > 0) append(" · 其中 ").append(usage.runsWithoutCacheData).append(" 次无缓存埋点")
    // 上下文与上面几个数字不是一回事：累计输入是「一共喂进去多少」，会按 O(N²) 涨到百万级；
    // 这里显示的是「此刻窗口里站着多少」，也就是压缩闸门真正盯的那个数。
    if (usage.contextTokens > 0) {
        val percent = usage.contextTokens * 100.0 / MODEL_CONTEXT_WINDOW_TOKENS
        append(" · 上下文 ").append(grouped(usage.contextTokens)).append("/").append(grouped(MODEL_CONTEXT_WINDOW_TOKENS))
        append(" (").append(String.format(java.util.Locale.CHINA, "%.1f", percent)).append("%)")
    }
}

private fun grouped(value: Int): String = String.format(java.util.Locale.CHINA, "%,d", value)

private fun loadMessages(workspace: Workspace): List<AgentMessage> {
    val output = mutableListOf<AgentMessage>()
    val tools = mutableListOf<String>()
    var toolRunId: String? = null
    var toolAt = 0L
    // 每条助手回复带的是「截至该次运行」的会话累计，所以历史消息上的数字不会随新消息变化。
    val usageByRun = workspace.conversationUsage(workspace.currentConversationId())
    fun flushTools() {
        if (tools.isNotEmpty()) output += AgentMessage(tools.joinToString("\n"), false, toolAt, toolName = "执行记录 · ${tools.size} 项")
        tools.clear(); toolRunId = null
    }
    // 内部事件（graph_state、checkpoint、intent_* 等）不渲染，也不应该打断执行记录的分组，
    // 否则每轮都会把一次运行切成很多条「执行记录」。
    val rendered = setOf("user_message", "user_attachment", "agent_plan", "agent_message", "app_result")
    workspace.events().asReversed().forEach { event ->
        if (event.kind.startsWith("tool_")) {
            if (toolRunId != null && toolRunId != event.runId) flushTools()
            toolRunId = event.runId; toolAt = event.at
            tools += "${toolLabel(event.kind)}：${event.message}"
            return@forEach
        }
        if (event.kind !in rendered) return@forEach
        flushTools()
        when (event.kind) {
            "user_message" -> output += AgentMessage(event.message, true, event.at)
            "user_attachment" -> output += AgentMessage(event.message, true, event.at, isAttachment = true)
            "agent_plan" -> output += AgentMessage(event.message, false, event.at, isPlan = true)
            "agent_message" -> output += AgentMessage(event.message, false, event.at, usage = event.runId?.let(usageByRun::get)?.let(::usageFooter))
            "app_result" -> runCatching {
                val json = JSONObject(event.message)
                val projectId = json.getString("projectId")
                val runtime = json.optString("runtime", "spec")
                AppResult(projectId, json.getString("name"), json.optInt("schemaVersion", 4), json.optInt("pages"), json.optInt("components"), runtime, json.optInt("files"),
                    project = if (runtime == "web") runCatching { workspace.webStore().load(projectId) }.getOrNull() else null)
            }.getOrNull()?.let { output += AgentMessage("", false, event.at, appResult = it) }
        }
    }
    flushTools()
    return output
}

private fun List<AgentMessage>.withAttachmentAfter(prompt: String, uri: Uri?): List<AgentMessage> {
    if (uri == null || any { it.isAttachment && it.attachmentUri == uri.toString() }) return this
    val output = toMutableList()
    val index = output.indexOfLast { it.fromUser && it.text == prompt }.takeIf { it >= 0 } ?: output.lastIndex
    output.add((index + 1).coerceAtMost(output.size), AgentMessage("已附加图片", true, isAttachment = true, attachmentUri = uri.toString()))
    return output
}

private fun toolLabel(kind: String) = when (kind.removePrefix("tool_")) {
    "read_project" -> "读取项目"
    "patch_project" -> "更新规格"
    "run_app" -> "运行检查"
    "inspect_app" -> "结果检查"
    "checkpoint" -> "保存版本"
    else -> kind.removePrefix("tool_").replace('_', ' ')
}
private fun isTerminal(state: AgentDisplayState) = state.phase in setOf("已完成", "失败", "需要确认", "需要澄清", "离线完成", "需要处理")

@Composable
private fun WelcomePanel(createMode: Boolean, choosePrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp)) {
        Text(if (createMode) "描述你要构建的小程序" else "今天想处理什么？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = Ink)
        Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
            if (createMode) {
                Suggestion("创建一个旅行物品清单", choosePrompt)
                Suggestion("创建一个习惯打卡小程序", choosePrompt)
            } else {
                Suggestion("帮我梳理今天最重要的三件事", choosePrompt)
                Suggestion("分析一个想法的目标、风险和下一步", choosePrompt)
                Suggestion("总结我接下来发送的内容", choosePrompt)
            }
        }
    }
}

@Composable
private fun Suggestion(text: String, choosePrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { choosePrompt(text) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyMedium); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        HorizontalDivider(color = Line)
    }
}

/** 新消息入场：轻微上移 + 淡入，只作用于刚到达的一条。 */
@Composable
private fun Appearing(animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f, visibilityThreshold = 0.001f)) }
    Box(Modifier.graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 22f }) { content() }
}

@Composable
private fun MessageItem(message: AgentMessage, openProject: (String) -> Unit) {
    message.appResult?.let { AppResultBlock(it, openProject); return }
    if (message.isPlan) {
        ExecutionDisclosure("准备执行", message.text.lineSequence().filter { it.isNotBlank() }.toList(), Blue)
        return
    }
    if (message.toolName != null) {
        ExecutionDisclosure(message.toolName, message.text.lineSequence().filter { it.isNotBlank() }.toList(), Success)
        return
    }
    if (message.isAttachment) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AttachmentMessage(message.attachmentUri?.let(Uri::parse))
        return
    }
    if (message.fromUser) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = UserBubble, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.86f)) { Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), color = Ink) }
    } else Column(Modifier.fillMaxWidth()) {
        AgentMarkdown(message.text)
        message.usage?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun AttachmentMessage(uri: Uri?) {
    var lightbox by remember { mutableStateOf(false) }
    Surface(color = UserBubble, shape = RoundedCornerShape(10.dp), modifier = Modifier.widthIn(max = 180.dp)) {
        Column(Modifier.padding(6.dp)) {
            if (uri != null) {
                ImagePreview(uri, Modifier.size(width = 164.dp, height = 132.dp).clickable { lightbox = true })
            } else {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Image, null, tint = Blue, modifier = Modifier.size(18.dp))
                    Text("图片附件", color = Ink, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (lightbox && uri != null) ImageLightbox(uri) { lightbox = false }
}

@Composable
private fun ImagePreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "图片预览", modifier.clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
    else Box(modifier.clip(RoundedCornerShape(7.dp)).background(RaisedTone), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Image, "图片加载失败", tint = Muted) }
}

@Composable
private fun ImageLightbox(uri: Uri, onDismiss: () -> Unit) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC101418)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            ImagePreview(
                uri,
                Modifier.fillMaxWidth().padding(18.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                    .pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offsetX += pan.x; offsetY += pan.y
                        }
                    }
                    .clickable { }
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(RaisedTone, CircleShape)) { Icon(Icons.Rounded.Close, "关闭图片", tint = Ink) }
        }
    }
}

@Composable
private fun StreamingReply(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Blue))
            Text("正在生成", color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        }
        AgentMarkdown(text)
    }
}

@Composable
private fun AppResultBlock(result: AppResult, openProject: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = SurfaceTone,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp)).clickable { openProject(result.projectId) }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(RaisedTone), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Code, null, tint = Ink, modifier = Modifier.size(20.dp)) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(result.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (result.runtime == "web") "Web 小程序 · ${result.files} 个文件 · 已验证" else "ApplicationSpec v${result.schemaVersion} · ${result.pages} 页 · ${result.components} 组件", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "打开小程序", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        // 结果直接长在对话里，用户不必切到「我的小程序」才能看到做出来的东西。
        // 只显示发布时留存的截图；找不到就只留入口，绝不在这里现场渲染——那会把一个绑定用户真实存储、
        // 还会执行项目 JS 的 WebView 挂进聊天列表。
        if (result.project != null) PreviewCard(
            shotKey = "pub-${result.projectId}-${result.project.revision}",
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/**
 * 对话内的预览卡片：**一张小图 + 点击放大**。
 *
 * 之前这里是「在聊天列表里现场渲染一个 WebView 再截图」，两个问题：横向拉满聊天框，而且截图分辨率
 * 取决于卡片宽度（1114×1505，比例 0.74），完全不像手机。现在截图由验证流程产出（WebPreview.verify，
 * 尺寸 = 设备真实像素，交互步骤跑完后再截），这里只负责按缩略图显示。
 *
 * 缩略图宽度固定，高度按图片自身比例走，所以永远是一张手机形状的小图，不会把聊天框撑满。
 */
@Composable
private fun PreviewCard(shotKey: String, version: Any? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 状态用「时间戳+体积」而不是 File：File 按路径比较相等，同一个 key 反复覆盖时状态不变，
    // Compose 不会重组——上一版就是这样把第一轮的旧图一直显示到运行结束的。
    var stamp by remember(shotKey) { mutableStateOf(shotStamp(context, shotKey)) }
    var lightbox by remember(shotKey) { mutableStateOf(false) }
    // version 变化（文件被改动）时重跑：图是验证流程写完才有的，卡片会先出现、图后到。
    LaunchedEffect(shotKey, version) {
        repeat(40) {
            val next = shotStamp(context, shotKey)
            if (next != stamp) stamp = next
            if (next > 0) return@LaunchedEffect
            delay(500)
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (stamp > 0) {
            val file = com.example.goon.core.ShotStore.file(context, shotKey)
            val ratio = remember(stamp) { previewSize(file) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // key(stamp)：ImagePreview 内部按 uri 缓存解码结果，同路径不会重新解码，必须换实例。
                key(stamp) {
                    ImagePreview(
                        Uri.fromFile(file),
                        Modifier.width(132.dp).aspectRatio(ratio).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Line, RoundedCornerShape(10.dp)).clickable { lightbox = true }
                    )
                }
                Text("点击放大", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
            }
        } else {
            Row(
                Modifier.height(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Muted)
                Text("正在生成预览图", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
    if (lightbox && stamp > 0) ImageLightbox(Uri.fromFile(com.example.goon.core.ShotStore.file(context, shotKey))) { lightbox = false }
}

/** 预览图的存在性指纹：0 表示还没有图；否则用「修改时间 + 体积」区分每一次覆盖。 */
private fun shotStamp(context: Context, shotKey: String): Long {
    val file = com.example.goon.core.ShotStore.file(context, shotKey)
    return if (file.exists() && file.length() > 0) file.lastModified() * 1000 + file.length() else 0L
}

/** 从文件头读出宽高比，用来给缩略图定形；读不到时退回手机竖屏比例。 */
private fun previewSize(file: java.io.File): Float = runCatching {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 0.46f
}.getOrDefault(0.46f)

@Composable
private fun AgentMarkdown(text: String) {
    // 模型偶尔把链接写成 [文字]\n(地址)，断行后正则匹配不到，先归一化。
    val lines = text.replace(Regex("\\]\\s*\\n\\s*\\("), "](").lines()
    var inCode = false
    val code = mutableListOf<String>()
    val table = mutableListOf<List<String>>()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        lines.forEach { raw ->
            if (raw.trimStart().startsWith("```")) {
                if (table.isNotEmpty()) { MarkdownTable(table.toList()); table.clear() }
                if (inCode) { CodeBlock(code.joinToString("\n")); code.clear() }
                inCode = !inCode
            } else if (inCode) {
                code += raw
            } else if (raw.isBlank()) {
                if (table.isNotEmpty()) { MarkdownTable(table.toList()); table.clear() }
                Spacer(Modifier.height(2.dp))
            } else if (raw.trim().startsWith("|") && raw.trim().endsWith("|")) {
                // |---|---| 这类对齐行只用来标记表头，不入表内容。
                val cells = raw.trim().trim('|').split('|').map { it.trim() }
                if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) table += cells
            } else {
                if (table.isNotEmpty()) { MarkdownTable(table.toList()); table.clear() }
                val line = raw.trim()
                val bullet = line.startsWith("- ") || line.startsWith("* ")
                val numbered = Regex("^(\\d+)[.)]\\s+(.+)$").matchEntire(line)
                when {
                    line.startsWith("### ") -> Text(inlineMarkdown(line.removePrefix("### ")), color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    line.startsWith("## ") -> Text(inlineMarkdown(line.removePrefix("## ")), color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    line.startsWith("# ") -> Text(inlineMarkdown(line.removePrefix("# ")), color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    line == "---" || line == "***" -> HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 6.dp))
                    line.startsWith("> ") -> Row(Modifier.padding(vertical = 2.dp)) { Box(Modifier.width(3.dp).height(22.dp).background(Blue)); Text(inlineMarkdown(line.drop(2)), color = Muted, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp, modifier = Modifier.padding(start = 10.dp)) }
                    bullet -> Row(Modifier.padding(start = 4.dp)) { Text("•", color = Blue, modifier = Modifier.padding(end = 9.dp)); Text(inlineMarkdown(line.drop(2)), color = Ink, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp, modifier = Modifier.weight(1f)) }
                    numbered != null -> Row(Modifier.padding(start = 4.dp)) { Text("${numbered.groupValues[1]}.", color = Blue, modifier = Modifier.padding(end = 9.dp)); Text(inlineMarkdown(numbered.groupValues[2]), color = Ink, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp, modifier = Modifier.weight(1f)) }
                    else -> Text(inlineMarkdown(line), color = Ink, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                }
            }
        }
        if (table.isNotEmpty()) { MarkdownTable(table.toList()); table.clear() }
        if (code.isNotEmpty()) CodeBlock(code.joinToString("\n"))
    }
}

/**
 * Markdown 表格。搜索结果和数据页的答案大量是「英雄 / 胜率 / 选取率」这种对照表，
 * 之前原样显示 `|---|---|` 等于把最有信息量的部分丢掉。
 * 只做等宽分栏，不做横向滚动：手机宽度下宽表本来就该由模型少列几列，而不是让用户左右拖。
 */
@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.size }
    // 数值列右对齐更容易竖着比大小；判据是表体里出现数字或百分号的比例。
    val numeric = (0 until columns).map { column ->
        val body = rows.drop(1).mapNotNull { it.getOrNull(column) }.filter { it.isNotBlank() }
        body.isNotEmpty() && body.count { it.any(Char::isDigit) } * 2 >= body.size
    }
    Surface(
        color = SurfaceTone,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, cells ->
                if (index > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (column in 0 until columns) {
                        Text(
                            inlineMarkdown(cells.getOrNull(column).orEmpty()),
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = if (numeric.getOrElse(column) { false }) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(color = CodeSurface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(8.dp))) {
        Text(code, color = Ink, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
    }
}

/** 链接文字是裸 URL 或这种泛指词时，改用站点名——用户要看的是「哪个站」，不是一长串地址。 */
private val genericLinkLabels = setOf(
    "来源", "链接", "链接见", "原文", "出处", "页面", "详情", "这里", "点击这里", "见", "参考",
    "source", "link", "here", "site", "url", "reference", "ref", "page", "details", "open"
)

fun linkLabel(label: String, url: String): String {
    val trimmed = label.trim()
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        .removePrefix("www.").ifBlank { url }
    // 裸地址（含裸 URL）与泛指词一律换成站点名，其余尊重作者写的文字。
    if (trimmed.isBlank() || trimmed.startsWith("http") || genericLinkLabels.contains(trimmed.lowercase())) return host
    return trimmed
}

private fun AnnotatedString.Builder.link(url: String, label: String) = withLink(
    LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = Blue, textDecoration = TextDecoration.Underline)))
) { append(label) }

/**
 * 行内 Markdown。链接与裸 URL 必须在同一次扫描里处理：
 * 分两步做的话，`[来源](https://x)` 括号里的地址会先被当成裸 URL，把链接拆散。
 * 链接的文字统一走 [linkLabel]——模型经常写「来源」或干脆把地址当文字，那不是用户想看的。
 */
private fun inlineMarkdown(text: String): AnnotatedString {
    val output = AnnotatedString.Builder()
    val pattern = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)|(https?://[^\\s)）】\\]，,。；;\"'<>]+)|\\*\\*[^*]+\\*\\*|`[^`]+`")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        output.append(text.substring(cursor, match.range.first))
        val token = match.value
        val label = match.groups[1]?.value
        val markdownUrl = match.groups[2]?.value
        val bareUrl = match.groups[3]?.value
        when {
            markdownUrl != null -> output.link(markdownUrl, linkLabel(label.orEmpty(), markdownUrl))
            bareUrl != null -> output.link(bareUrl, linkLabel(bareUrl, bareUrl))
            token.startsWith("**") -> {
                output.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                output.append(token.removePrefix("**").removeSuffix("**")); output.pop()
            }
            else -> {
                output.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Blue))
                output.append(token.removeSurrounding("`")); output.pop()
            }
        }
        cursor = match.range.last + 1
    }
    output.append(text.substring(cursor))
    return output.toAnnotatedString()
}

@Composable
private fun ExecutionDisclosure(title: String, lines: List<String>, accent: Color) {
    var expanded by remember(title + lines.joinToString()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = 1f, stiffness = 700f)).clickable { expanded = !expanded }) {
        HorizontalDivider(color = Line)
        Column(Modifier.padding(vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, null, tint = accent, modifier = Modifier.size(15.dp))
                Text(title, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起" else "展开", tint = Muted, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(expanded) { Column(Modifier.padding(top = 8.dp, start = 24.dp)) { lines.forEachIndexed { index, line -> Text(if (lines.size > 1) "${index + 1}. $line" else line, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = if (index == 0) 0.dp else 5.dp)) } } }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun AgentTrace(state: AgentDisplayState) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Line)
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.canCancel) Box(Modifier.size(7.dp).clip(CircleShape).background(Blue)) else Icon(Icons.Rounded.CheckCircle, null, tint = if (state.phase == "失败") Danger else Success, modifier = Modifier.size(16.dp))
            Text(state.phase, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (state.canCancel) Text("处理中", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        if (state.canCancel) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(bottom = 9.dp))
        AnimatedVisibility(state.canCancel) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Blue, trackColor = RaisedTone) }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    hasImage: Boolean,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    showActions: Boolean,
    onToggleActions: () -> Unit,
    onDismissActions: () -> Unit,
    showMentions: Boolean,
    onDismissMentions: () -> Unit,
    projects: List<ProjectSummary>,
    onSelectProject: (String) -> Unit,
    onSelectCreate: () -> Unit,
    onSelectCommand: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 0.dp)
            .border(1.dp, Line, RoundedCornerShape(12.dp)),
        color = SurfaceTone,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value, onValueChange, placeholder = { Text("给 Goon 发送消息", color = Muted) },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            )
            DropdownMenu(
                expanded = showMentions && !busy,
                onDismissRequest = onDismissMentions,
                modifier = Modifier.fillMaxWidth(0.92f).background(SurfaceTone)
            ) {
                DropdownMenuItem(
                    text = { Column { Text("分析需求", color = Ink); Text("按目标、风险和下一步整理", color = Muted, style = MaterialTheme.typography.labelSmall) } },
                    onClick = { onSelectCommand("分析需求") },
                    leadingIcon = { Icon(Icons.Rounded.History, null, tint = Blue) }
                )
                DropdownMenuItem(
                    text = { Column { Text("总结对话", color = Ink); Text("提炼结论与待办事项", color = Muted, style = MaterialTheme.typography.labelSmall) } },
                    onClick = { onSelectCommand("总结对话") },
                    leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Blue) }
                )
                DropdownMenuItem(
                    text = { Column { Text("创建新小程序", color = Ink); Text("进入受限应用构建流程", color = Muted, style = MaterialTheme.typography.labelSmall) } },
                    onClick = onSelectCreate,
                    leadingIcon = { Icon(Icons.Rounded.Add, null, tint = Blue) }
                )
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Column { Text(project.name, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("关联并修改此小程序", color = Muted, style = MaterialTheme.typography.labelSmall) } },
                        onClick = { onSelectProject(project.id) },
                        leadingIcon = { Icon(Icons.Rounded.Apps, null, tint = Muted) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = onToggleActions, enabled = !busy, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Add, "更多操作", tint = if (showActions) Blue else Muted, modifier = Modifier.size(21.dp)) }
                    DropdownMenu(expanded = showActions && !busy, onDismissRequest = onDismissActions, modifier = Modifier.background(SurfaceTone)) {
                        DropdownMenuItem(text = { Text("新建小程序", color = Ink) }, onClick = { onSelectCreate(); onDismissActions() }, leadingIcon = { Icon(Icons.Rounded.Apps, null, tint = Blue) })
                        DropdownMenuItem(text = { Text("添加图片", color = Ink) }, onClick = { onPickImage(); onDismissActions() }, leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Muted) })
                        DropdownMenuItem(text = { Text("清空输入", color = Ink) }, onClick = { onValueChange(""); onDismissActions() }, leadingIcon = { Icon(Icons.Rounded.Close, null, tint = Muted) })
                    }
                }
                IconButton(onClick = if (hasImage) onClearImage else onPickImage, enabled = !busy, modifier = Modifier.size(36.dp)) { Icon(if (hasImage) Icons.Rounded.Close else Icons.Rounded.Image, if (hasImage) "移除图片" else "添加图片", tint = if (hasImage) Blue else Muted, modifier = Modifier.size(19.dp)) }
                if (hasImage) Text("已附加图片", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(40.dp).clip(CircleShape).background(if (value.isNotBlank()) Blue else RaisedTone)) {
                    Icon(if (busy) Icons.Rounded.MoreHoriz else Icons.AutoMirrored.Rounded.Send, if (busy) "追加要求" else "发送", tint = if (value.isNotBlank() && !busy) Color.White else Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniAppsScreen(workspace: Workspace, openProject: (String) -> Unit, editProject: (String) -> Unit, deleteProject: (String) -> Unit, createNew: () -> Unit, modifier: Modifier = Modifier) {
    val projects = workspace.projects(); val active = workspace.activeProjectId()
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${project.name}？") },
            text = { Text("小程序、运行数据和版本记录会从本机删除。相关对话仍会保留。") },
            confirmButton = { TextButton(onClick = { deleteProject(project.id); pendingDelete = null }) { Text("删除", color = Color(0xFFFF6B5B)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            containerColor = SurfaceTone
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("我的小程序", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("本地项目与运行状态", color = Muted, modifier = Modifier.padding(top = 4.dp)) }; IconButton(onClick = createNew, Modifier.clip(CircleShape).background(RaisedTone)) { Icon(Icons.Rounded.Add, "新建", tint = Blue) } } }
        if (projects.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Apps, null, tint = Muted, modifier = Modifier.size(30.dp))
                Text("还没有小程序", color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = createNew) { Text("创建第一个") }
            }
        }
        items(projects, key = { it.id }) { project ->
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceTone), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { openProject(project.id) }) {
                Row(Modifier.padding(start = 15.dp, top = 11.dp, bottom = 11.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (project.id == active) Blue else RaisedTone), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Apps, null, tint = if (project.id == active) Color.White else Muted) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(project.name, fontWeight = FontWeight.SemiBold); Text(project.description.ifBlank { "本地小程序" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 3.dp)) }
                    IconButton(onClick = { editProject(project.id) }) { Icon(Icons.Rounded.Edit, "修改", tint = Muted, modifier = Modifier.size(19.dp)) }
                    IconButton(onClick = { pendingDelete = project }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = Muted, modifier = Modifier.size(19.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProjectScreen(workspace: Workspace, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val webProject = workspace.activeProjectId()?.let { workspace.webStore().load(it) }
    if (webProject != null) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
            item { Text(webProject.manifest.name, style = MaterialTheme.typography.headlineSmall) }
            item { Text("项目文件", Modifier.padding(vertical = 12.dp)) }
            items(webProject.files.keys.toList()) { path -> Text(path, Modifier.padding(vertical = 4.dp)) }
            item { Text("版本记录", Modifier.padding(vertical = 12.dp)) }
            items(workspace.webStore().revisions(webProject.manifest.id)) { point ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(workspace.displayTime(point.createdAt), Modifier.weight(1f))
                    TextButton(onClick = { workspace.webStore().restore(webProject.manifest.id, point.id); onChanged() }) { Text("恢复") }
                }
            }
        }
        return
    }
    val spec = workspace.projectSpec(workspace.activeProjectId())
    if (spec == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有可管理的小程序", color = Muted) }
        return
    }
    val checkpoints = workspace.checkpoints()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("项目与版本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(colors = CardDefaults.cardColors(containerColor = SurfaceTone), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(spec.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(spec.description, color = Muted, modifier = Modifier.padding(top = 5.dp)); Text("规格 v${spec.schemaVersion} · ${spec.componentCount()} 个组件", color = Blue, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp)) } } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("版本记录", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = { workspace.createCheckpoint("手动保存"); onChanged() }) { Text("保存当前版本") } } }
        items(checkpoints, key = { it.id }) { point -> Card(colors = CardDefaults.cardColors(containerColor = SurfaceTone), shape = RoundedCornerShape(8.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.History, null, tint = Muted); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(point.label, fontWeight = FontWeight.Medium); Text(workspace.displayTime(point.createdAt), style = MaterialTheme.typography.labelSmall, color = Muted) }; TextButton(onClick = { workspace.restore(point); onChanged() }) { Text("恢复") } } } }
    }
}

/** 技能库：展示已加载的 skill 及其在当前 Android 宿主上的可落地程度。 */
/**
 * 素材库：用户能看见自己有哪些素材，并点开放大。
 *
 * 只读展示——素材的写入走宿主的导入路径，不在这里做增删，避免"用户手滑删掉小程序正在引用的图"。
 */
@Composable
private fun AssetsScreen(workspace: Workspace, modifier: Modifier = Modifier) {
    val assets = remember { com.example.goon.core.AssetStore.list(workspace.context()) }
    var preview by remember { mutableStateOf<String?>(null) }
    val totalMb = remember { assets.sumOf { it.bytes } / 1024.0 / 1024.0 }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("素材库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${assets.size} 个素材 · ${String.format(java.util.Locale.CHINA, "%.1f", totalMb)} MB。小程序要用的素材必须先在项目里声明，之后用 assets/文件名 引用。", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (assets.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("素材库是空的。把图片放进 files/assets/ 后重新打开这一页即可看到。", color = Muted, modifier = Modifier.padding(top = 20.dp))
            }
        }
        items(assets, key = { it.name }) { asset ->
            val file = com.example.goon.core.AssetStore.file(workspace.context(), asset.name)
            Column {
                Surface(color = SurfaceTone, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Line, RoundedCornerShape(10.dp)).clickable { preview = asset.name }) {
                    if (file != null) ImagePreview(Uri.fromFile(file), Modifier.fillMaxSize())
                }
                Text(asset.name.substringBeforeLast('.'), color = Ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    preview?.let { name ->
        com.example.goon.core.AssetStore.file(workspace.context(), name)?.let { ImageLightbox(Uri.fromFile(it)) { preview = null } }
    }
}

@Composable
private fun SkillsScreen(modifier: Modifier = Modifier) {
    val skills = remember { com.example.goon.core.BuiltInSkills.listJson() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("技能库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("skill 是给 Agent 的提示规范，只影响决策、不增加任何运行权限。下面是已加载的 skill 和在手机上的实际可落地程度。", color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        items(count = skills.length(), key = { index -> skills.optJSONObject(index)?.optString("id") ?: index.toString() }) { index ->
            val skill = skills.optJSONObject(index)
            if (skill != null) {
                val label = when (skill.optString("applicability")) { "partial" -> "部分可用"; "reference" -> "仅供参考"; else -> "可直接使用" }
                val tone = when (skill.optString("applicability")) { "partial" -> Color(0xFFB7791F); "reference" -> Muted; else -> Success }
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceTone), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp))) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.optString("title"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Surface(color = RaisedTone, shape = RoundedCornerShape(6.dp)) { Text(label, color = tone, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
                        }
                        Text(skill.optString("id"), color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                        Text(skill.optString("summary"), color = Ink, modifier = Modifier.padding(top = 7.dp))
                        val note = skill.optString("platformNote")
                        if (note.isNotBlank()) Text(note, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelScreen(secrets: SecretStore, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val initial = remember { secrets.readSettings() }; var baseUrl by remember { mutableStateOf(initial.baseUrl) }; var model by remember { mutableStateOf(initial.model) }; var key by remember { mutableStateOf(initial.apiKey) }; var notice by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        item { Text("模型设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("连接兼容 OpenAI Chat Completions 的服务", color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)) }
        item { Card(colors = CardDefaults.cardColors(containerColor = SurfaceTone), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("https://api.openai.com/v1") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
            OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, shape = RoundedCornerShape(10.dp))
            OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, shape = RoundedCornerShape(10.dp))
            Button(onClick = { secrets.saveSettings(ProviderSettings(baseUrl, model, key)); notice = "设置已保存"; onSaved() }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("保存设置") }
            TextButton(onClick = { secrets.clearKey(); key = ""; notice = "API Key 已清除"; onSaved() }, modifier = Modifier.align(Alignment.End)) { Text("清除 API Key", color = Color(0xFFD85845)) }
        } } }
            if (notice.isNotBlank()) item { Text(notice, color = Success, modifier = Modifier.padding(top = 12.dp)) }
        item { Text("API Key 使用 Android Keystore 加密，只保存在本机。项目、快照和普通日志都不会包含凭据。", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 16.dp)) }
    }
}
