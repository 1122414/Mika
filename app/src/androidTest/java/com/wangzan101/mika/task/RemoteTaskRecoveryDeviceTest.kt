package com.wangzan101.mika.task

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class RemoteTaskRecoveryDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<MikaApp>()

    @Test fun retainedIdsAndTerminalResultsSurviveReopening() {
        val db = "receipts-test-" + UUID.randomUUID()
        val done = RemoteTask("first", "mcp-first", "review", 1, null)
        val result = TaskState.Succeeded("answer", false, "unchecked items", unconfirmed = listOf("one"))
        val notes = listOf(RemoteSteer("s1", "joined"))
        try {
            RemoteTaskReceipts(app, db).use { store ->
                store.insert(done, true)
                assertTrue(store.claimStart(done.id))
                assertFalse(store.claimStart(done.id))
                store.finish(done.id, result, notes)
                store.insert(done.copy(id = "cancelled"), false)
                store.finish("cancelled", TaskState.Cancelled, emptyList())
                repeat(105) { store.insert(done.copy(id = "queued-$it", conversation = "another"), false) }
            }
            RemoteTaskReceipts(app, db).use { store ->
                assertEquals(done.copy(state = result, steers = notes), store.find(done.id))
                assertEquals(TaskState.Cancelled, store.find("cancelled")!!.state)
                assertEquals(FailKind.Interrupted, (store.find("queued-0")!!.state as TaskState.Failed).kind)
                assertFalse(store.claimStart("queued-0"))
                assertEquals("queued-104", store.recent(1).single().id)
                store.forget(done.conversation)
                assertFalse(store.finish(done.id, result, notes))
                val gone = runCatching { store.find(done.id, done.goal) }.exceptionOrNull() as RemoteTaskError
                assertEquals(RemoteTaskError.Code.GONE, gone.code)
                assertTrue(store.recent(110).none { it.conversation == done.conversation })
            }
        } finally { app.deleteDatabase(db) }
    }

    /** An external CLI driver can submit the first request and kill this process during the second run. */
    @Test fun concurrentRetriesStartOnlyOneTurn() {
        val driver = InstrumentationRegistry.getArguments().getString("recoveryDriver")
        val id = driver ?: UUID.randomUUID().toString()
        val goal = "Recovery check $id"
        val service = RemoteTaskService(app.remoteTasks)
        val model = ScriptedModel().apply { intent = "ACT" }
        val pool = Executors.newFixedThreadPool(6)
        val usb = app.mcp.usb.enabled.value
        var chat: String? = null
        try {
            testTasks.modelFactory = { _, _ -> model }
            testTasks.lightModelFactory = { _, _ -> null }
            testTasks.visionModelFactory = { _, _ -> null }
            if (driver != null) {
                ui.runOnIdle { app.mcp.setUsbEnabled(true) }
                ui.waitUntil(30_000) { app.mcp.state.value is McpServerState.Listening }
                File(app.cacheDir, "recovery-listening-$id").writeText("ready")
                ui.waitUntil(90_000) { app.remoteTasks.get(id) != null }
            }
            val tasks = pool.invokeAll(List(12) { Callable { service.start(goal, null, id) } }).map { it.get() }
            val conversation = tasks.first().conversation
            chat = conversation
            assertTrue(tasks.all { it.id == id && it.conversation == conversation })
            val mcp = runBlocking { McpTaskTools(service).call(McpTaskTools.RUN, buildJsonObject {
                put("goal", goal); put("request_id", id)
            }) }
            assertEquals(id, mcp.getValue("task_id").jsonPrimitive.content)
            for ((otherGoal, thread) in listOf("other" to null, goal to conversation)) {
                val error = runCatching { service.start(otherGoal, thread, id) }.exceptionOrNull() as RemoteTaskError
                assertEquals(RemoteTaskError.Code.CONFLICT, error.code)
            }
            model.awaitCall()
            assertEquals(1, app.taskState.runs.value.count { it.conversationId == conversation })
            if (driver == null) {
                // Make exactly this receipt write fail until the ordinary phone turn has been saved.
                app.openOrCreateDatabase("remote-tasks.db", 0, null).use { db ->
                    db.execSQL("""CREATE TRIGGER reject_test_result BEFORE UPDATE OF result ON receipts
                        WHEN NEW.id='$id' BEGIN SELECT RAISE(ABORT,'test write failure'); END""")
                    try {
                        model.answer("Recovered answer")
                        ui.waitUntil(30_000) { app.conversations.turns(conversation).any { it.userText == goal } }
                        val failure = runCatching { service.get(id) }.exceptionOrNull() as RemoteTaskError
                        assertEquals(RemoteTaskError.Code.UNAVAILABLE, failure.code)
                    } finally { db.execSQL("DROP TRIGGER reject_test_result") }
                }
            } else model.answer("Recovered answer")
            ui.waitUntil(30_000) { service.get(id).state is TaskState.Succeeded }
            assertEquals("Recovered answer", (service.start(goal, null, id).state as TaskState.Succeeded).answer)
            ui.waitUntil(20_000) { app.conversations.turns(conversation).count { it.userText == goal } == 1 }
            if (driver != null) {
                val interrupted = requireNotNull(InstrumentationRegistry.getArguments().getString("interruptId"))
                service.start("Interrupt check $interrupted", conversation, interrupted)
                model.awaitCall()
                File(app.cacheDir, "recovery-ready-$id").writeText("ready")
                // The disposable emulator driver force-stops this process here.
                ui.waitUntil(90_000) { false }
            }
            runBlocking { app.conversationDeleter.delete(conversation) }
            chat = null
            val gone = runCatching { service.start(goal, null, id) }.exceptionOrNull() as RemoteTaskError
            assertEquals(RemoteTaskError.Code.GONE, gone.code)
        } finally {
            pool.shutdownNow()
            testTasks.modelFactory = TaskEnvironment.defaultModelFactory
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            if (driver != null && !usb) ui.runOnIdle { app.mcp.setUsbEnabled(false) }
            chat?.let { runBlocking { app.conversationDeleter.delete(it) } }
        }
    }
}
