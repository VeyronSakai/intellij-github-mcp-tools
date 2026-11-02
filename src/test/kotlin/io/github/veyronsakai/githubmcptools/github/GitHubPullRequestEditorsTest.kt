// Tests use the GitHub plugin's internal / experimental classes on purpose.
@file:Suppress("UnstableApiUsage")

package io.github.veyronsakai.githubmcptools.github

import com.intellij.vcs.editor.ComplexPathVirtualFileSystem
import com.intellij.vcs.editor.GsonComplexPathSerializer
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GHRepositoryPath
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPullRequestEditorsTest {
    /**
     * Same fields as the GitHub plugin's `GHPRVirtualFileSystem.GHPRFilePath` (Kotlin `internal`, so it is mirrored here).
     * Serialized with the platform's real serializer, so the nested JSON comes from the real GitHub classes.
     */
    private data class FilePath(
        override val sessionId: String,
        override val projectHash: String,
        val repository: GHRepositoryCoordinates,
        val prId: GHPRIdentifier?,
        val isDiff: Boolean,
    ) : ComplexPathVirtualFileSystem.ComplexPath

    private val repository = GHRepositoryCoordinates(GithubServerPath("github.com"), GHRepositoryPath("octo", "hello"))

    private fun path(prId: GHPRIdentifier?, isDiff: Boolean): String =
        GsonComplexPathSerializer(FilePath::class.java).serialize(FilePath("session", "hash", repository, prId, isDiff))

    @Test
    fun `timeline tab`() {
        val view = GitHubPullRequestEditors.parsePath(path(GHPRIdentifier("PR_abc", 12), isDiff = false), "title")
        assertEquals(PullRequestView("octo/hello", 12, PullRequestView.Kind.TIMELINE, "title"), view)
    }

    @Test
    fun `diff tab`() {
        val view = GitHubPullRequestEditors.parsePath(path(GHPRIdentifier("PR_abc", 12), isDiff = true), "title")
        assertEquals(PullRequestView("octo/hello", 12, PullRequestView.Kind.DIFF, "title"), view)
    }

    @Test
    fun `diff of a new pull request has no number`() {
        val view = GitHubPullRequestEditors.parsePath(path(null, isDiff = true), "title")
        assertEquals(PullRequestView("octo/hello", null, PullRequestView.Kind.DIFF, "title"), view)
    }

    @Test
    fun `invalid paths are ignored`() {
        assertNull(GitHubPullRequestEditors.parsePath("not json", "title"))
        assertNull(GitHubPullRequestEditors.parsePath("[1, 2]", "title"))
        assertNull(GitHubPullRequestEditors.parsePath("", "title"))
    }

    @Test
    fun `unexpected value types do not throw`() {
        val view = GitHubPullRequestEditors.parsePath(
            """{"repository": "octo/hello", "prId": "PR_abc", "isDiff": "yes"}""", "title"
        )
        assertEquals(PullRequestView(null, null, PullRequestView.Kind.TIMELINE, "title"), view)
    }
}
