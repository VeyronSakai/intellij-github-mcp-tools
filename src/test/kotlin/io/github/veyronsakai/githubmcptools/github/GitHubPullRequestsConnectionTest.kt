// Tests use the GitHub plugin's internal / experimental classes on purpose.
@file:Suppress("UnstableApiUsage")

package io.github.veyronsakai.githubmcptools.github

import com.intellij.collaboration.ui.codereview.list.ReviewListViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewTabViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowProjectViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowTabs
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Companion.closeDetailsTabs
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Companion.normalizeRepositoryName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.ui.toolwindow.GHPRToolWindowTab
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

    /** Tool window tabs like the GitHub plugin's `GHPRToolWindowProjectViewModel`: closing a tab removes it from [tabs]. */
    private class FakeToolWindowVm(vararg tabs: GHPRToolWindowTab) : ReviewToolwindowProjectViewModel<GHPRToolWindowTab, ReviewTabViewModel> {
        override val projectName: String = "octo/hello"
        override val listVm: ReviewListViewModel get() = throw UnsupportedOperationException()
        override val tabs = MutableStateFlow(ReviewToolwindowTabs(tabs.associateWith(::tabVm), tabs.lastOrNull()))
        override fun selectTab(tab: GHPRToolWindowTab?) = tabs.update { it.copy(selectedTab = tab) }
        override fun closeTab(tab: GHPRToolWindowTab) =
            tabs.update { it.copy(tabs = it.tabs - tab, selectedTab = it.selectedTab.takeIf { selected -> selected != tab }) }

        private companion object {
            fun tabVm(tab: GHPRToolWindowTab): ReviewTabViewModel = object : ReviewTabViewModel {
                override val displayName: String =
                    if (tab is GHPRToolWindowTab.PullRequest) "#${tab.prId.number}" else "New Pull Request"
            }
        }
    }

    private fun pullRequestTab(number: Long) = GHPRToolWindowTab.PullRequest(GHPRIdentifier("PR_$number", number))

    private fun details(number: Long) = PullRequestView("octo/hello", number, PullRequestView.Kind.DETAILS, "#$number")

    @Test
    fun `closes the details tab of the given pull request only`() {
        val vm = FakeToolWindowVm(pullRequestTab(1), pullRequestTab(2), pullRequestTab(3))
        assertEquals(listOf(details(2)), closeDetailsTabs(vm, "octo/hello", 2))
        assertEquals(setOf(pullRequestTab(1), pullRequestTab(3)), vm.tabs.value.tabs.keys)
    }

    @Test
    fun `closes the details tabs of all pull requests but keeps the new pull request tab`() {
        val vm = FakeToolWindowVm(pullRequestTab(1), GHPRToolWindowTab.NewPullRequest, pullRequestTab(2))
        assertEquals(listOf(details(1), details(2)), closeDetailsTabs(vm, "octo/hello", null))
        assertEquals(setOf(GHPRToolWindowTab.NewPullRequest), vm.tabs.value.tabs.keys)
    }

    @Test
    fun `nothing to close`() {
        assertEquals(emptyList<PullRequestView>(), closeDetailsTabs(FakeToolWindowVm(), "octo/hello", null))
        val vm = FakeToolWindowVm(pullRequestTab(1))
        assertEquals(emptyList<PullRequestView>(), closeDetailsTabs(vm, "octo/hello", 2))
        assertEquals(setOf(pullRequestTab(1)), vm.tabs.value.tabs.keys)
    }
}
