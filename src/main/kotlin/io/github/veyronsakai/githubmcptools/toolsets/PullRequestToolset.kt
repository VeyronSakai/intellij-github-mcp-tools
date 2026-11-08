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

private const val NUMBER_PARAMETER_DESCRIPTION = "Pull request number."

/** Also used by [PullRequestFilterToolset]. */
internal const val REPOSITORY_PARAMETER_DESCRIPTION =
    "owner/repo or GitHub URL. Default: the repository selected in the Pull Requests tool window. " +
        "Another repository is selected first, like github_select_repository."

private const val CLOSE_NUMBER_PARAMETER_DESCRIPTION = "Pull request number. Default: all pull requests."

private const val CLOSE_REPOSITORY_PARAMETER_DESCRIPTION = "owner/repo or GitHub URL. Default: any repository."

private const val UI_ONLY = "Only changes the IDE UI; nothing on GitHub is modified."

/** Common end of the descriptions of the tools that close views of pull requests. */
private const val CLOSE_VIEW_NOTES =
    """Does NOT close pull requests on GitHub. Returns JSON: {"closed": [{"repository": "owner/repo", "number": 123, "title": "..."}]}."""

class PullRequestToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Shows a pull request's details (description, status checks, reviewers, commits, changed files) " +
            "in the IDE's Pull Requests tool window. $UI_ONLY"
    )
    suspend fun github_open_pull_request_details(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.DETAILS)

    @McpTool
    @McpDescription(
        "Shows a pull request's timeline (description, comments, reviews) in an IDE editor tab. $UI_ONLY"
    )
    suspend fun github_open_pull_request_timeline(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.TIMELINE)

    @McpTool
    @McpDescription(
        "Shows a pull request's changes (diff of the changed files) in an IDE editor tab. $UI_ONLY"
    )
    suspend fun github_open_pull_request_diff(
        @McpDescription(NUMBER_PARAMETER_DESCRIPTION)
        number: Long,
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = openView(number, repository, PullRequestView.Kind.DIFF)

    @McpTool
    @McpDescription(
        "Shows the pull request list (with the current filter) in the IDE's Pull Requests tool window. $UI_ONLY"
    )
    suspend fun github_open_pull_request_list(
        @McpDescription("Reload the list from GitHub. Default: false.")
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
        "Closes pull request details tabs in the IDE's Pull Requests tool window. $CLOSE_VIEW_NOTES"
    )
    suspend fun github_close_pull_request_details(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.DETAILS)

    @McpTool
    @McpDescription(
        "Closes pull request timeline editor tabs in the IDE. $CLOSE_VIEW_NOTES"
    )
    suspend fun github_close_pull_request_timeline(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.TIMELINE)

    @McpTool
    @McpDescription(
        "Closes pull request diff editor tabs in the IDE. $CLOSE_VIEW_NOTES"
    )
    suspend fun github_close_pull_request_diff(
        @McpDescription(CLOSE_NUMBER_PARAMETER_DESCRIPTION)
        number: Long = 0,
        @McpDescription(CLOSE_REPOSITORY_PARAMETER_DESCRIPTION)
        repository: String = "",
    ): String = closeViews(number, repository, PullRequestView.Kind.DIFF)

    @McpTool
    @McpDescription(
        "Hides the IDE's Pull Requests tool window. Its tabs, repository and filter are kept."
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
