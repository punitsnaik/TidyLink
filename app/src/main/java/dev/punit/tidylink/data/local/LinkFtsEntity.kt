package dev.punit.tidylink.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Full-text search index over [LinkEntity].
 *
 * Note: Room supports FTS3/FTS4 natively (not FTS5). FTS4 with the unicode61
 * tokenizer gives fast, diacritic-insensitive full-text search. Because
 * [contentEntity] is set, Room auto-generates triggers that keep this table
 * in sync with `links` - no manual dual writes needed.
 */
@Fts4(contentEntity = LinkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "links_fts")
data class LinkFtsEntity(
    val title: String,
    val description: String,
    val category: String,
    val aiSummary: String,
    /**
     * Appended last on purpose: an FTS4 table can't be ALTERed at all, so
     * MIGRATION_4_5 (adding this column) and MIGRATION_6_7 (dropping the
     * old `tags` column) both drop and recreate this table. The recreated
     * column order has to match this declaration exactly or Room's schema
     * validation fails.
     */
    val note: String,
)
