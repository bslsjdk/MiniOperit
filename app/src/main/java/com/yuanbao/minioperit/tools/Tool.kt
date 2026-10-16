package com.yuanbao.minioperit.tools

import com.yuanbao.minioperit.prompt.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 工具系统。
 *
 * 调用协议刻意做得宽松：不强求模型输出严格 JSON。
 * 解析器会先试严格 JSON，失败后退化到"从文本里抠出第一个 {...} 块"，
 * 再失败就把整段当普通回复。这样即使小模型格式写歪了也不会崩。
 */
interface Tool {
    val spec: ToolSpec
    suspend fun invoke(args: Map<String, String>): String
}

object ToolRegistry {
    private val tools = linkedMapOf<String, Tool>()

    init {
        register(ReadFileTool())
        register(WriteFileTool())
        register(ListDirTool())
        register(RunCommandTool())
    }

    fun register(t: Tool) { tools[t.spec.name] = t }
    fun all(): List<Tool> = tools.values.toList()
    fun specs() = all().map { it.spec }
    fun get(name: String) = tools[name]

    /** 工作根目录，所有文件工具都限制在这个目录内，防止越权读写 */
    var workspaceRoot: File? = null
}

/** 从模型输出里解析工具调用。解析不出来返回 null（当作普通回复）。 */
fun parseToolCall(raw: String): ToolCall? {
    val text = raw.trim().trimMargin().trim('`')
    // 去掉可能的 ```json 围栏
    val cleaned = text.removePrefix("json").removePrefix("JSON").trim()

    val jsonStart = cleaned.indexOf('{')
    val jsonEnd = cleaned.lastIndexOf('}')
    if (jsonStart < 0 || jsonEnd <= jsonStart) return null

    val candidate = cleaned.substring(jsonStart, jsonEnd + 1)
    return try {
        val obj = Json.parseToJsonElement(candidate).jsonObject
        val name = obj["tool"]?.jsonPrimitive?.content ?: return null
        val args = mutableMapOf<String, String>()
        obj["args"]?.jsonObject?.forEach { k, v ->
            args[k] = runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString())
        }
        ToolCall(name, args)
    } catch (e: Exception) {
        null
    }
}

data class ToolCall(val name: String, val args: Map<String, String>)

/** 路径安全校验：必须落在 workspaceRoot 内 */
internal fun safeResolve(path: String): File? {
    val root = ToolRegistry.workspaceRoot ?: return null
    val f = File(path)
    val target = if (f.isAbsolute) f else File(root, path)
    val canonical = runCatching { target.canonicalPath }.getOrNull() ?: return null
    val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return null
    return if (canonical.startsWith(rootCanonical)) File(canonical) else null
}

class ReadFileTool : Tool {
    override val spec = ToolSpec(
        "read_file", "读取文件内容",
        mapOf("path" to "文件路径")
    )
    override suspend fun invoke(args: Map<String, String>): String {
        val p = args["path"] ?: return "缺少 path 参数"
        val f = safeResolve(p) ?: return "路径越权或无效：$p"
        if (!f.exists()) return "文件不存在：$p"
        if (f.length() > 256 * 1024) return "文件过大（${f.length()} 字节），已跳过"
        return runCatching { f.readText() }.getOrElse { "读取失败：${it.message}" }
    }
}

class WriteFileTool : Tool {
    override val spec = ToolSpec(
        "write_file", "写入文件（自动创建父目录）",
        mapOf("path" to "文件路径", "content" to "文件内容")
    )
    override suspend fun invoke(args: Map<String, String>): String {
        val p = args["path"] ?: return "缺少 path 参数"
        val content = args["content"] ?: return "缺少 content 参数"
        val f = safeResolve(p) ?: return "路径越权或无效：$p"
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            "已写入 ${f.length()} 字节到 $p"
        }.getOrElse { "写入失败：${it.message}" }
    }
}

class ListDirTool : Tool {
    override val spec = ToolSpec(
        "list_dir", "列出目录内容",
        mapOf("path" to "目录路径，留空为根目录")
    )
    override suspend fun invoke(args: Map<String, String>): String {
        val p = args["path"].takeUnless { it.isNullOrBlank() } ?: "."
        val f = safeResolve(p) ?: return "路径越权或无效：$p"
        if (!f.isDirectory) return "不是目录：$p"
        val items = f.listFiles()?.take(100) ?: return "无法列出（空或无权访问）"
        return items.joinToString("\n") {
            "${if (it.isDirectory) "D" else "F"}  ${it.name}${if (it.isDirectory) "/" else "  ${it.length()}B"}"
        }
    }
}

class RunCommandTool : Tool {
    override val spec = ToolSpec(
        "run_command", "执行 shell 命令（有超时，禁止后台常驻）",
        mapOf("cmd" to "要执行的命令")
    )
    override suspend fun invoke(args: Map<String, String>): String {
        val cmd = args["cmd"] ?: return "缺少 cmd 参数"
        // 基础安全拦截
        val denied = listOf("rm -rf /", "reboot", "shutdown", ":(){", "mkfs", "dd if=")
        if (denied.any { cmd.contains(it) }) return "命令被安全策略拒绝：$cmd"
        return runCatching {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val ok = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); return "命令超时（15s），已终止" }
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.exitValue()
            val trimmed = if (out.length > 8000) out.take(8000) + "\n…（输出过长已截断）" else out
            "退出码 $code\n$trimmed"
        }.getOrElse { "执行失败：${it.message}" }
    }
}
