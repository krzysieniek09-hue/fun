package com.krzys.hardwareinfo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tiny history store for chart samples, on Android's built-in SQLite.
 * One table: (timestamp, metric key, value). Rows older than the
 * retention window are purged so the file stays small forever.
 */
class SampleStore(context: Context) :
    SQLiteOpenHelper(context, "samples.db", null, 1) {

    companion object {
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000   // three days
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (" +
                "ts INTEGER NOT NULL, " +
                "key TEXT NOT NULL, " +
                "value REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_samples_key_ts ON samples (key, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS samples")
        onCreate(db)
    }

    /** Delete everything older than the retention window. */
    fun purgeOld(now: Long = System.currentTimeMillis()) {
        writableDatabase.delete(
            "samples", "ts < ?", arrayOf((now - RETENTION_MS).toString())
        )
    }

    /** One timestamped value per metric, written in a single transaction. */
    fun insertBatch(ts: Long, values: Map<String, Float>) {
        if (values.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((key, value) in values) {
                val row = ContentValues(3).apply {
                    put("ts", ts)
                    put("key", key)
                    put("value", value)
                }
                db.insert("samples", null, row)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The most recent [limit] values for a metric, oldest first. */
    fun recent(key: String, limit: Int): List<Float> {
        val out = ArrayList<Float>(limit)
        readableDatabase.rawQuery(
            "SELECT value FROM samples WHERE key = ? ORDER BY ts DESC LIMIT ?",
            arrayOf(key, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(cursor.getFloat(0))
        }
        out.reverse()
        return out
    }
}
