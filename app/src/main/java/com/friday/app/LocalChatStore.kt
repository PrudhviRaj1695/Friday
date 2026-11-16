package com.friday.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val role: String, val text: String)

class LocalChatStore(context: Context) : SQLiteOpenHelper(context, "friday_memory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL CHECK(role IN ('user','assistant')),
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun append(role: String, content: String) {
        writableDatabase.execSQL("INSERT INTO messages(role, content, created_at) VALUES(?, ?, ?)",
            arrayOf(role, content, System.currentTimeMillis()))
    }
    @Synchronized fun readAll(): List<ChatLine> {
        val result = mutableListOf<ChatLine>()
        readableDatabase.rawQuery("SELECT role, content FROM messages ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) result += ChatLine(c.getString(0), c.getString(1))
        }
        return result
    }
    @Synchronized fun readRecent(limit: Int = 20): List<ChatLine> {
        val result = mutableListOf<ChatLine>()
        readableDatabase.rawQuery("SELECT role, content FROM messages ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result += ChatLine(c.getString(0), c.getString(1))
        }
        return result.asReversed()
    }
    @Synchronized fun clear() { writableDatabase.delete("messages", null, null) }
}