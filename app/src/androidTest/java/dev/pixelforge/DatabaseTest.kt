package dev.pixelforge

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelforge.core.data.PixelForgeDatabase
import dev.pixelforge.core.data.ReversibleChange
import dev.pixelforge.core.data.TrashEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: PixelForgeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PixelForgeDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun earliestChangeHoldsOriginalValue() = runBlocking {
        val dao = db.changes()
        dao.insert(ReversibleChange(time = 1, kind = "SETTING", target = "GLOBAL", key = "k", previousValue = "1.0", newValue = "0.5", label = "a", source = "p1"))
        dao.insert(ReversibleChange(time = 2, kind = "SETTING", target = "GLOBAL", key = "k", previousValue = "0.5", newValue = "0", label = "b", source = "p2"))
        assertEquals("1.0", dao.earliestActive("SETTING", "GLOBAL", "k")!!.previousValue)
        dao.markReverted("SETTING", "GLOBAL", "k")
        assertEquals(0, dao.active().size)
    }

    @Test
    fun trashRetentionQuery() = runBlocking {
        val dao = db.trash()
        dao.insert(TrashEntry(originalPath = "/a", trashPath = "/t/a", sizeBytes = 10, isDirectory = false, deletedAt = 1000))
        dao.insert(TrashEntry(originalPath = "/b", trashPath = "/t/b", sizeBytes = 20, isDirectory = false, deletedAt = 5000))
        assertEquals(1, dao.olderThan(2000).size)
        assertEquals(2, dao.all().size)
    }
}
