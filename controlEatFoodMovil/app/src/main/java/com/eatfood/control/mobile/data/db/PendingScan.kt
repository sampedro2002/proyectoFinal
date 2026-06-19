package com.eatfood.control.mobile.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * Cola local de consumos para el modo offline (equivale a la store IndexedDB
 * 'pending-scans' del frontend). Cada registro lleva un clientUuid único que
 * garantiza idempotencia al sincronizar.
 */
@Entity(tableName = "pending_scans")
data class PendingScan(
    @PrimaryKey val clientUuid: String,
    val templateB64: String,
    val mealTypeCode: String?,
    val consumedAt: String
)

@Dao
interface PendingScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(scan: PendingScan)

    @Query("SELECT * FROM pending_scans ORDER BY consumedAt ASC")
    suspend fun pending(): List<PendingScan>

    @Query("DELETE FROM pending_scans WHERE clientUuid = :uuid")
    suspend fun remove(uuid: String)

    @Query("SELECT COUNT(*) FROM pending_scans")
    suspend fun count(): Int
}

@Database(entities = [PendingScan::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingScanDao(): PendingScanDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "eatfood-offline.db"
                ).build().also { instance = it }
            }
    }
}
