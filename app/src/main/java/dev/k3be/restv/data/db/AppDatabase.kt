package dev.k3be.restv.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        LoginEntity::class,
        LiveCategoryEntity::class,
        ChannelEntity::class,
        CategoryPrefEntity::class,
        FavoriteEntity::class,
        ChannelPrefEntity::class,
        VodCategoryEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        WatchEntity::class,
        WishEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // Movies & series only add tables, so favourites, category order and history carry over.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun logins(): LoginDao
    abstract fun live(): LiveDao
    abstract fun prefs(): PrefDao
    abstract fun vod(): VodDao
    abstract fun watch(): WatchDao
    abstract fun wish(): WishDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "restv.db")
                // Catalogue data is re-downloadable; personal data migrations get written once we ship v1.
                .fallbackToDestructiveMigration()
                .build()
    }
}
