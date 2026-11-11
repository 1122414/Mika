package com.wangzan101.mika.record

import com.wangzan101.mika.core.context.TaskCheckpoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskCheckpointFileTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun reopenRejectsStaleWriterAndKeepsLastCommittedEffect() {
        val f = File(temp.root, "private/checkpoint")
        val a = TaskCheckpointFile(f)
        val b = TaskCheckpointFile(f)
        val state = TaskCheckpoint(1, pendingEffect = "order")
        assertTrue(a.save(0, state))
        assertFalse(b.save(0, TaskCheckpoint(1)))
        assertEquals(state, TaskCheckpointFile(f).load())
        assertTrue(b.save(1, state.copy(revision = 2, pendingEffect = null, lastEffect = "order")))
        assertEquals("order", a.load().lastEffect)
    }
    @Test fun invalidOrUnwritableLocationDoesNotReplaceExistingData() {
        val f = temp.newFile()
        f.writeText("corrupt")
        assertFalse(TaskCheckpointFile(f).save(0, TaskCheckpoint(1)))
        assertEquals("corrupt", f.readText())
        assertFalse(TaskCheckpointFile(File(f,"child")).save(0, TaskCheckpoint(1)))
    }
}
