package ir.simscan.fast

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SimDb(context: Context) : SQLiteOpenHelper(context, "simscan_v3.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE records(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL DEFAULT '',
                barcode TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertBarcode(barcode: String): Long {
        val values = ContentValues().apply {
            put("barcode", barcode)
            put("phone", "")
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "records", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun findByBarcode(barcode: String): SimRecord? {
        readableDatabase.query(
            "records", arrayOf("id", "phone", "barcode", "created_at"),
            "barcode=?", arrayOf(barcode), null, null, null, "1"
        ).use { c ->
            return if (c.moveToFirst()) {
                SimRecord(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
            } else null
        }
    }

    fun update(id: Long, phone: String, barcode: String): Boolean {
        val values = ContentValues().apply {
            put("phone", phone)
            put("barcode", barcode)
        }
        return try {
            writableDatabase.update("records", values, "id=?", arrayOf(id.toString())) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun updatePhone(id: Long, phone: String): Boolean {
        val values = ContentValues().apply { put("phone", phone) }
        return writableDatabase.update("records", values, "id=?", arrayOf(id.toString())) > 0
    }

    fun delete(id: Long) {
        writableDatabase.delete("records", "id=?", arrayOf(id.toString()))
    }

    fun all(): List<SimRecord> {
        val list = ArrayList<SimRecord>()
        readableDatabase.query(
            "records", arrayOf("id", "phone", "barcode", "created_at"),
            null, null, null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                list += SimRecord(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
            }
        }
        return list
    }
}
