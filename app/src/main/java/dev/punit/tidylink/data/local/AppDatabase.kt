package dev.punit.tidylink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LinkEntity::class, LinkFtsEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun linkDao(): LinkDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Every migration, in order. Exposed (not private) so MigrationTest
         * exercises exactly what ships - a test with its own copy of the
         * migration list would drift from this one.
         */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /** v2: indexed dedupeKey (fast duplicate checks) + pinned flag. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` ADD COLUMN `dedupeKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `links` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_links_dedupeKey` ON `links` (`dedupeKey`)"
                )
            }
        }

        /** v3: scrape bookkeeping (lastScrapedAt, scrapeAttempts). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` ADD COLUMN `lastScrapedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `links` ADD COLUMN `scrapeAttempts` INTEGER NOT NULL DEFAULT 0")
                // Rows that already carry scraped details were obviously
                // scraped at least once - mark them so the enrichment sweep
                // doesn't re-fetch the whole library after this upgrade.
                db.execSQL(
                    """
                    UPDATE `links` SET `lastScrapedAt` = `timestamp`, `scrapeAttempts` = 1
                    WHERE `imageUrl` IS NOT NULL OR `description` != ''
                    """
                )
            }
        }

        /**
         * v4: drops the never-read lastScrapedAt column. Rowids are untouched
         * so the external-content FTS index stays valid, and no index/trigger
         * references the column.
         *
         * DROP COLUMN needs SQLite 3.35+, which means API 34+. minSdk is 29, so
         * this statement would throw on Android 10–13 - it is unreachable there
         * in practice: v3 only ever existed on this developer's device, nothing
         * with that schema was ever published, and a fresh install creates v4
         * outright without running any migration. It is left as-is rather than
         * rewritten blind, because the recreate-the-table alternative can't be
         * verified without a device (MigrationTest is instrumented).
         *
         * The rule this leaves behind: **a future migration must not use DROP
         * COLUMN** while minSdk < 34. Use the 12-step recreate instead, and run
         * `connectedDebugAndroidTest` on an API 29 device before shipping it.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` DROP COLUMN `lastScrapedAt`")
            }
        }

        /**
         * v5: `isRead` (read/unread state) and `note` (the user's own text).
         *
         * ADD COLUMN only on `links`, so unlike MIGRATION_3_4 this is safe
         * all the way down to minSdk 29 - the SQLite 3.35 / API 34 rule only
         * bites DROP COLUMN.
         *
         * The FTS work is the part that isn't obvious. `note` joins the
         * search index, and **an FTS4 virtual table cannot be ALTERed to add
         * a column** - it has to be dropped and recreated with the new column
         * list. Column order below must match [LinkFtsEntity] exactly or
         * Room's schema validation rejects the result.
         *
         * Recreating leaves the index EMPTY (an external-content table stores
         * no rows of its own), so the rebuild is mandatory, not tidiness:
         * without it every pre-upgrade link vanishes from search entirely.
         *
         * Sync triggers are deliberately not touched here. Room drops them in
         * onPreMigrate and recreates them in onPostMigrate around this call,
         * which is also why MIGRATION_1_2 never had to create them.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` ADD COLUMN `isRead` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `links` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP TABLE IF EXISTS `links_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `links_fts` USING FTS4(" +
                        "`title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                        "`aiSummary` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "tokenize=unicode61, content=`links`)"
                )
                db.execSQL("INSERT INTO `links_fts`(`links_fts`) VALUES('rebuild')")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tidylink.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
