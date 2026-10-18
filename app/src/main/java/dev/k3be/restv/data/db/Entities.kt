package dev.k3be.restv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A person using the app. Everything personal (favourites, order, history) hangs off an account. */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lastUsedAt: Long,
)

/** One Xtream Codes login. An account can hold several; the UI uses the first one for now. */
@Entity(tableName = "xtream_login", indices = [Index("accountId")])
data class LoginEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val host: String,
    val username: String,
    val password: String,
    val expiresAt: Long? = null,
    val maxConnections: Int = 1,
    val serverTimezone: String? = null,
    val lastSyncAt: Long? = null,
)

@Entity(tableName = "live_category", primaryKeys = ["loginId", "categoryId"])
data class LiveCategoryEntity(
    val loginId: Long,
    val categoryId: String,
    val rawName: String,
    /** Name with the country prefix removed, e.g. "Sports" for "UK | Sports". */
    val name: String,
    /** Country or group code from the prefix, e.g. "UK"; "" when there is none. */
    val groupKey: String,
    val groupLabel: String,
    val providerOrder: Int,
)

@Entity(
    tableName = "channel",
    primaryKeys = ["loginId", "streamId"],
    indices = [Index(value = ["loginId", "categoryId"]), Index(value = ["loginId", "variantKey"])],
)
data class ChannelEntity(
    val loginId: Long,
    val streamId: Long,
    val num: Int,
    val rawName: String,
    /** Clean display name, without country prefix or quality tags. */
    val name: String,
    val categoryId: String,
    val icon: String?,
    val epgChannelId: String?,
    val hasArchive: Boolean,
    /** 0 = SD, 1 = HD, 2 = FHD, 3 = 4K, read from the name. */
    val quality: Int,
    /** Same value for "Sports 1 HD", "Sports 1 FHD" and "Sports 1 4K", so variants group together. */
    val variantKey: String,
)

@Entity(tableName = "category_pref", primaryKeys = ["accountId", "loginId", "categoryId"])
data class CategoryPrefEntity(
    val accountId: Long,
    val loginId: Long,
    val categoryId: String,
    val position: Int,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
)

@Entity(tableName = "favorite", primaryKeys = ["accountId", "loginId", "streamId"])
data class FavoriteEntity(
    val accountId: Long,
    val loginId: Long,
    val streamId: Long,
    val addedAt: Long,
)

/** Remembers which stream format worked for a channel after an automatic fallback. */
@Entity(tableName = "channel_pref", primaryKeys = ["loginId", "streamId"])
data class ChannelPrefEntity(
    val loginId: Long,
    val streamId: Long,
    val format: String,
)

data class CategoryCount(val categoryId: String, val n: Int)
