package dev.punit.tidylink.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubRepoServiceTest {

    private val service = GitHubRepoService()

    @Test
    fun `extractRepoPath extracts owner and repo correctly`() {
        val result = service.extractRepoPath("https://github.com/google/guava")
        assertEquals("google" to "guava", result)

        val withSlash = service.extractRepoPath("https://github.com/torvalds/linux/")
        assertEquals("torvalds" to "linux", withSlash)

        val withSubpath = service.extractRepoPath("https://github.com/punitsnaik/TidyLink/tree/main/app")
        assertEquals("punitsnaik" to "TidyLink", withSubpath)

        val withGit = service.extractRepoPath("https://github.com/rust-lang/rust.git")
        assertEquals("rust-lang" to "rust", withGit)
    }

    @Test
    fun `extractRepoPath rejects non-github and reserved paths`() {
        assertNull(service.extractRepoPath("https://gitlab.com/google/guava"))
        assertNull(service.extractRepoPath("https://github.com"))
        assertNull(service.extractRepoPath("https://github.com/explore"))
        assertNull(service.extractRepoPath("https://github.com/trending"))
        assertNull(service.extractRepoPath("https://github.com/topics"))
        assertNull(service.extractRepoPath("https://github.com/settings"))
    }
}
