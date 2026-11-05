package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import java.io.File

data class PlaybackStep(
    val step: Int, val tool: String?, val beforeId: Int?, val afterId: Int?,
    val mark: PlaybackMark? = null, val succeeded: Boolean = true, val legacy: Boolean = false,
) {
    /** 旧记录只有一张观察画面，不将它同时充作动作前后。 */
    val frames: List<PlaybackFrame> get() = if (legacy) listOf(PlaybackFrame(beforeId, null))
        else listOf(PlaybackFrame(beforeId, false), PlaybackFrame(afterId, true))
}

data class PlaybackFrame(val screenshotId: Int?, val after: Boolean?)
data class TaskReport(val header: TimelineHeader, val answer: String, val playback: List<PlaybackStep>)

/** 只读运行记录，不持有工具分发器、设备端口或任务执行入口。 */
class TaskReportViewModel(private val store: RunStore, val runId: String) {
    fun load(): TaskReport? {
        val timeline = RunTimelineViewModel(store, runId).load() as? TimelineUi.Ready ?: return null
        val events = store.events(runId)
        val answer = store.summary(runId)?.answerOrReason
            ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason.orEmpty()
        return TaskReport(timeline.header, answer, playbackSteps(events))
    }

    fun screen(id: Int): File? = store.screen(runId, id)

    companion object {
        internal fun playbackSteps(events: List<RunEvent>): List<PlaybackStep> {
            val recorded = events.filterIsInstance<RunEvent.TaskPlayback>()
            if (recorded.isNotEmpty()) return recorded.map { e ->
                PlaybackStep(e.step, e.tool, e.beforeId, e.afterId,
                    e.mark?.takeIf { e.succeeded && it.valid() && e.beforeId != null }, e.succeeded)
            }
            // 不用模型计划的坐标反推旧画面；一轮可能有多个子动作，只有截图编号是可信的。
            return events.filterIsInstance<RunEvent.Media>().distinctBy { it.mediaId }
                .map { PlaybackStep(it.step, null, it.mediaId, null, legacy = true) }
        }

        internal fun excerpt(answer: String): String {
            val text = answer.trim()
            if (text.length <= 240) return text
            return text.take(240).trimEnd() + "…"
        }
    }
}
