package dev.punit.tidylink.shared.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkDaoTest {

    private lateinit var db: TidyLinkDb
    private lateinit var dao: LinkDao

    @Before
    fun setUp() {
        db = TidyLinkDb.inMemory()
        dao = db.linkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun link(
        id: String,
        url: String = "https://example.com/$id",
        title: String = "",
        category: String = "Unsorted",
        note: String = "",
        timestamp: Long = 0L,
        pinned: Boolean = false,
        modifiedAt: Long = 0L,
    ) = Link(
        id = id, url = url, title = title, category = category, note = note,
        timestamp = timestamp, pinned = pinned, modifiedAt = modifiedAt,
    )

    @Test
    fun upsert_then_observeAll_emits_the_link() = runTest {
        val l = link("a", title = "Kotlin docs", timestamp = 10, modifiedAt = 10)
        dao.upsert(l)
        assertEquals(listOf(l), dao.observeAll("").first())
    }

    @Test
    fun upsert_replaces_an_existing_row() = runTest {
        dao.upsert(link("a", title = "old"))
        dao.upsert(link("a", title = "new"))
        assertEquals("new", dao.getById("a")?.title)
        assertEquals(1, dao.observeAll("").first().size)
    }

    @Test
    fun delete_removes_the_row() = runTest {
        dao.upsert(link("a"))
        dao.delete("a")
        assertNull(dao.getById("a"))
    }

    @Test
    fun search_matches_title_url_note_and_category_but_not_others() = runTest {
        dao.upsert(link("t", title = "a kotlin guide", timestamp = 4))
        dao.upsert(link("u", url = "https://kotlinlang.org", timestamp = 3))
        dao.upsert(link("n", note = "learn kotlin later", timestamp = 2))
        dao.upsert(link("c", category = "Kotlin", timestamp = 1))
        dao.upsert(link("x", title = "swift only", url = "https://swift.org", timestamp = 0))
        val ids = dao.observeAll("kotlin").first().map { it.id }
        assertEquals(listOf("t", "u", "n", "c"), ids)
    }

    @Test
    fun ordering_is_pinned_first_then_timestamp_desc() = runTest {
        dao.upsert(link("old-pin", timestamp = 1, pinned = true))
        dao.upsert(link("new-pin", timestamp = 5, pinned = true))
        dao.upsert(link("newest", timestamp = 9))
        dao.upsert(link("oldest", timestamp = 2))
        val ids = dao.observeAll("").first().map { it.id }
        assertEquals(listOf("new-pin", "old-pin", "newest", "oldest"), ids)
    }

    @Test
    fun changedSince_is_a_strict_boundary() = runTest {
        dao.upsert(link("before", modifiedAt = 99))
        dao.upsert(link("at", modifiedAt = 100))
        dao.upsert(link("after", modifiedAt = 101))
        assertEquals(listOf("after"), dao.changedSince(100).map { it.id })
    }
}
