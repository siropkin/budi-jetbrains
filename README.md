# budi — JetBrains plugin

[![Build](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml/badge.svg)](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

See what your AI coding costs at a glance. **budi** sits in your status bar and shows rolling spend for the last **1 day / 7 days / 30 days**, scoped to your JetBrains IDE. No extra panels, no popups — just three numbers in the corner.

```
budi · $2.34 1d · $12.50 7d · $48.10 30d
```

Click the widget to open the [cloud dashboard](https://app.getbudi.dev). Spend is broken down by editor so you can see exactly where your AI budget goes.

## Status bar states

| State   | Status bar                                | What it means                                                  |
| ------- | ----------------------------------------- | -------------------------------------------------------------- |
| Healthy | `budi · $X 1d · $Y 7d · $Z 30d`         | Daemon running, spend recorded in the rolling window.          |
| Idle    | `budi · $0.00 1d · $0.00 7d · $0.00 30d` | Daemon running, no spend yet (not an error).                   |
| Offline | `budi · offline`                          | Daemon not reachable. Run `budi doctor` to diagnose.           |
| Loading | `budi`                                    | Starting up, first reading on the way.                         |
| Setup   | `budi · setup`                            | First run — click to install the budi daemon.                  |

## Install

Search **budi** in <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>, or jump to the listing: **[plugins.jetbrains.com/plugin/31662-budi](https://plugins.jetbrains.com/plugin/31662-budi)**.

Or via the CLI:

```bash
budi integrations install --with jetbrains-plugin
```

### Prerequisites

budi needs a local [daemon](https://getbudi.dev) to read your AI usage. Install it with `budi init` — it starts automatically and runs in the background.

If the daemon isn't installed yet, the plugin shows a sticky balloon with the platform-specific install command. The command is never auto-executed — you copy and run it yourself.

## Settings

<kbd>Settings</kbd> → <kbd>Tools</kbd> → <kbd>budi</kbd> exposes daemon URL, cloud endpoint, polling interval, the `includeOtherSurfaces` opt-out, and a read-only **Detected sources** row showing what the daemon is tailing for `surface=jetbrains`.

## Troubleshooting

**`budi · offline`** — Run `budi doctor` to check daemon health. Run `budi init` if the daemon is not running.

**`budi · offline` + upgrade balloon** — The local daemon is older than this plugin requires. Run `budi update` (or `brew upgrade budi`) and restart the IDE.

## Compatibility

| | Pinned in `gradle.properties` |
| --- | --- |
| IntelliJ Platform floor | `2024.2` (`pluginSinceBuild = 242`) |
| IntelliJ Platform ceiling | `2025.2.x` (`pluginUntilBuild = 252.*`) |
| Java toolchain | 21 |

Verified against IntelliJ IDEA Community 2024.2, 2024.3, 2025.1, and 2025.2 by JetBrains' Plugin Verifier on every build.

## Build from source

```bash
./gradlew buildPlugin     # produce build/distributions/budi-<version>.zip
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew test            # run JUnit tests
./gradlew check           # tests + plugin-structure verifier + ktlint + detekt
```

Then <kbd>Settings</kbd> → <kbd>Plugins</kbd> → ⚙ → <kbd>Install plugin from disk…</kbd> and pick the zip.

For architecture and contributor details, read **[SOUL.md](./SOUL.md)**.

## Ecosystem

- **[budi](https://github.com/siropkin/budi)** — Daemon + CLI (required).
- **[budi-cursor](https://github.com/siropkin/budi-cursor)** — VS Code / Cursor extension sibling.
- **[budi-cloud](https://github.com/siropkin/budi-cloud)** — Cloud dashboard and ingest API.
- **[homebrew-budi](https://github.com/siropkin/homebrew-budi)** — Homebrew tap for the `budi` CLI.
- **[getbudi.dev](https://github.com/siropkin/getbudi.dev)** — Public marketing site.

## License

[MIT](./LICENSE).
