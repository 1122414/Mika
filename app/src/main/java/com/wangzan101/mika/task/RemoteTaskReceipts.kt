package com.wangzan101.mika.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 收件先于启动服务落盘。只保存电脑发起的任务，进度仍从正在执行的 TaskRun 读取。 */
internal class RemoteTaskReceipts(context: Context, name: String = "remote-tasks.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE receipts (
            id TEXT PRIMARY KEY NOT NULL, conversation TEXT, goal TEXT, started INTEGER NOT NULL,
            new_chat INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, result TEXT, steers TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unknown remote task receipt schema $oldVersion -> $newVersion")

    // MikaApp owns one hub per process. A previous process cannot still be dispatching these tasks.
    init {
        val interrupted: TaskState.Terminal = TaskState.Failed("The phone process stopped before this task finished.", FailKind.Interrupted)
        writableDatabase.execSQL("UPDATE receipts SET result=? WHERE result IS NULL AND goal IS NOT NULL",
            arrayOf(Json.encodeToString(interrupted)))
    }

    private fun Cursor.task(): RemoteTask {
        if (isNull(2)) throw RemoteTaskError.General(RemoteTaskError.Code.GONE, "This task's conversation was deleted.")
        return RemoteTask(getString(0), getString(1), getString(2), getLong(3),
            getString(4)?.let { Json.decodeFromString<TaskState.Terminal>(it) },
            getString(5)?.let { Json.decodeFromString<List<RemoteSteer>>(it) }.orEmpty())
    }

    private val columns = "id, conversation, goal, started, result, steers, new_chat"

    fun find(id: String, goal: String? = null, requestedChat: String? = null): RemoteTask? =
        readableDatabase.rawQuery("SELECT $columns FROM receipts WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val task = cursor.task()
            val originalChat = if (cursor.getInt(6) != 0) null else task.conversation
            if (goal != null && (goal != task.goal || requestedChat != originalChat)) {
                throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, "This request_id was already used for a different task.")
            }
            task
        }

    fun recent(limit: Int): List<RemoteTask> = readableDatabase.rawQuery(
        "SELECT $columns FROM receipts WHERE goal IS NOT NULL ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.task()) } }

    fun insert(task: RemoteTask, newChat: Boolean) {
        writableDatabase.insertOrThrow("receipts", null, ContentValues().apply {
            put("id", task.id); put("conversation", task.conversation); put("goal", task.goal)
            put("started", task.createdAtMs); put("new_chat", newChat)
        })
    }

    /** Android 可能在进程重建后才投递一个已排队的 Intent，已中断或已领取的都不能执行。 */
    fun claimStart(id: String): Boolean = writableDatabase.update("receipts", ContentValues().apply { put("claimed", 1) },
        "id=? AND goal IS NOT NULL AND result IS NULL AND claimed=0", arrayOf(id)) == 1

    fun finish(id: String, state: TaskState.Terminal, steers: List<RemoteSteer>, onlyUnclaimed: Boolean = false): Boolean {
        val condition = "id=? AND goal IS NOT NULL AND result IS NULL" + if (onlyUnclaimed) " AND claimed=0" else ""
        return writableDatabase.update("receipts", ContentValues().apply {
            // 远程接口不暴露回答的证据账本。
            val end = if (state is TaskState.Succeeded) state.copy(sources = emptyList()) else state
            put("result", Json.encodeToString<TaskState.Terminal>(end))
            if (steers.isNotEmpty()) put("steers", Json.encodeToString(steers))
        }, condition, arrayOf(id)) == 1
    }

    fun forget(conversation: String) {
        // 留下占用过的编号，迟到的重试不能重新执行已删除的任务。
        writableDatabase.execSQL("UPDATE receipts SET conversation=NULL, goal=NULL, result=NULL, steers=NULL, started=0, claimed=0, new_chat=0 WHERE conversation=?",
            arrayOf(conversation))
    }
}
