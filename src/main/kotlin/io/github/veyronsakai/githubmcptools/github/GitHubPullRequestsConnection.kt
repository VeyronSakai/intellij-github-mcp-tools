// GitHub plugin internal / experimental APIs are used on purpose and only in this package
// (checked on every supported IDE line and by `./gradlew verifyPlugin`), so the IDE's "Unstable API Usage" warnings are suppressed here.
@file:Suppress("UnstableApiUsage")

package io.github.veyronsakai.githubmcptools.github

import com.intellij.collaboration.util.URIUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.github.api.GHGQLRequests
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.authentication.accounts.GithubProjectDefaultAccountHolder
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubConfusingException
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.ui.GHPRConnectedProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import org.jetbrains.plugins.github.util.GHHostedRepositoriesManager
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A GitHub repository, e.g. `owner/repo` at `https://github.com/owner/repo`. */
internal data class GitHubRepositoryInfo(val fullName: String, val url: String) {
    /** GitHub names are case-insensitive, and the remote URL and the GitHub API can spell them differently. */
    fun isSameAs(other: GitHubRepositoryInfo?): Boolean =
        other != null && fullName.equals(other.fullName, ignoreCase = true) && url.equals(other.url, ignoreCase = true)

    companion object {
        fun of(coordinates: GHRepositoryCoordinates) =
            GitHubRepositoryInfo(coordinates.repositoryPath.toString(), coordinates.toUrl())
    }
}

/**
 * Access to the Pull Requests tool window of the bundled GitHub plugin.
 *
 * All GitHub plugin internal APIs (`@ApiStatus.Internal`) are used only in this package.
 * Every API used here was checked to exist with the same signature on branches 253 / 261 / 262 / 263.
 *
 * Git4Idea classes (`GitRepository`, `git4idea.remote.hosting.*`) are not referenced on purpose:
 * since 262 they live in the non-embedded module `intellij.vcs.git.backend`, which this plugin cannot see.
 */
