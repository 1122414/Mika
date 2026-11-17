package com.wangzan101.mika.task

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class TaskLiveUpdateDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun sameNotificationFollowsTheTaskAndDisappearsWhenItEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nm = context.getSystemService(NotificationManager::class.java)
        val thread = UUID.randomUUID().toString()
        val goal = "Ask which color I chose, then repeat my answer."
        val model = ScriptedModel().apply { intent = "ACT" }
        val target = TaskService.openApp(context, thread)
        // Optional time for an observer to inspect the OEM status bar outside Mika.
        val observeMs = InstrumentationRegistry.getArguments().getString("observeChipMs")?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
        fun progress() = nm.activeNotifications.firstOrNull {
            it.notification.channelId == TaskService.CHANNEL_PROGRESS && it.notification.contentIntent == target
        }
        fun awaitLabel(res: Int) = requireNotNull(pollUntil(15_000) {
            progress()?.takeIf { it.notification.shortCriticalText == context.getString(res) }
        }) { "Missing task notification with label " + context.getString(res) }.also {
            assertTrue(it.notification.hasPromotableCharacteristics())
            assertTrue(it.notification.extras.getBoolean("android.requestPromotedOngoing"))
            Log.i("MikaLiveUpdateTest", "label=${it.notification.shortCriticalText} eligible=${it.notification.hasPromotableCharacteristics()} allowed=${nm.canPostPromotedNotifications()} promoted=${it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0}")
            if (observeMs > 0) SystemClock.sleep(observeMs)
        }
        val env = testTasks
        val primary = env.modelFactory
        val light = env.lightModelFactory
        val vision = env.visionModelFactory
        check(!DeviceTaskState.busy.value)
        try {
            env.modelFactory = { _, _ -> model }
            env.lightModelFactory = { _, _ -> null }
            env.visionModelFactory = { _, _ -> null }
            TaskService.start(context, goal, thread)
            model.awaitCall()
            val running = awaitLabel(R.string.task_running)
            model.feed("ask_user", """{"prompt":"Which color did you choose?"}""")
            assertEquals(running.key, awaitLabel(R.string.chat_wait_answer).key)
            val id = DeviceTaskState.runs.value.single { it.conversationId == thread }.key
            TaskService.answer(context, "Blue", id)
            model.awaitCall()
            // After ask_user the agent can request one follow-up before accepting the final answer.
            repeat(2) { model.answer("Blue.") }
            val cleared = pollUntil(15_000) { true.takeIf { !DeviceTaskState.busy.value && progress() == null } }
            assertNotNull("state=${DeviceTaskState.state.value.javaClass.simpleName}, busy=${DeviceTaskState.busy.value}, remaining=${progress()?.id}", cleared)
            assertEquals("Blue.", (DeviceTaskState.state.value as TaskState.Succeeded).answer)
        } finally {
            DeviceTaskState.runs.value.firstOrNull { it.conversationId == thread }?.let {
                TaskService.cancel(context, it.key)
                pollUntil(10_000) { true.takeIf { !DeviceTaskState.busy.value } }
            }
            env.modelFactory = primary
            env.lightModelFactory = light
            env.visionModelFactory = vision
            ConversationStore.shared(context).delete(thread)
            nm.activeNotifications.filter { it.notification.contentIntent == target }.forEach { nm.cancel(it.tag, it.id) }
        }
    }
}
