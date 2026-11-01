# intellij-github-mcp-tools

IntelliJ Platform IDE（主な利用先は Rider）に組み込まれた MCP サーバーへ、GitHub 連携機能を操作するツールを追加するプラグイン。
MCP クライアント（Claude Code など）から、IDE 内蔵 GitHub プラグインの UI（Pull Requests ツールウィンドウなど）を操作できるようにする。

- 独立した MCP サーバーではない。IDE 内蔵 MCP サーバーの拡張ポイント `com.intellij.mcpServer.mcpToolset` にツールを登録するプラグインである。
- 目的は「IDE の UI で開く・表示する」こと。PR の作成やマージなど GitHub 上のデータを変更する操作はスコープ外（GitHub 公式 MCP サーバーの役割）。

## 技術スタック

- Kotlin / IntelliJ Platform Gradle Plugin 2.x（`org.jetbrains.intellij.platform.settings` 2.19.0、Gradle 9.8.1）
- 構成は JetBrains 公式の `intellij-platform-plugin-template` に準拠
- コンパイル対象: IntelliJ IDEA `platformVersion`（gradle.properties、対応範囲で最古の 2025.3 系）。Kotlin の `apiVersion` も 2.2 に固定している。プラグインはプラットフォーム API と同梱プラグインのみに依存するため Rider でも動く
- 依存する同梱プラグイン: `org.jetbrains.plugins.github`、`com.intellij.mcpServer`
- 対応 IDE: ビルド 253（2025.3）以降（`pluginSinceBuild`）
- プラグイン ID: `io.github.veyronsakai.githubmcptools` ／ 表示名: `GitHub MCP Toolset`（公開後に ID は変更不可）

## コマンド

```shell
./gradlew buildPlugin   # ビルド。build/distributions に zip が出る
./gradlew runRider      # gradle.properties の riderLocalPath の Rider でプラグインを読み込んで起動
./gradlew runIde        # IntelliJ IDEA で起動
```

`runRider` / `runIde` は IDE を GUI で起動する。動作確認（UI 上で PR が開くか）はユーザーが行うので、起動後はユーザーに確認を依頼すること。

## ディレクトリ構成

```
src/main/kotlin/io/github/veyronsakai/githubmcptools/
  toolsets/   McpToolset 実装。機能領域ごとに 1 クラス（PR、Issue など）
  github/     GitHub プラグインの内部 API へのアクセスをすべてここに集約する
src/main/resources/META-INF/plugin.xml
```

## 内部 API について（重要）

GitHub プラグインの以下のクラスは `@ApiStatus.Internal` で、IDE のバージョンによって変わる。
新しい API を使う前に、必ず intellij-community のソースで **ブランチ 253 / 261 / 262 / 263 のすべてに同じシグネチャで存在すること** を確認する。

```shell
git clone --depth 1 --filter=blob:none --sparse https://github.com/JetBrains/intellij-community.git /tmp/ic
cd /tmp/ic && git sparse-checkout set plugins/github plugins/mcp-server
# 特定ブランチのファイルを見る
git fetch --depth 1 --filter=blob:none origin 253
git show FETCH_HEAD:plugins/github/github-core/src/org/jetbrains/plugins/github/pullrequest/ui/GHPRConnectedProjectViewModel.kt
```

MCP ツールの書き方は `plugins/mcp-server/README.md` が公式の詳細ガイド（ただし master 基準なので、253 に無い機能がある点に注意）。

### 確認済みの事実（253 / 261 / 262 / 263 で確認）

- `GHPRProjectViewModel`（`org.jetbrains.plugins.github.pullrequest.ui`）はプロジェクトサービス。`project.serviceAsync<GHPRProjectViewModel>()` で取得
- `GHPRProjectViewModel.activate()` と `connectedProjectVm: StateFlow<GHPRConnectedProjectViewModel?>` は全バージョンで同じ
- `GHPRConnectedProjectViewModel` には `repository`、`dataContext`、`viewPullRequest(id, requestFocus)`、`openPullRequestInfoAndTimeline(number: Long)`、`openPullRequestTimeline(id, requestFocus)` がある
- `GHPRIdentifier` は `data class GHPRIdentifier(val id: String, val number: Long)`。`id` は GraphQL のノード ID で、番号だけでは作れない
- `GHPRProjectViewModel`・`GHPRConnectedProjectViewModel` を含む `intellij.vcs.github` モジュールは `loading="embedded"` なので、`<depends>org.jetbrains.plugins.github</depends>` だけでクラスにアクセスできる
- `GHPRProjectViewModel.isAvailable: StateFlow<Boolean>` は GitHub リポジトリが見つかっているかを表す
- `openPullRequestInfoAndTimeline(number)` は非同期で、該当 PR が無いときは何もせず終わる（エラーは返らない）
- `GHRepositoryCoordinates.toUrl()` でリポジトリの URL が取れる
- 263 では MCP サーバーがコンテンツモジュールに分割されたが、`intellij.mcpserver` は `loading="embedded"` なので `<depends>com.intellij.mcpServer</depends>` で引き続きアクセスできる
- リポジトリの切り替えは `GHPRProjectViewModel.selectorVm`（`GHRepositoryAndAccountSelectorViewModel`）の `repositoriesState`、`repoSelectionState`、`accountSelectionState`、`missingCredentialsState`、`submitSelection()` で行う（UI のリポジトリ選択欄と同じ経路）。アカウント一覧は `service<GHAccountManager>().accountsState`
- PR 一覧のフィルターは `GHPRConnectedProjectViewModel.listVm.searchVm.searchState: MutableStateFlow<GHPRListSearchValue>`。値を代入すると一覧が再読み込みされる（GitHub プラグイン自身も同じ書き方をしている）。`GHPRListSearchValue` とその enum は全バージョンで同一
- MCP ツールの引数にデフォルト値を付けると省略可能になる（253 から対応）
- PR のタイムライン・差分のタブは、ファイルシステムのプロトコルが `ghpr` の仮想ファイル。パスは `GHPRVirtualFileSystem.GHPRFilePath` を Gson で JSON にしたもの（`repository.repositoryPath.owner/repository`、`prId.number`、`isDiff`）。クラス自体は `internal` なので、プロトコルとパスで見分ける（`GitHubPullRequestEditors` 参照）
- Pull Requests ツールウィンドウの ID は `"Pull Requests"`
- `GHPRConnectedProjectViewModel` の `viewList(requestFocus)`、`openPullRequestDiff(id, requestFocus)`、`listVm.refresh()`、`listVm.account` は全バージョンで同じ
- PR 番号 → `GHPRIdentifier` は `GHGQLRequests.PullRequest.findOneId(repository, number)` を `GithubApiRequestExecutor.Factory.getInstance().create(account.server, token).execute(EmptyProgressIndicator(), request)` で実行して得る（トークンは `GHAccountManager.findCredentials(account)`）。PR が無いと `null` ではなく `GithubConfusingException`（メッセージ "Could not resolve to a PullRequest ..."）が投げられる
- MCP 側: `McpToolset`、`@McpTool`、`@McpDescription`、`mcpFail()`、`CoroutineContext.project` は 253 から存在

