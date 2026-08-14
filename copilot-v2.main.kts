@file:CompilerOptions("-jvm-target", "11")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
@file:DependsOn("ai.koog:prompt-executor-dashscope-client-jvm:1.1.1-beta")
@file:DependsOn("ai.koog:prompt-executor-openrouter-client-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-executor-openai-client-jvm:1.1.1")
@file:DependsOn("ai.koog:agents-core-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-executor-model-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-model-jvm:1.1.1")
@file:DependsOn("ai.koog:http-client-ktor-jvm:1.1.1")
@file:DependsOn("org.jsoup:jsoup:1.18.1")
@file:DependsOn("org.angryscan:core-jvm:1.5.1")
@file:DependsOn("io.github.cdimascio:dotenv-kotlin:6.4.1")
@file:DependsOn("org.angryscan:core-jvm:1.5.1")
@file:OptIn(kotlin.time.ExperimentalTime::class)

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIBaseLLMResponse
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIBaseLLMStreamResponse
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.toMessageResponse
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.angryscan.common.engine.kotlin.IKotlinMatcher
import org.angryscan.common.engine.kotlin.KotlinEngine
import org.angryscan.common.matchers.Address
import org.angryscan.common.matchers.BankAccount
import org.angryscan.common.matchers.BankAccountLE
import org.angryscan.common.matchers.Birthday
import org.angryscan.common.matchers.CVV
import org.angryscan.common.matchers.CadastralNumber
import org.angryscan.common.matchers.CardNumber
import org.angryscan.common.matchers.Certificate
import org.angryscan.common.matchers.CryptoSeedPhrase
import org.angryscan.common.matchers.CryptoWallet
import org.angryscan.common.matchers.DriverLicense
import org.angryscan.common.matchers.EducationDoc
import org.angryscan.common.matchers.EducationLicense
import org.angryscan.common.matchers.Email
import org.angryscan.common.matchers.ExecDocNumber
import org.angryscan.common.matchers.FullName
import org.angryscan.common.matchers.Geo
import org.angryscan.common.matchers.HashData
import org.angryscan.common.matchers.INN
import org.angryscan.common.matchers.LegalEntityId
import org.angryscan.common.matchers.LegalEntityName
import org.angryscan.common.matchers.Login
import org.angryscan.common.matchers.MilitaryID
import org.angryscan.common.matchers.OGRNIP
import org.angryscan.common.matchers.OKPO
import org.angryscan.common.matchers.OMS
import org.angryscan.common.matchers.OSAGOPolicy
import org.angryscan.common.matchers.Passport
import org.angryscan.common.matchers.Password
import org.angryscan.common.matchers.Phone
import org.angryscan.common.matchers.ResidencePermit
import org.angryscan.common.matchers.SNILS
import org.angryscan.common.matchers.SberBook
import org.angryscan.common.matchers.StateRegContract
import org.angryscan.common.matchers.VIN
import org.angryscan.common.matchers.VehicleRegNumber
import java.io.File
import kotlin.collections.set
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
System.setProperty("org.slf4j.simpleLogger.log.ai.koog", "error")

data class ProviderTuning(
    val clientClass: KClass<out AbstractOpenAILLMClient<*, *>>,
    val baseUrl: String? = null,
    val requestModifier: (MutableMap<String, JsonElement>) -> Unit = {},
)

val llmCommon = LlmCommon()

class LlmCommon {
    fun executor(providerTuning: ProviderTuning): PromptExecutor {
        return PromptExecutor.builder().addClient(createClient(providerTuning)).build()
    }

    fun createClient(tuning: ProviderTuning): AbstractOpenAILLMClient<out OpenAIBaseLLMResponse, out OpenAIBaseLLMStreamResponse> {
        val factory = HttpClient(CIO) {
            install(RequestModifierPlugin(tuning.requestModifier))
            install(ResponseChunkLoggerPlugin())
        }.let { KtorKoogHttpClient.Factory(it) }

        return when (tuning.clientClass) {
            OpenRouterLLMClient::class -> {
                val default = OpenRouterClientSettings()
                OpenRouterLLMClient(
                    settings = OpenRouterClientSettings(
                        baseUrl = tuning.baseUrl ?: default.baseUrl,
                    ),
                    apiKey = dotenv()["ORKEY"],
                    httpClientFactory = factory
                )
            }

            DashscopeLLMClient::class -> {
                DashscopeLLMClient(
                    settings = DashscopeClientSettings(
                        baseUrl = tuning.baseUrl ?: "https://token-plan.ap-southeast-1.maas.aliyuncs.com"
                    ),
                    apiKey = dotenv()["DSKEY"],
                    httpClientFactory = factory
                )
            }

            else -> error("Unsupported client: ${tuning.clientClass}")
        }
    }

