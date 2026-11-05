package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.record.RunStore
import java.io.File

data class PlaybackStep(
    val step: Int, val tool: String?, val beforeId: Int?, val afterId: Int?,
    val mark: PlaybackMark? = null, val succeeded: Boolean = true, val legacy: Boolean = false,
    val callId: String? = null,
) {
    /** 没有采集的帧不是丢失文件，也不生成空白播放项。 */
    val frames: List<PlaybackFrame> get() = if (legacy) listOfNotNull(beforeId?.let { PlaybackFrame(it, null) })
        else listOfNotNull(beforeId?.let { PlaybackFrame(it, false) }, afterId?.let { PlaybackFrame(it, true) })
}

data class PlaybackFrame(val screenshotId: Int, val after: Boolean?)
data class ReplayFrame(val stepIndex: Int, val frame: PlaybackFrame, val mark: PlaybackMark?)
data class ReportPage(val title: String, val stepIndex: Int, val screenshotId: Int)
data class PlaybackStage(val stepIndex: Int, val title: String)
data class TaskReport(
    val header: TimelineHeader, val answer: String, val playback: List<PlaybackStep>,
    val frames: List<ReplayFrame> = emptyList(), val pages: List<ReportPage> = emptyList(),
    val stages: List<PlaybackStage> = emptyList(), val missingFrames: Int = 0,
)

/** 只读运行记录，不持有工具分发器、设备端口或任务执行入口。 */
class TaskReportViewModel(private val store: RunStore, val runId: String) {
    fun load(): TaskReport? {
        val timeline = RunTimelineViewModel(store, runId).load() as? TimelineUi.Ready ?: return null
        val events = store.events(runId)
        val answer = store.summary(runId)?.answerOrReason
            ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason.orEmpty()
        val steps = playbackSteps(events)
        val all = steps.flatMapIndexed { index, step -> step.frames.map {
            ReplayFrame(index, it, step.mark.takeIf { _ -> it.after == false })
        } }
        val frames = all.filter { screen(it.frame.screenshotId)?.length()?.let { n -> n > 0 } == true }
        val pages = keyPages(events, answer, steps, frames)
        return TaskReport(timeline.header, answer, steps, frames, pages, stages(steps, frames, pages), all.size - frames.size)
    }

    fun screen(id: Int): File? = store.screen(runId, id)

    companion object {
        const val FRAME_DURATION_MS = 2_400L
        const val TRANSITION_MS = 360

        internal fun playbackSteps(events: List<RunEvent>): List<PlaybackStep> {
            val recorded = events.filterIsInstance<RunEvent.TaskPlayback>()
            if (recorded.isNotEmpty()) return recorded.map { e ->
                PlaybackStep(e.step, e.tool, e.beforeId, e.afterId,
                    e.mark?.takeIf { e.succeeded && it.valid() && e.beforeId != null }, e.succeeded, callId = e.callId)
            }
            // 旧记录没有实际动作坐标，不从模型计划推测。
            return events.filterIsInstance<RunEvent.Media>().distinctBy { it.mediaId }
                .map { PlaybackStep(it.step, null, it.mediaId, null, legacy = true) }
        }

        /** 只选正文引用过的页面文字，并通过观测编号→实际调用关联截图，不能按轮次猜批次子动作。 */
        internal fun keyPages(events: List<RunEvent>, answer: String, steps: List<PlaybackStep>, frames: List<ReplayFrame>): List<ReportPage> {
            val normalizedAnswer = normalize(answer)
            val activities = events.filterIsInstance<RunEvent.Activity>()
            val pages = events.filterIsInstance<RunEvent.Observed>().mapNotNull { obs ->
                val activity = activities.firstOrNull { obs.id > 0 && obs.id in it.obsIds && it.callId != null } ?: return@mapNotNull null
                val index = steps.indexOfFirst { it.callId == activity.callId }
                if (index < 0) return@mapNotNull null
                val frame = frames.lastOrNull { it.stepIndex == index && it.frame.after == true } ?: return@mapNotNull null
                val title = (listOfNotNull(obs.title) + obs.text.lineSequence().toList()).map(String::trim)
                    .filter { it.length in 8..90 && '\t' !in it && !it.startsWith("当前 App:") }
                    .filter { normalize(it).length >= 8 && normalize(it) in normalizedAnswer }
                    .maxByOrNull { normalize(it).length } ?: return@mapNotNull null
                ReportPage(title, index, frame.frame.screenshotId)
            }.distinctBy { normalize(it.title) }.takeLast(4)
            if (pages.isNotEmpty()) return pages
            // 没有可确认的引用页面，只承诺这是记录结束时的画面。
            return frames.lastOrNull()?.let { listOf(ReportPage(tr(R.string.report_final_page), it.stepIndex, it.frame.screenshotId)) }.orEmpty()
        }

        internal fun stages(steps: List<PlaybackStep>, frames: List<ReplayFrame>, pages: List<ReportPage>): List<PlaybackStage> {
            val available = frames.map { it.stepIndex }.distinct()
            if (available.isEmpty()) return emptyList()
            val result = mutableListOf(PlaybackStage(available.first(), tr(R.string.playback_stage_start)))
            available.firstOrNull { steps[it].tool in setOf("phone_type_text", "phone_clear_text", "web_type") }
                ?.let { result += PlaybackStage(it, tr(R.string.playback_stage_input)) }
            result += pages.map { PlaybackStage(it.stepIndex, it.title) }
            return result.distinctBy { it.stepIndex }.sortedBy { it.stepIndex }
        }

        private fun normalize(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

        internal fun excerpt(answer: String): String {
            val text = answer.trim()
            return if (text.length <= 240) text else text.take(240).trimEnd() + "…"
        }
    }
}
