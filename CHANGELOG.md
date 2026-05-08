<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

### Added

- Auto-publish dry-run: first CI-driven push to JetBrains Marketplace under the Beta channel. Validates that `release.yml` builds, signs (skipped for v0.1), and publishes via `PUBLISH_TOKEN` end-to-end. Closes #4.
- Repo bootstrap: pinned platform floor (`2024.2` / `242`), plugin metadata, MIT license, contributor scaffolding.
- Daemon HTTP client (`BudiClient`) — `/health` and `/analytics/statusline` requests with `surface=jetbrains` filter, 3 s timeout, 64 KB body cap, JSON-only content-type guard. Pure helpers: `resolveCosts`, `formatCost`, `formatCostLine`, `deriveHealthState`, `buildStatusText`, `buildTooltip`, `clickUrl`, `buildStatuslineUrl`, `isLoopbackDaemonUrl`, `isAllowedCloudEndpoint`. Mirrors `budi-cursor`'s `budiClient.ts` shape for byte-identical statusline rendering.
- Status-bar widget (`BudiStatusBarWidgetFactory` / `BudiStatusBarWidget`) — renders `budi · $X 1d · $Y 7d · $Z 30d`; click-through opens the cloud dashboard (or the welcome notification on first run).
- Background poller (`BudiPoller`) — application-scoped `Alarm` loop, single in-flight request, coalesces overlapping refreshes. Reads from the persistent settings, writes to the shared app-state, marshals widget repaints onto the EDT.
- Persistent settings (`BudiSettings` / `BudiConfigurable`) — Settings → Tools → budi page exposes daemon URL (loopback-validated), cloud endpoint (getbudi.dev-validated), polling interval (3 s minimum), and the `includeOtherSurfaces` opt-out that drops the `?surface=jetbrains` filter.
- First-run welcome notification — sticky balloon under a `Budi` notification group; "Show install command" surfaces the platform-appropriate Homebrew / curl / PowerShell one-liner with copy-to-clipboard. Command is never auto-executed.
- Pinned install / upgrade commands (`BudiInstallCommands`) for macOS / Linux / Windows, mirroring `siropkin/budi-cursor/src/installCommands.ts`.
- Actionable upgrade prompt (`BudiUpgradeNotifier`) — sticky balloon when the daemon's `api_version` is below `MIN_API_VERSION`. Two-layer throttle: per-session latch (one balloon per IDE start) + persistent `suppressUpdateNotification` setting (auto-resets when the daemon catches up and then drifts stale again). "Show update command" action surfaces both the universal `budi update` one-liner and the platform-specific upgrade fallback. Never auto-runs anything; user copies and runs themselves.

## [0.1.0]

_Planned. Status-bar widget, daemon health polling, surface=jetbrains analytics filter. Publishing recipe lands ahead of the feature code under v0.0.1-beta.x._