    fun parsePromptWithMarkers(prompt: String): List<Pair<Message.Role, String>> {
        val messages = mutableListOf<Pair<Message.Role, String>>()
        val lines = prompt.lines()
        var currentRole: Message.Role = Message.Role.User
        val currentContent = StringBuilder()
        var isMarkerMode = false
        for (line in lines) {
            if (line.startsWith("# As") || line.startsWith("# Us")) {
                if (currentContent.isNotEmpty()) {
                    messages.add(Pair(currentRole, currentContent.toString()))
                    currentContent.clear()
                }
                currentRole = if (line.startsWith("# As")) {
                    Message.Role.Assistant
                } else {
                    Message.Role.User
                }
                isMarkerMode = true
            } else {
                if (currentContent.isNotEmpty() && isMarkerMode) {
                    currentContent.append("\n")
                }
                currentContent.append(line)
            }
        }
        if (currentContent.isNotEmpty())
            messages.add(Pair(currentRole, currentContent.toString()))
        return messages
    }

    fun outputAnswer(response: String, file: File) {
        val hasFirstLevelHeading = Regex("^# ").containsMatchIn(response)

        val processedResponse = if (hasFirstLevelHeading) {
            response.replace(Regex("^#+ ")) { match ->
                val hashCount = match.value.count { it == '#' }
                "${"#".repeat(hashCount + 1)} "
            }
        } else {
            response
        }

        val content = file.readText().trimEnd()
        val updatedContent = content + "\n\n# As\n\n$processedResponse\n\n# Us"

        file.writeText(updatedContent)
    }

    inner class RequestModifierPlugin(val requestModifier: (MutableMap<String, JsonElement>) -> Unit) :
        HttpClientPlugin<Unit, Unit> {
        override val key = AttributeKey<Unit>("RequestLoggerPlugin")
        override fun prepare(block: Unit.() -> Unit) = Unit

        @OptIn(InternalAPI::class)
        override fun install(plugin: Unit, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Transform) {
                val body = context.body as? String
                if (body != null) {
//                    info("Original request body:\n$body")
                    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
                    val tree = json.parseToJsonElement(body).jsonObject.toMutableMap()
                    requestModifier(tree)
                    val modifiedBody = json.encodeToString(JsonElement.serializer(), JsonObject(tree))
//                    info("Modified request body:\n$modifiedBody")
                    proceedWith(modifiedBody)
                } else {
                    proceed()
                }
            }
        }
    }

    inner class ResponseChunkLoggerPlugin : HttpClientPlugin<Unit, Unit> {
        override val key = AttributeKey<Unit>("ResponseChunkLoggerPlugin")

        override fun prepare(block: Unit.() -> Unit): Unit = Unit

        @OptIn(InternalAPI::class)
        override fun install(plugin: Unit, scope: HttpClient) {
            scope.responsePipeline.intercept(HttpResponsePipeline.Receive) { container ->
                val session = container.response as? SSESession ?: return@intercept

                val loggingSession = object : SSESession by session {
                    override val incoming: Flow<ServerSentEvent> =
                        session.incoming.map { event ->
                            // Workaround for Koog bug: DashScope sends "content":"" (empty string)
                            // in tool call chunks, which causes StreamFrameFlowBuilder to prematurely
                            // flush pending tool calls with incomplete arguments.
                            event.copy(data = event.data?.replace("\"content\":\"\"", "\"content\":null"))
                        }.onEach { monitoring.processEvent(it.data) }
                            .onCompletion { monitoring.flushAll() }
                }
                proceedWith(HttpResponseContainer(container.expectedType, loggingSession))
            }
        }
    }


}

val monitoring = Monitoring()

class Monitoring {
    private val json = Json { ignoreUnknownKeys = true }
    private val progressMap = mutableMapOf<String, ResponseProgress>()
    private val outputDir = File("monitoring-output").also { it.mkdirs() }


