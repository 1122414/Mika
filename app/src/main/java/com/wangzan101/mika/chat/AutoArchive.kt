package com.wangzan101.mika.chat

/**
 * 自动归档:太久没有新消息的对话收进归档(和手动归档一样,随时能在归档视图里恢复)。
 * 默认关闭,天数在 设置 › 我的数据 › 自动归档对话 里选。
 */
object AutoArchive {
    /** 可选的「多久没新消息就归档」天数;0 表示关闭。 */
    val DAYS = listOf(0, 7, 30, 90, 180)

    /** 多久没新消息算「太久」:从 [days] 天前这一刻算起。 */
    fun cutoff(days: Int, now: Long): Long = now - days * DAY_MS

    /**
     * 从 [all] 里挑出这次该归档的:还没归档、没置顶、最后改动早于 [cutoff] 的。
     * [keep] 里的不动(在跑的任务、用户此刻在看的对话)。
     */
    fun candidates(all: List<ConversationSummary>, cutoff: Long, keep: Set<String> = emptySet()): List<String> =
        all.asSequence()
            .filter { it.archivedAt == null && it.pinnedAt == null }
            .filter { it.updatedAt < cutoff }
            .filter { it.id !in keep }
            .map { it.id }
            .toList()

    /**
     * 按设置跑一次,返回归档了几个。对话文件的时间就是最后一次存进新一轮的时间;
     * 之后再有新的一轮写进来,和手动归档一样会自动取消归档(见 [ConversationStore.append])。
     */
    fun run(
        store: ConversationStore,
        days: Int,
        running: Collection<String> = emptyList(),
        currentId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (days <= 0) return 0
        val keep = running.toSet() + listOfNotNull(currentId)
        return candidates(store.list(includeArchived = true), cutoff(days, now), keep)
            .count { store.setArchived(it, archived = true, at = now) }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
