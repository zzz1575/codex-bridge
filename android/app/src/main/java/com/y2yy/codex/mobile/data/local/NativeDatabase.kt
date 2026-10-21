package com.y2yy.codex.mobile.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "encrypted_cache", indices = [Index("updatedAt")])
internal data class CacheEntry(
    @PrimaryKey val key: String,
    val encryptedPayload: ByteArray,
    val updatedAt: Long
)

@Dao
internal abstract class CacheDao {
    @Query("SELECT * FROM encrypted_cache WHERE `key` = :key LIMIT 1")
    abstract suspend fun find(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(entry: CacheEntry)

    @Query("DELETE FROM encrypted_cache WHERE `key` = :key")
    abstract suspend fun delete(key: String)

    @Query("DELETE FROM encrypted_cache")
    abstract suspend fun clear()

    @Query("DELETE FROM encrypted_cache WHERE `key` NOT IN (SELECT `key` FROM encrypted_cache ORDER BY updatedAt DESC LIMIT :limit)")
    protected abstract suspend fun trim(limit: Int)

    @Transaction
    open suspend fun save(entry: CacheEntry) {
        put(entry)
        trim(128)
    }
}

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
internal abstract class NativeDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        @Volatile private var instance: NativeDatabase? = null

        fun get(context: Context): NativeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NativeDatabase::class.java,
                "native-cache.db"
            ).build().also { instance = it }
        }
    }
}