    data class ResponseProgress(
        private var reasoningContent: String = "",
        private var textContent: String = "",
        private var savedLength: Int = 0,
        private var toolCallsLog: String = ""
    ) {
        fun appendReasoning(text: String) {
            if (text.isNotEmpty()) reasoningContent += text
        }

        fun appendText(text: String) {
            if (text.isNotEmpty()) textContent += text
        }

        fun appendToolCall(name: String?, argsChunk: String?) {
            name?.apply { toolCallsLog += "\n\nName: $name\nArgs: $toolCallsLog" }
            argsChunk?.apply { toolCallsLog += argsChunk }
        }

        fun shouldSerialize(threshold: Int = 200): Boolean {
            val currentTotal = reasoningContent.length + textContent.length
            return currentTotal - savedLength >= threshold
        }

        fun toMarkdown(): String = arrayOf(
            reasoningContent.takeIf { it.isNotEmpty() }?.let { "# Reasoning Content\n\n${it.trim()}" },
            textContent.takeIf { it.isNotEmpty() }?.let { "# Text Content\n\n${it.trim()}" },
            toolCallsLog.takeIf { it.isNotEmpty() }?.let { "# Tool Calling Log\n\n${it.trim()}" },

            ).filterNotNull().joinToString("\n\n")

        fun resetCounter() {
            savedLength = reasoningContent.length + textContent.length
        }
    }

    fun flushAll() {
        progressMap.forEach { (id, progress) ->
            progress.toMarkdown()
                .takeIf { it.isNotBlank() }
                ?.let { File(outputDir, "$id.md").writeText(it) }
        }
        progressMap.clear()
    }

    fun processEvent(eventData: String?) {
        if (eventData.isNullOrBlank() || eventData == "[DONE]") return

        val root = runCatching {
            json.parseToJsonElement(eventData).jsonObject
        }.getOrNull() ?: return

        val delta = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject ?: return

        val id = root["id"]?.jsonPrimitive?.content ?: return
        val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
        val content = delta["content"]?.jsonPrimitive?.contentOrNull
        val (tcArgs, tcName) = arrayOf("arguments", "name").map { key ->
            delta["tool_calls"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("function")?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull
        }

        if (arrayOf(reasoning, content, tcArgs, tcName).filterNotNull().isEmpty()) return

        val progress = progressMap.getOrPut(id) { ResponseProgress() }
        reasoning?.let(progress::appendReasoning)
        content?.let(progress::appendText)
        if (tcArgs != null || tcName != null) progress.appendToolCall(tcName, tcArgs)

        if (progress.shouldSerialize()) {
            File(outputDir, "$id.md").writeText(progress.toMarkdown())
            progress.resetCounter()
        }
    }
}

fun info(message: String) {
    val datetime =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
    val lines = message.lines()
    if (lines.size <= 1) println("$datetime $message") else {
        println(datetime)
        lines.forEach { println(it) }
    }
}

object Redaction {
    val redactionMatchers = listOf<IKotlinMatcher>(
        Passport, SNILS, INN, OMS, OGRNIP, OKPO, Phone, Email, Address, FullName,
        CardNumber(), BankAccount, BankAccountLE, DriverLicense, VehicleRegNumber, VIN,
        CadastralNumber, OSAGOPolicy, SberBook, ResidencePermit, MilitaryID,
        LegalEntityId, LegalEntityName, Birthday, Certificate, EducationDoc, EducationLicense,
        ExecDocNumber, StateRegContract, Login, Geo, Password,
        CryptoWallet, CryptoSeedPhrase, CVV, HashData
    )
    val redactionEngine = KotlinEngine(redactionMatchers)
    fun redactText(text: String): String {
        val apiKeyPatterns = listOf(
            "sk-or-v1-[a-fA-F0-9]{64}",
            "sk-sp-H\\.[A-Za-z0-9._-]+",
            "sk-kr_live_[A-Za-z0-9_-]+"
        ).map { it.toRegex() }
        var result = text
        for (pattern in apiKeyPatterns) {
            val matches = pattern.findAll(result).toList().sortedByDescending { it.range.first }
            for (match in matches) {
                result = result.replaceRange(match.range, "[REDACTED:OpenRouterKey]")
            }
        }
        val angryMatches = redactionEngine.scan(result).sortedByDescending { it.startPosition.toInt() }
        for (match in angryMatches) {
            result = result.replaceRange(
                match.startPosition.toInt()..match.endPosition.toInt(),
                "[REDACTED:${match.matcher.name}]"
            )
        }
        return result
    }
}

val prompts = Prompts()

class Prompts {
    private val toolPromptRegistry = mutableMapOf<KFunction<*>, String>()
    fun KFunction<*>.defineDescription(block: KFunction<*>.() -> String) {
        toolPromptRegistry[this] = "## ${this.name}\n\n${block()}"
    }

