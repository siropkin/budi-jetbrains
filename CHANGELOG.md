<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

### Added

- Repo bootstrap: pinned platform floor (`2024.2` / `242`), plugin metadata, MIT license, contributor scaffolding.
- Daemon HTTP client (`BudiClient`) — `/health` and `/analytics/statusline` requests with `surface=jetbrains` filter, 3 s timeout, 64 KB body cap, JSON-only content-type guard. Pure helpers: `resolveCosts`, `formatCost`, `formatCostLine`, `deriveHealthState`, `buildStatusText`, `buildTooltip`, `clickUrl`, `buildStatuslineUrl`, `isLoopbackDaemonUrl`, `isAllowedCloudEndpoint`. Mirrors `budi-cursor`'s `budiClient.ts` shape for byte-identical statusline rendering.

## [0.1.0]

_Initial release. Status-bar widget, daemon health polling, surface=jetbrains analytics filter._
