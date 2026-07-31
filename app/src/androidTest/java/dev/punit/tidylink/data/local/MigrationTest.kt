package dev.punit.tidylink.data.local

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration coverage for [AppDatabase]. Needs a device or emulator:
 *
 *     ./gradlew connectedDebugAndroidTest
 *
 * These are deliberately INSTRUMENTED rather than Robolectric unit tests.
 * MIGRATION_3_4 uses `ALTER TABLE ... DROP COLUMN`, which SQLite only supports
 * from 3.35.0; Robolectric bundles 3.32.2 (Android 11's SQLite) and fails with
 * `near "DROP": syntax error`. SQLite 3.35+ arrives with API 34, so a real
 * device on API 34+ is the only place this migration can be asserted at all.
 *
 * Since minSdk dropped to 29, that has a consequence worth stating: running
 * this suite on an API 29–33 device WILL fail on the 3→4 cases. That's the
 * test being honest, not flaky - see MIGRATION_3_4's comment for why the
 * statement is nonetheless unreachable in production.
 *
 * Method names here are snake_case, NOT Kotlin's backticked
 * `names with spaces`. Spaces in a SimpleName need DEX 040, which the dexer
 * only emits at minSdk 30+; at minSdk 29 it emits DEX 039 and D8 hard-fails
 * the androidTest build. Unit tests under src/test are JVM-only, never dexed,
 * and keep their backticks.
 *
 * The target schema is validated by Room itself: runMigrationsAndValidate
 * compares the migrated database against the generated 4.json / 5.json and
 * throws if anything differs.
 *
 * MIGRATION_4_5 recreates the FTS table (an FTS4 table can't be ALTERed to
 * add the new `note` column). If a v5 test fails complaining about missing
 * FTS sync triggers rather than about content, suspect the harness rather
 * than the migration: Room drops those triggers in onPreMigrate and
 * recreates them in onPostMigrate, and the migration deliberately doesn't
 * manage them itself.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate_2_to_4_keeps_rows_and_lands_on_the_generated_v4_schema() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO links VALUES
                ('rich','https://a.com','Real Title','A description','http://img',
                 'Dev','["k"]','summary',100,'a.com',0)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS)

        assertEquals(listOf("rich"), db.query("SELECT id FROM links").ids())
        assertTrue("lastScrapedAt should be gone at v4", "lastScrapedAt" !in db.columnsOf("links"))
    }

    @Test
    fun migrate_3_to_4_drops_lastScrapedAt_without_touching_anything_else() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO links VALUES
                ('a','https://a.com','T','D','http://img','Dev','["k"]','s',100,'a.com',1,100,2)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS)

        val row = db.query("SELECT pinned, scrapeAttempts, dedupeKey FROM links").use {
            it.moveToFirst()
            Triple(it.getInt(0), it.getInt(1), it.getString(2))
        }
        assertEquals("pinned survives the column drop", 1, row.first)
        assertEquals("scrapeAttempts survives the column drop", 2, row.second)
        assertEquals("dedupeKey survives the column drop", "a.com", row.third)
    }

    /**
     * The v1 database is built from raw DDL rather than a hand-written
     * 1.json. v1 shipped with `exportSchema = false`, so no 1.json exists,
     * and MigrationTestHelper.createDatabase() would need one - including a
     * correct `identityHash`, which only Room's compiler can produce. Inventing
     * that hash would risk a test that passes for the wrong reason. The DDL
     * below is taken verbatim from the v1 entity definitions (commit 8ad5c92^:
     * LinkEntity without dedupeKey/pinned/scrapeAttempts, plus LinkFtsEntity,
     * which v1 did already have - which is why MIGRATION_1_2 does not create
     * links_fts). Room still validates the v4 *result* against 4.json.
     */
    @Test
    fun migrate_1_to_4_backfills_scrape_bookkeeping_and_preserves_the_fts_index() {
        createV1Database()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS)

        // v3 backfill: rows carrying scraped detail are marked as scraped once,
        // so the enrichment sweep doesn't re-fetch the whole library. A bare
        // placeholder stays at 0 and is picked up by getScrapeCandidates.
        val attempts = db.query("SELECT id, scrapeAttempts FROM links ORDER BY id")
            .use { c ->
                buildMap {
                    while (c.moveToNext()) put(c.getString(0), c.getInt(1))
                }
            }
        assertEquals(mapOf("placeholder" to 0, "rich" to 1, "scraped-no-og" to 1), attempts)

        // The external-content FTS index keys off links.rowid. MIGRATION_3_4
        // drops a column, which rewrites rows - if rowids shifted, every search
        // result would silently point at the wrong link.
        val hits = db.query(
            "SELECT links.title FROM links JOIN links_fts ON links.rowid = links_fts.rowid " +
                "WHERE links_fts MATCH 'kotl*'"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("Kotlin Compose Guide"), hits)
    }

    @Test
    fun migrate_4_to_5_defaults_the_new_columns_and_keeps_rows() {
        createV4Database()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, *ALL_MIGRATIONS)

        val row = db.query("SELECT id, isRead, note, pinned FROM links").use {
            it.moveToFirst()
            listOf(it.getString(0), it.getInt(1), it.getString(2), it.getInt(3))
        }
        assertEquals("existing row survives", "kot", row[0])
        assertEquals("isRead defaults to unread", 0, row[1])
        assertEquals("note defaults to empty", "", row[2])
        assertEquals("pinned is untouched by the FTS recreate", 1, row[3])
    }

    /**
     * The regression this migration exists to avoid.
     *
     * An FTS4 table can't gain a column by ALTER, so MIGRATION_4_5 drops and
     * recreates links_fts. A recreated external-content table starts EMPTY -
     * so without the `rebuild` command every link saved before the upgrade
     * would silently disappear from search while still sitting in the list.
     * Delete the rebuild line and this test fails; nothing else would.
     */
    @Test
    fun migrate_4_to_5_rebuilds_the_fts_index_so_existing_links_stay_searchable() {
        createV4Database()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, *ALL_MIGRATIONS)

        val hits = db.query(
            "SELECT links.title FROM links JOIN links_fts ON links.rowid = links_fts.rowid " +
                "WHERE links_fts MATCH 'kotl*'"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("Kotlin Compose Guide"), hits)
    }

    /**
     * The note column has to be in the index too, not just on the row - a
     * note is the user's own words and the highest-signal thing to search
     * for. This goes through a real Room instance so the FTS sync triggers
     * Room recreates in onPostMigrate are the ones under test.
     */
    @Test
    fun notes_written_after_migrating_are_searchable() {
        createV4Database()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *ALL_MIGRATIONS).close()

        val db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            AppDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        db.openHelper.writableDatabase.execSQL(
            "UPDATE links SET note = 'reread before the offsite' WHERE id = 'kot'"
        )

        val query = LinkQueryBuilder.build("offsite", category = null, sort = SortOrder.NEWEST)
        val hits = db.openHelper.readableDatabase.query(query).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("id"))) }
        }
        assertEquals("a word only in the note must find the link", listOf("kot"), hits)
    }

    /**
     * v6 adds the trash table and touches nothing else - that's the payoff
     * of trash being a separate table rather than a `deletedAt` column on
     * `links`. If a future change to this migration starts modifying
     * `links`, the FTS assertion here is what should catch it.
     */
    @Test
    fun migrate_5_to_6_adds_the_trash_table_and_leaves_links_alone() {
        createV4Database()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *ALL_MIGRATIONS).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *ALL_MIGRATIONS)

        assertEquals(
            listOf("deletedAt", "id", "json"),
            db.columnsOf("trashed_links").sorted(),
        )
        assertEquals("the existing link is untouched", listOf("kot"), db.query("SELECT id FROM links").ids())
        // Nothing recreated links_fts, so the index still resolves.
        val hits = db.query(
            "SELECT links.title FROM links JOIN links_fts ON links.rowid = links_fts.rowid " +
                "WHERE links_fts MATCH 'kotl*'"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("Kotlin Compose Guide"), hits)
    }

    /** A fresh v4 install must reach v6 in one go, not just v4 -> v5. */
    @Test
    fun migrate_4_to_6_runs_both_new_migrations_in_sequence() {
        createV4Database()

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *ALL_MIGRATIONS)

        assertTrue("isRead should exist at v6", "isRead" in db.columnsOf("links"))
        assertTrue("note should exist at v6", "note" in db.columnsOf("links"))
        assertTrue("trash table should exist at v6", db.columnsOf("trashed_links").isNotEmpty())
    }

    /** Search must still work through a live Room instance after migrating. */
    @Test
    fun database_opens_and_searches_after_migrating_from_v1() {
        createV1Database()
        helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS).close()

        val db = androidx.room.Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            AppDatabase::class.java,
            TEST_DB,
        ).addMigrations(*ALL_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        val query = LinkQueryBuilder.build("kotl", category = null, sort = SortOrder.TITLE_AZ)
        val hits = db.openHelper.readableDatabase.query(query).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("title"))) }
        }
        assertEquals(listOf("Kotlin Compose Guide"), hits)
    }

    /**
     * A v4 database with one searchable row. Built through
     * MigrationTestHelper (unlike [createV1Database]) because 4.json exists,
     * so Room can create the schema itself.
     */
    private fun createV4Database() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO links
                (id, url, title, description, imageUrl, category, tags, aiSummary,
                 timestamp, dedupeKey, pinned, scrapeAttempts)
                VALUES
                ('kot','https://kotlinlang.org','Kotlin Compose Guide','Building UI',
                 'http://img','Dev','["kotlin"]','A guide',100,'kotlinlang.org',1,1)
                """.trimIndent()
            )
        }
    }

    private fun createV1Database() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getDatabasePath(TEST_DB).also { it.parentFile?.mkdirs(); it.delete() }

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_DDL.forEach(db::execSQL)
                    V1_ROWS.forEach(db::execSQL)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    private fun SupportSQLiteDatabase.columnsOf(table: String): List<String> =
        query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }

    private fun android.database.Cursor.ids(): List<String> =
        use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private companion object {
        const val TEST_DB = "migration-test.db"

        val ALL_MIGRATIONS: Array<Migration> = AppDatabase.ALL_MIGRATIONS

        /** Verbatim v1 schema (commit 8ad5c92^, exportSchema was false). */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `links` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `description` TEXT NOT NULL, `imageUrl` TEXT, " +
                "`category` TEXT NOT NULL, `tags` TEXT NOT NULL, `aiSummary` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `links_fts` USING FTS4(`title` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `category` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                "`aiSummary` TEXT NOT NULL, tokenize=unicode61, content=`links`)",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_links_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `links` BEGIN DELETE FROM `links_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_links_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `links` BEGIN DELETE FROM `links_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_links_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `links` BEGIN INSERT INTO `links_fts`(`docid`, `title`, " +
                "`description`, `category`, `tags`, `aiSummary`) VALUES (NEW.`rowid`, NEW.`title`, " +
                "NEW.`description`, NEW.`category`, NEW.`tags`, NEW.`aiSummary`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_links_fts_AFTER_INSERT " +
                "AFTER INSERT ON `links` BEGIN INSERT INTO `links_fts`(`docid`, `title`, " +
                "`description`, `category`, `tags`, `aiSummary`) VALUES (NEW.`rowid`, NEW.`title`, " +
                "NEW.`description`, NEW.`category`, NEW.`tags`, NEW.`aiSummary`); END",
        )

        /** A scraped row, a bare placeholder, and a page with no OG image. */
        val V1_ROWS = listOf(
            "INSERT INTO links VALUES('rich','https://kotlinlang.org','Kotlin Compose Guide'," +
                "'Building UI','http://img','Dev','[\"kotlin\"]','A guide',100)",
            "INSERT INTO links VALUES('placeholder','https://b.com','b.com','',NULL," +
                "'Uncategorized','[]','',200)",
            "INSERT INTO links VALUES('scraped-no-og','https://c.com','Real Title C'," +
                "'Has description',NULL,'Dev','[\"k\"]','summary',300)",
        )
    }
}
