package dev.k3be.restv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun lastUsed(): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Query("UPDATE account SET lastUsedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)
}

@Dao
interface LoginDao {
    @Query("SELECT * FROM xtream_login WHERE accountId = :accountId ORDER BY id LIMIT 1")
    suspend fun firstFor(accountId: Long): LoginEntity?

    @Query("SELECT * FROM xtream_login WHERE id = :id")
    fun observe(id: Long): Flow<LoginEntity?>

    @Insert
    suspend fun insert(login: LoginEntity): Long

    @Update
    suspend fun update(login: LoginEntity)
}

@Dao
interface LiveDao {
    @Query("DELETE FROM live_category WHERE loginId = :loginId")
    suspend fun clearCategories(loginId: Long)

    @Query("DELETE FROM channel WHERE loginId = :loginId")
    suspend fun clearChannels(loginId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(list: List<LiveCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(list: List<ChannelEntity>)

    @Query("SELECT * FROM live_category WHERE loginId = :loginId ORDER BY providerOrder")
    fun observeCategories(loginId: Long): Flow<List<LiveCategoryEntity>>

    @Query("SELECT categoryId, COUNT(*) AS n FROM channel WHERE loginId = :loginId GROUP BY categoryId")
    fun observeCounts(loginId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM channel WHERE loginId = :loginId AND categoryId = :categoryId ORDER BY quality DESC, num, name")
    fun observeChannels(loginId: Long, categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE loginId = :loginId AND quality = 3 ORDER BY num, name")
    fun observe4k(loginId: Long): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channel c
           JOIN favorite f ON f.loginId = c.loginId AND f.streamId = c.streamId
           WHERE f.accountId = :accountId AND c.loginId = :loginId
           ORDER BY f.addedAt"""
    )
    fun observeFavorites(accountId: Long, loginId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE loginId = :loginId AND variantKey = :key ORDER BY quality DESC")
    suspend fun variants(loginId: Long, key: String): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channel WHERE loginId = :loginId")
    suspend fun countChannels(loginId: Long): Int
}

@Dao
interface PrefDao {
    @Query("SELECT * FROM category_pref WHERE accountId = :accountId AND loginId = :loginId")
    fun observeCategoryPrefs(accountId: Long, loginId: Long): Flow<List<CategoryPrefEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryPrefs(list: List<CategoryPrefEntity>)

    @Query("UPDATE category_pref SET hidden = 0 WHERE accountId = :accountId AND loginId = :loginId")
    suspend fun unhideAll(accountId: Long, loginId: Long)

    @Query("SELECT streamId FROM favorite WHERE accountId = :accountId AND loginId = :loginId")
    fun observeFavoriteIds(accountId: Long, loginId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE accountId = :accountId AND loginId = :loginId AND streamId = :streamId")
    suspend fun removeFavorite(accountId: Long, loginId: Long, streamId: Long)

    @Query("SELECT format FROM channel_pref WHERE loginId = :loginId AND streamId = :streamId")
    suspend fun channelFormat(loginId: Long, streamId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setChannelFormat(pref: ChannelPrefEntity)
}
