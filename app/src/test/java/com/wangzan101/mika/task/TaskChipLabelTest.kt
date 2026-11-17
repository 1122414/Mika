package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.RunOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskChipLabelTest {
    @Test fun onlyActiveUserStartedTasksRequestAChip() {
        val running = TaskState.Running(1, "private task details")
        for (origin in RunOrigin.entries - RunOrigin.Ui) assertNull(TaskService.taskChipLabel(origin, running))
        for (state in listOf(TaskState.Idle, TaskState.Held(GateReason.queued("next")), TaskState.Cancelled, TaskState.Succeeded("done"))) {
            assertNull(TaskService.taskChipLabel(RunOrigin.Ui, state))
        }
        assertEquals(R.string.task_running, TaskService.taskChipLabel(RunOrigin.Ui, running))
    }

    @Test fun waitingChipUsesOnlyTheKindOfInteraction() {
        val request = ConfirmationRequest(IrrevAction.order, "private recipient", "private content", null, "app")
        assertEquals(R.string.chat_wait_approval, TaskService.taskChipLabel(RunOrigin.Ui, TaskState.WaitingConfirmation(request)))
        assertEquals(R.string.chat_wait_answer, TaskService.taskChipLabel(RunOrigin.Ui, TaskState.WaitingInput("private question")))
    }
}