### 使ってはいけないもの（理由つき）

- `GHPRProjectViewModel.activateAndAwaitProject` … 262 までは引数 1 つ、263 では引数 3 つ（デフォルト値つき）に変わっており、バイナリ互換性がない。`activate()` ＋ `connectedProjectVm.filterNotNull().first()` で代替する（`GitHubPullRequestsConnection` 参照）
- `GHPRDataContext.detailsService`（`findPRId` など）… Kotlin の `internal` なので外部プラグインからコンパイルできない
- Git4Idea のクラス（`GitRepository`、`git4idea.remote.hosting.*` など）… 262 以降は embedded でないモジュール `intellij.vcs.git.backend` にあり、このプラグインからは見えない可能性がある。ビルド後に `javap` で生成クラスが参照していないことを確認する
- `GHRepositoryConnectionManager` … Kotlin の `internal`
- `GHPRSearchPanelViewModel.getAuthors()` / `getLabels()` など … 262 で `StateFlow` のプロパティに変わった
- `GHPRListViewModel.loadedData` … `internal`
- `GithubApiRequestExecutor.execute(request)`（引数 1 つ）… 263 で `suspend` に変わった。`execute(indicator, request)` を `Dispatchers.IO` で使う
- `GHPRConnectedProjectViewModel.prOnCurrentBranch` … 型の `ComputedResult` が value class で、263 ではモジュールも移動している
- `checkoutPullRequest`、`isCheckedOut`、`openPullRequestInfoAndDiff` … 263 にしか無い
- `@McpToolHints`、`McpToolset.isExperimental()`、`displayName()`、`displayDescription()` … 253 に存在しない

## コーディング規約

- ツール名は `github_<動詞>_<目的語>` の snake_case（例: `github_open_pull_request_view`、`github_list_repositories`、`github_set_pull_request_filter`）。IDE 内蔵ツール（`get_file_problems`、`open_file_in_editor` など）や GitHub 公式 MCP サーバーと同じく動詞＋目的語にし、`pr` などの略語は使わない。接頭辞 `github_` は内蔵ツール（`get_repositories` など）との取り違えを防ぐため。GitHub 上の操作（PR の作成・クローズなど）と誤解される名前は避ける（例: 「open a pull request」は GitHub では PR の作成を意味するので `github_open_pull_request_view` とする）。ファイル先頭に `@file:Suppress("FunctionName", "unused")`
- ツール関数は `suspend fun`。プロジェクトは `currentCoroutineContext().project` で取得し、`projectPath` 引数を自分で宣言しない
- `@McpDescription` は LLM 向けの英語で書く。何をするか、副作用の有無（UI を開くだけでデータは変更しない、など）、失敗時の挙動、全パラメータの意味とデフォルト値を明記する
- 想定内のエラーは `mcpFail("...")` で返す。メッセージは LLM が次の行動を判断できるように書く（例: ユーザーに Pull Requests ツールウィンドウでログインを依頼する）
- `CancellationException` を catch しない。UI 操作が必要なら `withContext(Dispatchers.EDT)`、ブロッキング IO は `Dispatchers.IO`
- 新しいツールを追加したら plugin.xml の `<description>` と README.md のツール一覧を更新する

## 完了の定義

1. `./gradlew buildPlugin` が成功する
2. 新しく使った内部 API が 253 / 261 / 262 / 263 に存在することを確認済み
3. README.md と plugin.xml を更新済み
4. ユーザーに `./gradlew runRider` での動作確認手順を伝えた

コミットはユーザーの確認後に行う。push はユーザーに明示的に頼まれたときだけ行う。

## 今後の候補（未調査）

- `github_checkout_pull_request`: `checkoutPullRequest(id)` は 263 にしか無い。253〜262 で使える代替手段は未調査
- 現在のブランチの PR を開く: `prOnCurrentBranch` は使えない（上記）。`GHGQLRequests.PullRequest.searchByHeadBranches` も 263 にしか無い
- Issue 系: IDE に Issue 用ツールウィンドウは無く、GitHub を Tasks のタスクサーバーとして使う連携（`plugins/github/github-tracker`、モジュール `intellij.vcs.github.tracker`）が中心になる見込み
