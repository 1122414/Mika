package com.wangzan101.mika.core.cap.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 服务端工具描述的统一解析;HTTP/SSE 两种传输共用同一套风险注解读取。 */
internal fun mcpToolDescriptor(o: JsonObject): McpToolDescriptor {
    val annotations = o["annotations"] as? JsonObject
    return McpToolDescriptor(
        name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
        annotations = McpAnnotations(
            readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            destructiveHint = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull,
            openWorldHint = (annotations?.get("openWorldHint") as? JsonPrimitive)?.booleanOrNull,
        ),
        estimatedCostUnits = (o["estimatedCostUnits"] as? JsonPrimitive)?.longOrNull ?: 0,
    )
}

/** JSON-RPC error 的文字;只取服务端 message,不含正文。 */
internal fun mcpErrorMessage(error: kotlinx.serialization.json.JsonElement?, fallback: String = "JSON-RPC error"): String =
    (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: fallback
