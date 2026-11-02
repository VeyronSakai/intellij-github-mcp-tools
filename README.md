# GitHub MCP Tools

![Build](https://github.com/VeyronSakai/intellij-github-mcp-tools/workflows/Build/badge.svg)

<!-- Plugin description -->
An IntelliJ Platform plugin (IntelliJ IDEA, Rider, PyCharm, ...) that adds GitHub tools to the IDE's built-in MCP server.
MCP clients such as Claude Code can use them to open pull requests in the IDE's Pull Requests tool window.

The tools only open and show things in the IDE. They never modify data on GitHub
(use the official GitHub MCP server for that).
<!-- Plugin description end -->

This is an independent plugin and is not affiliated with or endorsed by GitHub or JetBrains.

## Installation

- From JetBrains Marketplace: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for "GitHub MCP Tools" > <kbd>Install</kbd>
- Manually: download the zip from the [latest release](https://github.com/VeyronSakai/intellij-github-mcp-tools/releases/latest),
  then <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Usage

1. Enable the IDE's MCP server: <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>MCP Server</kbd>.
2. Connect your MCP client (e.g. Claude Code) to it; the same settings page shows how.
3. Open a project with a GitHub remote, and log in to GitHub in the IDE (<kbd>Settings</kbd> > <kbd>Version Control</kbd> > <kbd>GitHub</kbd>).
4. Ask the client, for example: "Open pull request #123 in the IDE".

## Tools

| Tool | Description |
| --- | --- |
| `github_list_repositories` | Lists the project's GitHub repositories, the one selected in the Pull Requests tool window, and the GitHub account. Read-only. |
| `github_select_repository` | Selects the repository of the Pull Requests tool window and shows its pull request list. Parameter: `repository` (`owner/repo`). |
| `github_open_pull_request_view` | Shows a pull request: details in the tool window, plus timeline and/or diff in editor tabs. Parameters: `number`, `kind` (`timeline` (default), `diff`, `all`), `repository` (optional). |
| `github_open_pull_request_list` | Shows the pull request list. Parameters: `refresh` (reload from GitHub, default false), `repository` (optional). |
| `github_close_pull_request_view` | Closes the timeline and/or diff views of pull requests. Does **not** close pull requests on GitHub. Parameters (all optional): `number` (all pull requests if omitted), `kind` (`timeline`, `diff`, `all` (default)), `repository`. |
| `github_close_tool_window` | Hides the Pull Requests tool window. |
| `github_get_pull_request_filter` | Returns the filter of the pull request list. Read-only. |
| `github_set_pull_request_filter` | Replaces the filter of the pull request list. Parameters (all optional): `query`, `state`, `author`, `assignee`, `label`, `reviewState`, `sort`, `repository`. |

When `repository` is given and differs from the selected one, the tool window is switched to it first.

## Requirements

- IntelliJ Platform IDE 2025.3 (build 253) or later, e.g. IntelliJ IDEA or Rider
- Bundled plugins enabled: GitHub, MCP Server
- The project has a GitHub remote, and you are logged in to GitHub in the IDE

## Development

```shell
./gradlew buildPlugin   # build the plugin zip into build/distributions
./gradlew runLocalIde   # run an IDE installed on your machine with the plugin (see below)
./gradlew runIde        # download and run IntelliJ IDEA (the oldest supported version) with the plugin
```

`runLocalIde` needs the installation path of the IDE to run (IntelliJ IDEA, Rider, ...) in the Gradle property `localIdePath`.
Set it outside the repository, e.g. in `~/.gradle/gradle.properties`:

```properties
localIdePath=/Applications/Rider.app
```

or pass it on the command line: `./gradlew runLocalIde -PlocalIdePath=<path>`.

## License

[Apache License 2.0](LICENSE)
