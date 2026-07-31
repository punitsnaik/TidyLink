package dev.punit.tidylink.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A deleted link, held for 90 days before being purged.
 *
 * A SEPARATE TABLE rather than a `deletedAt` column on `links`, and that
 * is the whole point of the design. Fourteen call sites read `links`;
 * twelve of them would need `AND deletedAt IS NULL`, and every omission
 * would be a *silent* leak - a trashed row inflating a category tile, or
 * landing in a JSON export the user then restores from. The two easiest to
 * miss are the dangerous ones: `mergeDuplicates()` reads `getAllOnce()`
 * and would happily merge a trashed row back into a live one, and
 * `getByDedupeKey()` gates save-time dedupe. With a separate table those
 * predicates don't exist, so export, backup, search, tiles, tag counts and
 * the enrichment sweep exclude trash **structurally** rather than by
 * remembering to.
 *
 * The row is kept as serialized JSON rather than as mirrored columns.
 * [LinkEntity] is already `@Serializable` and the repository already has a
 * `Json` configured with `ignoreUnknownKeys`, so this table never needs a
 * migration of its own: a future column on `links` simply decodes at its
 * default when an older trashed row is restored.
 */
@Entity(tableName = "trashed_links")
data class TrashedLinkEntity(
    @PrimaryKey val id: String,
    /** The whole [LinkEntity], serialized. */
    val json: String,
    val deletedAt: Long,
)
