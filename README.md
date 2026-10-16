# MiniOperit

一个精简的 Android 本地 AI 助手骨架。存在的意义是：**先把 Operit 的功能复现出来，把推理后端换成可插拔的，解决提示词过长导致的崩溃。**

---

## 为什么重写，而不是改 Operit

你反馈的核心问题是：**本地推理根本用不了，因为提示词太长所以崩掉。**

这在 llama.cpp 上的表现是：输入 token 超过 `n_ctx` 时，轻则输出乱码，重则直接 abort。Operit 的提示词里塞了系统提示词 + 角色卡 + 工具定义 + 长对话历史，很容易顶到上限，而且**没有任何一层在做预算控制**。

所以这个项目的第一原则不是"功能多"，而是：

> **提示词长度是硬约束，宁可少放，绝不多放。**

---

## 一、防崩是怎么做到的

核心在 `prompt/PromptBuilder.kt`。

### 预算公式

```
可用输入预算 = 后端 contextLimit
             - 生成预留（35%，最多 2048）
             - 安全余量（64）
```

**生成预留是关键**：很多崩溃不是输入太长，而是输入占了 90% 上下文，生成到 100 个 token 时撞墙。这里强制留 35% 给输出。

### 分段优先级

| 优先级 | 内容 | 超预算时 |
|---|---|---|
| P0 | 系统提示词 | 不裁剪（所以它必须短） |
| P1 | 角色卡 | 截断到剩余预算 40% |
| P2 | 长期记忆摘要 | 截断到剩余 25% |
| P3 | 工具定义 | 超了就**关闭本轮工具调用** |
| P4 | 近期对话 | 从最旧的一条条丢 |

### 双重防线

- **软压缩**（`MemoryManager`）：对话超 20 条时，把早期的压成摘要存库，只留最近 6 条原文
- **硬裁剪**（`PromptBuilder`）：每次生成前按预算强制裁剪，绝不超限

### token 计数

优先用后端真实 tokenizer（`countTokens`）。拿不到就启发式估算：**中文 1 字 ≈ 1.2 token，英文 3.6 字符 ≈ 1 token，整体上浮 20%**。宁可高估。

界面上点「提示词」按钮能实时看到：当前多少 token、上限多少、裁掉了什么。

---

## 二、系统提示词为什么这么短

`PromptBuilder.buildSystemPrompt()` 只有一句话：

> 你是手机上的 AI 助手。用中文回复，简洁自然，不要说教，不要自称 AI。

Operit 那类系统提示词动辄几百 token，叠加工具定义和对话就爆了。这里把角色设定移到**角色卡**单独承载，并且角色卡有独立预算（剩余空间的 40%），超了就截断而不是顶爆整体。

针对你说的几个问题：

| 症状 | 处理 |
|---|---|
| 人设不稳 | 角色卡作为独立 system 段注入，不被历史冲掉 |
| 工具格式错乱 | 宽松解析器：先试严格 JSON，失败就从文本里抠 `{...}` 块，再失败当普通回复。**不会崩** |
| 回复啰嗦说教 | 系统提示词明确"简洁自然，不要说教，不要自称 AI" |
| 中文生硬 | 明确要求中文回复 |

---

## 三、怎么接入你自己的后端（重点）

只需要实现一个接口。

### 1. 写你的后端类

```kotlin
class MyHexagonBackend : InferenceBackend {
    override val id = "myhex"
    override val displayName = "我的 Hexagon NPU 后端"
    override val contextLimit = 4096   // 必须如实填，PromptBuilder 靠它裁剪

    override fun isReady() = loaded

    override suspend fun load(config: BackendConfig): String? {
        // 加载模型 / 初始化 NPU，返回 null 表示成功，返回字符串表示错误
        return null
    }

    override fun unload() { }

    override suspend fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onToken: (String) -> Unit
    ): GenerateResult {
        // 把 messages 拼成你的格式，调你的引擎，逐 token 回调
        return GenerateResult(text = "...")
    }

    // 有 tokenizer 就实现，没有就返回 null（会用启发式估算）
    override fun countTokens(text: String): Int? = myTokenizer(text)
}
```

### 2. 注册

在 `MiniApp.kt` 的 `onCreate()` 里：

```kotlin
BackendRegistry.register(MyHexagonBackend())
BackendRegistry.setActive("myhex")
```

UI、提示词裁剪、工具调用、历史管理全部自动适配，**不需要改任何其它代码**。

### 3. 放 .so

把你编好的库放进：

```
app/src/main/jniLibs/arm64-v8a/
  libllama.so
  libggml-base.so
  libggml-cpu.so
  libggml-hexagon.so
  libggml-htp-v73.so
  libggml-opencl.so
  libc++_shared.so
```

`build.gradle.kts` 里已设 `useLegacyPackaging = true`，确保 .so 能被 `System.loadLibrary` 正常加载。

### 内置的 LlamaCppBackend

`backend/BackendRegistry.kt` 里有个 `LlamaCppBackend` 骨架，声明了最小 JNI 接口：

| Kotlin | C++ |
|---|---|
| `nativeLoad(modelPath, nCtx, nGpuLayers, threads)` → Long | 加载模型，返回句柄 |
| `nativeGenerate(handle, prompt, temp, topP, topK, maxTokens, repeatPenalty, onToken)` | 流式生成 |
| `nativeCountTokens(handle, text)` → Int | token 计数 |
| `nativeStop(handle)` | 停止生成 |
| `nativeFree(handle)` | 释放 |
| `nativeLastError()` → String | 最近错误 |

你可以**直接用它**（实现对应 C++ 函数），也可以**整个类替换成你自己的实现**——只要接口一致即可。

### MockBackend

内置一个"调试回显"后端，不加载模型，把**实际要发给模型的完整提示词**原样显示出来。这是排查提示词问题的利器——不用模型就能看到裁剪效果。

---

## 四、功能清单

已实现：

- **聊天**：流式输出、停止生成、气泡 UI
- **本地 GGUF 推理**：通过后端接口（自带 llama.cpp JNI 骨架）
- **对话历史 / 记忆**：Room 持久化、多会话、超 20 条自动压缩成摘要
- **工具调用**：`read_file` / `write_file` / `list_dir` / `run_command`
- **角色卡 / 提示词编辑**：界面内直接改，实时生效
- **提示词检查器**：实时显示 token 用量、裁剪情况

工具安全：
- 文件操作限制在 `getExternalFilesDir("workspace")` 内，做了 canonicalPath 越权校验
- 命令执行 15 秒超时，拦截 `rm -rf /` 等危险命令

---

## 五、编译

```bash
./gradlew :app:assembleDebug
```

要求：JDK 17、Android SDK 34、NDK（只在你要编译 C++ 时才需要）。

---

## 六、第一版没有做的

刻意砍掉，避免重蹈 Operit 覆辙：

- 浏览器自动化、终端模拟器、Ubuntu PRoot
- 市场 / ToolPkg / MCP / 工作流 / 语音 / 虚拟形象
- 多角色群聊、联网搜索

这些都能在后端跑稳之后再加。**先保证本地推理不崩。**
