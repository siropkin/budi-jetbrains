<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

## [0.1.2]

### Changed

- Widen `pluginUntilBuild` from `252.*` to `261.*` so the plugin installs on WebStorm / IntelliJ / PyCharm builds up through the 2026.1 line. The widget surfaces (`StatusBarWidget`, `Configurable`, `NotificationGroupManager`) have been stable since 2024.2; the widened range matches that.

### Fixed

- Drop Kotlin-synthesized bridge for the deprecated `StatusBarWidget.getPresentation(PlatformType)` overload. Moves the `TextPresentation` implementation into a separate inner object and switches the Kotlin compiler to `-jvm-default=no-compatibility` so default-interface methods stop being re-emitted as `DefaultImpls`-style bridges. Removes the 3 deprecated-API hits the Marketplace plugin verifier flagged on 0.1.1.

## [0.1.1]

### Added

- Settings panel: read-only **Detected sources** row in `Settings → Tools → budi` that lists the filesystem paths the daemon is tailing for `surface=jetbrains`. Refreshes on panel open; degrades quietly to "No sources detected" when the daemon is offline or the endpoint isn't implemented yet. Wire: `GET /health/sources?surface=jetbrains`. Daemon-side follow-up tracked in siropkin/budi#735. (#33, #36)
- Settings panel: top-of-panel note clarifying that the status bar reflects the daemon's `surface=jetbrains` rollup, that v0.1 tracks GitHub Copilot for JetBrains, and that JetBrains AI Assistant tracking is planned for v0.2. Daemon-side follow-up tracked in siropkin/budi#736. (#32, #35)

## [0.1.0]

### Changed

- Promote v0.1.0-beta.1 to **Stable** channel after dogfooding. No functional changes from the beta — same feature set, same compatibility floor (IntelliJ Platform 2024.2 / 242). See `[0.1.0-beta.1]` below for the full feature list.

## [0.1.0-beta.1]

### Added

- Repo bootstrap: pinned platform floor (`2024.2` / `242`), plugin metadata, MIT license, contributor scaffolding.
- Daemon HTTP client (`BudiClient`) — `/health` and `/analytics/statusline` requests with `surface=jetbrains` filter, 3 s timeout, 64 KB body cap, JSON-only content-type guard. Pure helpers: `resolveCosts`, `formatCost`, `formatCostLine`, `deriveHealthState`, `buildStatusText`, `buildTooltip`, `clickUrl`, `buildStatuslineUrl`, `isLoopbackDaemonUrl`, `isAllowedCloudEndpoint`. Mirrors `budi-cursor`'s `budiClient.ts` shape for byte-identical statusline rendering.
- Status-bar widget (`BudiStatusBarWidgetFactory` / `BudiStatusBarWidget`) — renders `budi · $X 1d · $Y 7d · $Z 30d`; click-through opens the cloud dashboard (or the welcome notification on first run).
- Background poller (`BudiPoller`) — application-scoped `Alarm` loop, single in-flight request, coalesces overlapping refreshes.
- Persistent settings (`BudiSettings` / `BudiConfigurable`) — Settings → Tools → budi page exposes daemon URL (loopback-validated), cloud endpoint (getbudi.dev-validated), polling interval (3 s minimum), the `includeOtherSurfaces` opt-out that drops the `?surface=jetbrains` filter, and the upgrade-prompt suppress toggle.
- First-run welcome notification — sticky balloon under a `Budi` notification group; "Show install command" surfaces the platform-appropriate Homebrew / curl / PowerShell one-liner with copy-to-clipboard. Command is never auto-executed.
- Actionable upgrade prompt (`BudiUpgradeNotifier`) — sticky balloon when the daemon's `api_version` is below `MIN_API_VERSION`. Two-layer throttle: per-session latch + persistent suppress that auto-resets on stale → healthy → stale transitions.
- Pinned install / upgrade commands (`BudiInstallCommands`) for macOS / Linux / Windows, mirroring `siropkin/budi-cursor/src/installCommands.ts`.

## [0.0.1-beta.2]

### Added

- Auto-publish dry-run: first CI-driven push to JetBrains Marketplace under the Beta channel. Validates that `release.yml` builds, signs (skipped for v0.1), and publishes via `PUBLISH_TOKEN` end-to-end. Closes #4.

[Unreleased]: https://github.com/siropkin/budi-jetbrains/compare/0.1.2...HEAD
[0.1.2]: https://github.com/siropkin/budi-jetbrains/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/siropkin/budi-jetbrains/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/siropkin/budi-jetbrains/compare/0.1.0-beta.1...0.1.0
[0.1.0-beta.1]: https://github.com/siropkin/budi-jetbrains/compare/0.0.1-beta.2...0.1.0-beta.1
[0.0.1-beta.2]: https://github.com/siropkin/budi-jetbrains/commits/0.0.1-beta.2
