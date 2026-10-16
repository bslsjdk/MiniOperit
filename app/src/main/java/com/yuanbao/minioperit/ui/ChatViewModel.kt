package com.yuanbao.minioperit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuanbao.minioperit.backend.*
import com.yuanbao.minioperit.data.*
import com.yuanbao.minioperit.prompt.*
import com.yuanbao.minioperit.tools.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiMessage(
    val id: Long = System.nanoTime(),
    val role: ChatMessage.Role,
    val content: String,
    val streaming: Boolean = false,
    val toolTrace: String? = null
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val statusText: String = "",
    val promptStats: String = "",
    val toolsEnabled: Boolean = true,
    val personaPrompt: String = "",
    val showPromptInspector: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()
    private val memory = MemoryManager(dao)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sessionId: Long = 0
    private var personaText: String = ""

    // ---- 可调参数 ----
    private var modelPathVal = ""
    private var ctxSize = 4096
    private var gpuLayersVal = 99
    private var temperatureVal = 0.7f
    private var maxTokensVal = 512

    private val backend get() = BackendRegistry.active()

    init {
        viewModelScope.launch {
            val sid = dao.insertSession(SessionEntity(title = "新对话"))
            sessionId = sid
            ToolRegistry.workspaceRoot = app.getExternalFilesDir("workspace")?.apply { mkdirs() }
        }
    }

    // ---- 设置面板用的读写 ----
    fun activeBackendName(): String = backend?.displayName ?: "未选择"
    fun activeBackendId(): String = backend?.id ?: ""
    fun selectBackend(id: String) {
        backend?.unload()
        BackendRegistry.setActive(id)
        _state.update { it.copy(statusText = "已切换到 ${activeBackendName()}") }
    }
    fun modelPath() = modelPathVal
    fun setModelPath(p: String) { modelPathVal = p }
    fun contextSize() = ctxSize
    fun gpuLayers() = gpuLayersVal
    fun isBackendReady() = backend?.isReady() ?: false
    fun temperature() = temperatureVal
    fun setTemperature(v: Float) { temperatureVal = v }
    fun maxTokens() = maxTokensVal
    fun setMaxTokens(v: Int) { maxTokensVal = v }

    fun newSession() {
        viewModelScope.launch {
            val sid = dao.insertSession(SessionEntity(title = "新对话"))
            sessionId = sid
            personaText = ""
            _state.update {
                it.copy(messages = emptyList(), personaPrompt = "", promptStats = "", statusText = "新对话")
            }
        }
    }

    fun clearCurrent() {
        _state.update { it.copy(messages = emptyList(), promptStats = "") }
        viewModelScope.launch {
            dao.deleteSession(sessionId)
            val sid = dao.insertSession(SessionEntity(title = "新对话"))
            sessionId = sid
        }
    }

    fun onInputChange(v: String) { _state.update { it.copy(input = v) } }

    fun toggleTools() { _state.update { it.copy(toolsEnabled = !it.toolsEnabled) } }

    fun toggleInspector() { _state.update { it.copy(showPromptInspector = !it.showPromptInspector) } }

    fun setPersona(p: String) {
        personaText = p
        _state.update { it.copy(personaPrompt = p) }
    }

    fun stop() { backend?.stop(); _state.update { it.copy(busy = false) } }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isBlank() || _state.value.busy) return

        val userMsg = UiMessage(role = ChatMessage.Role.USER, content = text)
        _state.update {
            it.copy(
                messages = it.messages + userMsg,
                input = "",
                busy = true,
                statusText = "生成中…"
            )
        }

        viewModelScope.launch {
            dao.insertMessage(
                MessageEntity(sessionId = sessionId, role = "USER", content = text)
            )

            val assistantId = System.nanoTime()
            _state.update {
                it.copy(messages = it.messages + UiMessage(assistantId, ChatMessage.Role.ASSISTANT, "", streaming = true))
            }

            runCatching { runTurn(text, assistantId) }
                .onFailure { e ->
                    appendTo(assistantId, "\n\n[出错] ${e.message}")
                }

            _state.update { it.copy(busy = false, statusText = "就绪") }
        }
    }

    private suspend fun runTurn(userText: String, assistantId: Long) {
        val be = backend
        if (be == null) {
            appendTo(assistantId, "[未选择推理后端]")
            return
        }
        if (!be.isReady()) {
            appendTo(assistantId, "[后端未加载]  请在左侧菜单填入模型路径并点「加载模型」")
            return
        }

        // 1. 压缩早期对话
        val memSummary = memory.maybeCompress(sessionId) ?: dao.latestMemory(sessionId)?.summary

        // 2. 取近期历史
        val history = dao.messages(sessionId).first().map {
            ChatMessage(
                role = when (it.role) {
                    "USER" -> ChatMessage.Role.USER
                    "TOOL" -> ChatMessage.Role.TOOL
                    else -> ChatMessage.Role.ASSISTANT
                },
                content = it.content
            )
        }.takeLast(12)

        // 3. 构建提示词（带硬性预算裁剪）
        val tools = if (_state.value.toolsEnabled) ToolRegistry.specs() else emptyList()
        val builder = PromptBuilder(be)
        val built = builder.build(
            BuildInput(
                userInput = userText,
                history = history,
                persona = personaText,
                memory = memSummary,
                tools = tools
            )
        )

        _state.update {
            it.copy(promptStats = "提示词约 ${built.estimatedTokens} token / 上限 ${builder.inputBudget}" +
                    (if (built.droppedNotices.isNotEmpty()) "\n⚠ " + built.droppedNotices.joinToString("；") else ""))
        }

        // 4. 生成
        val params = SamplingParams(
            temperature = temperatureVal,
            maxTokens = maxTokensVal
        )
        val raw = StringBuilder()
        val result = be.generate(built.messages, params) { tok ->
            raw.append(tok)
            viewModelScope.launch { appendTo(assistantId, tok) }
        }

        if (!result.isSuccess) {
            appendTo(assistantId, "\n[推理失败] ${result.errorMessage}")
            return
        }

        // 5. 工具调用（只解析第一轮，避免无限循环）
        if (tools.isNotEmpty()) {
            val call = parseToolCall(raw.toString())
            if (call != null) {
                val tool = ToolRegistry.get(call.name)
                val out = if (tool != null) {
                    tool.invoke(call.args)
                } else {
                    "未知工具：${call.name}。可用：${tools.joinToString(", ") { it.name }}"
                }
                _state.update { s ->
                    val idx = s.messages.indexOfFirst { it.id == assistantId }
                    val updated = s.messages.toMutableList()
                    if (idx >= 0) updated[idx] = updated[idx].copy(toolTrace = "调用 ${call.name} →\n$out")
                    s.copy(messages = updated)
                }
                dao.insertMessage(
                    MessageEntity(sessionId = sessionId, role = "TOOL", content = out)
                )
                return
            }
        }

        // 6. 落库
        dao.insertMessage(
            MessageEntity(sessionId = sessionId, role = "ASSISTANT", content = raw.toString())
        )
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == assistantId) it.copy(streaming = false) else it })
        }
    }

    private fun appendTo(id: Long, chunk: String) {
        _state.update { s ->
            s.copy(messages = s.messages.map {
                if (it.id == id) it.copy(content = it.content + chunk) else it
            })
        }
    }

    fun loadBackend(config: BackendConfig, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(statusText = "加载中…") }
            val err = backend?.load(config)
            _state.update { it.copy(statusText = if (err == null) "后端已就绪" else "加载失败：$err") }
            onDone(err)
        }
    }
}
