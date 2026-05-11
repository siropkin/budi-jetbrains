# budi — JetBrains plugin

[![Build](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml/badge.svg)](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

JetBrains IDE status-bar plugin for [budi](https://getbudi.dev). Renders your AI coding spend over the last **1 day / 7 days / 30 days** without you ever leaving the editor — same shape as the [Claude Code statusline](https://docs.anthropic.com/en/docs/claude-code).

```
budi · $1.42 1d · $8.30 7d · $34.21 30d
```

JetBrains-rooted Copilot Chat usage is tracked under the `jetbrains` surface so the [cloud dashboard](https://app.getbudi.dev) can break costs down by editor host.

> **Status:** v0.1 in development. Listing is published to the [JetBrains Marketplace Beta channel](https://plugins.jetbrains.com/plugin/31662-budi/versions/beta) until daemon-path detection is confirmed on a non-dev machine.

## Install

### From JetBrains Marketplace (Stable)

1. <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>.
2. Search for **budi**.
3. <kbd>Install</kbd>, restart the IDE.

Or jump directly to the listing: **[plugins.jetbrains.com/plugin/31662-budi](https://plugins.jetbrains.com/plugin/31662-budi)**.

### Beta channel (pre-releases)

To pick up Beta builds before they promote to Stable:

1. <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → ⚙ → <kbd>Manage Plugin Repositories…</kbd>.
2. Add: `https://plugins.jetbrains.com/plugins/beta/list`.
3. Search for **budi** in the Marketplace tab.

### From a local build

```bash
./gradlew buildPlugin
# Result: build/distributions/budi-<version>.zip
```

Then <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → ⚙ → <kbd>Install plugin from disk…</kbd> and pick the zip.

### Daemon dependency

You also need the [budi daemon](https://getbudi.dev) running locally on `127.0.0.1:7878`. On first run, the plugin shows a sticky balloon with the platform-specific install one-liner (Homebrew on macOS, curl pipe on Linux, PowerShell on Windows). The command is **never** auto-executed — you copy and run it yourself.

## What you get

- **Status-bar widget** — `budi · $X 1d · $Y 7d · $Z 30d`. Click opens the [budi cloud dashboard](https://app.getbudi.dev/dashboard).
- **First-run hand-off** — sticky balloon with the canonical install command when the daemon is missing. Auto-retires on the first daemon reading.
- **Actionable upgrade prompt** — when the daemon's `api_version` is below this plugin's floor, a balloon offers `budi update` (plus the platform fallback) with a "Don't show again" action that auto-resets when the daemon catches up.
- **Settings panel** — <kbd>Settings/Preferences</kbd> → <kbd>Tools</kbd> → <kbd>budi</kbd> exposes daemon URL, cloud endpoint, polling interval, and the `includeOtherSurfaces` opt-out.

That's it. Per [SOUL.md](./SOUL.md), the plugin is intentionally statusline-only — no tool window, no session list, no vitals grid.

## Compatibility

| | Pinned in `gradle.properties` |
| --- | --- |
| IntelliJ Platform floor | `2024.2` (`pluginSinceBuild = 242`) |
| IntelliJ Platform ceiling | `2025.2.x` (`pluginUntilBuild = 252.*`) — bumped in lockstep with the verifier matrix |
| Java toolchain | 21 |

Verified against IntelliJ IDEA Community 2024.2, 2024.3, 2025.1, and 2025.2 by JetBrains' Plugin Verifier on every build.

## Development

```bash
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin     # produce a distributable zip
./gradlew test            # run JUnit tests
./gradlew check           # tests + plugin-structure verifier
./gradlew verifyPlugin    # JetBrains' Plugin Verifier against the IDE matrix
```

For a deeper architecture / contributor guide, read **[SOUL.md](./SOUL.md)** — the canonical AI-agent + human onboarding doc.

## Releasing

> **Auto-publish only.** No manual Marketplace uploads. Tag pushes are the single source of release truth — except for the very first slug-claim, which JetBrains policy requires to be a manual web-UI upload.

1. Bump `pluginVersion` in [`gradle.properties`](./gradle.properties). Suffix-driven channel routing:
   - `0.1.0-beta.1` → **Beta** channel.
   - `0.1.0` → **default / Stable** channel.
2. Update the `[Unreleased]` section of [`CHANGELOG.md`](./CHANGELOG.md).
3. Merge the bump to `main`. The [`Build`](./.github/workflows/build.yml) workflow creates a draft GitHub Release.
4. Edit the draft notes and click **Publish release** (tick **pre-release** for Beta tags). The [`Release`](./.github/workflows/release.yml) workflow then runs `./gradlew publishPlugin` against the JetBrains Marketplace.

### Required GitHub secrets

| Secret | Purpose | Required for v0.1 |
| --- | --- | --- |
| `PUBLISH_TOKEN` | Marketplace publish token (`Upload plugin` scope, scoped to plugin id `com.github.siropkin.budijetbrains`). Create at [plugins.jetbrains.com/author/me/tokens](https://plugins.jetbrains.com/author/me/tokens). | **Yes** |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Plugin-signing keys (see [docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)). | No — signing deferred to v0.2. |

To rotate `PUBLISH_TOKEN`: revoke the existing token at the link above, create a new one with the same scope, then update the repo secret under **Settings → Secrets and variables → Actions** (or run `gh secret set PUBLISH_TOKEN --repo siropkin/budi-jetbrains`).

## Repo layout

| Path | What lives there |
| --- | --- |
| [`SOUL.md`](./SOUL.md) | Canonical AI-agent + contributor guide. **Read first.** |
| [`src/main/kotlin/.../daemon/BudiClient.kt`](./src/main/kotlin/com/github/siropkin/budijetbrains/daemon/BudiClient.kt) | HTTP client, request builder, health-state derivation, formatting helpers. All pure logic. |
| [`src/main/kotlin/.../poller/BudiPoller.kt`](./src/main/kotlin/com/github/siropkin/budijetbrains/poller/BudiPoller.kt) | Background polling loop (Alarm-based, off-EDT). |
| [`src/main/kotlin/.../statusbar/`](./src/main/kotlin/com/github/siropkin/budijetbrains/statusbar/) | Status-bar widget factory + widget. |
| [`src/main/kotlin/.../settings/`](./src/main/kotlin/com/github/siropkin/budijetbrains/settings/) | `PersistentStateComponent` + `Configurable`. |
| [`src/main/kotlin/.../notifier/`](./src/main/kotlin/com/github/siropkin/budijetbrains/notifier/) | Welcome + actionable upgrade balloons. |
| [`src/main/kotlin/.../install/`](./src/main/kotlin/com/github/siropkin/budijetbrains/install/) | Pinned install + upgrade commands per platform. |
| [`src/main/resources/META-INF/plugin.xml`](./src/main/resources/META-INF/plugin.xml) | Plugin manifest. |
| [`src/test/kotlin`](./src/test/kotlin) | JUnit 4 tests. |
| [`gradle.properties`](./gradle.properties) | All pinned versions (platform floor, plugin version, channel-routing inputs). |
| [`.github/workflows/build.yml`](./.github/workflows/build.yml) | CI: build, test, verify, draft release. |
| [`.github/workflows/release.yml`](./.github/workflows/release.yml) | CI: Marketplace publish on GitHub Release. |

## Related projects

- **[siropkin/budi](https://github.com/siropkin/budi)** — the daemon this plugin talks to. Rust; owns SQLite and the statusline contract.
- **[siropkin/budi-cloud](https://github.com/siropkin/budi-cloud)** — cloud dashboard at [`app.getbudi.dev`](https://app.getbudi.dev). Opens when you click the status-bar item.

## License

[MIT](./LICENSE).
