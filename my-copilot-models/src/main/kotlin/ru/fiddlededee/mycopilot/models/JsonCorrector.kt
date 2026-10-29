package ru.fiddlededee.mycopilot.models

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.executor.model.PromptExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Corrects JSON string and deserializes it to the specified type.
 * Falls back to LLM-based correction if direct deserialization fails.
 *
 * @param jsonString The JSON string to parse
 * @param executor The prompt executor for LLM correction
 * @param model The LLM model to use for correction
 * @param logger Optional logger function
 * @return Deserialized object or null if correction failed
 */
inline fun <reified T> correctJson(
    jsonString: String,
    executor: PromptExecutor,
    model: LLModel,
    logger: (String) -> Unit = {}
): T? {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    try {
        return json.decodeFromString<T>(jsonString)
    } catch (_: Exception) {
        logger("correctJson: direct deserialization failed")
    }

    val cleaned = jsonString.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    try {
        return json.decodeFromString<T>(cleaned)
    } catch (e: Exception) {
        logger("correctJson: after markdown cleanup failed: ${e.message}")
    }

    logger("correctJson: falling back to LLM correction")
    return runBlocking {
        val agent = AIAgent(
            promptExecutor = executor,
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
                model = model,
                maxAgentIterations = 3
            )
        )
        agent.run(jsonString)
    }
}