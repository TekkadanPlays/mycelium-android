package social.mycelium.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedProfileEntity::class, CachedNip65Entity::class, CachedFollowListEntity::class, CachedEventEntity::class, CachedNip11Entity::class, CachedEmojiPackEntity::class, CachedNotificationEntity::class],
    version = 11,
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

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_notifications ADD COLUMN targetNoteAuthorId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_notifications ADD COLUMN verificationStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add isReply column for SQL-level reply filtering (windowed paging)
                db.execSQL("ALTER TABLE cached_events ADD COLUMN isReply INTEGER NOT NULL DEFAULT 0")
                // Composite index for windowed feed queries: WHERE isReply=0 AND kind IN (...) ORDER BY createdAt
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_events_isReply_kind_createdAt ON cached_events(isReply, kind, createdAt)")
                // Backfill: mark kind-1 events as replies if they have e-tags with root/reply markers.
                // We can't run NIP-10 detection in SQL, so mark ALL kind-1 events with e-tags as
                // potentially replies. The next cold-start will re-evaluate via convertEventToNote.
                // For now, leave all as isReply=0 (default) — they'll be correctly set on next ingest.
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
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
