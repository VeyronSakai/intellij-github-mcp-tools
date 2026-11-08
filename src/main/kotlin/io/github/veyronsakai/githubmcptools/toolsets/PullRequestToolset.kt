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

private const val NUMBER_PARAMETER_DESCRIPTION =
    "Pull request number, e.g. 123 for https://github.com/owner/repo/pull/123. Must be positive."

private const val REPOSITORY_PARAMETER_DESCRIPTION =
    "Repository as owner/repo (a GitHub URL is also accepted). Optional; if omitted or empty, " +
        "the repository selected in the Pull Requests tool window is used. If it differs from the selected one, " +
        "the tool window is switched to it first (same as github_select_repository)."

private const val CLOSE_NUMBER_PARAMETER_DESCRIPTION =
    "Pull request number whose tabs to close. Optional; if omitted or 0, the tabs of all pull requests are closed."

private const val CLOSE_REPOSITORY_PARAMETER_DESCRIPTION =
    "Repository as owner/repo (a GitHub URL is also accepted). Optional; if omitted or empty, any repository."

/** Common end of the descriptions of the tools that open a view of a pull request. */
private const val OPEN_VIEW_NOTES = """
        |This only changes the IDE UI. It does NOT create a pull request, does not modify anything on GitHub,
        |and does not check out the branch. It makes one read-only GitHub API request to look up the pull request
        |(none if the same pull request was looked up before).
        |Fails if no pull request with this number exists in the repository, if the project has no GitHub remote,
        |if `repository` is not one of the project's GitHub repositories (see github_list_repositories),
        |or if the user is not logged in to GitHub in the IDE (ask the user to log in, then retry).
        |To show several views of a pull request, call github_open_pull_request_details, github_open_pull_request_timeline
        |and github_open_pull_request_diff for it."""

/** Common end of the descriptions of the tools that close views of pull requests. */
private const val CLOSE_VIEW_NOTES = """
        |This only closes IDE tabs. It does NOT close the pull request on GitHub, does not modify anything on GitHub,
        |and does not open the tool window or contact GitHub.
        |Returns JSON: {"closed": [{"repository": "owner/repo", "number": 123, "title": "..."}]}, where title is the tab title
        |shown in the IDE; an empty list means no matching tab was open (not an error). Fails only if `number` is negative."""

class PullRequestToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Shows the details of a GitHub pull request (description, branches, status checks, reviewers, commits and changed files)
        |in the IDE's Pull Requests tool window (bundled GitHub plugin), and brings the tool window to the front.
        |github_close_pull_request_details closes the tab again.""" + OPEN_VIEW_NOTES
    )
    suspend fun github_open_pull_request_details(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.DETAILS)

    @McpTool
    @McpDescription(
        """
        |Shows the timeline of a GitHub pull request (description, comments, reviews) in an editor tab of the IDE
        |(bundled GitHub plugin), so the user can read the conversation there. The Pull Requests tool window is not
        |brought to the front, unless it has to connect or switch to `repository` first.
        |github_close_pull_request_timeline closes the tab again.""" + OPEN_VIEW_NOTES
    )
    suspend fun github_open_pull_request_timeline(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.TIMELINE)

    @McpTool
    @McpDescription(
        """
        |Shows the changes of a GitHub pull request (the diff of its changed files) in an editor tab of the IDE
        |(bundled GitHub plugin), so the user can review the code there. The Pull Requests tool window is not
        |brought to the front, unless it has to connect or switch to `repository` first.
        |github_close_pull_request_diff closes the tab again.""" + OPEN_VIEW_NOTES
    )
    suspend fun github_open_pull_request_diff(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.DIFF)

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
        |Closes pull request details tabs in the IDE's Pull Requests tool window (bundled GitHub plugin),
        |e.g. opened by github_open_pull_request_details, the same as clicking their close buttons.
        |Without parameters, closes the details tabs of all pull requests. The tab for creating a new pull request is never closed.""" +
            CLOSE_VIEW_NOTES
    )
    suspend fun github_close_pull_request_details(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.DETAILS)

    @McpTool
    @McpDescription(
        """
        |Closes pull request timeline editor tabs opened by the bundled GitHub plugin (e.g. by github_open_pull_request_timeline).
        |Without parameters, closes the timeline tabs of all pull requests.""" + CLOSE_VIEW_NOTES
    )
    suspend fun github_close_pull_request_timeline(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.TIMELINE)

    @McpTool
    @McpDescription(
        """
        |Closes pull request diff editor tabs opened by the bundled GitHub plugin (e.g. by github_open_pull_request_diff).
        |Without parameters, closes the diff tabs of all pull requests, including the diff of a pull request being created
        |(its number is null in the result).""" + CLOSE_VIEW_NOTES
    )
    suspend fun github_close_pull_request_diff(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.DIFF)

    @McpTool
    @McpDescription(
        """
        |Closes (hides) the IDE's Pull Requests tool window (bundled GitHub plugin).
        |The selected repository, the filter, the pull request details tabs and the editor tabs are kept
        |(github_close_pull_request_details, github_close_pull_request_timeline and github_close_pull_request_diff close the tabs).
        |github_select_repository, github_open_pull_request_details, github_open_pull_request_list and
        |github_set_pull_request_filter show the tool window again.
        |This only changes the IDE UI; nothing on GitHub is modified. Never fails; does nothing if the tool window is already hidden.
        """
    )
    suspend fun github_close_tool_window(): String {
        val project = currentCoroutineContext().project
        return if (GitHubPullRequestEditors.hideToolWindow(project)) "Closed the Pull Requests tool window."
        else "The Pull Requests tool window was not open."
    }

    private suspend fun openView(number: Long, repository: String, kind: PullRequestView.Kind): String {
        if (number <= 0) mcpFail("Pull request number must be positive, but was $number.")
        val project = currentCoroutineContext().project
        // Not shown here: the details tab brings the tool window to the front by itself, once the pull request is found.
        val connection = GitHubPullRequestsConnection.connect(project, repository.ifBlank { null }, showToolWindow = false)
            .connectionOrFail(project)
        val opened = failOnGitHubAccessError { connection.openPullRequestView(number, kind) }
        if (!opened) {
            mcpFail(
                "Pull request #$number was not found in ${connection.repository.fullName}. " +
                    "Check the number, or pass `repository` if it belongs to another repository (see github_list_repositories)."
            )
        }
        val pullRequest = "pull request #$number of ${connection.repository.url}"
        return when (kind) {
            PullRequestView.Kind.DETAILS -> "Opened the details of $pullRequest in the IDE's Pull Requests tool window."
            PullRequestView.Kind.TIMELINE -> "Opened the timeline of $pullRequest in an editor tab."
            PullRequestView.Kind.DIFF -> "Opened the diff of $pullRequest in an editor tab."
        }
    }

    private suspend fun closeViews(number: Long, repository: String, kind: PullRequestView.Kind): String {
        if (number < 0) mcpFail("Pull request number must not be negative, but was $number.")
        val project = currentCoroutineContext().project
        val closed = GitHubPullRequestEditors.closeViews(project, kind, repository.ifBlank { null }, number.takeIf { it > 0 })
        return buildJsonObject {
            putJsonArray("closed") {
                closed.forEach { view ->
                    addJsonObject {
                        put("repository", view.repository?.let(::JsonPrimitive) ?: JsonNull)
                        put("number", view.number?.let(::JsonPrimitive) ?: JsonNull)
                        put("title", view.title)
                    }
                }
            }
        }.toString()
    }
}
