package ru.fiddlededee.mycopilot.models

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.schema.defaultJsonSchemaConfig
import ai.koog.agents.core.tools.schema.getJsonSchema
import ai.koog.serialization.typeToken
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.json.JsonSchema
import kotlinx.serialization.json.Json

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