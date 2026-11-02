@file:Suppress("FunctionName", "unused")

package io.github.veyronsakai.githubmcptools.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestEditors
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection
import io.github.veyronsakai.githubmcptools.github.PullRequestView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val REPOSITORY_PARAMETER_DESCRIPTION =
    "Repository as owner/repo (a GitHub URL is also accepted). Optional; if omitted or empty, " +
        "the repository selected in the Pull Requests tool window is used. If it differs from the selected one, " +
        "the tool window is switched to it first (same as github_select_repository)."

class PullRequestToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Shows a GitHub pull request in the IDE (bundled GitHub plugin) so the user can read or review it there:
        |its details in the Pull Requests tool window, plus its timeline (description, comments, reviews) and/or
        |its changes (diff) in editor tabs, depending on `kind`.
        |This only changes the IDE UI. It does NOT create a pull request, does not modify anything on GitHub,
        |and does not check out the branch. It makes one read-only GitHub API request to look up the pull request.
        |Fails if no pull request with this number exists in the repository, if `kind` is invalid,
        |if the project has no GitHub remote, if `repository` is not one of the project's GitHub repositories
        |(see github_list_repositories), or if the user is not logged in to GitHub in the IDE (ask the user to log in, then retry).
        |Use github_close_pull_request_view to close the views again.
        """
    )
    suspend fun github_open_pull_request_view(
        @McpDescription("Pull request number, e.g. 123 for https://github.com/owner/repo/pull/123. Must be positive.")
        number: Long,
        @McpDescription("What to open: timeline (description, comments, reviews), diff (changed files), or all (both). Default: timeline.")
        kind: String = "timeline",
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String {
        val kinds = parseKinds(kind)
        val connection = connect(number, repository)
        val opened = failOnGitHubAccessError { connection.openPullRequestView(number, kinds) }
        if (!opened) {
            mcpFail(
                "Pull request #$number was not found in ${connection.repository.fullName}. " +
                    "Check the number, or pass `repository` if it belongs to another repository (see github_list_repositories)."
            )
        }
        return "Opened pull request #$number of ${connection.repository.url} in the IDE " +
            "(details and ${kinds.joinToString(" and ") { it.name.lowercase() }})."
    }

    @McpTool
    @McpDescription(
        """
        |Shows the pull request list in the IDE's Pull Requests tool window (bundled GitHub plugin),
        |e.g. to go back to the list from a pull request's details. The list uses the current filter
        |(see github_get_pull_request_filter / github_set_pull_request_filter).
        |This only changes the IDE UI; nothing on GitHub is modified.
        |Fails if the project has no GitHub remote or the user is not logged in to GitHub in the IDE.
        """
    )
    suspend fun github_open_pull_request_list(
        @McpDescription("If true, reloads the list from GitHub. Default: false.")
        refresh: Boolean = false,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val connection = GitHubPullRequestsConnection.connect(project, repository.ifBlank { null }).connectionOrFail(project)
        connection.viewList(refresh)
        return "Showed the pull request list of ${connection.repository.fullName}" +
            (if (refresh) " and reloaded it" else "") + " in the IDE's Pull Requests tool window."
    }

    @McpTool
    @McpDescription(
        """
        |Closes pull request views in the IDE: the timeline and/or diff editor tabs opened by the bundled GitHub plugin
        |(e.g. by github_open_pull_request_view).
        |This only closes IDE tabs. It does NOT close the pull request on GitHub and does not modify anything on GitHub.
        |Without parameters, closes all views of all pull requests.
        |Returns JSON: {"closed": [{"repository": "owner/repo", "number": 123, "kind": "timeline"|"diff", "title": "..."}]};
        |an empty list means no matching view was open (not an error).
        |Fails only if `kind` is invalid or `number` is negative. Does not open the tool window or contact GitHub.
        """
    )
    suspend fun github_close_pull_request_view(
        @McpDescription("Pull request number whose views to close. Optional; if omitted or 0, views of all pull requests are closed.")
        number: Long = 0,
        @McpDescription("What to close: timeline, diff, or all (both). Default: all.")
        kind: String = "all",
        @McpDescription("Repository as owner/repo (a GitHub URL is also accepted). Optional; if omitted or empty, any repository.")
        repository: String = "",
    ): String {
        if (number < 0) mcpFail("Pull request number must not be negative, but was $number.")
        val kinds = parseKinds(kind)
        val project = currentCoroutineContext().project
        val closed = GitHubPullRequestEditors.closeViews(project, kinds, repository.ifBlank { null }, number.takeIf { it > 0 })
        return buildJsonObject {
            putJsonArray("closed") {
                closed.forEach { view ->
                    addJsonObject {
                        put("repository", view.repository?.let(::JsonPrimitive) ?: JsonNull)
                        put("number", view.number?.let(::JsonPrimitive) ?: JsonNull)
                        put("kind", view.kind.name.lowercase())
                        put("title", view.title)
                    }
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription(
        """
        |Closes (hides) the IDE's Pull Requests tool window (bundled GitHub plugin).
        |The selected repository, the filter and open editor tabs are kept. github_select_repository, github_open_pull_request_view,
        |github_open_pull_request_list and github_set_pull_request_filter show the tool window again.
        |This only changes the IDE UI; nothing on GitHub is modified. Never fails; does nothing if the tool window is already hidden.
        """
    )
    suspend fun github_close_tool_window(): String {
        val project = currentCoroutineContext().project
        return if (GitHubPullRequestEditors.hideToolWindow(project)) "Closed the Pull Requests tool window."
        else "The Pull Requests tool window was not open."
    }

    private suspend fun connect(number: Long, repository: String): GitHubPullRequestsConnection {
        if (number <= 0) mcpFail("Pull request number must be positive, but was $number.")
        val project = currentCoroutineContext().project
        return GitHubPullRequestsConnection.connect(project, repository.ifBlank { null }).connectionOrFail(project)
    }

    private fun parseKinds(kind: String): Set<PullRequestView.Kind> =
        PullRequestView.Kind.parseSet(kind) ?: mcpFail("Invalid kind '$kind'. Allowed values: timeline, diff, all.")
}
