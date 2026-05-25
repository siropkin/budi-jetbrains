<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

## [0.1.6] - 2026-05-25

### Added

- Cloud onboard notification for browser-based sign-up — when the daemon reports `cloud_onboard_url`, a one-time balloon guides the user to complete account setup in their browser. (#92)
- Per-provider cost breakdown and billing-cycle pacing in the status bar tooltip. (#88)
- Active-session burn rate displayed in the tooltip and optionally in the status bar. (#90)
- Copilot quota / requests-remaining mode in the status bar for quota-based plans. (#86)

### Changed

- Rewrite plugin description for the Marketplace listing. (#76)
- Point Privacy Policy link at getbudi.dev/privacy. (#84)
- Codify canonical Marketplace listing URLs. (#83)
- Restructure README hero with Marketplace badges. (#80)

## [0.1.5] - 2026-05-21

### Added

- One-time notification when the budi daemon binary is not installed, with a platform-appropriate install command. (#69)
- Plugin icon for the JetBrains Marketplace listing. (#67)
- Screenshots (status bar widget, settings panel, setup notification) for the Marketplace listing and README. (#68)

### Fixed

- Drop `withContext(Dispatchers.IO)` to avoid `SpillingKt` class-loading errors on IC-242/243.

## [0.1.4] - 2026-05-18

Updated plugin.xml description to sync with Cursor marketplace listing.

## [0.1.3]

Code quality & hygiene milestone — no user-visible behavior changes. Closes the [`v0.1.3 — Code quality & hygiene`](https://github.com/siropkin/budi-jetbrains/milestone/2) milestone.

### Changed

- Wire `ktlint` + `detekt` into `./gradlew check` so style and smell findings gate CI. Pre-existing detekt findings are pinned in `config/detekt/detekt-baseline.xml` — track that file toward zero rather than letting it grow. (#49, #55)
- KDoc pass on the public surface and load-bearing internals (`BudiClient`, statusline helpers, settings, poller, widget). (#50, #53)
- Tighten internal visibility and rename the first-run notifier file to match its class. (#44, #52)
- Drop the legacy `today_cost` / `week_cost` / `month_cost` statusline aliases — the daemon's canonical `1d` / `7d` / `30d` keys are the only ones the plugin now reads. (#47, #60)

### Removed

- Dead-code & unused-symbol sweep across production Kotlin sources. (#48, #56)

### Fixed

- Skip the welcome balloon when the project is disposed mid-wait, fixing a startup-race NPE seen in plugin verifier runs. (#51)

### Tests

- Raise the coverage floor for `BudiAppState` and `buildTooltip` routing. (#46, #59)

### Build

- Bump `org.jlleitschuh.gradle.ktlint` from 12.1.2 to 14.2.0. (#58)
- Bump `io.gitlab.arturbosch.detekt` from 1.23.7 to 1.23.8. (#57)
- Bump Gradle wrapper from 9.5.0 to 9.5.1. (#42)
- Bump `nick-fields/retry` from 3 to 4. (#41)

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

[Unreleased]: https://github.com/siropkin/budi-jetbrains/compare/0.1.6...HEAD
[0.1.6]: https://github.com/siropkin/budi-jetbrains/compare/0.1.5...0.1.6
[0.1.5]: https://github.com/siropkin/budi-jetbrains/compare/0.1.4...0.1.5
[0.1.4]: https://github.com/siropkin/budi-jetbrains/compare/0.1.3...0.1.4
[0.1.3]: https://github.com/siropkin/budi-jetbrains/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/siropkin/budi-jetbrains/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/siropkin/budi-jetbrains/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/siropkin/budi-jetbrains/compare/0.1.0-beta.1...0.1.0
[0.1.0-beta.1]: https://github.com/siropkin/budi-jetbrains/compare/0.0.1-beta.2...0.1.0-beta.1
[0.0.1-beta.2]: https://github.com/siropkin/budi-jetbrains/commits/0.0.1-beta.2
