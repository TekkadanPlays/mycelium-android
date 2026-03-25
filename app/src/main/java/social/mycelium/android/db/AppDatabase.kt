package social.mycelium.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedProfileEntity::class, CachedNip65Entity::class, CachedFollowListEntity::class, CachedEventEntity::class, CachedNip11Entity::class, CachedEmojiPackEntity::class, CachedNotificationEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun nip65Dao(): Nip65Dao
    abstract fun followListDao(): FollowListDao
    abstract fun eventDao(): EventDao
    abstract fun nip11Dao(): Nip11Dao
    abstract fun emojiPackDao(): EmojiPackDao
    abstract fun notificationDao(): NotificationDao

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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_notifications (
                        id TEXT NOT NULL PRIMARY KEY,
                        ownerPubkey TEXT NOT NULL,
                        type TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sortTimestamp INTEGER NOT NULL,
                        authorId TEXT,
                        authorDisplayName TEXT,
                        authorUsername TEXT,
                        authorAvatarUrl TEXT,
                        targetNoteId TEXT,
                        rootNoteId TEXT,
                        replyNoteId TEXT,
                        replyKind INTEGER,
                        reactionEmoji TEXT,
                        reactionEmojisJson TEXT,
                        zapAmountSats INTEGER NOT NULL DEFAULT 0,
                        actorPubkeysJson TEXT,
                        customEmojiUrlsJson TEXT,
                        customEmojiUrl TEXT,
                        badgeName TEXT,
                        badgeImageUrl TEXT,
                        pollId TEXT,
                        pollQuestion TEXT,
                        pollOptionCodesJson TEXT,
                        pollOptionLabelsJson TEXT,
                        pollAllOptionsJson TEXT,
                        pollIsMultipleChoice INTEGER NOT NULL DEFAULT 0,
                        rawContent TEXT,
                        noteContent TEXT,
                        targetNoteContent TEXT,
                        cachedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_notifications_ownerPubkey ON cached_notifications(ownerPubkey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_notifications_sortTimestamp ON cached_notifications(sortTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_notifications_type ON cached_notifications(type)")
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
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
