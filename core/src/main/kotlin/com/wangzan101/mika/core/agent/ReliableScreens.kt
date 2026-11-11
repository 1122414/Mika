package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Historical screen tables keep every cell and its field name. Raw history remains the source of truth. */
internal class ReliableScreens {
    private var original = emptyList<Message>()
    private var packed = emptyList<Message>()

    var changed = false
        private set
    fun consumeChange() = changed.also { changed = false }

    fun clear() { changed = original.isNotEmpty(); original = emptyList(); packed = emptyList() }

    fun project(history: List<Message>): List<Message> {
        if (original.size > history.size || original != history.subList(0, original.size)) clear()
        val starts = history.indices.filter { i -> history[i].role == Role.Assistant && history[i].parts.any { it is Part.ToolCall } }
        val rounds = ((starts.size - RECENT).coerceAtLeast(0) / BLOCK) * BLOCK
        val cut = if (rounds == 0) 0 else starts[rounds]
        if (cut > original.size) {
            val add = history.subList(original.size, cut)
            val batch = add.map(::packMessage)
            if (batch != add) changed = true
            packed = packed + batch
            original = history.take(cut)
        }
        return if (packed.isEmpty()) history else packed + history.drop(original.size)
    }

    private fun packMessage(m: Message): Message {
        if (m.role != Role.Tool) return m
        val parts = m.parts.map { p ->
            if (p !is Part.ToolResult || p.isError) p else pack(p.content)?.let { p.copy(content = it) } ?: p
        }
        return if (parts == m.parts) m else m.copy(parts = parts)
    }

    companion object {
        private const val RECENT = 3
        private const val BLOCK = 6
        private const val COLUMNS = "columns"
        private const val ROWS = "rows"

        private fun pack(raw: String): String? {
            if (raw.length !in 180..2_000_000 || "\"elements\"" !in raw) return null
            val obj = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: SerializationException) { null } ?: return null
            val nested = obj["screen"] as? JsonObject
            val screen = nested ?: obj
            val elements = screen["elements"] as? JsonArray ?: return null
            if (elements.size < 3) return null
            // A missing field differs from JSON null; only combine consecutive rows with exactly the same keys.
            val blocks = mutableListOf<JsonElement>()
            var i = 0
            while (i < elements.size) {
                val row = elements[i] as? JsonObject ?: return null
                val cols = row.keys.toList()
                var j = i + 1
                while (j < elements.size && (elements[j] as? JsonObject)?.keys == row.keys) j++
                blocks += buildJsonObject {
                    put(COLUMNS, JsonArray(cols.map(::JsonPrimitive)))
                    put(ROWS, JsonArray((i until j).map { n -> JsonArray(cols.map { elements[n].jsonObject.getValue(it) }) }))
                }
                i = j
            }
            if ("elementTables" in screen) return null
            val view = JsonObject(screen - "elements" + ("elementTables" to JsonArray(blocks)))
            val out = (if (nested == null) view else JsonObject(obj + ("screen" to view))).toString()
            return out.takeIf { it.length <= raw.length * 0.94 && TokenEstimate.of(it) < TokenEstimate.of(raw) }
        }

        /** The table is model-readable without a codec; this inverse also verifies new formats in replay tests. */
        fun elements(screen: JsonObject): JsonArray? {
            (screen["elements"] as? JsonArray)?.let { return it }
            val tables = screen["elementTables"] as? JsonArray ?: return null
            val out = mutableListOf<JsonElement>()
            for (t in tables) {
                val block = t as? JsonObject ?: return null
                val cols = (block[COLUMNS] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { k -> k.isString }?.content ?: return null } ?: return null
                if (cols.size != cols.distinct().size) return null
                for (r in block[ROWS] as? JsonArray ?: return null) {
                    val row = r as? JsonArray ?: return null
                    if (row.size != cols.size) return null
                    out += JsonObject(cols.zip(row).toMap())
                }
            }
            return JsonArray(out)
        }
    }
}