    fun KFunction<*>.description(): String? = toolPromptRegistry[this]
    infix fun String.from(func: KFunction<*>): String {
        val validParams = func.parameters.asSequence()
            .filter { it.kind == KParameter.Kind.VALUE && it.name != null }
            .map { it.name!! }.toSet()
        if (this !in validParams)
            error("Prompt validation failed: parameter '$this' not found in ${func.name}(). Valid: $validParams")
        return this
    }

    init {
        MainAgentTools::readFile.defineDescription {
            """
            Gets file contents by path with numbered lines

            ### Params

            - ${"path" from this} -- absolute path to the file
            - ${"maxSizeText" from this} -- tool returns first maxSizeText characters
              recommended value to start with is ${maxSizeText()}""".trimIndent()
        }

        MainAgentTools::readDirectory.defineDescription {
            """
            Lists directory contents as an ASCII tree with file sizes.
            Expands level by level (BFS); stops when cumulative entry count would exceed maxEntries.

            ### Params

            - ${"path" from this} -- absolute path to the directory
            - ${"maxEntries" from this} -- max total entries across all levels; tree stops expanding when next level would exceed this
              recommended value to start with is 50""".trimIndent()
        }
    }
}

class MainAgentTools : ToolSet {
    companion object {
        private val readFiles = mutableSetOf<String>()
    }

    @Tool
    fun readFile(path: String, maxSizeText: Int): String {
        readFiles.add(File(path).absolutePath)
        val proposalFile = File("$path.proposal")
        val file = if (proposalFile.exists()) {
            info("readFile: proposal found")
            proposalFile
        } else File(path)
        info("readFile is run: ${file.absolutePath} (maxSizeText: $maxSizeText)")
        if (!file.exists()) return "ERROR: File doesn't exist"
        if (file.isDirectory) return "ERROR: It is a path, not a file"
        val fullText = file.readText()
        val truncated = fullText.length > maxSizeText
        val rawText = fullText.take(maxSizeText)
        val redactedText = Redaction.redactText(rawText)
        val lines = redactedText.lines() // разбиваем на строки
        val numberedContent = lines.mapIndexed { index, line ->
            "${index + 1}: $line"
        }.joinToString("\n")
        val lineNumberInstruction = "The returned file content includes line numbers. " +
                "Each line is prefixed with the line number followed by a colon. " +
                "For example, `1: println(\"first line\")` means line number one contains " +
                "the code `println(\"first line\")`.\n\n"
        val truncationMessage = if (truncated) "Returned only first $maxSizeText characters of the file.\n\n" else ""
        return "$truncationMessage$lineNumberInstruction$numberedContent"
    }

    @Tool
    fun readDirectory(path: String, maxEntries: Int): String {
        info("readDirectory is run: $path (maxEntries: $maxEntries)")
        val dir = File(path)
        if (!dir.exists()) return "ERROR: Directory doesn't exist"
        if (!dir.isDirectory) return "ERROR: It is a file, not a directory"

        // ── Phase 1: BFS to find how many levels fit within the threshold ──
        var currentLevelDirs = listOf(dir)
        var totalEntries = 0
        var maxDepth = 0
        var truncated = false

        while (currentLevelDirs.isNotEmpty()) {
            var levelCount = 0
            val nextLevelDirs = mutableListOf<File>()

            for (d in currentLevelDirs) {
                val entries = d.listFiles() ?: continue
                levelCount += entries.size
                for (entry in entries) {
                    if (entry.isDirectory) nextLevelDirs += entry
                }
            }

            if (totalEntries + levelCount > maxEntries) {
                truncated = true
                break
            }

            totalEntries += levelCount
            currentLevelDirs = nextLevelDirs
            maxDepth++
        }

        // ── Phase 2: DFS to render the tree up to maxDepth ──
        val sb = StringBuilder()
        sb.appendLine(dir.absolutePath)

        fun render(file: File, prefix: String, depth: Int) {
            if (depth >= maxDepth) return
            val entries = file.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?: return

            entries.forEachIndexed { i, entry ->
                val last = i == entries.size - 1
                val connector = if (last) "└── " else "├── "
                val size = if (entry.isFile) " (${formatSize(entry.length())})" else ""
                val mark = if (entry.isDirectory) "/" else ""
                sb.appendLine("$prefix$connector${entry.name}$mark$size")
                if (entry.isDirectory) {
                    render(entry, prefix + if (last) "    " else "│   ", depth + 1)
                }
            }
        }

        render(dir, "", 0)

        if (truncated) {
            sb.appendLine("… (truncated — next level would exceed $maxEntries entries)")
        }

        return sb.toString().trimEnd()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024L * 1024 * 1024)} GB"
    }
}

