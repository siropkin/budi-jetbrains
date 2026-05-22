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
| Privacy Policy   | `https://getbudi.dev/privacy`                        |

The Privacy Policy URL depends on the `/privacy` page being live on `getbudi.dev` — tracked in [`siropkin/getbudi.dev`](https://github.com/siropkin/getbudi.dev). Until that page returns 200, leave the Privacy Policy field unset rather than pointing at a 404.

## Where else these surfaces live

- `<vendor url="https://getbudi.dev">` in `plugin.xml` already covers the vendor homepage shown on the listing.
- `pluginRepositoryUrl=https://github.com/siropkin/budi-jetbrains` in `gradle.properties` feeds the `org.jetbrains.changelog` plugin (release-notes commit links), not the marketplace listing.
- Plugin Screenshots (the carousel above the description) are uploaded via the same editor page — see PR #72 for the canonical PNG set in `docs/screenshots/`.
