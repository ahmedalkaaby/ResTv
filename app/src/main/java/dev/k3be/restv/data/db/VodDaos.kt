package dev.k3be.restv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VodDao {
    @Query("DELETE FROM vod_category WHERE loginId = :loginId AND kind = :kind")
    fun clearCategories(loginId: Long, kind: String)

    @Query("DELETE FROM movie WHERE loginId = :loginId")
    fun clearMovies(loginId: Long)

    @Query("DELETE FROM series WHERE loginId = :loginId")
    fun clearSeries(loginId: Long)

    @Query("SELECT streamId FROM movie WHERE loginId = :loginId")
    fun movieIds(loginId: Long): List<Long>

    @Query("SELECT seriesId FROM series WHERE loginId = :loginId")
    fun seriesIds(loginId: Long): List<Long>

    @Query("DELETE FROM movie WHERE loginId = :loginId AND streamId IN (:ids)")
    fun deleteMovies(loginId: Long, ids: List<Long>)

    @Query("DELETE FROM series WHERE loginId = :loginId AND seriesId IN (:ids)")
    fun deleteSeries(loginId: Long, ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCategories(list: List<VodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMovies(list: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSeries(list: List<SeriesEntity>)

    @Query("SELECT * FROM vod_category WHERE loginId = :loginId AND kind = :kind ORDER BY providerOrder")
    suspend fun categories(loginId: Long, kind: String): List<VodCategoryEntity>

    @Query("SELECT COUNT(*) FROM movie WHERE loginId = :loginId")
    suspend fun countMovies(loginId: Long): Int

    @Query("SELECT COUNT(*) FROM series WHERE loginId = :loginId")
    suspend fun countSeries(loginId: Long): Int

    @Query("SELECT categoryId, COUNT(*) AS n FROM movie WHERE loginId = :loginId GROUP BY categoryId")
    suspend fun movieCounts(loginId: Long): List<CategoryCount>

    @Query("SELECT categoryId, COUNT(*) AS n FROM series WHERE loginId = :loginId GROUP BY categoryId")
    suspend fun seriesCounts(loginId: Long): List<CategoryCount>

    @Query("SELECT * FROM movie WHERE loginId = :loginId AND categoryId = :categoryId ORDER BY added DESC LIMIT :limit")
    suspend fun moviesIn(loginId: Long, categoryId: String, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM series WHERE loginId = :loginId AND categoryId = :categoryId ORDER BY updated DESC LIMIT :limit")
    suspend fun seriesIn(loginId: Long, categoryId: String, limit: Int): List<SeriesEntity>

    @Query(
        """SELECT m.* FROM movie m JOIN vod_category c
           ON c.loginId = m.loginId AND c.kind = 'movie' AND c.categoryId = m.categoryId
           WHERE m.loginId = :loginId AND c.platform = :platform ORDER BY m.added DESC LIMIT :limit"""
    )
    suspend fun moviesOnPlatform(loginId: Long, platform: String, limit: Int): List<MovieEntity>

    @Query(
        """SELECT s.* FROM series s JOIN vod_category c
           ON c.loginId = s.loginId AND c.kind = 'series' AND c.categoryId = s.categoryId
           WHERE s.loginId = :loginId AND c.platform = :platform ORDER BY s.updated DESC LIMIT :limit"""
    )
    suspend fun seriesOnPlatform(loginId: Long, platform: String, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE loginId = :loginId ORDER BY added DESC LIMIT :limit")
    suspend fun latestMovies(loginId: Long, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM series WHERE loginId = :loginId ORDER BY updated DESC LIMIT :limit")
    suspend fun latestSeries(loginId: Long, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE loginId = :loginId AND titleKey IN (:keys)")
    suspend fun moviesByKeys(loginId: Long, keys: List<String>): List<MovieEntity>

    @Query("SELECT * FROM series WHERE loginId = :loginId AND titleKey IN (:keys)")
    suspend fun seriesByKeys(loginId: Long, keys: List<String>): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE loginId = :loginId AND streamId IN (:ids)")
    suspend fun moviesByIds(loginId: Long, ids: List<Long>): List<MovieEntity>

    @Query("SELECT * FROM series WHERE loginId = :loginId AND seriesId IN (:ids)")
    suspend fun seriesByIds(loginId: Long, ids: List<Long>): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE loginId = :loginId AND streamId = :id")
    suspend fun movie(loginId: Long, id: Long): MovieEntity?

    @Query("SELECT * FROM series WHERE loginId = :loginId AND seriesId = :id")
    suspend fun series(loginId: Long, id: Long): SeriesEntity?

    @Query("SELECT * FROM movie WHERE loginId = :loginId AND titleKey = :key")
    suspend fun movieVersions(loginId: Long, key: String): List<MovieEntity>

    @Query(
        """SELECT * FROM movie WHERE loginId = :loginId AND name LIKE '%' || :q || '%'
           ORDER BY CASE WHEN name LIKE :q || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"""
    )
    suspend fun searchMovies(loginId: Long, q: String, limit: Int = 60): List<MovieEntity>

    @Query(
        """SELECT * FROM series WHERE loginId = :loginId AND name LIKE '%' || :q || '%'
           ORDER BY CASE WHEN name LIKE :q || '%' THEN 0 ELSE 1 END, updated DESC LIMIT :limit"""
    )
    suspend fun searchSeries(loginId: Long, q: String, limit: Int = 60): List<SeriesEntity>
}

@Dao
interface WatchDao {
    @Upsert
    suspend fun upsert(w: WatchEntity)

    @Query("SELECT * FROM watch WHERE accountId = :accountId AND loginId = :loginId ORDER BY updatedAt DESC LIMIT 200")
    fun observeRecent(accountId: Long, loginId: Long): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watch WHERE accountId = :accountId AND loginId = :loginId AND seriesId = :seriesId AND kind = 'episode'")
    fun observeSeries(accountId: Long, loginId: Long, seriesId: Long): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watch WHERE accountId = :accountId AND loginId = :loginId AND kind = :kind AND itemId = :itemId")
    suspend fun get(accountId: Long, loginId: Long, kind: String, itemId: Long): WatchEntity?

    @Query("SELECT * FROM watch WHERE accountId = :accountId AND loginId = :loginId AND kind = :kind AND itemId = :itemId")
    fun observe(accountId: Long, loginId: Long, kind: String, itemId: Long): Flow<WatchEntity?>

    @Query("DELETE FROM watch WHERE accountId = :accountId AND loginId = :loginId AND (itemId = :itemId OR seriesId = :itemId) AND kind IN (:kinds)")
    suspend fun forget(accountId: Long, loginId: Long, itemId: Long, kinds: List<String>)
}

@Dao
interface WishDao {
    @Query("SELECT * FROM wish WHERE accountId = :accountId AND loginId = :loginId ORDER BY addedAt DESC")
    fun observe(accountId: Long, loginId: Long): Flow<List<WishEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(w: WishEntity)

    @Query("DELETE FROM wish WHERE accountId = :accountId AND loginId = :loginId AND kind = :kind AND itemId = :itemId")
    suspend fun remove(accountId: Long, loginId: Long, kind: String, itemId: Long)
}
