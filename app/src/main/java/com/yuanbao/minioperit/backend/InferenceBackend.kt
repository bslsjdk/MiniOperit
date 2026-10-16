package com.yuanbao.minioperit.backend

/**
 * 可插拔推理后端接口。
 *
 * 设计意图：本 App 不绑定任何具体推理引擎。你可以实现这个接口，
 * 注册进 BackendRegistry，就能把 llama.cpp / MNN / 自己的 Hexagon NPU
 * 后端 / 远端 HTTP 服务接进来，UI 和提示词层完全不用改。
 *
 * 最小实现只需要：
 *   1. 实现 generate()，把 Message 列表变成流式文本
 *   2. 在 BackendRegistry.register() 里注册
 * 其余方法都有默认实现。
 */
interface InferenceBackend {

    /** 唯一标识，用于 UI 下拉框和持久化 */
    val id: String

    /** 展示名 */
    val displayName: String

    /**
     * 上下文上限。提示词构建器严格按这个值裁剪，绝不会超。
     * 返回 0 表示不限制（不推荐，容易崩）。
     */
    val contextLimit: Int

    /** 后端是否就绪（模型已加载 / 服务已连接） */
    fun isReady(): Boolean

    /**
     * 加载资源。会在后台线程调用，可以做耗时操作。
     * @return 成功返回 null，失败返回错误信息
     */
    suspend fun load(config: BackendConfig): String?

    /** 释放资源 */
    fun unload()

    /**
     * 流式生成。
     *
     * @param messages 已经由 PromptBuilder 裁剪好的完整对话（含 system）
     * @param params 采样参数
     * @param onToken 每生成一个 token 回调一次（在调用线程，一般已是 IO 线程）
     * @return 生成结果，包含完整文本和统计
     */
    suspend fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onToken: (String) -> Unit
    ): GenerateResult

    /**
     * 精确 token 计数。用于提示词裁剪。
     * 如果后端拿不到 tokenizer，返回 null，PromptBuilder 会退回启发式估算
     * （估算会偏保守，宁可少放也不能撑爆上下文）。
     */
    fun countTokens(text: String): Int? = null

    /** 停止当前生成 */
    fun stop() {}
}

/** 后端加载配置。各后端按需取用自己关心的字段。 */
data class BackendConfig(
    /** 模型文件路径（本地后端用） */
    val modelPath: String = "",
    /** 额外的 .so / 依赖库目录（比如你编好的 jniLibs） */
    val libDir: String = "",
    /** 服务端点（远端后端用） */
    val endpoint: String = "",
    /** API Key */
    val apiKey: String = "",
    /** 上下文长度，0 表示用后端默认值 */
    val contextSize: Int = 0,
    /** GPU/NPU offload 层数 */
    val nGpuLayers: Int = 99,
    /** 线程数 */
    val threads: Int = 4,
    /** 任意扩展参数 */
    val extra: Map<String, String> = emptyMap()
)

data class ChatMessage(
    val role: Role,
    val content: String,
    /** 工具调用结果回填时用，用于区分普通消息 */
    val name: String? = null
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val repeatPenalty: Float = 1.1f,
    val stopSequences: List<String> = emptyList()
)

data class GenerateResult(
    val text: String,
    val tokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f,
    val truncated: Boolean = false,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}
