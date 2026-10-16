package com.yuanbao.minioperit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanbao.minioperit.backend.BackendConfig
import com.yuanbao.minioperit.backend.BackendRegistry

@Composable
fun DrawerPanel(vm: ChatViewModel, onClose: () -> Unit) {
    val st by vm.state.collectAsState()
    val scroll = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxHeight().widthIn(max = 320.dp),
        color = DarkSurface
    ) {
        Column(Modifier.verticalScroll(scroll).statusBarsPadding()) {

            // ---- 头部品牌区 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(BrandIndigo, BrandViolet)))
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("MO", color = Color.White, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("MiniOperit", color = Color.White, style = AppTypography.titleLarge)
                            Text("本地推理 · 提示词受控", color = Color.White.copy(alpha = 0.8f), style = AppTypography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 后端选择 ----
            SectionTitle("推理后端", Icons.Default.Memory)
            BackendSection(vm)

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF262B42))

            // ---- 模型加载 ----
            SectionTitle("模型", Icons.Default.Storage)
            ModelSection(vm)

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF262B42))

            // ---- 采样参数 ----
            SectionTitle("生成参数", Icons.Default.Tune)
            ParamsSection(vm)

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF262B42))

            // ---- 上下文预算 ----
            SectionTitle("上下文预算", Icons.Default.Shield)
            BudgetSection(st)

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF262B42))

            // ---- 操作 ----
            SectionTitle("会话", Icons.Default.Chat)
            DrawerAction(Icons.Default.Add, "新建对话") { vm.newSession(); onClose() }
            DrawerAction(Icons.Default.DeleteSweep, "清空当前对话") { vm.clearCurrent(); onClose() }

            Spacer(Modifier.height(24.dp))
            Text(
                "v0.1.0 · 后端可插拔",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = AppTypography.labelSmall,
                color = DarkTextDim.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = BrandCyan, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = AppTypography.labelMedium, color = BrandCyan)
    }
}

@Composable
private fun DrawerAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = DarkTextDim, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = AppTypography.bodyMedium, color = DarkText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSection(vm: ChatViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val current = vm.activeBackendName()
    val all = BackendRegistry.all()

    Column(Modifier.padding(horizontal = 20.dp)) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = DarkSurfaceAlt,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C3149))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current, style = AppTypography.bodyMedium, color = DarkText, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null, tint = DarkTextDim, modifier = Modifier.size(18.dp))
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurfaceAlt)
        ) {
            all.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.displayName, color = DarkText, style = AppTypography.bodyMedium) },
                    onClick = { vm.selectBackend(b.id); expanded = false },
                    trailingIcon = {
                        if (b.id == vm.activeBackendId()) {
                            Icon(Icons.Default.Check, null, tint = BrandCyan, modifier = Modifier.size(17.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSection(vm: ChatViewModel) {
    var path by remember { mutableStateOf(vm.modelPath()) }
    var ctx by remember { mutableStateOf(vm.contextSize().toString()) }
    var gpu by remember { mutableStateOf(vm.gpuLayers().toString()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val ready = vm.isBackendReady()

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = path,
            onValueChange = { path = it; vm.setModelPath(it) },
            label = { Text("模型路径 (.gguf)", style = AppTypography.labelMedium) },
            placeholder = { Text("/sdcard/Download/model.gguf", style = AppTypography.labelSmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = AppTypography.bodyMedium.copy(color = DarkText, fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrandIndigo,
                unfocusedBorderColor = Color(0xFF2C3149),
                cursorColor = BrandIndigo
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ctx,
                onValueChange = { ctx = it },
                label = { Text("上下文", style = AppTypography.labelSmall) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = AppTypography.bodyMedium.copy(color = DarkText),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandIndigo,
                    unfocusedBorderColor = Color(0xFF2C3149)
                )
            )
            OutlinedTextField(
                value = gpu,
                onValueChange = { gpu = it },
                label = { Text("GPU层", style = AppTypography.labelSmall) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = AppTypography.bodyMedium.copy(color = DarkText),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandIndigo,
                    unfocusedBorderColor = Color(0xFF2C3149)
                )
            )
        }

        Button(
            onClick = {
                loading = true
                msg = null
                vm.loadBackend(
                    BackendConfig(
                        modelPath = path,
                        contextSize = ctx.toIntOrNull() ?: 4096,
                        nGpuLayers = gpu.toIntOrNull() ?: 99
                    )
                ) { err ->
                    loading = false
                    msg = err ?: "加载成功"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandIndigo,
                contentColor = Color.White
            )
        ) {
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("加载中…", style = AppTypography.labelMedium)
            } else {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("加载模型", style = AppTypography.labelMedium)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (ready) Color(0xFF4ADE80) else Color(0xFF6B7280))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (ready) "后端已就绪" else "未加载",
                style = AppTypography.labelSmall,
                color = DarkTextDim
            )
        }

        msg?.let {
            Text(
                it,
                style = AppTypography.labelSmall,
                color = if (it == "加载成功") Color(0xFF4ADE80) else Color(0xFFFF6B6B)
            )
        }
    }
}

@Composable
private fun ParamsSection(vm: ChatViewModel) {
    var temp by remember { mutableFloatStateOf(vm.temperature()) }
    var maxTok by remember { mutableFloatStateOf(vm.maxTokens().toFloat()) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("温度", style = AppTypography.bodyMedium, color = DarkText, modifier = Modifier.width(52.dp))
            Slider(
                value = temp,
                onValueChange = { temp = it; vm.setTemperature(it) },
                valueRange = 0f..1.5f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = BrandIndigo,
                    activeTrackColor = BrandIndigo,
                    inactiveTrackColor = Color(0xFF2C3149)
                )
            )
            Text(
                "%.2f".format(temp),
                style = AppTypography.labelSmall,
                color = DarkTextDim,
                modifier = Modifier.width(36.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("长度", style = AppTypography.bodyMedium, color = DarkText, modifier = Modifier.width(52.dp))
            Slider(
                value = maxTok,
                onValueChange = { maxTok = it; vm.setMaxTokens(it.toInt()) },
                valueRange = 64f..2048f,
                steps = 16,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = BrandViolet,
                    activeTrackColor = BrandViolet,
                    inactiveTrackColor = Color(0xFF2C3149)
                )
            )
            Text(
                maxTok.toInt().toString(),
                style = AppTypography.labelSmall,
                color = DarkTextDim,
                modifier = Modifier.width(36.dp)
            )
        }
    }
}

@Composable
private fun BudgetSection(st: ChatUiState) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow("生成预留", "35%")
        InfoRow("安全余量", "64 token")
        InfoRow("历史保留", "最近 12 条")
        InfoRow("自动压缩", "超过 20 条")
        Spacer(Modifier.height(4.dp))
        Text(
            st.promptStats.ifBlank { "发一条消息查看实际用量" },
            style = AppTypography.labelSmall,
            color = DarkTextDim,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AppTypography.bodyMedium, color = DarkTextDim, modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = DarkSurfaceAlt
        ) {
            Text(
                value,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = AppTypography.labelSmall,
                color = BrandCyan
            )
        }
    }
}
