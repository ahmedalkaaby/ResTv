package dev.k3be.restv.data.db

import androidx.room.Entity
import androidx.room.Index

/** Movie or series category from the provider. [kind] is "movie" or "series". */
@Entity(tableName = "vod_category", primaryKeys = ["loginId", "kind", "categoryId"])
data class VodCategoryEntity(
    val loginId: Long,
    val kind: String,
    val categoryId: String,
    val rawName: String,
    val name: String,
    val providerOrder: Int,
    /** "Apple TV+", "Netflix", … when the category belongs to a streaming platform. */
    val platform: String?,
)

@Entity(
    tableName = "movie",
    primaryKeys = ["loginId", "streamId"],
    indices = [Index(value = ["loginId", "categoryId"]), Index(value = ["loginId", "titleKey"])],
)
data class MovieEntity(
    val loginId: Long,
    val streamId: Long,
    val rawName: String,
    /** Clean title, without prefixes, year or quality tags. */
    val name: String,
    val year: Int?,
    val categoryId: String,
    val poster: String?,
    val rating: Float?,
    /** When the provider added it (ms). */
    val added: Long,
    val ext: String,
    /** Normalised title used to match IMDb lists and duplicates. */
    val titleKey: String,
    val quality: Int,
)

@Entity(
    tableName = "series",
    primaryKeys = ["loginId", "seriesId"],
    indices = [Index(value = ["loginId", "categoryId"]), Index(value = ["loginId", "titleKey"])],
)
data class SeriesEntity(
    val loginId: Long,
    val seriesId: Long,
    val rawName: String,
    val name: String,
    val year: Int?,
    val categoryId: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val rating: Float?,
    /** Last time the provider changed it (ms): new episodes push a show up. */
    val updated: Long,
    val titleKey: String,
)

/** Where you stopped in a movie or an episode. [kind] is "movie" or "episode". */
@Entity(
    tableName = "watch",
    primaryKeys = ["accountId", "loginId", "kind", "itemId"],
    indices = [Index(value = ["accountId", "loginId", "updatedAt"]), Index(value = ["accountId", "loginId", "seriesId"])],
)
data class WatchEntity(
    val accountId: Long,
    val loginId: Long,
    val kind: String,
    val itemId: Long,
    /** 0 for movies. */
    val seriesId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val ext: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && (fraction >= 0.92f || durationMs - positionMs < 3 * 60_000)
}

/** My List. [kind] is "movie" or "series". */
@Entity(tableName = "wish", primaryKeys = ["accountId", "loginId", "kind", "itemId"])
data class WishEntity(
    val accountId: Long,
    val loginId: Long,
    val kind: String,
    val itemId: Long,
    val addedAt: Long,
)
