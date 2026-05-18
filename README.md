# budi — JetBrains plugin

[![Build](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml/badge.svg)](https://github.com/siropkin/budi-jetbrains/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31662.svg)](https://plugins.jetbrains.com/plugin/31662-budi)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

A quiet status-bar widget for JetBrains IDEs that shows your AI coding spend over the last **1 day / 7 days / 30 days**, scoped to the IDE you're working in. No tool window, no popup, no nag — just three numbers in the corner of the IDE.

```
budi · $1.42 1d · $8.30 7d · $34.21 30d
```

## Status bar states

| State | Display | Meaning |
| --- | --- | --- |
| **Healthy** | `budi · $X 1d · $Y 7d · $Z 30d` | Daemon reachable, recent traffic. |
| **Idle** | `budi · $0.00 1d · $0.00 7d · $0.00 30d` | Daemon reachable, no recent traffic. |
| **Offline** | `budi · offline` | Daemon unreachable or API version too low. |
| **Loading** | `budi` | Waiting for the first daemon response. |
| **Setup** | `budi · setup` | Daemon not installed. Balloon offers the install command. |

Click the widget to open the [cloud dashboard](https://app.getbudi.dev).

## Install

1. <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search **budi** > <kbd>Install</kbd>.

Or go directly to **[plugins.jetbrains.com/plugin/31662-budi](https://plugins.jetbrains.com/plugin/31662-budi)**.

To build from source: `./gradlew buildPlugin` produces a zip you can install via <kbd>Install plugin from disk...</kbd>.

## Prerequisites

The plugin requires the [budi daemon](https://getbudi.dev) running locally on `127.0.0.1:7878`. On first run a balloon shows the platform-specific install command (Homebrew / curl / PowerShell) — copy and run it yourself.

## Commands

| Command | What it does |
| --- | --- |
| `budi: Open Dashboard` | Opens the cloud dashboard in your browser. |

## Configuration

<kbd>Settings/Preferences</kbd> > <kbd>Tools</kbd> > <kbd>budi</kbd>

| Setting | Default | Description |
| --- | --- | --- |
| Daemon URL | `http://127.0.0.1:7878` | Address of the local daemon. |
| Cloud URL | `https://app.getbudi.dev` | Cloud dashboard endpoint. |
| Polling interval | `30 s` | How often the widget refreshes. |
| Include other surfaces | `true` | Show spend from all surfaces, not just JetBrains. |
| Detected sources | *(read-only)* | Filesystem paths the daemon tails for `surface=jetbrains`. |

## Troubleshooting

- **Widget shows "offline"** — check the daemon is running: `curl http://127.0.0.1:7878/health`. If the daemon is running but the widget stays offline, the daemon's API version may be too low — run `budi update`.
- **Widget shows "setup"** — the daemon has never been seen on this machine. Run the install command from the balloon or visit [getbudi.dev](https://getbudi.dev).
- **Costs look wrong** — the daemon aggregates what its transcript tailers see. Restart the daemon and check the cloud dashboard for a detailed breakdown.

## Ecosystem

- **[siropkin/budi](https://github.com/siropkin/budi)** — the daemon (Rust). Owns SQLite and the statusline contract.
- **[siropkin/budi-cloud](https://github.com/siropkin/budi-cloud)** — cloud dashboard at [app.getbudi.dev](https://app.getbudi.dev).
- **[siropkin/budi-cursor](https://github.com/siropkin/budi-cursor)** — VS Code / Cursor extension. Same daemon, sibling surface.
- **[siropkin/homebrew-budi](https://github.com/siropkin/homebrew-budi)** — Homebrew tap for macOS installs.
- **[siropkin/getbudi.dev](https://github.com/siropkin/getbudi.dev)** — marketing site at [getbudi.dev](https://getbudi.dev).

## License

[MIT](./LICENSE).
