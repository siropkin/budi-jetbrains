# Marketplace listing metadata

Canonical values for the [JetBrains Marketplace listing](https://plugins.jetbrains.com/plugin/31662-budi).

Most listing copy is driven from `src/main/resources/META-INF/plugin.xml` (`<name>`, `<vendor>`, `<description>`, `<change-notes>`) and auto-publishes with each release. The fields below are **not** expressible in `plugin.xml` and must be set once via the Marketplace plugin editor at `https://plugins.jetbrains.com/plugin/edit/31662-budi`. They persist across publishes — there is no need to re-apply them on each release.

## URLs

| Field            | Value                                                |
|------------------|------------------------------------------------------|
| Plugin Homepage  | `https://getbudi.dev`                                |
| Documentation    | `https://getbudi.dev`                                |
| Bug Tracker      | `https://github.com/siropkin/budi-jetbrains/issues`  |
| Source Code      | `https://github.com/siropkin/budi-jetbrains`         |
| Privacy Policy   | `https://github.com/siropkin/budi#privacy`           |

Privacy Policy points at the `## Privacy` section of the `siropkin/budi` README (the daemon repo, where the actual data lifecycle is documented — the plugin itself only renders what the local daemon returns and transmits nothing). Swap this to `https://getbudi.dev/privacy` once that page lands (tracked in [`siropkin/getbudi.dev`](https://github.com/siropkin/getbudi.dev)) so the canonical privacy surface lives on the marketing site rather than a README anchor.

## Where else these surfaces live

- `<vendor url="https://getbudi.dev">` in `plugin.xml` already covers the vendor homepage shown on the listing.
- `pluginRepositoryUrl=https://github.com/siropkin/budi-jetbrains` in `gradle.properties` feeds the `org.jetbrains.changelog` plugin (release-notes commit links), not the marketplace listing.
- Plugin Screenshots (the carousel above the description) are uploaded via the same editor page — see PR #72 for the canonical PNG set in `docs/screenshots/`.
