package com.yuanbao.minioperit.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuanbao.minioperit.backend.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val dark = true

    LaunchedEffect(st.messages.size, st.messages.lastOrNull()?.content?.length) {
        if (st.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(st.messages.lastIndex) }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerPanel(
                vm = vm,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = DarkBg,
            topBar = { ChatTopBar(st, onMenu = { scope.launch { drawerState.open() } }, onInspector = vm::toggleInspector, onTools = vm::toggleTools) },
            bottomBar = {
                InputBar(
                    value = st.input,
                    onValueChange = vm::onInputChange,
                    busy = st.busy,
                    onSend = vm::send,
                    onStop = vm::stop
                )
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                // 背景光晕
                AmbientGlow()

                if (st.messages.isEmpty()) {
                    EmptyHero(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
                    ) {
                        items(st.messages, key = { it.id }) { m -> MessageBubble(m) }
                    }
                }

                // 提示词检查器（底部浮层）
                AnimatedVisibility(
                    visible = st.showPromptInspector,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    PromptInspector(
                        stats = st.promptStats,
                        persona = st.personaPrompt,
                        onPersonaChange = vm::setPersona,
                        onDismiss = vm::toggleInspector
                    )
                }
            }
        }
    }
}

/** 背景氛围光 */
@Composable
private fun AmbientGlow() {
    val inf = rememberInfiniteTransition(label = "glow")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                listOf(BrandIndigo.copy(alpha = 0.16f * t + 0.06f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.12f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.15f, h * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(BrandViolet.copy(alpha = 0.13f * (1f - t) + 0.05f), Color.Transparent),
                center = Offset(w * 0.9f, h * 0.75f),
                radius = w * 0.65f
            ),
            radius = w * 0.65f,
            center = Offset(w * 0.9f, h * 0.75f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    st: ChatUiState,
    onMenu: () -> Unit,
    onInspector: () -> Unit,
    onTools: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(brandBrush(dark = true))
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 品牌 logo
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(BrandIndigo, BrandViolet, BrandCyan))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("M", color = Color.White, fontSize = 15.sp, style = AppTypography.titleMedium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("MiniOperit", style = AppTypography.titleMedium, color = DarkText)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(st.busy)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                st.statusText.ifBlank { "就绪" },
                                style = AppTypography.labelSmall,
                                color = DarkTextDim
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onMenu) {
                    Icon(Icons.Default.Menu, "菜单", tint = DarkTextDim)
                }
            },
            actions = {
                IconButton(onTools) {
                    Icon(
                        Icons.Default.Build,
                        if (st.toolsEnabled) "工具已开" else "工具已关",
                        tint = if (st.toolsEnabled) BrandCyan else DarkTextDim
                    )
                }
                IconButton(onInspector) {
                    Icon(
                        Icons.Default.Analytics,
                        "提示词",
                        tint = if (st.showPromptInspector) BrandPink else DarkTextDim
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = DarkText
            )
        )
    }
}

@Composable
private fun StatusDot(busy: Boolean) {
    val inf = rememberInfiniteTransition(label = "dot")
    val a by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(7.dp).clip(CircleShape)
            .background(if (busy) BrandCyan.copy(alpha = a) else Color(0xFF4ADE80))
    )
}

/** 空状态引导页 */
@Composable
private fun EmptyHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(78.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(BrandIndigo, BrandViolet, BrandCyan))),
            contentAlignment = Alignment.Center
        ) {
            Text("MO", color = Color.White, fontSize = 26.sp, style = AppTypography.titleLarge)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "有什么可以帮你？",
            style = AppTypography.titleLarge,
            color = DarkText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "本地推理 · 提示词自动裁剪 · 不会因超长而崩溃",
            style = AppTypography.bodyMedium,
            color = DarkTextDim,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        SuggestionChips()
    }
}

