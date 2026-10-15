package com.abeswastaken.trades.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "fills")
data class FillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val ticker: String,
    val type: String,
    val strike: Double?,
    val expiryEpochDay: Long?,
    val side: String,
    val quantity: Double,
    val price: Double,
    val fees: Double,
    val note: String,
    val recommendationId: Long?,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toFill() = Fill(
        id = id,
        date = LocalDate.ofEpochDay(epochDay),
        instrument = Instrument(ticker, AssetType.valueOf(type), strike, expiryEpochDay?.let(LocalDate::ofEpochDay)),
        side = Side.valueOf(side),
        quantity = quantity, price = price, fees = fees, note = note,
        recommendationId = recommendationId, seq = id,
    )

    companion object {
        fun from(f: Fill) = FillEntity(
            id = f.id, epochDay = f.date.toEpochDay(), ticker = f.instrument.ticker.uppercase(),
            type = f.instrument.type.name, strike = f.instrument.strike,
            expiryEpochDay = f.instrument.expiry?.toEpochDay(), side = f.side.name,
            quantity = f.quantity, price = f.price, fees = f.fees, note = f.note,
            recommendationId = f.recommendationId,
        )
    }
}

object RecStatus {
    const val NEW = "NEW"
    const val TAKEN = "TAKEN"
    const val SKIPPED = "SKIPPED"
}

@Entity(tableName = "recommendations")
data class RecEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val model: String,
    val servedModel: String,
    val json: String,
    val summary: String,
    val action: String,
    val status: String = RecStatus.NEW,
    val costUsd: Double,
    val digest: String?,
    val focus: String?,
)

@Entity(tableName = "watchlist")
data class WatchEntity(@PrimaryKey val ticker: String, val addedAt: Long = System.currentTimeMillis())

@Entity(tableName = "spend")
data class SpendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val purpose: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costUsd: Double,
)

@Dao
interface TradesDao {
    @Query("SELECT * FROM fills ORDER BY epochDay DESC, id DESC")
    fun fills(): Flow<List<FillEntity>>

    @Query("SELECT * FROM fills")
    suspend fun allFills(): List<FillEntity>

    @Insert
    suspend fun insertFill(f: FillEntity): Long

    @Insert
    suspend fun insertFills(f: List<FillEntity>): List<Long>

    @Query("DELETE FROM fills WHERE id = :id")
    suspend fun deleteFill(id: Long)

    @Query("SELECT * FROM recommendations ORDER BY createdAt DESC")
    fun recs(): Flow<List<RecEntity>>

    @Query("SELECT * FROM recommendations")
    suspend fun allRecs(): List<RecEntity>

    @Query("SELECT * FROM recommendations WHERE id = :id")
    suspend fun rec(id: Long): RecEntity?

    @Insert
    suspend fun insertRec(r: RecEntity): Long

    @Query("UPDATE recommendations SET status = :status WHERE id = :id")
    suspend fun setRecStatus(id: Long, status: String)

    @Query("SELECT * FROM watchlist ORDER BY ticker")
    fun watchlist(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watchlist")
    suspend fun allWatch(): List<WatchEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWatch(w: WatchEntity)

    @Query("DELETE FROM watchlist WHERE ticker = :ticker")
    suspend fun removeWatch(ticker: String)

    @Query("SELECT * FROM spend ORDER BY ts DESC")
    fun spend(): Flow<List<SpendEntity>>

    @Query("SELECT * FROM spend")
    suspend fun allSpend(): List<SpendEntity>

    @Insert
    suspend fun insertSpend(s: List<SpendEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFills(f: List<FillEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRecs(r: List<RecEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWatch(w: List<WatchEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSpend(s: List<SpendEntity>)
    @Query("DELETE FROM fills") suspend fun clearFills()
    @Query("DELETE FROM recommendations") suspend fun clearRecs()
    @Query("DELETE FROM watchlist") suspend fun clearWatch()
    @Query("DELETE FROM spend") suspend fun clearSpend()
}

@Database(
    entities = [FillEntity::class, RecEntity::class, WatchEntity::class, SpendEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TradesDb : RoomDatabase() {
    abstract fun dao(): TradesDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, TradesDb::class.java, "trades.db").build()
    }
}
