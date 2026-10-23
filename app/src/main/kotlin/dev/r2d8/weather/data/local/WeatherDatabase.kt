package dev.r2d8.weather.data.local

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The last successful response for one place, stored as the API's own JSON. Keeping raw JSON
 * (instead of one column per value) means new fields only need a mapper change, not a migration.
 */
@Entity(tableName = "cached_weather")
data class CachedWeather(
    /** See [dev.r2d8.weather.data.WeatherRepository.cacheKey]. */
    @PrimaryKey val key: String,
    val forecastJson: String,
    val airQualityJson: String?,
    val fetchedAtEpochMs: Long,
)

@Dao
interface CachedWeatherDao {
    @Query("SELECT * FROM cached_weather WHERE `key` = :key")
    fun observe(key: String): Flow<CachedWeather?>

    @Query("SELECT * FROM cached_weather WHERE `key` = :key")
    suspend fun get(key: String): CachedWeather?

    @Upsert
    suspend fun upsert(entry: CachedWeather)
}

/** A place the user saved. [position] orders the list (ascending). */
@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val region: String?,
    val latitude: Double,
    val longitude: Double,
    val position: Int,
)

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY position ASC, id ASC")
    suspend fun getAll(): List<SavedPlaceEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM saved_places")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(entity: SavedPlaceEntity): Long

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE saved_places SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)
}

// Version 2 added saved_places. The auto-migration only adds the table, so the weather cache is kept.
@Database(
    entities = [CachedWeather::class, SavedPlaceEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WeatherDatabase : RoomDatabase() {
    abstract fun cachedWeatherDao(): CachedWeatherDao
    abstract fun savedPlaceDao(): SavedPlaceDao
}