fun mainAgent() = AIAgent(
    promptExecutor = llmCommon.executor(providerTuning()),
    strategy = functionalStrategy<String, String> { input ->

        llm().writeSession {
            for ((role, content) in llmCommon.run { parsePromptWithMarkers(input) }) {
                appendPrompt {
                    when (role) {
                        Message.Role.User -> user(content)
                        Message.Role.Assistant -> assistant(content)
                        else -> {}
                    }
                }
            }
        }

        suspend fun callAndLog(iteration: Int): Pair<Message.Assistant, List<MessagePart.Tool.Call>> {
            val response1 = llm().writeSession { requestLLMStreaming() }
            val response2 = response1.toList()
            val response = response2.toMessageResponse()
            info(
                "Iteration: $iteration, Input: ${response.metaInfo.inputTokensCount}, " +
                        "Output: ${response.metaInfo.outputTokensCount}"
            )
            val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            return response to toolCalls
        }

        var iteration = 1
        var (response, toolCalls) = callAndLog(iteration)  // сохраняем первый ответ
        while (toolCalls.isNotEmpty() && iteration < 30) {
            executeTools(toolCalls).let { results ->
                appendPrompt {
                    user { results.forEach { toolResult(it.toMessagePart()) } }
                }
            }
            val (newResponse, newToolCalls) = callAndLog(++iteration)
            response = newResponse
            toolCalls = newToolCalls
        }

        val finalText = response.parts
            .filterIsInstance<MessagePart.Text>()
            .joinToString("\n\n") { it.text }
        finalText
    },
    agentConfig = AIAgentConfig(
        prompt = Prompt.build(id = "agent-1") {
            val toolPrompts = currentTools().mapNotNull { toolRef ->
                prompts.run { toolRef.description() }
            }.ifEmpty { null }?.joinToString("\n\n------\n\n")
            system(
                "Ты агент-ассистент. Окажи максимальную поддержку" +
                        "У тебя есть следующие инструменты: \n\n$toolPrompts "
            )
        },
        model = currentModel(),
//        model = OpenAIModels.Chat.GPT5_4.copy(id = "qwen3.7-plus"),

        maxAgentIterations = 5
    ),
    toolRegistry = ToolRegistry {
        tools(
            MainAgentTools().asTools()
                .filter { tool -> currentTools().any { currentTool -> currentTool.name == tool.name } })
    },
)

val promptFileObj = File(promptFilePath())
if (!promptFileObj.exists()) {
    info("Prompt file not found: ${promptFileObj.absolutePath}")
    if (args.isEmpty()) {
        info("No command line argument provided, and 'prompt.md' doesn't exist in the current directory.")
    }
    kotlin.system.exitProcess(1)
}
info("start v1")
val response =
    runBlocking { mainAgent().run(promptFileObj.readText()) }
llmCommon.apply { outputAnswer(response, promptFileObj) }
info("finish")

// ========== Tuning agent =============

fun promptFilePath() = args[0]
fun providerTuning() = ProviderTuning(clientClass = DashscopeLLMClient::class) {
    it["enable_thinking"] = JsonPrimitive(true)
//    it["enable_search"] = JsonPrimitive(true)
//    it["search_options"] = JsonObject(mapOf("search_strategy" to JsonPrimitive("agent")))
}
fun currentModel() = DashscopeModels.QWEN3_MAX.copy(id = "qwen3.7-plus")
fun maxSizeText() = 40000
fun currentTools() = setOf<KFunction<String>>(
    MainAgentTools::readFile,
    MainAgentTools::readDirectory
)

