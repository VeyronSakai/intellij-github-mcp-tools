package io.github.veyronsakai.githubmcptools.github

import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Companion.normalizeRepositoryName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPullRequestsConnectionTest {
    @Test
    fun `repository names in various formats`() {
        assertEquals("octo/hello", normalizeRepositoryName("octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("  octo/hello/  "))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello.git"))
        assertEquals("octo/hello", normalizeRepositoryName("git@github.com:octo/hello.git"))
        assertEquals("octo/hello", normalizeRepositoryName("https://ghe.example.com/octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("ssh://git@github.com/octo/hello.git"))
        assertEquals("hello", normalizeRepositoryName("hello"))
    }

    @Test
    fun `pull request and other GitHub page URLs give the repository`() {
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello/pull/12"))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello/pull/12/files"))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello/tree/main/src"))
        assertEquals("octo/hello", normalizeRepositoryName("github.com/octo/hello/pull/12"))
        assertEquals("octo/hello", normalizeRepositoryName("github.com/octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("octo/hello/pull/12"))
        assertEquals("my-org/repo.name", normalizeRepositoryName("https://github.com/my-org/repo.name.git"))
    }

    @Test
    fun `query, fragment and hosts with a port are ignored`() {
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello?tab=readme"))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello/pull/12#discussion_r1"))
        assertEquals("octo/hello", normalizeRepositoryName("localhost:8080/octo/hello"))
    }

    @Test
    fun `repository info comparison ignores case`() {
        val remote = GitHubRepositoryInfo("Octo/Hello", "https://github.com/Octo/Hello")
        assertTrue(remote.isSameAs(GitHubRepositoryInfo("octo/hello", "https://github.com/octo/hello")))
        assertFalse(remote.isSameAs(GitHubRepositoryInfo("octo/other", "https://github.com/octo/other")))
        assertFalse(remote.isSameAs(null))
    }
}
