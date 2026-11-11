package com.wangzan101.mika.core.context

import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A key is a model-proposed label, never evidence for the meaning of its quote. */
@Serializable
data class FactRevision(val key: String, val version: Int, val obsId: Int, val step: Int, val source: String,
                        val quote: String, val epoch: Int, val digest: String)

@Serializable
data class TaskCheckpoint(val revision: Int = 0, val epoch: Int = 0, val facts: List<FactRevision> = emptyList(),
                          val pendingEffect: String? = null, val lastEffect: String? = null,
                          val cursor: Int = 0, val blocks: List<SummaryBlock> = emptyList(),
                          val steers: List<String> = emptyList(), val evidenceFloor: Int = 0)

/** CAS must durably replace the whole checkpoint or leave the old one intact. */
interface CheckpointStore {
    fun load(): TaskCheckpoint
    fun save(expected: Int, next: TaskCheckpoint): Boolean
    object Unavailable : CheckpointStore {
        override fun load() = TaskCheckpoint()
        override fun save(expected: Int, next: TaskCheckpoint) = false
    }
}

internal class TaskMemory(private val evidence: (Int) -> Observation?, private var store: CheckpointStore? = null) {
    var state = store?.load() ?: TaskCheckpoint()
        private set
    // Independent, bounded source copies for active facts survive ordinary Ledger eviction.
    private val pinnedSources = mutableMapOf<Int, Observation>()
    fun resetForNewRun() {
        // Reusing a dispatcher must not grant access to the previous run's file.
        if (store != null) store = CheckpointStore.Unavailable
        state = TaskCheckpoint()
        missing.clear(); pinnedSources.clear()
    }
    fun source(id: Int): Observation? = evidence(id) ?: pinnedSources[id]
    private val missing = linkedSetOf<String>()
    fun missing(selector: String) { if (missing.size < 32) missing.add(selector) }
    fun resolved(selector: String) { missing.remove(selector) }
    val blocked: Boolean get() = missing.isNotEmpty() ||
        active().size < state.facts.associateBy { it.key }.values.count { it.epoch == state.epoch }

    private fun commit(next: TaskCheckpoint): Boolean {
        if (state.revision == Int.MAX_VALUE) return false
        val updated = next.copy(revision = state.revision + 1)
        if (store?.save(state.revision, updated) == false) return false
        state = updated
        return true
    }

    /** Advance even if there are no facts: an old proposal must never apply to a new instruction. */
    fun steer(words: String = "", lastObservation: Int = 0): Boolean {
        val updated = commit(state.copy(epoch = state.epoch + 1, blocks = emptyList(),
            evidenceFloor = maxOf(state.evidenceFloor, lastObservation),
            steers = if (words.isBlank()) state.steers else (state.steers + Redactor.evidence(words).take(800)).takeLast(12)))
        if (updated) { missing.clear(); pinnedSources.clear() }
        return updated
    }

    fun record(key: String, expected: Int, obsId: Int, quote: String): Boolean {
        if (!KEY.matches(key) || Redactor.evidence(key) != key || quote.length !in 2..240 || quote.isBlank() || Redactor.evidence(quote) != quote || "***" in quote) return false
        val o = source(obsId)?.takeIf { it.id > state.evidenceFloor } ?: return false
        if (o.id != obsId || !o.text.contains(quote)) return false
        val prior = state.facts.lastOrNull { it.key == key }
        if ((prior?.version ?: 0) != expected) return false
        if (prior != null && prior.epoch == state.epoch && prior.obsId > obsId) return false
        val digest = evidenceDigest(o.text)
        if (prior?.obsId == obsId && prior.digest == digest && prior.quote == quote && prior.epoch == state.epoch) return true
        if (state.facts.size >= 64 || (prior == null && state.facts.distinctBy { it.key }.size >= 16)) return false
        val fact = FactRevision(key, expected + 1, obsId, o.step, o.source.orEmpty().take(40), quote, state.epoch, digest)
        if (!commit(state.copy(facts = state.facts + fact))) return false
        pinnedSources[obsId] = o
        val activeIds = state.facts.associateBy { it.key }.values.filter { it.epoch == state.epoch }.map { it.obsId }.toSet()
        pinnedSources.keys.retainAll(activeIds)
        return true
    }

    fun active(): List<FactRevision> = state.facts.associateBy { it.key }.values.filter { f ->
        f.epoch == state.epoch && source(f.obsId)?.let { it.id == f.obsId && f.quote in it.text && evidenceDigest(it.text) == f.digest } == true
    }

    fun projection(): String? = active().takeIf { it.isNotEmpty() }?.joinToString("\n") { f ->
        "${f.key}@${f.version} obs=${f.obsId} step=${f.step}: ${Untrusted.wrap(f.quote)}"
    }

    fun summary(expected: Int, cursor: Int, blocks: List<SummaryBlock>): Boolean =
        state.revision == expected && cursor > state.cursor && blocks.size <= 32 &&
            commit(state.copy(cursor = cursor, blocks = blocks))

    /** A dispatch acknowledgement is not proof of a booking. Until the call returns its outcome is unknown. */
    fun beginEffect(kind: String) = state.pendingEffect == null && commit(state.copy(pendingEffect = kind))
    fun returned(kind: String) = state.pendingEffect == kind && commit(state.copy(pendingEffect = null, lastEffect = kind))

    companion object {
        internal val KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,39}")
    }
}

fun evidenceDigest(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it) }
