package com.wangzan101.mika.record

import com.wangzan101.mika.core.context.CheckpointStore
import com.wangzan101.mika.core.context.TaskCheckpoint
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private sibling of the exportable run directory. All handles in this process share the CAS lock. */
internal class TaskCheckpointFile(private val file: File) : CheckpointStore {
    override fun load(): TaskCheckpoint = synchronized(lock) {
        if (!file.exists()) TaskCheckpoint() else {
            check(file.length() <= 256_000) { "Invalid checkpoint length" }
            Json.decodeFromString<TaskCheckpoint>(file.readText())
        }
    }

    override fun save(expected: Int, next: TaskCheckpoint): Boolean = synchronized(lock) {
        if (next.revision != expected + 1) return false
        try {
            if (load().revision != expected) return false
            val dir = file.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val temp = File.createTempFile("checkpoint", ".tmp", dir)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(Json.encodeToString(TaskCheckpoint.serializer(), next).toByteArray())
                    out.fd.sync()
                }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally { temp.delete() }
        } catch (_: Exception) { false }
    }

    private companion object { val lock = Any() }
}
