package com.yuanbao.minioperit.backend

import kotlinx.coroutines.delay

/**
 * 后端注册表。
 *
 * 接入你自己的后端只需要一行：
 *   BackendRegistry.register(MyHexagonBackend())
 * 建议在 Application.onCreate() 里做。
 */
object BackendRegistry {

    private val backends = linkedMapOf<String, InferenceBackend>()
    private var activeId: String? = null

    init {
        // 默认内置两个，保证 App 开箱能跑起来
        register(MockBackend())
        register(LlamaCppBackend())
    }

    fun register(backend: InferenceBackend) {
        backends[backend.id] = backend
        if (activeId == null) activeId = backend.id
    }

    fun all(): List<InferenceBackend> = backends.values.toList()

    fun get(id: String): InferenceBackend? = backends[id]

    fun active(): InferenceBackend? = activeId?.let { backends[it] }

    fun setActive(id: String): Boolean {
        if (!backends.containsKey(id)) return false
        activeId = id
        return true
    }
}

/**
 * 本地回显后端：不加载任何模型，把收到的最终提示词原样回显。
 *
 * 用途：在没有模型、或者只想检查"提示词到底长什么样、有没有被裁掉"
 * 的时候调试用。这也是排查 Operit 那类"提示词过长崩溃"问题的利器。
 */
class MockBackend : InferenceBackend {
    override val id = "mock"
    override val displayName = "调试回显（不推理）"
    override val contextLimit = 4096
    private var ready = true

    override fun isReady() = ready

    override suspend fun load(config: BackendConfig): String? {
        ready = true
        return null
    }

    override fun unload() { ready = false }

    override suspend fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onToken: (String) -> Unit
    ): GenerateResult {
        val sb = StringBuilder()
        sb.appendLine("【调试模式】以下是实际发给模型的完整内容：\n")

        messages.forEach { m ->
            sb.appendLine("── ${m.role.name} ──")
            sb.appendLine(m.content)
            sb.appendLine()
        }
        sb.appendLine("── 统计 ──")
        sb.appendLine("消息数：${messages.size}")
        sb.appendLine("字符数：${messages.sumOf { it.content.length }}")

        val out = sb.toString()
        // 模拟流式吐字，方便看 UI 行为
        out.chunked(12).forEach { chunk ->
            if (!ready) return@forEach
            onToken(chunk)
            delay(16)
        }
        return GenerateResult(out, out.length / 3, 0f)
    }

    override fun countTokens(text: String): Int? = null
}

/**
 * llama.cpp JNI 后端骨架。
 *
 * 这里用 external 声明了最小可用的 JNI 接口。你需要：
 *  1. 把自己编好的 libllama.so / libggml-*.so 放进 app/src/main/jniLibs/arm64-v8a/
 *  2. 在 C++ 侧实现下面这几个 native 方法
 *     （签名见 README 的 JNI 对照表）
 *  3. 或者更简单：直接把整个类替换成你自己的实现，只要保持接口一致
 *
 * 之所以留这个骨架而不是写死，是因为具体符号名取决于你那版
 * llama.cpp 的 JNI 封装，硬编码容易对不上。
 */
class LlamaCppBackend : InferenceBackend {

    override val id = "llamacpp"
    override val displayName = "llama.cpp（本地 GGUF）"
    override val contextLimit get() = _ctx
    override fun isReady() = _loaded

    private var _loaded = false
    private var _ctx = 4096
    private var handle: Long = 0

    override suspend fun load(config: BackendConfig): String? {
        if (config.modelPath.isBlank()) return "未指定模型路径"
        return try {
            System.loadLibrary("llama")
            if (config.libDir.isNotBlank()) {
                // 如有额外的后端插件（如 libggml-hexagon.so）可在此显式加载
            }
            val nCtx = if (config.contextSize > 0) config.contextSize else 4096
            handle = nativeLoad(config.modelPath, nCtx, config.nGpuLayers, config.threads)
            if (handle == 0L) {
                "模型加载失败：${nativeLastError()}"
            } else {
                _ctx = nCtx
                _loaded = true
                null
            }
        } catch (e: UnsatisfiedLinkError) {
            "缺少 libllama.so：${e.message}"
        } catch (e: Exception) {
            e.message ?: "加载异常"
        }
    }

    override fun unload() {
        if (handle != 0L) {
            runCatching { nativeFree(handle) }
            handle = 0
        }
        _loaded = false
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onToken: (String) -> Unit
    ): GenerateResult {
        if (!_loaded) return GenerateResult("", errorMessage = "后端未加载")

        val prompt = messages.joinToString("") { m ->
            when (m.role) {
                ChatMessage.Role.SYSTEM -> "<|system|>\n${m.content}\n"
                ChatMessage.Role.USER -> "<|user|>\n${m.content}\n"
                ChatMessage.Role.ASSISTANT -> "<|assistant|>\n${m.content}\n"
                ChatMessage.Role.TOOL -> "<|tool|>\n${m.content}\n"
            }
        } + "<|assistant|>\n"

        val sb = StringBuilder()
        val result = nativeGenerate(
            handle, prompt, params.temperature, params.topP, params.topK,
            params.maxTokens, params.repeatPenalty
        ) { tok ->
            sb.append(tok)
            onToken(tok)
        }
        _loaded = _loaded // 保活
        return GenerateResult(
            text = sb.toString(),
            tokensGenerated = result.first,
            tokensPerSecond = result.second
        )
    }

    override fun countTokens(text: String): Int? =
        if (_loaded) runCatching { nativeCountTokens(handle, text) }.getOrNull() else null

    override fun stop() { if (handle != 0L) runCatching { nativeStop(handle) } }

    // ---- JNI 声明：需要你在 C++ 侧实现 ----
    private external fun nativeLoad(modelPath: String, nCtx: Int, nGpuLayers: Int, threads: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(
        handle: Long, prompt: String, temp: Float, topP: Float, topK: Int,
        maxTokens: Int, repeatPenalty: Float, onToken: (String) -> Unit
    ): Pair<Int, Float>
    private external fun nativeCountTokens(handle: Long, text: String): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeLastError(): String
}
