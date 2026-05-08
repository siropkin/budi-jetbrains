<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# budi-jetbrains Changelog

## [Unreleased]

### Added

- Auto-publish dry-run: first CI-driven push to JetBrains Marketplace under the Beta channel. Validates that `release.yml` builds, signs (skipped for v0.1), and publishes via `PUBLISH_TOKEN` end-to-end. Closes #4.
- Repo bootstrap: pinned platform floor (`2024.2` / `242`), plugin metadata, MIT license, contributor scaffolding.

## [0.1.0]

_Planned. Status-bar widget, daemon health polling, surface=jetbrains analytics filter. Publishing recipe lands ahead of the feature code under v0.0.1-beta.x._
