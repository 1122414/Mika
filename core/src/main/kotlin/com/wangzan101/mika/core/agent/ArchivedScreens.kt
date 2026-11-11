package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.context.ScreenArchive
import com.wangzan101.mika.core.context.TaskMemory
import com.wangzan101.mika.core.model.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.util.IdentityHashMap

/** Only old, self-contained screens. Keep every live diff base, each recent round, and every tool pair. */
internal class ArchivedScreens(private val archive: ScreenArchive) {
    private data class Screen(val outer: JsonObject, val body: JsonObject, val nested: Boolean)
    private val parsed = IdentityHashMap<Part.ToolResult, Screen?>()
    private val keys = IdentityHashMap<Part.ToolResult, String?>()

    fun clear() { parsed.clear(); keys.clear(); archive.clear() }

    private fun screen(p: Part.ToolResult): Screen? {
        if (p !in parsed) {
            val o = if (!p.isError && ("\"screen\"" in p.content || "\"snapshotId\"" in p.content))
                try { Json.parseToJsonElement(p.content) as? JsonObject } catch (_: SerializationException) { null } else null
            val s = o?.get("screen") as? JsonObject
            parsed[p] = o?.let { Screen(it, s ?: it, s != null) }
        }
        return parsed[p]
    }
    fun useful(history: List<Message>) = history.asSequence().flatMap { it.parts.asSequence() }.filterIsInstance<Part.ToolResult>()
        .any { it.content.length >= 4096 && it.reads.isNotEmpty() && screen(it)?.body?.get("elements") is JsonArray }

    fun project(history: List<Message>, memory: TaskMemory): List<Message> {
        val starts = history.indices.filter { i -> history[i].role == Role.Assistant && history[i].parts.any { it is Part.ToolCall } }
        if (starts.size < 10) return history
        val recentCost = TokenEstimate.of(history.subList(starts[starts.size - 5], history.size))
        val recent = when { recentCost > 20_000 -> 3; recentCost > 12_000 -> 4; else -> 5 }
        val count = (starts.size - recent).coerceAtLeast(0) / 6 * 6
        if (count == 0) return history
        val cut = starts[count]
        val bases = history.asSequence().flatMap { it.parts.asSequence() }.filterIsInstance<Part.ToolResult>()
            .mapNotNull { (screen(it)?.body?.get("base") as? JsonPrimitive)?.content }.toSet()
        val pinned = memory.state.blocks.flatMap { it.keep }.toSet() + memory.active().map { it.obsId }
        val covered = memory.state.blocks.flatMap { it.covered }.toSet()
        return history.mapIndexed { i, m ->
            if (i >= cut || m.role != Role.Tool) return@mapIndexed m
            val parts = m.parts.map { p ->
                if (p !is Part.ToolResult || p.content.length < 2048) return@map p
                val s = screen(p) ?: return@map p
                val id = (s.body["snapshotId"] as? JsonPrimitive)?.content ?: return@map p
                if (id in bases || "base" in s.body || s.body["elements"] !is JsonArray || "archiveKey" in s.body) return@map p
                val refs = p.reads.map { it.obsId }
                val select = refs.isNotEmpty() && refs.all { obs -> obs !in pinned && obs in covered }
                val tree = s.body["tree"]
                if (!select && (tree == null || tree.toString().length < 1600)) return@map p
                if (p !in keys) keys[p] = archive.put(s.body.toString())
                val key = keys[p]?.takeIf(archive::contains) ?: return@map p
                val data = s.body - "tree" - if (select) setOf("elements","full") else emptySet()
                val compact = JsonObject(data + ("archiveKey" to JsonPrimitive(key)) +
                    (if (select) mapOf("historical" to JsonPrimitive("details: history_read(snapshot,offset)")) else emptyMap()))
                val content = (if (s.nested) JsonObject(s.outer + ("screen" to compact)) else compact).toString()
                if (content.length >= p.content.length) p else p.copy(content = content)
            }
            if (parts == m.parts) m else m.copy(parts = parts)
        }
    }
}
