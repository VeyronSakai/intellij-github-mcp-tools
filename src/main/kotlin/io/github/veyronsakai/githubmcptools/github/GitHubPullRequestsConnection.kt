package io.github.veyronsakai.githubmcptools.github

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.intellij.openapi.progress.EmptyProgressIndicator
import org.jetbrains.plugins.github.api.GHGQLRequests
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.pullrequest.ui.GHPRConnectedProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubConfusingException
import java.io.IOException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A GitHub repository, e.g. `owner/repo` at `https://github.com/owner/repo`. */
internal data class GitHubRepositoryInfo(val fullName: String, val url: String) {
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
                executor.execute(EmptyProgressIndicator(), request)
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

    /** Replaces the whole filter of the pull request list; the list reloads automatically. */
    suspend fun setFilter(filter: PullRequestFilter) {
        val value = filter.toSearchValue()
        withContext(Dispatchers.EDT) {
            connected.listVm.searchVm.searchState.value = value
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

        /** The current connection, without opening the tool window or changing anything. Null if not connected. */
        suspend fun current(project: Project): GitHubPullRequestsConnection? {
            val projectVm = project.serviceAsync<GHPRProjectViewModel>()
            return withTimeoutOrNull(STATUS_TIMEOUT) {
                projectVm.connectedProjectVm.filterNotNull().first()
            }?.let(::GitHubPullRequestsConnection)
        }

        /** Reads the state without opening the tool window or changing anything. */
        suspend fun status(project: Project): Status {
            val projectVm = project.serviceAsync<GHPRProjectViewModel>()
            // connectedProjectVm starts lazily: wait briefly for its first real value.
            val connected = withTimeoutOrNull(STATUS_TIMEOUT) {
                projectVm.connectedProjectVm.filterNotNull().first()
            }
            return Status(
                repositories = knownRepositories(projectVm).map { GitHubRepositoryInfo.of(it.repository) },
                connected = connected?.repository?.let(GitHubRepositoryInfo::of),
                account = connected?.listVm?.account?.name,
            )
        }

        /**
         * Shows the Pull Requests tool window and waits until it is connected.
         *
         * @param repository `owner/repo` or a repository URL. If null, keeps the current repository
         * (or whatever the GitHub plugin selects automatically). If it differs from the current one,
         * the tool window is switched to it, the same as choosing it in the tool window's selector.
         *
         * `activateAndAwaitProject` is intentionally not used: its signature changed in 263 (binary incompatible).
         */
        suspend fun connect(project: Project, repository: String? = null): Result {
            val projectVm = project.serviceAsync<GHPRProjectViewModel>()
            projectVm.activate()

            if (repository == null) {
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT) {
                    projectVm.connectedProjectVm.filterNotNull().first()
                }
                return when {
                    connected != null -> Result.Connected(GitHubPullRequestsConnection(connected))
                    !projectVm.isAvailable.value -> Result.NoGitHubRepository
                    else -> Result.NotConnected(null)
                }
            }

            val known = knownRepositories(projectVm)
            if (known.isEmpty()) return Result.NoGitHubRepository
            val target = findRepository(known, repository)
                ?: return Result.RepositoryNotFound(repository, known.map { GitHubRepositoryInfo.of(it.repository) })
            val targetInfo = GitHubRepositoryInfo.of(target.repository)

            val current = withTimeoutOrNull(STATUS_TIMEOUT) {
                projectVm.connectedProjectVm.filterNotNull().first()
            }
            if (current != null && current.repository == target.repository) {
                return Result.Connected(GitHubPullRequestsConnection(current))
            }

            val selector = projectVm.selectorVm
            val accounts = service<GHAccountManager>().accountsState.value
                .filter { sameServer(it.server.toURI(), target.repository.serverPath.toURI()) }
            if (accounts.isEmpty()) return Result.NotLoggedIn(targetInfo)
            // Prefer the account that is already selected (the one used for the current connection).
            val account: GithubAccount = selector.accountSelectionState.value?.takeIf { it in accounts }
                ?: accounts.sortedBy { it.name }.first()

            withContext(Dispatchers.EDT) {
                selector.repoSelectionState.value = target
                selector.accountSelectionState.value = account
            }
            // null while the token is being looked up
            val credentialsMissing = withTimeoutOrNull(CONNECT_TIMEOUT) {
                selector.missingCredentialsState.filterNotNull().first()
            }
            if (credentialsMissing == true) return Result.MissingCredentials(targetInfo, account.name)

            withContext(Dispatchers.EDT) {
                selector.submitSelection()
            }
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT) {
                projectVm.connectedProjectVm.filterNotNull().first { it.repository == target.repository }
            }
            return if (connected != null) Result.Connected(GitHubPullRequestsConnection(connected))
            else Result.NotConnected(targetInfo)
        }

        private fun knownRepositories(projectVm: GHPRProjectViewModel): List<GHGitRepositoryMapping> =
            projectVm.selectorVm.repositoriesState.value.sortedBy { it.repository.repositoryPath.toString() }

        private fun findRepository(known: List<GHGitRepositoryMapping>, requested: String): GHGitRepositoryMapping? {
            val fullName = normalizeRepositoryName(requested)
            return known.firstOrNull { it.repository.repositoryPath.toString().equals(fullName, ignoreCase = true) }
        }

        /** Accepts `owner/repo`, `https://host/owner/repo(.git)` and `git@host:owner/repo(.git)`. */
        internal fun normalizeRepositoryName(value: String): String {
            val trimmed = value.trim().removeSuffix("/").removeSuffix(".git")
            val parts = trimmed.split('/', ':').filter { it.isNotEmpty() }
            return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else trimmed
        }

        private fun sameServer(a: URI, b: URI): Boolean =
            a.host.equals(b.host, ignoreCase = true) && a.port == b.port &&
                a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }
}
