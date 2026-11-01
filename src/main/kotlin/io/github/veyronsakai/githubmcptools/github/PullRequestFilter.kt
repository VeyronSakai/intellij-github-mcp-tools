package io.github.veyronsakai.githubmcptools.github

import org.jetbrains.plugins.github.pullrequest.ui.filters.GHPRListSearchValue

/**
 * Filter of the pull request list in the Pull Requests tool window.
 * Mirrors `GHPRListSearchValue` (same on 253 / 261 / 262 / 263). null means "not set".
 * Enum values are lower-case names of the GitHub plugin enums, e.g. `open`, `changes_requested`, `recently_updated`.
 */
internal data class PullRequestFilter(
    val query: String? = null,
    val state: String? = null,
    val author: String? = null,
    val assignee: String? = null,
    val label: String? = null,
    val reviewState: String? = null,
    val sort: String? = null,
) {
    fun toSearchValue(): GHPRListSearchValue = GHPRListSearchValue(
        searchQuery = query,
        state = state?.let { parseEnum<GHPRListSearchValue.State>(it, "state") },
        assignee = assignee,
        reviewState = reviewState?.let { parseEnum<GHPRListSearchValue.ReviewState>(it, "reviewState") },
        author = author,
        label = label,
        sort = sort?.let { parseEnum<GHPRListSearchValue.Sort>(it, "sort") },
    )

    class InvalidValueException(message: String) : IllegalArgumentException(message)

    companion object {
        fun of(value: GHPRListSearchValue) = PullRequestFilter(
            query = value.searchQuery,
            state = value.state?.name?.lowercase(),
            author = value.author,
            assignee = value.assignee,
            label = value.label,
            reviewState = value.reviewState?.name?.lowercase(),
            sort = value.sort?.name?.lowercase(),
        )

        private inline fun <reified E : Enum<E>> parseEnum(value: String, field: String): E {
            val normalized = value.trim().replace('-', '_').uppercase()
            return enumValues<E>().firstOrNull { it.name == normalized }
                ?: throw InvalidValueException(
                    "Invalid $field '$value'. Allowed values: ${enumValues<E>().joinToString { it.name.lowercase() }}."
                )
        }
    }
}
