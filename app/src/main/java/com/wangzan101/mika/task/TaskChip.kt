package com.wangzan101.mika.task

import android.os.SystemClock
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ToolLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val tools by lazy { knownTools() }
private val labels by lazy { ToolLabels(tools) }
private val screenReads = setOf(Verb.Observe, Verb.ListElements, Verb.Query, Verb.PhoneExtract, Verb.PhoneEvent,
    Verb.WaitFor, Verb.Screenshot, Verb.Describe, Verb.FindVisually, Verb.Diagnose)

internal data class TaskChip(val text: String, val kind: Kind = Kind.Status) {
    enum class Kind { Action, Thinking, Status }
}

/** 收起时只用固定文案和结构化计数。目标、旁白、工具参数、应用名留在卡片里。 */
internal fun taskChip(origin: RunOrigin, state: TaskState): TaskChip? {
    if (origin != RunOrigin.Ui) return null
    val label = when (state) {
        is TaskState.WaitingConfirmation -> R.string.chat_wait_approval
        is TaskState.WaitingInput -> R.string.chat_wait_answer
        is TaskState.WaitingTakeover -> R.string.chip_take_over
        is TaskState.Held -> when {
            state.reason.queued -> return null
            state.reason == TaskGate.LOCKED -> R.string.task_wait_unlock
            state.reason.needsYou -> R.string.screens_needs_you
            else -> R.string.chat_paused
        }
        TaskState.Idle, is TaskState.Terminal -> return null
        is TaskState.Running -> {
            when (val p = state.progress) {
                is RunProgress.Replay -> return TaskChip(tr(R.string.chip_route_step, p.step, p.total))
                is RunProgress.Task -> if (p.done != null) {
                    return TaskChip(if (p.total == null) tr(R.string.chip_done, p.done)
                        else tr(R.string.chip_count, p.done, p.total))
                }
                is RunProgress.WaitingForApp -> return TaskChip(tr(R.string.chip_wait_app))
                is RunProgress.Batch, null -> Unit // 批次长度不是当前步数。
            }
            if (state.tool == null) return if (state.step == 0) TaskChip(tr(R.string.runtime_preparing))
                else TaskChip(tr(R.string.chip_thinking), TaskChip.Kind.Thinking)
            val tool = tools[state.tool]
            val action = when (tool?.capability) {
                PHONE -> if (tool.verb in screenReads) R.string.chip_read_screen else R.string.chip_use_phone
                WEB -> R.string.chip_browse
                else -> if (tool?.verb == Verb.WebSearch) R.string.web_search_action else R.string.task_running
            }
            return TaskChip(tr(action), TaskChip.Kind.Action)
        }
    }
    return TaskChip(tr(label))
}

/** 没有旁白时展开卡片也能看懂当前动作；等模型时不沿用上一步的工具名。 */
internal fun chipDetail(state: TaskState.Running): String {
    val tool = state.tool
    return when {
        state.progress != null || state.step == 0 -> TaskService.describe(state).second
        tool == null -> tr(R.string.chip_thinking)
        else -> tr(R.string.task_step_note, state.step, state.narration?.takeIf { it.isNotBlank() }
            ?: state.reason?.takeIf { it.isNotBlank() } ?: tools[tool]?.let { labels.label(tool) } ?: tr(R.string.task_running))
    }
}

/** 每个任务各自保持短动作可读；到期重读任务现状，不重发捕获的旧通知。 */
internal class TaskChipDisplay(
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val refresh: () -> Unit,
) {
    private var shown: TaskChip? = null
    private var holdUntil = 0L
    private var wakeup: Job? = null

    @Synchronized fun show(next: TaskChip?): String? {
        val time = now()
        if (next != null && next != shown && next.kind != TaskChip.Kind.Status && time < holdUntil) {
            val remaining = holdUntil - time
            if (wakeup?.isActive != true) wakeup = scope.launch {
                delay(remaining)
                refresh()
            }
            return shown?.text
        }
        wakeup?.cancel()
        wakeup = null
        if (shown != next) {
            shown = next
            holdUntil = if (next?.kind == TaskChip.Kind.Action) time + MIN_ACTION_MS else 0L
        }
        return next?.text
    }

    companion object {
        internal const val MIN_ACTION_MS = 1200L
    }
}
