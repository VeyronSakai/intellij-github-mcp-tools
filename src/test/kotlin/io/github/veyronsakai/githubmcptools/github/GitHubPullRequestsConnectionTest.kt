package io.github.veyronsakai.githubmcptools.github

import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Companion.normalizeRepositoryName
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Companion.sameServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class GitHubPullRequestsConnectionTest {
    @Test
    fun `repository names in various formats`() {
        assertEquals("octo/hello", normalizeRepositoryName("octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("  octo/hello/  "))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello"))
        assertEquals("octo/hello", normalizeRepositoryName("https://github.com/octo/hello.git"))
        assertEquals("octo/hello", normalizeRepositoryName("git@github.com:octo/hello.git"))
        assertEquals("octo/hello", normalizeRepositoryName("https://ghe.example.com/octo/hello"))
        assertEquals("hello", normalizeRepositoryName("hello"))
    }

    @Test
    fun `servers are compared without the scheme`() {
        assertTrue(sameServer(URI("https://github.com"), URI("http://github.com")))
        assertTrue(sameServer(URI("https://GitHub.com/"), URI("https://github.com")))
        assertTrue(sameServer(URI("https://ghe.example.com/api"), URI("https://ghe.example.com/api/")))
        assertFalse(sameServer(URI("https://github.com"), URI("https://ghe.example.com")))
        assertFalse(sameServer(URI("https://ghe.example.com:8443"), URI("https://ghe.example.com")))
        assertFalse(sameServer(URI("https://ghe.example.com/a"), URI("https://ghe.example.com/b")))
    }
}
