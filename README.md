# budi — JetBrains plugin

[![Build](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml/badge.svg)](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml)

JetBrains IDE status-bar plugin for [budi](https://getbudi.dev). Shows your AI coding spend over the last 1d / 7d / 30d, mirroring the [Claude Code statusline](https://docs.anthropic.com/en/docs/claude-code) and the [budi-cursor](https://github.com/siropkin/budi-cursor) extension for VS Code / Cursor.

JetBrains-rooted Copilot Chat usage is tracked under the `jetbrains` surface so the cloud dashboard can break costs down by editor host.

> **Status:** v0.1 in development. The plugin is published to the JetBrains Marketplace [Beta channel](https://plugins.jetbrains.com/docs/marketplace/release-channel.html) until daemon-path detection is confirmed on a non-dev machine.

## Install

### From JetBrains Marketplace (recommended)

1. <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>.
2. Search for **budi**.
3. <kbd>Install</kbd>, restart the IDE.

To pick up Beta builds: open the gear icon → <kbd>Manage Plugin Repositories…</kbd> and add `https://plugins.jetbrains.com/plugins/beta/list`.

### From a local build

```bash
./gradlew buildPlugin
# Result: build/distributions/budi-<version>.zip
```

Then <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install plugin from disk…</kbd> and pick the zip.

You also need the [budi daemon](https://getbudi.dev) running locally on `127.0.0.1:7878` — the plugin guides you through the install on first run.

## Development

```bash
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin    # produce a distributable zip
./gradlew check          # run JUnit tests + plugin-structure verifier
./gradlew verifyPlugin   # run JetBrains' Plugin Verifier against the IDE matrix
```

Compatibility floor pinned in [`gradle.properties`](./gradle.properties): IntelliJ Platform `2024.2` (`242`) and above.

## Releasing

> **Auto-publish only.** No manual Marketplace uploads. Tag pushes are the single source of release truth.

1. Bump `pluginVersion` in [`gradle.properties`](./gradle.properties). Suffix the version (e.g. `0.1.0-beta.1`) to publish to the Beta channel; drop the suffix (e.g. `0.1.0`) to promote to Stable.
2. Update the `[Unreleased]` section of [`CHANGELOG.md`](./CHANGELOG.md).
3. Merge the bump to `main`. The [`Build`](./.github/workflows/build.yml) workflow creates a draft GitHub Release.
4. Edit the draft notes and click **Publish release**. The [`Release`](./.github/workflows/release.yml) workflow then runs `./gradlew publishPlugin` against the JetBrains Marketplace.

### Required GitHub secrets

| Secret | Purpose | Required for v0.1 |
| --- | --- | --- |
| `PUBLISH_TOKEN` | Marketplace publish token (`Upload plugin` scope, scoped to plugin id `com.github.siropkin.budijetbrains`). Create at [plugins.jetbrains.com/author/me/tokens](https://plugins.jetbrains.com/author/me/tokens). | **Yes** |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Plugin-signing keys (see [docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)). | No — signing skipped for v0.1; tracked as a v0.2 follow-up. |

To rotate `PUBLISH_TOKEN`: revoke the existing token at the link above, create a new one with the same scope, then update the repo secret under **Settings → Secrets and variables → Actions**.

## Repo layout

| Path | What lives there |
| --- | --- |
| [`src/main/kotlin`](./src/main/kotlin) | Plugin source. |
| [`src/main/resources/META-INF/plugin.xml`](./src/main/resources/META-INF/plugin.xml) | Plugin manifest — id, name, vendor, IDE compatibility. |
| [`src/test/kotlin`](./src/test/kotlin) | JUnit tests run by `./gradlew check`. |
| [`gradle.properties`](./gradle.properties) | All pinned versions (platform floor, Kotlin/JVM, plugin version). |
| [`.github/workflows/build.yml`](./.github/workflows/build.yml) | CI build, test, verify, draft release. |
| [`.github/workflows/release.yml`](./.github/workflows/release.yml) | Marketplace publish on GitHub Release. |

## Sibling repos

- [siropkin/budi](https://github.com/siropkin/budi) — daemon, CLI, and statusline contract.
- [siropkin/budi-cursor](https://github.com/siropkin/budi-cursor) — VS Code / Cursor extension this plugin mirrors.
- [siropkin/budi-cloud](https://github.com/siropkin/budi-cloud) — cloud dashboard.

## License

[MIT](./LICENSE).
