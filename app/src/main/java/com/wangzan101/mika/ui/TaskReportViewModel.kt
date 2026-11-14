package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore

data class TaskReport(val header: TimelineHeader, val answer: String)

/** 只读运行记录，不持有工具分发器、设备端口或任务执行入口。 */
class TaskReportViewModel(private val store: RunStore, val runId: String) {
    fun load(): TaskReport? {
        val timeline = RunTimelineViewModel(store, runId).load() as? TimelineUi.Ready ?: return null
        val events = store.events(runId)
        val answer = store.summary(runId)?.answerOrReason
            ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason.orEmpty()
        return TaskReport(timeline.header, answer)
    }

    companion object {
        internal fun excerpt(answer: String): String {
            val text = answer.trim()
            return if (text.length <= 240) text else text.take(240).trimEnd() + "…"
        }
    }
}
