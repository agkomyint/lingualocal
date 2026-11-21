package com.example.lingualocal.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The pieces of one project, in a small SQLite file inside the project's folder (pieces.db). Each save writes
 * only the pieces that changed, so a 10-hour meeting saves as fast as a 1-minute one, and opening reads rows
 * instead of one huge file. The folder stays self-contained (project.json, people.json, pieces.db, audio/),
 * so it can be backed up or uploaded as is once the database is closed.
 *
 * Each row keeps the piece as JSON (the same format as the old captions.json, so nothing is lost and the
 * format can grow without migrations) plus plain columns for searching.
 */
class PieceDb private constructor(val file: File, private val db: SQLiteDatabase) {

    fun readAll(): List<String> = db.rawQuery("SELECT json FROM pieces ORDER BY id", null).use { c ->
        ArrayList<String>(c.count).apply { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM pieces", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Writes [changed] pieces (id, time, text for search, json) and deletes [removed] ids, in one transaction. */
    fun write(changed: List<Row>, removed: Collection<Long>) {
        if (changed.isEmpty() && removed.isEmpty()) return
        db.beginTransaction()
        try {
            val v = ContentValues()
            for (r in changed) {
                v.clear()
                v.put("id", r.id); v.put("time", r.time); v.put("text", r.text); v.put("json", r.json)
                db.insertWithOnConflict("pieces", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (id in removed) db.delete("pieces", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Folds the write-ahead log into pieces.db, so the single file is complete (before a backup or upload). */
    fun checkpoint() = runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }

    fun close() { checkpoint(); db.close() }

    class Row(val id: Long, val time: Long, val text: String, val json: String)

    companion object {
        const val FILE = "pieces.db"

        fun open(dir: File): PieceDb {
            val f = File(dir, FILE)
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE)
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS pieces (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, text TEXT NOT NULL, json TEXT NOT NULL)")
            return PieceDb(f, db)
        }
    }
}
