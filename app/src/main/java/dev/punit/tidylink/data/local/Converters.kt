package dev.punit.tidylink.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores List<String> as a JSON array string. JSON (rather than a naive
 * comma-join) survives tags that themselves contain commas, and keeps the
 * stored value unambiguous for the FTS index.
 */
class Converters {

    @TypeConverter
    fun fromTagList(tags: List<String>): String = Json.encodeToString(tags)

    @TypeConverter
    fun toTagList(value: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
