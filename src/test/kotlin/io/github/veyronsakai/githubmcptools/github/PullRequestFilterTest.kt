// Tests use the GitHub plugin's internal / experimental classes on purpose.
@file:Suppress("UnstableApiUsage")

package io.github.veyronsakai.githubmcptools.github

import org.jetbrains.plugins.github.pullrequest.ui.filters.GHPRListSearchValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestFilterTest {
    @Test
    fun `empty filter clears everything`() {
        assertEquals(GHPRListSearchValue.EMPTY, PullRequestFilter().toSearchValue())
        assertEquals(PullRequestFilter(), PullRequestFilter.of(GHPRListSearchValue.EMPTY))
    }

    @Test
    fun `all fields are converted`() {
        val filter = PullRequestFilter(
            query = "fix",
            state = "merged",
            author = "alice",
            assignee = "bob",
            label = "bug",
            reviewState = "changes_requested",
            sort = "recently_updated",
        )
        val expected = GHPRListSearchValue(
            searchQuery = "fix",
            state = GHPRListSearchValue.State.MERGED,
            assignee = "bob",
            reviewState = GHPRListSearchValue.ReviewState.CHANGES_REQUESTED,
            author = "alice",
            label = "bug",
            sort = GHPRListSearchValue.Sort.RECENTLY_UPDATED,
        )
        assertEquals(expected, filter.toSearchValue())
        assertEquals(filter, PullRequestFilter.of(expected))
    }

    @Test
    fun `enum values ignore case, spaces and hyphens`() {
        val value = PullRequestFilter(state = " OPEN ", reviewState = "Changes-Requested", sort = "Least-Recently-Updated").toSearchValue()
        assertEquals(GHPRListSearchValue.State.OPEN, value.state)
        assertEquals(GHPRListSearchValue.ReviewState.CHANGES_REQUESTED, value.reviewState)
        assertEquals(GHPRListSearchValue.Sort.LEAST_RECENTLY_UPDATED, value.sort)
    }

    /** The tool descriptions list these names, so every enum value must round-trip. */
    @Test
    fun `every enum value round-trips`() {
        GHPRListSearchValue.State.values().forEach {
            assertEquals(it, PullRequestFilter(state = it.name.lowercase()).toSearchValue().state)
            assertEquals(it.name.lowercase(), PullRequestFilter.of(GHPRListSearchValue(state = it)).state)
        }
        GHPRListSearchValue.ReviewState.values().forEach {
            assertEquals(it, PullRequestFilter(reviewState = it.name.lowercase()).toSearchValue().reviewState)
        }
        GHPRListSearchValue.Sort.values().forEach {
            assertEquals(it, PullRequestFilter(sort = it.name.lowercase()).toSearchValue().sort)
        }
    }

    @Test
    fun `invalid enum value names the allowed values`() {
        val e = assertThrows(PullRequestFilter.InvalidValueException::class.java) {
            PullRequestFilter(state = "draft").toSearchValue()
        }
        assertTrue(e.message!!, e.message!!.contains("open, closed, merged"))
    }

    @Test
    fun `view kinds`() {
        assertEquals(setOf(PullRequestView.Kind.TIMELINE), PullRequestView.Kind.parseSet("timeline"))
        assertEquals(setOf(PullRequestView.Kind.DIFF), PullRequestView.Kind.parseSet(" DIFF "))
        assertEquals(PullRequestView.Kind.values().toSet(), PullRequestView.Kind.parseSet("all"))
        assertNull(PullRequestView.Kind.parseSet("both"))
    }
}
