package io.zer0.muse.data.session

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fts5ProbeTest {
    private fun database(options: List<String>?): SupportSQLiteDatabase {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        if (options == null) {
            every { db.query("PRAGMA compile_options") } throws IllegalStateException("PRAGMA unavailable")
        } else {
            val cursor = mockk<Cursor>(relaxed = true)
            var index = -1
            every { cursor.moveToNext() } answers { ++index < options.size }
            every { cursor.getString(0) } answers { options[index] }
            every { db.query("PRAGMA compile_options") } returns cursor
        }
        return db
    }

    @Test
    fun `FTS5 compile option avoids creating a virtual table`() {
        val db = database(listOf("ENABLE_FTS5", "THREADSAFE=1"))
        assertTrue(MuseDb.fts5Available(db))
        verify(exactly = 0) { db.execSQL(any<String>()) }
    }

    @Test
    fun `known options without FTS5 avoid the failing probe`() {
        val db = database(listOf("ENABLE_FTS3", "THREADSAFE=1"))
        assertFalse(MuseDb.fts5Available(db))
        verify(exactly = 0) { db.execSQL(any<String>()) }
    }

    @Test
    fun `empty options fall back to the original probe`() {
        val db = database(emptyList())
        assertTrue(MuseDb.fts5Available(db))
        verify(exactly = 1) { db.execSQL(match { it.contains("USING fts5") }) }
    }

    @Test
    fun `unavailable PRAGMA falls back to the original probe`() {
        val db = database(null)
        assertTrue(MuseDb.fts5Available(db))
        verify(exactly = 1) { db.execSQL(match { it.contains("USING fts5") }) }
    }

    @Test
    fun `unsupported fallback returns false and cleans up`() {
        val db = database(emptyList())
        every { db.execSQL(match { it.contains("USING fts5") }) } throws IllegalStateException("no such module: fts5")
        assertFalse(MuseDb.fts5Available(db))
        verify(atLeast = 1) { db.execSQL("DROP TABLE IF EXISTS temp.muse_fts5_probe") }
    }
}
