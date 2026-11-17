package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskChipLabelTest {
    @get:Rule val texts = ChineseTexts()
    private val running = TaskState.Running(3, "private note", reason = "private reason")
    private fun label(state: TaskState) = taskChip(RunOrigin.Ui, state)?.text

    @Test fun onlyActiveUserStartedTasksRequestAChip() {
        for (origin in RunOrigin.entries - RunOrigin.Ui) assertNull(taskChip(origin, running))
        for (state in listOf(TaskState.Idle, TaskState.Held(GateReason.queued("next")), TaskState.Cancelled, TaskState.Succeeded("done"))) {
            assertNull(label(state))
        }
    }

    @Test fun actionsUseToolSemanticsNotNamesOrPrivateNarration() {
        assertEquals("正在思考", label(running))
        for ((tool, text) in mapOf("phone_observe" to "查看屏幕", "phone_query" to "查看屏幕",
            "phone_tap" to "操作手机", "phone_launch_app" to "操作手机", "phone_type_text" to "操作手机",
            "web_tap" to "浏览网页", "web_navigate" to "浏览网页", "web_search" to "搜索网页", "web_private_plugin" to "正在执行")) {
            assertEquals(tool, text, label(running.copy(tool = tool, narration = "private")))
        }
        assertEquals("第 3 步:private reason", chipDetail(running.copy(tool = "phone_launch_app")))
        assertEquals("第 3 步:打开 App", chipDetail(running.copy(tool = "phone_launch_app", reason = null)))
        assertEquals("正在思考", chipDetail(running))
    }

    @Test fun countsRequireReportedProgressNotAnAgentTurnOrBatchSize() {
        fun text(p: RunProgress) = label(running.copy(tool = "web_navigate", progress = p))
        assertEquals("第2/5步", text(RunProgress.Replay(2, 5)))
        assertEquals("进度3/9", text(RunProgress.Task("private metric", 3, 9, "private")))
        assertEquals("已完成 3", text(RunProgress.Task("private metric", 3, null, null)))
        assertEquals("浏览网页", text(RunProgress.Task("private metric", null, 9, "private")))
        assertEquals("等待应用", text(RunProgress.WaitingForApp("private app")))
        assertEquals("正在执行", label(running.copy(tool = "do_steps", progress = RunProgress.Batch(8))))
    }

    @Test fun personalDetailsNeverBecomeWaitingLabels() {
        val request = ConfirmationRequest(IrrevAction.order, "private recipient", "private content", null, "app")
        assertEquals("等你同意", label(TaskState.WaitingConfirmation(request)))
        assertEquals("等你回答", label(TaskState.WaitingInput("private question")))
        assertEquals("等你接手", label(TaskState.WaitingTakeover("payment", "private", amount = "999")))
        assertEquals("等待解锁", label(TaskState.Held(TaskGate.LOCKED)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun shortActionsStayReadableButWaitingAndProgressInterruptImmediately() = runTest {
        var state: TaskState = running
        var refreshed: String? = null
        lateinit var display: TaskChipDisplay
        display = TaskChipDisplay(this, { testScheduler.currentTime }) {
            refreshed = display.show(taskChip(RunOrigin.Ui, state))
        }
        fun show(next: TaskState): String? {
            state = next
            return display.show(taskChip(RunOrigin.Ui, next))
        }
        assertEquals("查看屏幕", show(running.copy(tool = "phone_observe")))
        assertEquals("查看屏幕", show(running))
        assertEquals("查看屏幕", show(running.copy(tool = "web_navigate")))
        advanceTimeBy(TaskChipDisplay.MIN_ACTION_MS)
        runCurrent()
        assertEquals("浏览网页", refreshed)
        assertEquals("浏览网页", show(running))
        assertEquals("等你接手", show(TaskState.WaitingTakeover("login", "private")))
        assertEquals("操作手机", show(running.copy(tool = "phone_tap")))
        assertEquals("第1/4步", show(running.copy(progress = RunProgress.Replay(1, 4))))
        assertNull(show(TaskState.Cancelled))
        advanceTimeBy(TaskChipDisplay.MIN_ACTION_MS * 2)
        assertEquals("浏览网页", refreshed) // 已取消的刷新没有重发旧状态。
    }
}
