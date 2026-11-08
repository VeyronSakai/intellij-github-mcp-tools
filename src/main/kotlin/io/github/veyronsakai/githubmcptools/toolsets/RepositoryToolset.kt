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
        |Lists the project's GitHub repositories and the one the IDE's Pull Requests tool window is connected to. Read-only.
        |Returns JSON: {"connected": "owner/repo" or null (not connected yet), "account": "login" or null,
        |"repositories": [{"repository": "owner/repo", "url": "...", "connected": true|false}]}.
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
                        put("connected", repo.isSameAs(status.connected))
                    }
                }
            }
        }.toString()
    }

    @McpTool
    @McpDescription(
        """
        |Selects the repository of the IDE's Pull Requests tool window and shows its pull request list.
        |The other tools then use it by default. Only changes the IDE UI; nothing on GitHub is modified.
        """
    )
    suspend fun github_select_repository(
        @McpDescription("owner/repo or GitHub URL.")
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
