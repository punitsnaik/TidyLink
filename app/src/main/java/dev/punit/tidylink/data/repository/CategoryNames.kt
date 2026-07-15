package dev.punit.tidylink.data.repository

/**
 * Pure category-name normalization, extracted from [LinkRepository] so it
 * can be unit-tested on the JVM.
 */
internal object CategoryNames {

    /** Case/punctuation/plural-insensitive identity for a category name. */
    fun key(name: String): String = name
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .removeSuffix("s")

    fun titleCase(name: String): String = name
        .split(Regex("\\s+"))
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
