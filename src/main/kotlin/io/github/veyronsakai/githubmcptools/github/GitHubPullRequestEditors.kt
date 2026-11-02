package io.github.veyronsakai.githubmcptools.github

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A pull request view opened by the GitHub plugin in an editor tab (timeline or diff). */
internal data class PullRequestView(
    val repository: String?,
    /** null for the diff of a pull request that is not created yet */
    val number: Long?,
    val kind: Kind,
    val title: String,
) {
    enum class Kind {
        TIMELINE, DIFF;

        companion object {
            /** Parses `timeline`, `diff` or `all` (case-insensitive). null if invalid. */
            fun parseSet(value: String): Set<Kind>? = when (value.trim().lowercase()) {
                "timeline" -> setOf(TIMELINE)
                "diff" -> setOf(DIFF)
                "all" -> setOf(TIMELINE, DIFF)
                else -> null
            }
        }
    }
}

/**
 * Editor tabs and the tool window of the bundled GitHub plugin.
 *
 * The virtual file classes (`GHPRTimelineVirtualFile`, `GHPRDiffVirtualFile`, `GHPRVirtualFileSystem`) are Kotlin `internal`,
 * so files are recognized by their file system protocol `ghpr` and their path, which is the Gson JSON of
 * `GHPRVirtualFileSystem.GHPRFilePath` (`{"repository": {"repositoryPath": {"owner", "repository"}}, "prId": {"id", "number"}, "isDiff"}`).
 * Both are the same on branches 253 / 261 / 262 / 263.
 */
internal object GitHubPullRequestEditors {
    private const val PROTOCOL = "ghpr"

    /** `id` of `<toolWindow>` in the GitHub plugin's XML (same on 253 / 261 / 262 / 263). */
    private const val TOOL_WINDOW_ID = "Pull Requests"

    /**
     * Closes pull request views (editor tabs).
     * @param kinds kinds of views to close
     * @param repository `owner/repo` (or URL) to limit to, or null for any repository
     * @param number pull request number to limit to, or null for all pull requests
     * @return the closed tabs
     */
    suspend fun closeViews(project: Project, kinds: Set<PullRequestView.Kind>, repository: String?, number: Long?): List<PullRequestView> {
        val fullName = repository?.let(GitHubPullRequestsConnection::normalizeRepositoryName)
        return withContext(Dispatchers.EDT) {
            val manager = FileEditorManager.getInstance(project)
            manager.openFiles
                .mapNotNull { file -> parse(file)?.let { file to it } }
                .filter { (_, tab) ->
                    tab.kind in kinds &&
                        (fullName == null || tab.repository.equals(fullName, ignoreCase = true)) &&
                        (number == null || tab.number == number)
                }
                .onEach { (file, _) -> manager.closeFile(file) }
                .map { it.second }
        }
    }

    /** Hides the Pull Requests tool window. @return false if it was not visible */
    suspend fun hideToolWindow(project: Project): Boolean = withContext(Dispatchers.EDT) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null || !toolWindow.isVisible) return@withContext false
        toolWindow.hide()
        true
    }

    private fun parse(file: VirtualFile): PullRequestView? {
        if (file.fileSystem.protocol != PROTOCOL) return null
        return parsePath(file.path, file.presentableName)
    }

    /** Parses the path of a `ghpr` file (Gson JSON of `GHPRFilePath`). null if it is not such a path. */
    internal fun parsePath(rawPath: String, title: String): PullRequestView? {
        val path = try {
            Json.parseToJsonElement(rawPath).jsonObject
        }
        catch (_: SerializationException) {
            return null
        }
        catch (_: IllegalArgumentException) { // not a JSON object
            return null
        }
        val repositoryPath = path.obj("repository")?.obj("repositoryPath")
        val owner = repositoryPath?.string("owner")
        val name = repositoryPath?.string("repository")
        return PullRequestView(
            repository = if (owner != null && name != null) "$owner/$name" else null,
            number = path.obj("prId")?.primitive("number")?.longOrNull,
            kind = if (path.primitive("isDiff")?.booleanOrNull == true) PullRequestView.Kind.DIFF else PullRequestView.Kind.TIMELINE,
            title = title,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull
}
