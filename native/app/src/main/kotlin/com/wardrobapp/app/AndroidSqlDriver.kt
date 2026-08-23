package com.wardrobapp.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.wardrobapp.data.SqlDriver

/**
 * [SqlDriver] over the platform's SQLite.
 *
 * Deliberately not Room. Two schemas exist in the wild -- `created_at` and
 * `updated_at` are NOT NULL on a fresh install and nullable on one upgraded
 * through the ALTER path, because SQLite cannot add a NOT NULL column without a
 * default -- and Room validates column nullability against its compiled schema
 * hash, so it would reject one of those populations outright. There is also no
 * `PRAGMA user_version`, the schema being applied idempotently, so Room would
 * see an unversioned database and try to migrate from zero.
 *
 * The pragmas match what expo-sqlite sets, so the file behaves the same way
 * under either app: WAL for concurrent reads, foreign keys on so the rating
 * cascade works.
 */
class AndroidSqlDriver private constructor(
    private val database: SupportSQLiteDatabase,
) : SqlDriver, AutoCloseable {

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        database.query(sql, args.toTypedArray()).use { cursor ->
            val columns = (0 until cursor.columnCount).map(cursor::getColumnName)
            val rows = mutableListOf<Map<String, Any?>>()

            while (cursor.moveToNext()) {
                rows.add(
                    columns.withIndex().associate { (index, name) ->
                        // Typed by what the column actually holds, not by what it
                        // was declared as: SQLite is dynamically typed, and the
                        // mapping layer relies on seeing a Long for an INTEGER
                        // and a String for TEXT.
                        name to when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                            else -> cursor.getString(index)
                        }
                    }
                )
            }

            return rows
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int {
        database.compileStatement(sql).use { statement ->
            args.forEachIndexed { index, arg ->
                val position = index + 1
                when (arg) {
                    null -> statement.bindNull(position)
                    is Long -> statement.bindLong(position, arg)
                    is Int -> statement.bindLong(position, arg.toLong())
                    is Boolean -> statement.bindLong(position, if (arg) 1L else 0L)
                    is Double -> statement.bindDouble(position, arg)
                    is Float -> statement.bindDouble(position, arg.toDouble())
                    is ByteArray -> statement.bindBlob(position, arg)
                    else -> statement.bindString(position, arg.toString())
                }
            }
            return statement.executeUpdateDelete()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        // Nested calls join the outer transaction rather than opening a second
        // one. The write paths compose -- deleting a garment calls into the
        // outfit writes, which are transactional themselves.
        if (database.inTransaction()) return block()

        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() = database.close()

    companion object {
        /**
         * The filename expo-sqlite uses, so this opens the same file the React
         * Native app wrote -- given the same applicationId.
         */
        const val DATABASE_NAME = "wardrobapp.db"

        fun open(context: Context, name: String = DATABASE_NAME): AndroidSqlDriver {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        // The schema is applied by WardrobeSchema on every open,
                        // idempotently, exactly as the TypeScript client does --
                        // so there is nothing to create or migrate here. Version
                        // numbers are deliberately unused; onUpgrade would fight
                        // the ALTER-based scheme.
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit

                        override fun onConfigure(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                        }
                    })
                    .build()
            )

            val database = helper.writableDatabase
            // WAL, as expo-sqlite sets it. Enabled through the helper rather
            // than a raw PRAGMA: the framework tracks the mode itself and a
            // direct pragma can disagree with it.
            database.enableWriteAheadLogging()

            return AndroidSqlDriver(database)
        }
    }
}
