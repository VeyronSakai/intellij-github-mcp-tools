package io.github.veyronsakai.githubmcptools.toolsets

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.project.Project
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection
import io.github.veyronsakai.githubmcptools.github.GitHubPullRequestsConnection.Result

/** Returns the connection, or fails the MCP call with a message that tells the LLM what to do next. */
internal fun Result.connectionOrFail(project: Project): GitHubPullRequestsConnection = when (this) {
    is Result.Connected -> connection
    Result.NoGitHubRepository -> mcpFail(
        "The project '${project.name}' has no Git remote pointing to GitHub, so the Pull Requests tool window cannot be used."
    )
    is Result.RepositoryNotFound -> mcpFail(
        "Repository '$requested' is not a GitHub repository of the project '${project.name}'. " +
            "Known repositories: ${known.joinToString { it.fullName }}. Use one of them (format: owner/repo)."
    )
    is Result.NotLoggedIn -> mcpFail(
        "No GitHub account for ${repository.url} is registered in the IDE. " +
            "Ask the user to log in to GitHub in the IDE (Settings | Version Control | GitHub), then call this tool again."
    )
    is Result.MissingCredentials -> mcpFail(
        "The GitHub account '$account' has no saved token. " +
            "Ask the user to log in again in the Pull Requests tool window, then call this tool again."
    )
    is Result.NotConnected -> mcpFail(
        "The Pull Requests tool window did not connect to ${requested?.fullName ?: "a GitHub repository"}. " +
            "Ask the user to open the Pull Requests tool window in the IDE, log in to GitHub " +
            "and select the repository, then call this tool again."
    )
}
