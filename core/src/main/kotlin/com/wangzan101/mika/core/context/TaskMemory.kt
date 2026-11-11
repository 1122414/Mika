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
                          val cursor: Int = 0, val blocks: List<SummaryBlock> = emptyList())

/** CAS must durably replace the whole checkpoint or leave the old one intact. */
interface CheckpointStore {
    fun load(): TaskCheckpoint
    fun save(expected: Int, next: TaskCheckpoint): Boolean
    object Unavailable : CheckpointStore {
        override fun load() = TaskCheckpoint()
        override fun save(expected: Int, next: TaskCheckpoint) = false
    }
}

internal class TaskMemory(private val evidence: (Int) -> Observation?, private val store: CheckpointStore? = null) {
    var state = store?.load() ?: TaskCheckpoint()
        private set
    private val missing = linkedSetOf<String>()
    fun missing(selector: String) { if (missing.size < 32) missing.add(selector) }
    fun resolved(selector: String) { missing.remove(selector) }
    val blocked: Boolean get() = missing.isNotEmpty() ||
        active().size < state.facts.associateBy { it.key }.values.count { it.epoch == state.epoch }

    private fun commit(next: TaskCheckpoint): Boolean {
        if (state.revision == Int.MAX_VALUE) return false
        val updated = next.copy(revision = state.revision + 1)
        if (store != null && !store.save(state.revision, updated)) return false
        state = updated
        return true
    }

    /** Advance even if there are no facts: an old proposal must never apply to a new instruction. */
    fun steer() = commit(state.copy(epoch = state.epoch + 1, blocks = emptyList())).also { if (it) missing.clear() }

    fun record(key: String, expected: Int, obsId: Int, quote: String): Boolean {
        if (!KEY.matches(key) || quote.length !in 2..240 || quote.isBlank() || Redactor.evidence(quote) != quote || "***" in quote) return false
        val o = evidence(obsId) ?: return false
        if (o.id != obsId || !o.text.contains(quote)) return false
        val prior = state.facts.lastOrNull { it.key == key }
        if ((prior?.version ?: 0) != expected) return false
        val digest = evidenceDigest(o.text)
        if (prior?.obsId == obsId && prior.digest == digest && prior.quote == quote && prior.epoch == state.epoch) return true
        if (state.facts.size >= 64 || (prior == null && state.facts.distinctBy { it.key }.size >= 16)) return false
        val fact = FactRevision(key, expected + 1, obsId, o.step, o.source.orEmpty().take(40), quote, state.epoch, digest)
        return commit(state.copy(facts = state.facts + fact))
    }

    fun active(): List<FactRevision> = state.facts.associateBy { it.key }.values.filter { f ->
        f.epoch == state.epoch && evidence(f.obsId)?.let { it.id == f.obsId && f.quote in it.text && evidenceDigest(it.text) == f.digest } == true
    }

    fun projection(): String? = active().takeIf { it.isNotEmpty() }?.joinToString("\n") { f ->
        "${f.key}@${f.version} obs=${f.obsId} step=${f.step}: ${Untrusted.wrap(f.quote)}"
    }

    /** A dispatch acknowledgement is not proof of a booking. Until the call returns its outcome is unknown. */
    fun summary(expected: Int, cursor: Int, blocks: List<SummaryBlock>): Boolean =
        state.revision == expected && cursor > state.cursor && blocks.size <= 32 &&
            commit(state.copy(cursor = cursor, blocks = blocks))

    fun beginEffect(kind: String) = state.pendingEffect == null && commit(state.copy(pendingEffect = kind))
    fun returned(kind: String) = state.pendingEffect == kind && commit(state.copy(pendingEffect = null, lastEffect = kind))

    companion object {
        internal val KEY = Regex("[A-Za-z][A-Za-z0-9_.-]{0,39}")
    }
}

fun evidenceDigest(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it) }
