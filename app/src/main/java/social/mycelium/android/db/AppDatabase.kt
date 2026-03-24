package social.mycelium.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedProfileEntity::class, CachedNip65Entity::class, CachedFollowListEntity::class, CachedEventEntity::class, CachedNip11Entity::class, CachedEmojiPackEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun nip65Dao(): Nip65Dao
    abstract fun followListDao(): FollowListDao
    abstract fun eventDao(): EventDao
    abstract fun nip11Dao(): Nip11Dao
    abstract fun emojiPackDao(): EmojiPackDao

    companion object {
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_events ADD COLUMN relayUrls TEXT DEFAULT NULL")
                db.execSQL("UPDATE cached_events SET relayUrls = relayUrl WHERE relayUrl IS NOT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_events ADD COLUMN referencedEventId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_events_referencedEventId ON cached_events(referencedEventId)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mycelium_cache.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
