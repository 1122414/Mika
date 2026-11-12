package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.TokenEstimate
import kotlinx.serialization.json.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Main-loop inference only. No prompt, image, or digest is written to the run record. */
internal class PrefixCall(val sending: (InferenceRequest) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrefixCall>
}

internal class RequestPrefix {
    private enum class Zone { TOOLS, SYSTEM, HISTORY }
    private data class Signature(val zone: Zone, val digest: ByteString)
    data class Break(val reason: String, val suffixTokens: Int)
    private var previous = emptyList<Signature>()

    /** Compare the adapter's actual prompt blocks, in cache order; ignore transport options and headers. */
    fun observe(request: InferenceRequest, compacted: Boolean): Break? {
        val (protocol, body) = request
        val blocks = buildList {
            (body["tools"] as? JsonArray)?.forEach { add(Zone.TOOLS to it) }
            protocol.instruction?.let { key -> body[key]?.let { add(Zone.SYSTEM to it) } }
            (body[protocol.messages] as? JsonArray)?.forEach {
                val role = ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.content
                add((if (protocol == InferenceProtocol.Chat && role in setOf("system", "developer")) Zone.SYSTEM else Zone.HISTORY) to it)
            }
        }
        val signatures = blocks.map { (zone, v) -> Signature(zone, v.toString().encodeUtf8().sha256()) }
        val last = previous
        previous = signatures
        val first = (0 until minOf(last.size, signatures.size)).firstOrNull { last[it] != signatures[it] }
            ?: minOf(last.size, signatures.size)
        if (first == last.size) return null
        val zone = minOf(last[first].zone, signatures.getOrNull(first)?.zone ?: last[first].zone)
        val reason = when (zone) {
            Zone.TOOLS -> "tools"
            Zone.SYSTEM -> "system_prompt"
            Zone.HISTORY -> if (compacted) "compaction" else "history"
        }
        return Break(reason, blocks.subList(first, blocks.size).sumOf { estimate(it.second) })
    }

    // Inline base64 is not text billed by the tokenizer. Use the project's unknown-size image allowance.
    private fun estimate(value: JsonElement): Int = when (value) {
        is JsonObject -> if (value["type"]?.toString() in setOf("\"image_url\"", "\"input_image\"", "\"image\"") ||
            "inlineData" in value || "inline_data" in value) TokenEstimate.image(0, 0)
            else 1 + value.entries.sumOf { (k, v) -> TokenEstimate.of(k) + 1 + estimate(v) }
        is JsonArray -> 1 + value.sumOf { estimate(it) }
        is JsonPrimitive -> TokenEstimate.of(value.toString())
    }
}
