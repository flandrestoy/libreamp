package dev.libreamp.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}

@Database(
    entities = [PlaylistEntryEntity::class, PlaylistGroupEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistEntryDao(): PlaylistEntryDao
    abstract fun playlistGroupDao(): PlaylistGroupDao

    companion object {
        /**
         * Groups arrive additively: every existing entry keeps its order and becomes a
         * loose top-level track. Written out rather than left to the destructive
         * fallback because by v3 the table holds a playlist the user built by hand.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`orderIndex` INTEGER NOT NULL, " +
                        "`collapsed` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE `playlist_entries` ADD COLUMN `groupId` INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "libreamp.db"
            ).addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