@Composable
private fun SuggestionChips() {
    val items = listOf("帮我写一个 Python 脚本", "解释一下这段代码", "总结今天的要点", "列一下工作区文件")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { text ->
                    Surface(
                        onClick = { },
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceAlt,
                        border = BorderStroke(1.dp, Color(0xFF2C3149)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = AppTypography.labelMedium,
                            color = DarkTextDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: UiMessage) {
    val isUser = m.role == ChatMessage.Role.USER
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Row(verticalAlignment = Alignment.Top) {
            if (!isUser) {
                // AI 头像
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandViolet, BrandIndigo))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AI", color = Color.White, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(Modifier.widthIn(max = 300.dp)) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    color = if (isUser) Color.Transparent else DarkSurfaceAlt,
                    border = if (isUser) null else BorderStroke(1.dp, Color(0xFF262B42)),
                    modifier = if (isUser) {
                        Modifier.background(userBubbleBrush(dark = true), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    } else Modifier
                ) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                        Text(
                            m.content.ifBlank { "" },
                            color = if (isUser) Color.White else DarkText,
                            style = AppTypography.bodyLarge
                        )
                        if (m.streaming && m.content.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            TypingCursor()
                        }
                    }
                }

                // 工具调用结果卡片
                m.toolTrace?.let { trace ->
                    Spacer(Modifier.height(6.dp))
                    ToolTraceCard(trace)
                }
            }

            if (isUser) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(DarkSurfaceAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Text("我", color = DarkTextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TypingCursor() {
    val inf = rememberInfiniteTransition(label = "cursor")
    val a by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(width = 7.dp, height = 15.dp)
            .graphicsLayer { alpha = a }
            .background(BrandCyan, RoundedCornerShape(1.dp))
    )
}

@Composable
private fun ToolTraceCard(trace: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14243A),
        border = BorderStroke(1.dp, BrandCyan.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    null,
                    tint = BrandCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "工具调用",
                    style = AppTypography.labelMedium,
                    color = BrandCyan,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = BrandCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(expanded || trace.length < 90) {
                Text(
                    trace,
                    modifier = Modifier.padding(top = 6.dp),
                    style = AppTypography.labelSmall,
                    color = DarkTextDim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) Int.MAX_VALUE else 4
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = DarkBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = DarkSurfaceAlt,
                border = BorderStroke(1.dp, Color(0xFF2C3149)),
                modifier = Modifier.weight(1f)
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("说点什么…", color = DarkTextDim, style = AppTypography.bodyMedium) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    textStyle = AppTypography.bodyLarge.copy(color = DarkText),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = BrandIndigo
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            // 发送 / 停止按钮
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .then(
                        if (busy) Modifier.background(Color(0xFF3A2333))
                        else Modifier.background(Brush.linearGradient(listOf(BrandIndigo, BrandViolet)))
                    )
                    .clickable { if (busy) onStop() else if (value.isNotBlank()) onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    if (busy) "停止" else "发送",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun PromptInspector(
    stats: String,
    persona: String,
    onPersonaChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(18.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, Color(0xFF2C3149)),
        tonalElevation = 6.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, null, tint = BrandPink, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("提示词预算", style = AppTypography.titleMedium, color = DarkText, modifier = Modifier.weight(1f))
                IconButton(onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "收起", tint = DarkTextDim, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1A1F33)
            ) {
                Text(
                    stats.ifBlank { "发一条消息后这里会显示 token 用量" },
                    modifier = Modifier.padding(10.dp),
                    style = AppTypography.labelSmall,
                    color = DarkTextDim,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = persona,
                onValueChange = onPersonaChange,
                label = { Text("角色卡 / 系统提示词", style = AppTypography.labelMedium) },
                placeholder = { Text("例：你是一个毒舌但靠谱的程序员…", style = AppTypography.bodyMedium) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 150.dp),
                maxLines = 5,
                textStyle = AppTypography.bodyMedium.copy(color = DarkText),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandIndigo,
                    unfocusedBorderColor = Color(0xFF2C3149),
                    cursorColor = BrandIndigo
                )
            )
        }
    }
}
