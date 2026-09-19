@file:CompilerOptions("-jvm-target", "17")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
@file:DependsOn("ai.koog:prompt-executor-dashscope-client-jvm:1.1.1-beta")
@file:DependsOn("ai.koog:prompt-executor-openrouter-client-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-executor-openai-client-jvm:1.1.1")
@file:DependsOn("ai.koog:agents-core-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-executor-model-jvm:1.1.1")
@file:DependsOn("ai.koog:prompt-model-jvm:1.1.1")
@file:DependsOn("ai.koog:http-client-ktor-jvm:1.1.1")
@file:DependsOn("ai.koog:agents-features-event-handler-jvm:1.1.1")
@file:DependsOn("org.jsoup:jsoup:1.18.1")
@file:DependsOn("org.angryscan:core-jvm:1.5.1")
@file:DependsOn("io.github.cdimascio:dotenv-kotlin:6.4.1")
@file:DependsOn("org.angryscan:core-jvm:1.5.1")
@file:OptIn(kotlin.time.ExperimentalTime::class)

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
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
import kotlinx.serialization.Serializable
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
import org.angryscan.common.matchers.*
import java.io.File
import kotlin.collections.set
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.schema.defaultJsonSchemaConfig
import ai.koog.agents.core.tools.schema.getJsonSchema
import ai.koog.serialization.typeToken
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.json.JsonSchema
import ai.koog.agents.features.eventHandler.feature.EventHandler


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


    fun saveCurrentPrompt(promptFilePath: String, iteration: Int, messages: List<Message>) {
        val nonSystemMessages = messages.filter { it.role != Message.Role.System }
        if (nonSystemMessages.isEmpty()) return

        val promptDir = File(promptFilePath).parent
        val promptFileName = File(promptFilePath).nameWithoutExtension
        val currentPromptFile = File(promptDir, "$promptFileName.current.md")

        fun String.unescape() = arrayOf("""\n""" to "\n", """\t""" to "\t", """\"""" to "\"")
            .fold(this) { acc, (from, to) -> acc.replace(from, to) }

        val formattedMessages = buildString {
            nonSystemMessages.forEach { message ->
                when (message) {
                    is Message.User -> {
                        message.parts.forEach { part ->
                            append("# Us\n\n")
                            when (part) {
                                is MessagePart.Tool.Result ->
                                    append("## ToolResult: ${part.tool}\n\n${part.output.unescape()}\n\n")

                                is MessagePart.Text -> append("${part.text.trim()}\n\n")
                                else -> {}
                            }
                        }
                    }

                    is Message.Assistant -> {
                        message.parts.forEach { part ->
                            append("# As\n\n")
                            when (part) {
                                is MessagePart.Tool.Call ->
                                    append("ToolCall: ${part.tool}\n\n${part.args.unescape()}\n\n")

                                is MessagePart.Text -> append("${part.text.trim()}\n\n")
                                else -> {}
                            }
                        }
                    }

                    else -> {}
                }
            }
        }

        currentPromptFile.writeText(formattedMessages)
        info("Saved current prompt to ${currentPromptFile.absolutePath} (iteration $iteration)")
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

    @OptIn(InternalAgentToolsApi::class)
    inline fun <reified T> generateSchema(
        config: JsonSchemaConfig = defaultJsonSchemaConfig
    ): String {
        val schema = getJsonSchema(
            typeToken = typeToken<T>(),
            jsonSchemaConfig = config
        )
        return Json { prettyPrint = true }
            .encodeToString(JsonSchema.serializer(), schema)
    }

    inline fun <reified T> correctJson(jsonString: String): T? {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        try {
            return json.decodeFromString<T>(jsonString)
        } catch (_: Exception) {
            info("correctJson: direct deserialization failed")
        }

        val cleaned = jsonString.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            return json.decodeFromString<T>(cleaned)
        } catch (e: Exception) {
            info("correctJson: after markdown cleanup failed: ${e.message}")
        }

        info("correctJson: falling back to LLM correction")
        return runBlocking {
            val agent = AIAgent(
                promptExecutor = executor(providerTuning()),
                strategy = functionalStrategy<String, T?> { input ->
                    requestLLMStructured<T>(
                        "The following message contains JSON that couldn't be deserialized." +
                                "\n\nCorrect it to match the required schema." +
                                "\n\n# The JSON is:\n\n$input"
                    ).getOrNull()?.data
                },
                agentConfig = AIAgentConfig(
                    prompt = Prompt.build(id = "json-correction") {
                        system("You are a helpful agent. Your task is to fix JSON errors.")
                    },
                    model = currentModel(),
                    maxAgentIterations = 3
                )
            )
            agent.run(jsonString)
        }
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
        private var toolCallsInitiated: Boolean = false
    ) {
        fun appendReasoning(text: String) = apply { if (text.isNotEmpty()) reasoningContent += text }
        fun appendText(text: String) = apply { if (text.isNotEmpty()) textContent += text }
        fun markToolCallInititaion() = apply { toolCallsInitiated = true }

        fun shouldSerialize(threshold: Int = 200): Boolean {
            val currentTotal = reasoningContent.length + textContent.length
            return currentTotal - savedLength >= threshold
        }

        fun toMarkdown(): String = arrayOf(
            reasoningContent.takeIf { it.isNotEmpty() }?.let { "# Reasoning Content\n\n${it.trim()}" },
            textContent.takeIf { it.isNotEmpty() }?.let { "# Text Content\n\n${it.trim()}" },
            toolCallsInitiated.takeIf { it }?.let { "# Tool Calling Log\n\nTools were called" }
        ).filterNotNull().joinToString("\n\n")

        fun resetCounter() {
            savedLength = reasoningContent.length + textContent.length
        }
    }

    fun flushAll() {
        progressMap.forEach { (id, progress) ->
            progress.toMarkdown()
                .takeIf { it.isNotBlank() }
                ?.let { saveToFile(id, it) }
        }
        progressMap.clear()
    }

    private fun saveToFile(id: String, content: String) {
        val file = File(outputDir, "$id.md")
        val isFirstWrite = !file.exists()
        file.writeText(content)
        if (isFirstWrite) info("Session log: file://${file.absolutePath}")
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
        val hasTcDelta = delta["tool_calls"]?.jsonArray?.isNotEmpty() ?: false

        val progress = progressMap.getOrPut(id) { ResponseProgress() }
        reasoning?.let(progress::appendReasoning)
        content?.let(progress::appendText)
        if (hasTcDelta) apply { progress.markToolCallInititaion() }
        if (progress.shouldSerialize()) {
            saveToFile(id, progress.toMarkdown())
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

val redaction = Redaction()

class Redaction {
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

        MainAgentTools::modifyFile.defineDescription {
            val schema = llmCommon.generateSchema<MainAgentTools.ModificationList>()
            """
            Modifies a file by applying a list of modifications.
            The modifications are provided as a JSON string that must conform to the schema below.

            ### PARAMETERS

            - ${"filePath" from this} -- absolute path to the file to create/modify

            - ${"modificationsJson" from this} -- JSON string containing the list of modifications.
              Must conform to the following JSON schema:

              ```
              $schema
              ```

            IMPORTANT:
            - Line numbers are 1-based
            - Modifications must NOT overlap (disjoint ranges/points)
            - Sort modifications by startLine in descending order (highest line numbers first)
            - Line numbers should refer to the ORIGINAL file state
            - Try to use this tool after you understand all needed changes to avoid running
              this tool twice against the same file
            """.trimIndent()
        }

        MainAgentTools::askForClarification.defineDescription {
            """
            Breaks the process and outputs a clarifying question to the user.
            Use this tool INSTEAD of guessing or generating multiple solution options
            when the task is ambiguous or requires user input.
            If the question implies a choice, format options as a numbered list in the text.

            ### Params

            - ${"question" from this} -- the clarifying question to ask the user
            """.trimIndent()
        }

        MainAgentTools::searchInternet.defineDescription {
            """
            Searches the public internet and returns a synthesized answer with source URLs.
            The query is delegated to a separate web-research agent that has live internet access.
            Use it whenever the answer is not in the local filesystem: library versions and
            changelogs, API signatures that changed after your knowledge cutoff, error messages,
            current documentation, release notes, anything time-sensitive.

            ### Params

            - ${"query" from this} -- a self-contained search query or a precise question.
              It is handed to the research agent verbatim, without any conversation history,
              so put all the needed context into it yourself (exact library names, versions,
              OS, the error text). Prefer one precise query over several vague ones.
            """.trimIndent()
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
        if (!file.exists()) return "ERROR: File doesn't exist"
        if (file.isDirectory) return "ERROR: It is a path, not a file"
        val fullText = file.readText()
        val truncated = fullText.length > maxSizeText
        val rawText = fullText.take(maxSizeText)
        val redactedText = redaction.redactText(rawText)
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
                val entries = d.listFiles()?.filter(::takeFileIf) ?: continue
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
            val entries = file.listFiles()?.filter(::takeFileIf)
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


    @Serializable
    enum class ModificationType { APPEND, DELETE, REPLACE, REPLACE_IN_LINE }

    @Serializable
    @LLMDescription(
        """
        A single modification instruction for a file.
        Exactly one of four operation types must be specified via the 'type' field.
        Fields that are not relevant for the chosen type must be null.

        - APPEND: Insert new content after a specific line.
          Required: startLine (the line AFTER which to insert), contents (text to insert).
          endLine, searchText, replacementText must be null.

        - DELETE: Remove a contiguous range of lines.
          Required: startLine (first line to delete), endLine (last line to delete, inclusive).
          contents, searchText, replacementText must be null.

        - REPLACE: Replace a contiguous range of lines with new content.
          Required: startLine (first line to replace), endLine (last line to replace, inclusive),
                    contents (replacement text).
          searchText, replacementText must be null.

        - REPLACE_IN_LINE: Find-and-replace a substring within a single line.
          Required: startLine (the line number), searchText (substring to find),
                    replacementText (substring to replace with).
          endLine, contents must be null.
        """
    )
    data class Modification(
        @property:LLMDescription("The type of modification to perform.")
        val type: ModificationType,

        @property:LLMDescription("Line number (1-based) where the operation starts or applies.")
        val startLine: Int?,

        @property:LLMDescription("Line number (1-based, inclusive) where the operation ends. Used only for DELETE and REPLACE.")
        val endLine: Int?,

        @property:LLMDescription("Text content to insert or use as replacement. Used for APPEND and REPLACE.")
        val contents: String?,

        @property:LLMDescription("Substring to search for within a single line. Used only for REPLACE_IN_LINE.")
        val searchText: String?,

        @property:LLMDescription("Replacement substring for find-and-replace. Used only for REPLACE_IN_LINE.")
        val replacementText: String?
    )

    @Serializable
    @LLMDescription("A wrapper for a list of file modifications")
    data class ModificationList(
        @property:LLMDescription("List of modifications sorted by startLine descending (highest line numbers first)")
        val modifications: List<Modification>
    )

    private val proposalSubAgent = ProposalSubAgent()

    @Tool
    fun modifyFile(filePath: String, modificationsJson: String): String {
        val canonicalPath = File(filePath).absolutePath
        val proposalFile = File("$filePath.proposal")
        if (proposalFile.exists() && canonicalPath !in readFiles) {
            return "ERROR: File '$filePath' has been modified. " +
                    "You must read the current state first " +
                    "before making further modifications."
        }
        readFiles.remove(canonicalPath)
        return proposalSubAgent.createProposal(filePath, modificationsJson)
    }

    @Tool
    fun askForClarification(question: String): String {
        return "CLARIFICATION_REQUESTED: $question"
    }

    private val searchSubAgent = SearchSubAgent()

    @Tool
    fun searchInternet(query: String): String {
        return try {
            searchSubAgent.search(query).trim()
                .ifEmpty { "ERROR: search agent returned an empty answer" }
        } catch (e: Exception) {
            "ERROR: internet search failed: ${e.message}"
        }
    }
}

class ProposalSubAgent {

    fun validateModifications(modifications: List<MainAgentTools.Modification>): String? {
        for ((index, mod) in modifications.withIndex()) {
            val label = "Modification #${index + 1} (${mod.type})"
            when (mod.type) {
                MainAgentTools.ModificationType.APPEND -> {
                    if (mod.startLine == null) return "$label: startLine is required"
                    if (mod.contents == null) return "$label: contents is required"
                    if (mod.endLine != null) return "$label: endLine must be null"
                    if (mod.searchText != null) return "$label: searchText must be null"
                    if (mod.replacementText != null) return "$label: replacementText must be null"
                }

                MainAgentTools.ModificationType.DELETE -> {
                    if (mod.startLine == null) return "$label: startLine is required"
                    if (mod.endLine == null) return "$label: endLine is required"
                    if (mod.contents != null) return "$label: contents must be null"
                    if (mod.searchText != null) return "$label: searchText must be null"
                    if (mod.replacementText != null) return "$label: replacementText must be null"
                }

                MainAgentTools.ModificationType.REPLACE -> {
                    if (mod.startLine == null) return "$label: startLine is required"
                    if (mod.endLine == null) return "$label: endLine is required"
                    if (mod.contents == null) return "$label: contents is required"
                    if (mod.searchText != null) return "$label: searchText must be null"
                    if (mod.replacementText != null) return "$label: replacementText must be null"
                }

                MainAgentTools.ModificationType.REPLACE_IN_LINE -> {
                    if (mod.startLine == null) return "$label: startLine is required"
                    if (mod.searchText == null) return "$label: searchText is required"
                    if (mod.replacementText == null) return "$label: replacementText is required"
                    if (mod.endLine != null) return "$label: endLine must be null"
                    if (mod.contents != null) return "$label: contents must be null"
                }
            }
        }
        return null
    }

    fun applyModifications(file: File, modifications: List<MainAgentTools.Modification>) {
        if (modifications.isEmpty()) return
        val lines = file.readLines().toMutableList()
        val sorted = modifications.sortedByDescending { it.startLine ?: 0 }

        for (mod in sorted) {
            when (mod.type) {
                MainAgentTools.ModificationType.APPEND -> {
                    val insertIndex = mod.startLine ?: continue
                    val safeIndex = insertIndex.coerceIn(0, lines.size)
                    val linesToInsert = mod.contents?.lines() ?: emptyList()
                    if (linesToInsert.isNotEmpty()) lines.addAll(safeIndex, linesToInsert)
                }

                MainAgentTools.ModificationType.DELETE -> {
                    val startLine = mod.startLine ?: continue
                    val endLine = mod.endLine ?: startLine
                    val si = startLine - 1;
                    val ei = endLine - 1
                    if (si >= 0 && ei < lines.size && si <= ei)
                        lines.subList(si, ei + 1).clear()
                }

                MainAgentTools.ModificationType.REPLACE -> {
                    val startLine = mod.startLine ?: continue
                    val endLine = mod.endLine ?: startLine
                    val si = startLine - 1;
                    val ei = endLine - 1
                    val newLines = mod.contents?.lines() ?: emptyList()
                    if (si >= 0 && ei < lines.size && si <= ei) {
                        lines.subList(si, ei + 1).clear()
                        lines.addAll(si, newLines)
                    }
                }

                MainAgentTools.ModificationType.REPLACE_IN_LINE -> {
                    val lineIndex = (mod.startLine ?: continue) - 1
                    if (lineIndex >= 0 && lineIndex < lines.size
                        && mod.searchText != null && mod.replacementText != null
                    ) {
                        lines[lineIndex] = lines[lineIndex].replace(mod.searchText, mod.replacementText)
                    }
                }
            }
        }
        file.writeText(lines.joinToString("\n"))
    }

    fun createProposal(originalFilePath: String, modificationsJson: String): String {
        val originalFile = File(originalFilePath)
        val proposalFile = File(
            originalFile.parentFile,
            "${originalFile.name}.proposal"
        )

        if (!proposalFile.exists()) {
            if (originalFile.exists()) originalFile.copyTo(proposalFile, overwrite = true)
            else proposalFile.writeText("")
        }

        val modificationList = llmCommon.correctJson<MainAgentTools.ModificationList>(modificationsJson)
            ?: return "ERROR: Couldn't parse modifications JSON even after LLM correction"

        val validationError = validateModifications(modificationList.modifications)
        if (validationError != null) return "ERROR: $validationError"

        applyModifications(proposalFile, modificationList.modifications)
        return "File ${originalFile.absolutePath} was successfully modified"
    }
}

class SearchSubAgent {
    private val systemPrompt =
        """
        You are a web research agent with live internet access.
        For EVERY request you must search the web and answer only from what you find,
        never from prior knowledge.

        Rules:
        - Answer directly and concretely: versions, dates, names, parameter lists,
          short code snippets.
        - State explicitly when your sources disagree or when a fact is unverified.
        - Do not repeat the question back, do not pad the answer.
        - End with a 'Sources:' section, one URL per line, for everything you used.
        - If the search results do not answer the question, reply exactly: NOT FOUND
        """.trimIndent()

    fun search(query: String): String {
        val answer = runBlocking {
            AIAgent(
                promptExecutor = llmCommon.executor(searchProviderTuning()),
                strategy = functionalStrategy<String, String> { input ->
                    requestLLMStreaming(input).toList().toMessageResponse().parts
                        .filterIsInstance<MessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                },
                agentConfig = AIAgentConfig(
                    prompt = Prompt.build(id = "web-search") { system(systemPrompt) },
                    model = searchModel(),
                    maxAgentIterations = 1
                )
            ).run(query)
        }
        info("searchInternet answer: ${answer.length} chars")
        return answer
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

        data class CallResult(
            val response: Message.Assistant,
            val toolCalls: List<MessagePart.Tool.Call>,
            val shouldGoOn: Boolean
        )

        fun checkRules(toolCalls: List<MessagePart.Tool.Call>): List<String> {
            val violations = mutableListOf<String>()
            return violations
        }

        suspend fun callAndLog(iteration: Int): CallResult {
            val response1 = llm().writeSession { requestLLMStreaming() }
            val response2 = response1.toList()
            val response = response2.toMessageResponse()
            llm().writeSession { appendPrompt { message(response) } }

            info(
                "Iteration: $iteration, Input: ${response.metaInfo.inputTokensCount}, " +
                        "Output: ${response.metaInfo.outputTokensCount}"
            )
            val toolCalls = response.parts.filterIsInstance<MessagePart.Tool.Call>()

            // Check rules
            val violations = checkRules(toolCalls)
            if (violations.isNotEmpty()) {
                llm().writeSession {
                    appendPrompt { user("RULE VIOLATION: ${violations.joinToString("; ")}") }
                }
                return CallResult(response, emptyList(), true)
            }

            return CallResult(response, toolCalls, toolCalls.isNotEmpty())
        }

        var iteration = 1
        var lastResponse: Message.Assistant? = null

        while (iteration < 30) {
            val result = callAndLog(iteration)
            lastResponse = result.response

            if (result.toolCalls.isNotEmpty()) {
                executeTools(result.toolCalls).let { results ->
                    val clarificationQuestion = results.firstNotNullOfOrNull { result ->
                        result.toMessagePart()
                            .output.removeSurrounding("\"")
                            .takeIf { it.startsWith("CLARIFICATION_REQUESTED:") }
                            ?.removePrefix("CLARIFICATION_REQUESTED:")?.trim()
                    }
                    if (clarificationQuestion != null) return@functionalStrategy clarificationQuestion
                    appendPrompt {
                        user { results.forEach { toolResult(it.toMessagePart()) } }
                    }
                }
                llmCommon.saveCurrentPrompt(
                    promptFilePath(),
                    iteration,
                    llm().readSession { prompt.messages }
                )
            }

            if (!result.shouldGoOn) break

            iteration++
        }

        val finalText = lastResponse?.parts
            ?.filterIsInstance<MessagePart.Text>()
            ?.joinToString("\n\n") { it.text } ?: ""
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
    installFeatures = {
        install(EventHandler) {
            onToolCallStarting { eventContext ->
                val args = eventContext.toolArgs.entries.toList()
                    .joinToString("\n") { "${it.first}\n${it.second}" }
                info("Tool: ${eventContext.toolName}\n$args")
            }
            onToolCallCompleted { eventContext ->
                // Here saving fact of tool calling
            }
        }
    }
)

if (mainCycle()) run {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    System.setProperty("org.slf4j.simpleLogger.log.ai.koog", "error")
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
}

// ========== Tuning agent =============

fun promptFilePath() = args[0]
fun providerTuning() = ProviderTuning(clientClass = DashscopeLLMClient::class) {
    it["enable_thinking"] = JsonPrimitive(true)
}

fun searchProviderTuning() = ProviderTuning(clientClass = DashscopeLLMClient::class) {
    it["enable_thinking"] = JsonPrimitive(true)
    it["enable_search"] = JsonPrimitive(true)
    it["search_options"] = JsonObject(
        mapOf(
            "search_strategy" to JsonPrimitive("agent"),
            "forced_search" to JsonPrimitive(true)
        )
    )
}
fun searchModel() = DashscopeModels.QWEN3_MAX.copy(id = "qwen3.7-plus")

fun currentModel() = DashscopeModels.QWEN3_MAX.copy(id = "qwen3.7-plus")
fun maxSizeText() = 40000
fun currentTools() = setOf<KFunction<String>>(
    MainAgentTools::readFile,
//    MainAgentTools::readDirectory,
    MainAgentTools::modifyFile,
    MainAgentTools::askForClarification,
    MainAgentTools::searchInternet
)

fun mainCycle() = true

fun takeFileIf(file: File): Boolean =
    !file.name.startsWith(".") && file.extension != "proposal"