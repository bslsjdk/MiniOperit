package com.yuanbao.minioperit.prompt

import com.yuanbao.minioperit.backend.ChatMessage
import com.yuanbao.minioperit.backend.InferenceBackend
import kotlin.math.ceil

/**
 * 提示词构建器 —— 这个 App 防崩溃的地基。
 *
 * Operit 本地推理崩掉的根因是提示词长度超过 n_ctx。
 * llama.cpp 在输入超过上下文时会直接 abort 或产生垃圾输出，
 * 而不是优雅报错。所以这里采用**硬性预算**策略：
 *
 *   总预算 = contextLimit - 安全余量 - 生成预留
 *
 * 各段按优先级分配，低优先级的内容先被裁掉：
 *
 *   P0 系统提示词      —— 永不裁剪（所以它必须短）
 *   P1 角色卡          —— 超预算时截断
 *   P2 长期记忆摘要    —— 超预算时截断
 *   P3 近期对话        —— 从最旧的一条条丢弃
 *   P4 工具定义        —— 按剩余空间决定能放几个工具
 *
 * 关键原则：**宁可少放，绝不多放**。估算拿不准时一律往大了估。
 */
class PromptBuilder(
    private val backend: InferenceBackend?
) {

    /** 生成预留：必须给输出留位置，否则生成到一半就撞墙 */
    private val reserveForGeneration: Int
        get() = (backend?.contextLimit?.let { (it * 0.35).toInt() } ?: 512)
            .coerceAtMost(2048)

    /** 安全余量：抵消 tokenizer 差异和估算误差 */
    private val safetyMargin = 64

    /** 实际可用于输入提示词的 token 数 */
    val inputBudget: Int
        get() {
            val limit = backend?.contextLimit?.takeIf { it > 0 } ?: 2048
            return (limit - reserveForGeneration - safetyMargin).coerceAtLeast(256)
        }

    /**
     * 构建完整消息列表。保证总 token 不超过 inputBudget。
     *
     * @return 裁剪后的消息列表，以及一份裁剪报告（方便调试"到底裁了什么"）
     */
    fun build(input: BuildInput): BuildResult {
        val dropped = mutableListOf<String>()
        val budget = inputBudget

        // ---- P0 系统提示词：不裁剪，但会记录它占了多少 ----
        val systemText = buildSystemPrompt(input)
        val systemCost = cost(systemText)
        var remaining = budget - systemCost

        val out = mutableListOf<ChatMessage>()
        out.add(ChatMessage(ChatMessage.Role.SYSTEM, systemText))

        if (remaining <= 0) {
            // 极端情况：系统提示词本身就超了预算
            dropped += "系统提示词占 ${systemCost} token，已超出总预算 $budget，本轮只发送系统提示词"
            return BuildResult(out, systemCost, dropped)
        }

        // ---- P1 角色卡 ----
        var personaCost = 0
        input.persona?.takeIf { it.isNotBlank() }?.let { persona ->
            val cap = (remaining * 0.4).toInt()
            val fitted = fitToBudget(persona, cap)
            personaCost = fitted.second
            if (fitted.first != persona) dropped += "角色卡被截断（${cost(persona)} → $personaCost token）"
            out.add(ChatMessage(ChatMessage.Role.SYSTEM, "【角色设定】\n${fitted.first}"))
            remaining -= personaCost
        }

        // ---- P2 长期记忆 ----
        var memoryCost = 0
        input.memory?.takeIf { it.isNotBlank() }?.let { mem ->
            val cap = (remaining * 0.25).toInt()
            val fitted = fitToBudget(mem, cap)
            memoryCost = fitted.second
            if (fitted.first != mem) dropped += "长期记忆被截断（${cost(mem)} → $memoryCost token）"
            out.add(ChatMessage(ChatMessage.Role.SYSTEM, "【关于用户的已知信息】\n${fitted.first}"))
            remaining -= memoryCost
        }

        // ---- P4 工具定义（先于对话计算，因为工具是刚需） ----
        var toolsCost = 0
        val toolsText = if (input.tools.isNotEmpty()) renderTools(input.tools) else ""
        if (toolsText.isNotBlank()) {
            val cap = (remaining * 0.35).toInt()
            val c = cost(toolsText)
            if (c <= cap) {
                toolsCost = c
                remaining -= c
            } else {
                dropped += "工具定义（$c token）超出剩余预算，本轮已关闭工具调用"
            }
        }

        // ---- P3 近期对话：从最新往回装，装不下就停 ----
        val history = input.history.toMutableList()
        val kept = mutableListOf<ChatMessage>()
        var historyCost = 0
        for (msg in history.asReversed()) {
            val c = cost(msg.content) + 8 // +8 覆盖角色标记等开销
            if (historyCost + c > remaining) {
                dropped += "更早期的 ${history.size - kept.size} 条对话因超出预算被丢弃"
                break
            }
            historyCost += c
            kept.add(0, msg)
        }

        out.addAll(kept)

        // ---- 当前输入：必须有，实在放不下就强行截断 ----
        val userCost = cost(input.userInput)
        if (historyCost + userCost <= remaining) {
            out.add(ChatMessage(ChatMessage.Role.USER, input.userInput))
            historyCost += userCost
        } else {
            val fitted = fitToBudget(input.userInput, (remaining - historyCost).coerceAtLeast(64))
            dropped += "当前输入被截断（$userCost → ${fitted.second} token）"
            out.add(ChatMessage(ChatMessage.Role.USER, fitted.first))
            historyCost += fitted.second
        }

        val total = systemCost + personaCost + memoryCost + toolsCost + historyCost
        return BuildResult(out, total, dropped)
    }

    /**
     * 系统提示词。刻意写得极短。
     *
     * Operit 那类崩溃，一半原因是系统提示词本身就几百上千 token，
     * 再叠加工具定义和长对话，很容易顶到上下文上限。
     * 这里只保留真正必要的行为约束，其余靠角色卡承载。
     */
    private fun buildSystemPrompt(input: BuildInput): String {
        val sb = StringBuilder()
        sb.appendLine("你是手机上的 AI 助手。用中文回复，简洁自然，不要说教，不要自称 AI。")
        if (input.tools.isNotEmpty() && input.toolsText.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(input.toolsText)
        }
        return sb.toString().trim()
    }

    private fun renderTools(tools: List<ToolSpec>): String {
        val sb = StringBuilder()
        sb.appendLine("可用工具：")
        tools.forEach { t ->
            sb.appendLine("- ${t.name}(${t.params.entries.joinToString(", ") { "${it.key}: ${it.value}" }})：${t.description}")
        }
        sb.appendLine()
        sb.appendLine("需要调用工具时，只输出一行 JSON，不要其他文字：")
        sb.appendLine("""{"tool":"工具名","args":{"参数名":"值"}}""")
        sb.appendLine("不需要工具时，直接正常回答。")
        return sb.toString()
    }

    /**
     * token 计费。优先用后端的真实 tokenizer；
     * 拿不到就用保守启发式（中文按 1 字 ≈ 1.2 token，英文按 4 字符 ≈ 1 token，
     * 整体上浮 20% 作为安全系数）。宁可高估也不能低估。
     */
    fun cost(text: String): Int {
        backend?.countTokens(text)?.let { return it }
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF ||
                ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF
            ) cjk++ else other++
        }
        return ceil((cjk * 1.2 + other / 3.6) * 1.2).toInt().coerceAtLeast(1)
    }

    /** 把文本裁剪到预算内，返回 (文本, 实际花费) */
    private fun fitToBudget(text: String, budget: Int): Pair<String, Int> {
        if (budget <= 0) return "" to 0
        val c = cost(text)
        if (c <= budget) return text to c
        // 二分找最长可容纳前缀
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cost(text.substring(0, mid)) <= budget) lo = mid else hi = mid - 1
        }
        val cut = text.substring(0, lo)
        return (cut + "…（已截断）") to cost(cut)
    }
}

data class ToolSpec(
    val name: String,
    val description: String,
    val params: Map<String, String>
)

data class BuildInput(
    val userInput: String,
    val history: List<ChatMessage>,
    val persona: String? = null,
    val memory: String? = null,
    val tools: List<ToolSpec> = emptyList(),
    val toolsText: String = ""
)

data class BuildResult(
    val messages: List<ChatMessage>,
    val estimatedTokens: Int,
    val droppedNotices: List<String>
)
