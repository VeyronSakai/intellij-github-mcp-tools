@file:Suppress("FunctionName", "unused")

package io.github.veyronsakai.githubmcptools.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection
import io.github.veyronsakai.githubmcptools.github.PullRequestFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class PullRequestFilterToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Returns the filter of the pull request list in the IDE's Pull Requests tool window (bundled GitHub plugin).
        |Read-only: does not open the tool window or change anything.
        |Returns JSON: {"repository": "owner/repo", "query": ..., "state": ..., "author": ..., "assignee": ...,
        |"label": ..., "reviewState": ..., "sort": ...}; each filter field is a string or null (not set).
        |Fails if the tool window is not connected to a repository (call github_select_repository first).
        """
    )
    suspend fun github_get_pull_request_filter(): String {
        val project = currentCoroutineContext().project
        val connection = GitHubPullRequestsConnection.current(project)
            ?: mcpFail(
                "The Pull Requests tool window is not connected to a GitHub repository. " +
                    "Call github_select_repository first (the user may need to log in to GitHub in the IDE)."
            )
        return toJson(connection.repository.fullName, connection.filter).toString()
    }

    @McpTool
    @McpDescription(
        """
        |Sets the filter of the pull request list in the IDE's Pull Requests tool window (bundled GitHub plugin)
        |and shows the list (also when a pull request's details were shown); the list reloads with the new filter.
        |The whole filter is replaced: every parameter that is omitted or empty is cleared. To change one field,
        |read the current filter with github_get_pull_request_filter and pass the other fields again. Calling with no parameters clears all filters.
        |This only changes the IDE UI; nothing on GitHub is modified.
        |If `repository` is given and differs from the connected repository, the tool window is switched to it first.
        |Fails on an invalid enum value, if `repository` is not one of the project's GitHub repositories,
        |or if the user is not logged in to GitHub in the IDE.
        |Returns the applied filter as JSON (same format as github_get_pull_request_filter).
        """
    )
    suspend fun github_set_pull_request_filter(
        @McpDescription("Free-text search query (GitHub search syntax is accepted). Default: none.")
        query: String = "",
        @McpDescription("Pull request state: open, closed or merged. Default: any state.")
        state: String = "",
        @McpDescription("Author login, e.g. octocat. Default: any author.")
        author: String = "",
        @McpDescription("Assignee login. Default: any assignee.")
        assignee: String = "",
        @McpDescription("Label name. Default: any label.")
        label: String = "",
        @McpDescription(
            "Review state: no_review, required, approved, changes_requested, reviewed_by_me, not_reviewed_by_me, " +
                "or awaiting_review (review requested from the current user). Default: any."
        )
        reviewState: String = "",
        @McpDescription(
            "Sort order: newest, oldest, most_commented, least_commented, recently_updated or least_recently_updated. " +
                "Default: the GitHub default order."
        )
        sort: String = "",
        @McpDescription(
            "Repository as owner/repo (a GitHub URL is also accepted). " +
                "Optional; if omitted or empty, the currently connected repository is used."
        )
        repository: String = "",
    ): String {
        val filter = PullRequestFilter(
            query = query.ifBlank { null },
            state = state.ifBlank { null },
            author = author.ifBlank { null },
            assignee = assignee.ifBlank { null },
            label = label.ifBlank { null },
            reviewState = reviewState.ifBlank { null },
            sort = sort.ifBlank { null },
        )
        // Validate before touching the UI
        try {
            filter.toSearchValue()
        }
        catch (e: PullRequestFilter.InvalidValueException) {
            mcpFail(e.message ?: "Invalid filter value.")
        }

        val project = currentCoroutineContext().project
        val connection = GitHubPullRequestsConnection.connect(project, repository.ifBlank { null })
            .connectionOrFail(project)
        connection.setFilter(filter)
        return toJson(connection.repository.fullName, connection.filter).toString()
    }

    private fun toJson(repository: String, filter: PullRequestFilter): JsonObject = buildJsonObject {
        put("repository", JsonPrimitive(repository))
        put("query", filter.query.toJson())
        put("state", filter.state.toJson())
        put("author", filter.author.toJson())
        put("assignee", filter.assignee.toJson())
        put("label", filter.label.toJson())
        put("reviewState", filter.reviewState.toJson())
        put("sort", filter.sort.toJson())
    }

    private fun String?.toJson() = this?.let(::JsonPrimitive) ?: JsonNull
}
