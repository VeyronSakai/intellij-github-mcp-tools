@file:Suppress("FunctionName", "unused")

package io.github.veyronsakai.githubmcptools.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class RepositoryToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Lists the GitHub repositories of the current project (found in its Git remotes)
        |and tells which one the IDE's Pull Requests tool window is connected to, and with which GitHub account.
        |Read-only: does not open the tool window or change anything.
        |Returns JSON: {"connected": "owner/repo" or null, "account": "login" or null,
        |"repositories": [{"repository": "owner/repo", "url": "...", "connected": true|false}]}.
        |An empty list means the project has no GitHub remote (or Git is still loading).
        |"connected" is null when the tool window is not connected yet (not logged in, no repository chosen, or never opened).
        """
    )
    suspend fun github_list_repositories(): String {
        val project = currentCoroutineContext().project
        val status = GitHubPullRequestsConnection.status(project)
        return buildJsonObject {
            put("connected", status.connected?.let { JsonPrimitive(it.fullName) } ?: JsonNull)
            put("account", status.account?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("repositories") {
                status.repositories.forEach { repo ->
                    addJsonObject {
                        put("repository", repo.fullName)
                        put("url", repo.url)
                        put("connected", repo == status.connected)
                    }
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription(
        """
        |Selects the GitHub repository that the IDE's Pull Requests tool window (bundled GitHub plugin) works with,
        |the same as choosing it in the tool window's repository selector, and shows its pull request list.
        |Other tools (github_open_pull_request_view, github_set_pull_request_filter, ...) then use this repository by default.
        |If the repository is already selected, only the list is shown.
        |This only changes the IDE UI; nothing on GitHub is modified.
        |Fails if `repository` is not one of the project's GitHub repositories (see github_list_repositories),
        |or if the user is not logged in to GitHub in the IDE (ask the user to log in, then retry).
        """
    )
    suspend fun github_select_repository(
        @McpDescription("Repository to select, as owner/repo (a GitHub URL is also accepted). Required.")
        repository: String,
    ): String {
        if (repository.isBlank()) mcpFail("repository is required (format: owner/repo). See github_list_repositories.")
        val project = currentCoroutineContext().project
        val connection = GitHubPullRequestsConnection.connect(project, repository).connectionOrFail(project)
        connection.viewList(refresh = false)
        return "Selected ${connection.repository.fullName} (account: ${connection.accountName}) " +
            "in the IDE's Pull Requests tool window and showed its pull request list."
    }
}