internal class GitHubPullRequestsConnection private constructor(
    private val connected: GHPRConnectedProjectViewModel,
) {
    val repository: GitHubRepositoryInfo get() = GitHubRepositoryInfo.of(connected.repository)

    /** Login of the GitHub account used by the tool window. */
    val accountName: String get() = connected.listVm.account.name

    /**
     * Opens the pull request details (tool window) and the given views (timeline and/or diff editor tabs).
     * Unlike `openPullRequestInfoAndTimeline(number)`, a missing PR is reported instead of silently ignored.
     * @return false if the repository has no pull request with this number
     */
    suspend fun openPullRequestView(number: Long, kinds: Set<PullRequestView.Kind>): Boolean {
        val id = findPullRequestId(number) ?: return false
        withContext(Dispatchers.EDT) {
            connected.viewPullRequest(id, true)
            if (PullRequestView.Kind.TIMELINE in kinds) connected.openPullRequestTimeline(id, true)
            if (PullRequestView.Kind.DIFF in kinds) connected.openPullRequestDiff(id, true)
        }
        return true
    }

    /** Shows the pull request list in the tool window, optionally reloading it from GitHub. */
    suspend fun viewList(refresh: Boolean) {
        withContext(Dispatchers.EDT) {
            if (refresh) connected.listVm.refresh()
            connected.viewList(true)
        }
    }

    /**
     * Number -> GraphQL node id, with one read-only GraphQL request using the tool window's account.
     * `GHPRDataContext.detailsService.findPRId` would do the same, but it is Kotlin `internal`.
     */
    private suspend fun findPullRequestId(number: Long): GHPRIdentifier? {
        val account = connected.listVm.account
        val token = service<GHAccountManager>().findCredentials(account)
            ?: throw GitHubAccessException(
                "The GitHub account '${account.name}' has no saved token.", authentication = true,
            )
        val executor = GithubApiRequestExecutor.Factory.getInstance().create(account.server, token)
        val request = GHGQLRequests.PullRequest.findOneId(connected.repository, number)
        return withContext(Dispatchers.IO) {
            try {
                // The indicator is cancelled together with the coroutine (e.g. when the MCP call is cancelled).
                coroutineToIndicator { indicator -> executor.execute(indicator, request) }
            }
            catch (e: GithubAuthenticationException) {
                throw GitHubAccessException("GitHub rejected the account '${account.name}': ${e.message}", authentication = true)
            }
            catch (e: GithubConfusingException) {
                // A missing PR is reported as a GraphQL error (type NOT_FOUND), and only the message is kept.
                if (e.message.orEmpty().contains("Could not resolve to a PullRequest", ignoreCase = true)) null
                else throw GitHubAccessException("GitHub API error: ${e.message}", authentication = false)
            }
            catch (e: IOException) {
                throw GitHubAccessException("GitHub API request failed: ${e.message}", authentication = false)
            }
        }
    }

    /** @property authentication true if the user must log in again */
    class GitHubAccessException(message: String, val authentication: Boolean) : Exception(message)

    /** Filter of the pull request list in the tool window. */
    val filter: PullRequestFilter
        get() = PullRequestFilter.of(connected.listVm.searchVm.searchState.value)

    /** Replaces the whole filter of the pull request list and shows the list; it reloads automatically. */
    suspend fun setFilter(filter: PullRequestFilter) {
        val value = filter.toSearchValue()
        withContext(Dispatchers.EDT) {
            connected.listVm.searchVm.searchState.value = value
            connected.viewList(true)
        }
    }

    sealed interface Result {
        class Connected(val connection: GitHubPullRequestsConnection) : Result

        /** The project has no Git remote pointing to a known GitHub server. */
        data object NoGitHubRepository : Result

        /** The requested repository is not one of the project's GitHub repositories. */
        class RepositoryNotFound(val requested: String, val known: List<GitHubRepositoryInfo>) : Result

        /** No GitHub account is registered in the IDE for the repository's server. */
        class NotLoggedIn(val repository: GitHubRepositoryInfo) : Result

        /** Several accounts exist for the server and none is selected or set as the project default. */
        class AmbiguousAccount(val repository: GitHubRepositoryInfo, val accounts: List<String>) : Result

        /** The account exists but its token is missing; the user must log in again. */
        class MissingCredentials(val repository: GitHubRepositoryInfo, val account: String) : Result

        /** The tool window did not connect in time (not logged in, repository not chosen, or a connection error). */
        class NotConnected(val requested: GitHubRepositoryInfo?) : Result
    }

    class Status(
        /** GitHub repositories found in the project's Git remotes. */
        val repositories: List<GitHubRepositoryInfo>,
        /** Repository the Pull Requests tool window is connected to, or null. */
        val connected: GitHubRepositoryInfo?,
        /** Login of the GitHub account used for the connection, or null. */
        val account: String?,
    )

    companion object {
        private val CONNECT_TIMEOUT: Duration = 15.seconds
        private val STATUS_TIMEOUT: Duration = 500.milliseconds

        /** How long to keep waiting for a repository that is not in the (possibly still loading) list yet. */
        private val REPOSITORY_DISCOVERY_TIMEOUT: Duration = 3.seconds

        /** The current connection, without opening the tool window or changing anything. Null if not connected. */
        suspend fun current(project: Project): GitHubPullRequestsConnection? =
            awaitConnected(project.serviceAsync<GHPRProjectViewModel>(), STATUS_TIMEOUT)?.let(::GitHubPullRequestsConnection)

        /** Reads the state without opening the tool window or changing anything. */
        suspend fun status(project: Project): Status {
            val connected = current(project)
            // Not `selectorVm`: creating it starts the GitHub plugin's auto-connection, and this must not change anything.
            val repositories = project.serviceAsync<GHHostedRepositoriesManager>().knownRepositoriesState.value
            return Status(
                repositories = repositories.map { GitHubRepositoryInfo.of(it.repository) }.sortedBy { it.fullName },
                connected = connected?.repository,
                account = connected?.accountName,
            )
        }

        /**
         * Shows the Pull Requests tool window and waits until it is connected.
         *
         * @param repository `owner/repo` or a repository URL. If null, keeps the current repository
         * (or whatever the GitHub plugin selects automatically). If it differs from the current one,
         * the tool window is switched to it, the same as choosing it in the tool window's selector.
         * The tool window is shown only after the arguments are checked, so a failing call does not steal the focus.
         *
         * `activateAndAwaitProject` is intentionally not used: its signature changed in 263 (binary incompatible).
         */
        suspend fun connect(project: Project, repository: String? = null): Result {
            val projectVm = project.serviceAsync<GHPRProjectViewModel>()

            if (repository == null) {
                projectVm.activate()
                val connected = awaitConnected(projectVm, CONNECT_TIMEOUT)
                return when {
                    connected != null -> Result.Connected(GitHubPullRequestsConnection(connected))
                    !projectVm.isAvailable.value -> Result.NoGitHubRepository
                    else -> Result.NotConnected(null)
                }
            }

            val (known, target) = awaitRepository(project, repository)
            if (known.isEmpty()) return Result.NoGitHubRepository
            if (target == null) {
                return Result.RepositoryNotFound(repository, known.map { GitHubRepositoryInfo.of(it.repository) }.sortedBy { it.fullName })
            }
            val targetInfo = GitHubRepositoryInfo.of(target.repository)

            // GHRepositoryCoordinates equality ignores the case of owner/repo.
            val current = awaitConnected(projectVm, STATUS_TIMEOUT)
            if (current != null && current.repository == target.repository) {
                projectVm.activate()
                return Result.Connected(GitHubPullRequestsConnection(current))
            }

            val accounts = service<GHAccountManager>().accountsState.value
                .filter { URIUtil.equalWithoutSchema(it.server.toURI(), target.repository.serverPath.toURI()) }
            if (accounts.isEmpty()) return Result.NotLoggedIn(targetInfo)
            val selector = projectVm.selectorVm
            // 1. the account already selected in the tool window (used for the current connection)
            // 2. the project's default GitHub account (Settings | Version Control | GitHub)
            // 3. the only account for the server
            val account: GithubAccount = selector.accountSelectionState.value?.takeIf { it in accounts }
                ?: project.service<GithubProjectDefaultAccountHolder>().account?.takeIf { it in accounts }
                ?: accounts.singleOrNull()
                ?: return Result.AmbiguousAccount(targetInfo, accounts.map { it.name }.sorted())
            // Asked directly: the selector's `missingCredentialsState` is updated asynchronously and can be stale.
            if (service<GHAccountManager>().findCredentials(account) == null) {
                return Result.MissingCredentials(targetInfo, account.name)
            }

            projectVm.activate()
            // Select and submit in one EDT task, so the GitHub plugin's own auto-connection cannot change the selection in between.
            withContext(Dispatchers.EDT) {
                selector.repoSelectionState.value = target
                selector.accountSelectionState.value = account
                selector.submitSelection()
            }
            val connected = awaitConnected(projectVm, CONNECT_TIMEOUT) { it.repository == target.repository }
            return if (connected != null) Result.Connected(GitHubPullRequestsConnection(connected))
            else Result.NotConnected(targetInfo)
        }

        /** Waits up to [timeout] for a connected tool window matching [predicate]. null on timeout. */
        private suspend fun awaitConnected(
            projectVm: GHPRProjectViewModel,
            timeout: Duration,
            predicate: (GHPRConnectedProjectViewModel) -> Boolean = { true },
        ): GHPRConnectedProjectViewModel? =
            withTimeoutOrNull(timeout) {
                // connectedProjectVm starts lazily, so its first value can be null even when connected.
                projectVm.connectedProjectVm.filterNotNull().first(predicate)
            }

        /**
         * Returns the project's GitHub repositories and the one matching [requested] (null if none).
         * Right after the IDE starts, Git remotes are still loading: waits until the list is not empty,
         * then a little longer for [requested], since more remotes (e.g. GitHub Enterprise ones) can appear later.
         */
        private suspend fun awaitRepository(
            project: Project,
            requested: String,
        ): Pair<Set<GHGitRepositoryMapping>, GHGitRepositoryMapping?> {
            val state = project.serviceAsync<GHHostedRepositoriesManager>().knownRepositoriesState
            val known = withTimeoutOrNull(CONNECT_TIMEOUT) { state.first { it.isNotEmpty() } }
                ?: return emptySet<GHGitRepositoryMapping>() to null
            findRepository(known, requested)?.let { return known to it }
            val later = withTimeoutOrNull(REPOSITORY_DISCOVERY_TIMEOUT) {
                state.first { repos -> findRepository(repos, requested) != null }
            } ?: state.value
            return later to findRepository(later, requested)
        }

        /**
         * Finds [requested] (`owner/repo` or a URL) among [known] repositories, ignoring case.
         * Falls back to looking for `owner/repo` anywhere in the path, for URLs that [normalizeRepositoryName]
         * cannot split (e.g. GitHub Enterprise with a path prefix: `https://ghe.example.com/github/owner/repo`).
         */
        private fun findRepository(known: Collection<GHGitRepositoryMapping>, requested: String): GHGitRepositoryMapping? {
            val fullName = normalizeRepositoryName(requested)
            known.firstOrNull { it.repository.repositoryPath.toString().equals(fullName, ignoreCase = true) }?.let { return it }
            val path = "/" + stripQueryAndFragment(requested).removeSuffix("/").removeSuffix(".git").lowercase() + "/"
            return known.firstOrNull { path.contains("/" + it.repository.repositoryPath.toString().lowercase() + "/") }
        }

        /**
         * Returns `owner/repo` from `owner/repo`, a repository or pull request URL
         * (`https://host/owner/repo(.git)`, `https://host/owner/repo/pull/12?tab=files#x`, `host/owner/repo`)
         * or an SSH remote (`git@host:owner/repo.git`).
         */
        internal fun normalizeRepositoryName(value: String): String {
            val trimmed = stripQueryAndFragment(value.trim())
            val path = when {
                "://" in trimmed -> trimmed.substringAfter("://").substringAfter('/', "")  // drop scheme and host
                Regex("^[^/]+@[^/:]+:").containsMatchIn(trimmed) -> trimmed.substringAfter(':')  // git@host:owner/repo
                else -> trimmed
            }
            // GitHub owner names cannot contain '.' or ':', so such a first segment is a host (github.com/..., localhost:8080/...).
            val segments = path.split('/').filter { it.isNotEmpty() }.let { s ->
                if (s.size > 2 && (s.first().contains('.') || s.first().contains(':'))) s.drop(1) else s
            }
            if (segments.size < 2) return trimmed
            return "${segments[0]}/${segments[1].removeSuffix(".git")}"
        }

        private fun stripQueryAndFragment(value: String): String = value.substringBefore('#').substringBefore('?')
    }
}
