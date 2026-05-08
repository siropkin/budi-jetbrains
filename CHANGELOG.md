<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

### Added

- Auto-publish dry-run: first CI-driven push to JetBrains Marketplace under the Beta channel. Validates that `release.yml` builds, signs (skipped for v0.1), and publishes via `PUBLISH_TOKEN` end-to-end. Closes #4.
- Repo bootstrap: pinned platform floor (`2024.2` / `242`), plugin metadata, MIT license, contributor scaffolding.
- Daemon HTTP client (`BudiClient`) — `/health` and `/analytics/statusline` requests with `surface=jetbrains` filter, 3 s timeout, 64 KB body cap, JSON-only content-type guard. Pure helpers: `resolveCosts`, `formatCost`, `formatCostLine`, `deriveHealthState`, `buildStatusText`, `buildTooltip`, `clickUrl`, `buildStatuslineUrl`, `isLoopbackDaemonUrl`, `isAllowedCloudEndpoint`. Mirrors `budi-cursor`'s `budiClient.ts` shape for byte-identical statusline rendering.

## [0.1.0]

_Planned. Status-bar widget, daemon health polling, surface=jetbrains analytics filter. Publishing recipe lands ahead of the feature code under v0.0.1-beta.x._
