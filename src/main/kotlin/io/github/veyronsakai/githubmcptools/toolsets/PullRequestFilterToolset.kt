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
        |Returns the filter of the pull request list in the IDE's Pull Requests tool window. Read-only.
        |Returns JSON: {"repository": "owner/repo", "query", "state", "author", "assignee", "label", "reviewState", "sort"};
        |each filter field is a string, or null if not set.
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
        |Replaces the filter of the pull request list in the IDE's Pull Requests tool window and shows the list.
        |Omitted parameters are cleared; to change one field, also pass the others from github_get_pull_request_filter.
        |Only changes the IDE UI; nothing on GitHub is modified. Returns the applied filter like github_get_pull_request_filter.
        """
    )
    suspend fun github_set_pull_request_filter(
        @McpDescription("Search query (GitHub search syntax).")
        query: String = "",
        @McpDescription("open, closed or merged.")
        state: String = "",
        @McpDescription("Author login.")
        author: String = "",
        @McpDescription("Assignee login.")
        assignee: String = "",
        @McpDescription("Label name.")
        label: String = "",
        @McpDescription(
            "no_review, required, approved, changes_requested, reviewed_by_me, not_reviewed_by_me " +
                "or awaiting_review (review requested from the current user)."
        )
        reviewState: String = "",
        @McpDescription("newest, oldest, most_commented, least_commented, recently_updated or least_recently_updated.")
        sort: String = "",
        @McpDescription(REPOSITORY_PARAMETER_DESCRIPTION)
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
