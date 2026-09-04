package dev.punit.tidylink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LinkEntity::class, LinkFtsEntity::class, TrashedLinkEntity::class,
        PeerEntity::class, SyncStateEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun linkDao(): LinkDao

    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Every migration, in order. Exposed (not private) so MigrationTest
         * exercises exactly what ships - a test with its own copy of the
         * migration list would drift from this one.
         */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            )

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

        /**
         * v6: the trash table.
         *
         * One CREATE TABLE and nothing else - `links` is untouched, so
         * there is no FTS drop-and-recreate here (unlike MIGRATION_4_5) and
         * no DROP COLUMN (unlike MIGRATION_3_4). That is a consequence of
         * trash being a separate table rather than a `deletedAt` column,
         * not a coincidence.
         *
         * Must match the generated 6.json exactly; Room validates it.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trashed_links` (" +
                        "`id` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v7: drops the `tags` column. Tag filtering collided with the
         * category filter in the UI and was removed along with the column
         * that fed it.
         *
         * This is the 12-step recreate, NOT `ALTER TABLE ... DROP COLUMN` -
         * that needs SQLite 3.35 (API 34) and minSdk here is 29. See
         * MIGRATION_3_4's comment for the rule this follows.
         *
         * Order matters and is not cosmetic:
         *
         * 1. `links_fts` goes first. It is an external-content table whose
         *    `content=links` points at the table about to be dropped, and
         *    it also carries a `tags` column of its own, so it has to be
         *    rebuilt either way.
         * 2. `links` is copied column-by-column into a new table without
         *    `tags`, then renamed into place. The column list in the INSERT
         *    is written out in full rather than `SELECT *`, so a future
         *    column added above `tags` cannot silently shift values into the
         *    wrong slots.
         * 3. The dedupeKey index is recreated - `DROP TABLE` takes its
         *    indices with it.
         * 4. The FTS index is repopulated. Recreating leaves an
         *    external-content table EMPTY, so without the rebuild every
         *    existing link vanishes from search. MIGRATION_4_5 has the same
         *    line for the same reason.
         *
         * Rowids are reassigned by the copy, which is exactly why the
         * rebuild must come after it: the FTS index references `links` by
         * rowid, and any index built before the copy would point at the old
         * numbering.
         *
         * Sync triggers are not touched here. Room drops them in
         * onPreMigrate and recreates them in onPostMigrate around this call.
         *
         * Must match the generated 7.json exactly; Room validates it.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `links_fts`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_links` (" +
                        "`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `imageUrl` TEXT, " +
                        "`category` TEXT NOT NULL, `aiSummary` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`dedupeKey` TEXT NOT NULL DEFAULT '', " +
                        "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                        "`scrapeAttempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`isRead` INTEGER NOT NULL DEFAULT 0, " +
                        "`note` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `_new_links` (" +
                        "`id`, `url`, `title`, `description`, `imageUrl`, `category`, " +
                        "`aiSummary`, `timestamp`, `dedupeKey`, `pinned`, `scrapeAttempts`, " +
                        "`isRead`, `note`) " +
                        "SELECT `id`, `url`, `title`, `description`, `imageUrl`, `category`, " +
                        "`aiSummary`, `timestamp`, `dedupeKey`, `pinned`, `scrapeAttempts`, " +
                        "`isRead`, `note` FROM `links`"
                )
                db.execSQL("DROP TABLE `links`")
                db.execSQL("ALTER TABLE `_new_links` RENAME TO `links`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_links_dedupeKey` ON `links` (`dedupeKey`)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `links_fts` USING FTS4(" +
                        "`title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, `aiSummary` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, tokenize=unicode61, content=`links`)"
                )
                db.execSQL("INSERT INTO `links_fts`(`links_fts`) VALUES('rebuild')")
            }
        }

        /**
         * v8: drops `isRead`. Same 12-step recreate as MIGRATION_6_7, and for
         * the same reason - `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35
         * (API 34) and minSdk is 29.
         *
         * One difference worth stating so nobody "optimises" it away:
         * `isRead` is NOT one of the FTS columns (the index covers title,
         * description, category, aiSummary, note), so `links_fts`'s own
         * definition is byte-identical to v7. It still has to be dropped
         * before the copy and rebuilt after it, because the copy reassigns
         * every rowid and the external-content index references `links` by
         * rowid. Skipping the rebuild silently empties search for every
         * pre-upgrade link - the MIGRATION_4_5 / MIGRATION_6_7 trap.
         *
         * The INSERT's column list is spelled out rather than `SELECT *` so a
         * future column added above the old `isRead` position cannot shift
         * values one slot sideways.
         *
         * Existing read state is destroyed. That is the point of the change.
         *
         * Must match the generated 8.json exactly; Room validates it.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `links_fts`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_links` (" +
                        "`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `imageUrl` TEXT, " +
                        "`category` TEXT NOT NULL, `aiSummary` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`dedupeKey` TEXT NOT NULL DEFAULT '', " +
                        "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                        "`scrapeAttempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`note` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `_new_links` (" +
                        "`id`, `url`, `title`, `description`, `imageUrl`, `category`, " +
                        "`aiSummary`, `timestamp`, `dedupeKey`, `pinned`, `scrapeAttempts`, " +
                        "`note`) " +
                        "SELECT `id`, `url`, `title`, `description`, `imageUrl`, `category`, " +
                        "`aiSummary`, `timestamp`, `dedupeKey`, `pinned`, `scrapeAttempts`, " +
                        "`note` FROM `links`"
                )
                db.execSQL("DROP TABLE `links`")
                db.execSQL("ALTER TABLE `_new_links` RENAME TO `links`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_links_dedupeKey` ON `links` (`dedupeKey`)"
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `links_fts` USING FTS4(" +
                        "`title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, `aiSummary` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, tokenize=unicode61, content=`links`)"
                )
                db.execSQL("INSERT INTO `links_fts`(`links_fts`) VALUES('rebuild')")
            }
        }

        /**
         * v9: device sync (`project-docs/PRD-android-sync.md`). Adds
         * `modifiedAt` to `links` - the LWW clock `changedSince` reads - plus
         * two new tables, `peers` and `sync_state`, that hold no data of
         * their own to migrate.
         *
         * `modifiedAt` is a plain `ADD COLUMN`, not the 12-step recreate:
         * unlike MIGRATION_3_4/6_7/7_8 this never DROPs a column, so the
         * SQLite 3.35 / API 34 restriction that forced those recreates does
         * not apply here - see MIGRATION_4_5's comment for the same
         * reasoning. `links_fts` is untouched for the same reason: nothing
         * about this migration reassigns rowids, since the table is never
         * dropped.
         *
         * Backfill sets `modifiedAt = timestamp` for every existing row -
         * "last touched now" would be a lie for links nobody has edited
         * since they were saved, and would make a fresh v9 upgrade look like
         * a burst of edits to the very first peer it syncs with.
         *
         * `peers`/`sync_state` mirror desktop/shared's `Peer`/`SyncState`
         * tables field-for-field - the two must agree, since a `PeerEntity`
         * row here is compared against a wire-decoded `Peer` on the Mac side
         * of every handshake. `publicKey` is a BLOB (X.509 SubjectPublicKeyInfo
         * bytes); Room maps `ByteArray` to BLOB with no `@ColumnInfo` needed.
         *
         * Must match the generated 9.json exactly; Room validates it. Not
         * yet generated - needs a Mac build, same as every schema bump here.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` ADD COLUMN `modifiedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `links` SET `modifiedAt` = `timestamp`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peers` (" +
                        "`deviceId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`publicKey` BLOB NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`deviceId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                        "`peerId` TEXT NOT NULL, `lastSyncAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`peerId`))"
                )
            }
        }

        /** v10: Android-local redirect and direct-relation enrichment. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `links` ADD COLUMN `resolvedUrl` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `links` ADD COLUMN `relatedLinksJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `links` ADD COLUMN `relatedLinksScannedAt` INTEGER NOT NULL DEFAULT 0")
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
