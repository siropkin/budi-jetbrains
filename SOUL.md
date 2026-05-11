# SOUL.md

JetBrains IDE plugin for **budi** — renders JetBrains-host AI spend in the IDE status bar by polling a locally-running `budi-daemon` over HTTP (`/analytics/statusline?surface=jetbrains` + `/health`).

This repo is **presentation only**. It does not touch SQLite, does not compute cost, does not classify prompts, does not read Copilot Chat / AI Assistant transcripts. Business logic — including the transcript tailers that feed the daemon — lives in [`siropkin/budi`](https://github.com/siropkin/budi). Keep it that way.

## Product boundaries

| Product | Repo | Role |
|---------|------|------|
| **budi-core** | [`siropkin/budi`](https://github.com/siropkin/budi) | Rust: daemon, CLI, transcript tailer, all business logic. Owns SQLite. |
| **budi-cursor** | [`siropkin/budi-cursor`](https://github.com/siropkin/budi-cursor) | VS Code / Cursor status-bar extension (TypeScript). Sibling surface — same daemon, `cursor` / `vscode` surface dimension. |
| **budi-jetbrains** | **this repo** (`siropkin/budi-jetbrains`) | IntelliJ Platform plugin (Kotlin). Renders what the daemon returns on the `jetbrains` surface. |
| **budi-cloud** | [`siropkin/budi-cloud`](https://github.com/siropkin/budi-cloud) | Next.js + Supabase cloud dashboard at `app.getbudi.dev`. Unrelated to this plugin directly; consumes the surface dimension produced here. |
| **homebrew-budi** | [`siropkin/homebrew-budi`](https://github.com/siropkin/homebrew-budi) | Homebrew tap that serves the `brew install siropkin/budi/budi` install path referenced by the first-run notification. |
| **getbudi.dev** | [`siropkin/getbudi.dev`](https://github.com/siropkin/getbudi.dev) | Marketing site at `getbudi.dev`. Must be kept in sync with any user-visible surface change in this plugin. |

Business logic lives in `budi-core`; this repo only renders what the daemon returns. The JetBrains surface specifically is motivated by `siropkin/budi#701` / `#702` (surface dimension) — keep that boundary intact so the plugin stays a thin presentation layer over the shared statusline contract.

## Build & test

```bash
./gradlew buildPlugin     # produce build/distributions/budi-<version>.zip
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew test            # JUnit tests
./gradlew check           # tests + plugin-structure verifier
./gradlew verifyPlugin    # IntelliJ Plugin Verifier across the configured IDE matrix
```

Marketplace publishing is driven from `.github/workflows/release.yml` (tag → GitHub Release → `gradle publishPlugin`); there is no local `publish` script. Auto-publish is the **only** way the plugin reaches the Marketplace — manual web-UI uploads are explicitly disallowed except for the very first slug-claim.

Compatibility floor pinned in `gradle.properties`:
- IntelliJ Platform `2024.2` (`pluginSinceBuild = 242`).
- Java toolchain `21`.
- Kotlin from the IntelliJ Platform Gradle plugin (currently `2.x`).

## Install (for users)

- JetBrains Marketplace: search for "budi" once the listing is publicly approved + un-hidden. Beta-channel pre-releases are installable via the custom-channel URL (see README).
- From a local build: `./gradlew buildPlugin` → install the resulting zip via Settings → Plugins → ⚙ → Install plugin from disk…
- From CLI (planned): `budi integrations install --with jetbrains-plugin` once the main repo grows that path.

Plugin activates on first project open (post-startup activity). No configuration required; it auto-discovers the daemon on `127.0.0.1:7878`.

## What the plugin does

The plugin is intentionally **statusline-only**:

1. **One status bar widget** — renders the shared status contract from the daemon, scoped to `surface=jetbrains`, in the same byte-for-byte shape the Claude Code statusline uses: `budi · $X 1d · $Y 7d · $Z 30d`. No leading glyph; health collapses into the copy (`budi`, `budi · setup`, `budi · offline`) so the surface stays as quiet as the Claude Code CLI statusline.
2. **Click-through** — opens the cloud dashboard, mirroring the Claude Code statusline URL composition (`/dashboard/sessions` when a JetBrains-surface session is active, `/dashboard` otherwise).
3. **Onboarding entry point** — when the daemon has never been seen healthy on this install, the plugin enters `FIRST_RUN` mode: the status bar shows `budi · setup` and a sticky balloon notification surfaces with a "Show install command" action that opens an information dialog with the canonical platform-specific install one-liner. The user copies and runs it themselves — never auto-executed. The notification retires automatically on the first daemon reading.
4. **Actionable upgrade prompt** — when the daemon's `api_version` is below `MIN_API_VERSION`, a sticky balloon offers `budi update` (plus the platform fallback) with a "Don't show again" action. Two-layer throttle: per-session latch + persistent suppress that auto-resets on the upward edge.
5. **Settings panel** — `Settings → Tools → budi` exposes daemon URL, cloud endpoint, polling interval, the `includeOtherSurfaces` opt-out, and the upgrade-prompt suppress toggle.

No tool window, no session list, no vitals grid, no tips feed. If real usage demands a richer surface it must come back behind a flag; it must never become the default.

## Data contract with the daemon

- HTTP: `GET http://127.0.0.1:7878/analytics/statusline?surface=jetbrains` (plus `project_dir` when a project is open) and `GET /health`. The plugin never sends a `?provider=` filter — surface-based scoping (siropkin/budi#702) is the daemon's job, and the wire response is rendered as-is.
- The response shape is the shared provider-scoped status contract pinned in [`docs/statusline-contract.md`](https://github.com/siropkin/budi/blob/main/docs/statusline-contract.md) in the main repo. The contract evolves in `siropkin/budi` first, then here — never the other way.
- On startup and every poll, read `/health` and verify `api_version`. If the daemon is older than this plugin's `MIN_API_VERSION`, show the actionable upgrade balloon and keep polling. Do not crash.
- Legacy aliases (`today_cost` / `week_cost` / `month_cost`) are still read as a fallback when the canonical `cost_1d` / `cost_7d` / `cost_30d` fields are missing. Drop the fallback the release after the main repo drops the aliases.
- Defense-in-depth on every request: 3 s timeout, 64 KB body cap, 2xx-only, `application/json` content-type only. Loopback-only daemon URL; off-loopback values fall back to `127.0.0.1:7878`. Cloud endpoint allowlisted to `getbudi.dev` (and subdomains) so an unsanitized override cannot redirect the click-through to a phishing host.

## Key files

- `src/main/kotlin/.../daemon/BudiClient.kt` — fetch helpers, health-state derivation (including `FIRST_RUN`), status-text + tooltip builders, click-URL composer, surface-filter request builder. All rendering logic lives here so it is easy to unit-test.
- `src/main/kotlin/.../poller/BudiPoller.kt` — application-scoped `Alarm` polling loop; single in-flight request, refresh coalescing, off-EDT HTTP. Single-flight refresh: a request in flight collapses subsequent triggers into one follow-up poll.
- `src/main/kotlin/.../state/BudiAppState.kt` — application-scoped runtime state holder (latest health, statusline, derived `HealthState`). Listener API so widgets repaint when the poller pushes a new reading.
- `src/main/kotlin/.../settings/BudiSettings.kt` — `PersistentStateComponent` for daemon URL, cloud endpoint, polling interval, `includeOtherSurfaces`, suppress flags, and the `everSawDaemon` first-run latch.
- `src/main/kotlin/.../settings/BudiConfigurable.kt` — `Settings → Tools → budi` page; allowlists enforced in `apply()` so off-policy values surface as a `ConfigurationException`.
- `src/main/kotlin/.../statusbar/BudiStatusBarWidgetFactory.kt` — `StatusBarWidgetFactory` + `StatusBarWidget.TextPresentation`. One widget per open project, all reading from the application-scoped state.
- `src/main/kotlin/.../notifier/BudiNotifier.kt` — first-run welcome balloon. "Show install command" opens a dialog with the platform-specific install one-liner.
- `src/main/kotlin/.../notifier/BudiUpgradeNotifier.kt` — actionable upgrade prompt (`MIN_API_VERSION` gate). `evaluateUpgradePrompt` is a pure decision function, separately unit-testable.
- `src/main/kotlin/.../install/BudiInstallCommands.kt` — canonical platform-specific install + upgrade commands (kept in lockstep with the install one-liners documented in `siropkin/budi/README.md`).
- `src/main/kotlin/.../startup/BudiProjectActivity.kt` — `ProjectActivity` that boots the poller on first project open and surfaces the welcome balloon when the daemon is missing.
- `src/main/resources/META-INF/plugin.xml` — plugin manifest; registers application services, status-bar widget factory, settings page, notification group, post-startup activity.
- `src/test/kotlin/...` — JUnit 4 tests. Pure logic (request builder, health-state derivation, formatting, URL allowlists, upgrade-decision matrix, install commands, settings round-trip) plus an in-process `com.sun.net.httpserver` HTTP-layer test.

## Dev notes

- **No business logic.** If you catch yourself computing a cost, classifying a prompt, or rolling up tokens in this repo, stop and move it into `budi-core`. The plugin must only render what the daemon returns.
- **No cross-surface blending.** The plugin always sends `?surface=jetbrains` and never `?provider=…`. The `includeOtherSurfaces` setting is the *only* way to drop the surface filter, and it is opt-in. Do not add summary surfaces that show blended multi-IDE totals by default — scoped surfaces display their own scope only.
- **Never read user prompts or code.** Only `/analytics/statusline` and `/health` are in scope. Do not call session-detail or message-content endpoints. Do not parse Copilot Chat / AI Assistant storage on disk — that is `budi-core`'s job.
- **Match the Claude Code statusline byte-for-byte where possible.** Number formatting, separator (` · `), slot labels (`1d` / `7d` / `30d`), and click-through URL shape are all mirrored from `crates/budi-cli/src/commands/statusline.rs` in the main repo. Drift is a bug.
- **Graceful degradation.** If the daemon is not running, show a quiet `budi · offline` (or `budi · setup` on first run) and never spam modal errors. The first-run welcome balloon is the *one* piece of in-face UI; everything else is silent.
- **API version skew.** The daemon's `api_version` is the contract. Bump `MIN_API_VERSION` in `BudiClient.kt` when the plugin starts depending on a new field shape, and ship the actionable upgrade prompt in the same release so users on older daemons get a fix path, not a silent break.
- **Auto-publish is non-negotiable.** Tag → GitHub Release → `release.yml` → Marketplace. No manual zip uploads except for the very first slug-claim (JB policy).
- **Plugin signing** is deferred until v0.2 (`siropkin/budi-jetbrains` follow-up). Marketplace does not require signed plugins; the signing keys are infra we don't want to manage during early v0.1 iteration.
- **Public-site sync.** Any visible change (status text, click-through URL, icon, Marketplace listing copy) must be mirrored on getbudi.dev so screenshots and copy do not drift.
- **Zip bundling for the main repo.** The main repo's `budi integrations install --with jetbrains-plugin` path will eventually shell out to a pre-built `.zip`. When cutting a release here, refresh the bundled artifact in the main repo in lockstep.
- **No Kotlin in `gradle.properties` business logic.** All version coordinates (platform floor, plugin version, channel routing) are pinned in `gradle.properties` and consumed via `providers.gradleProperty(...)` in `build.gradle.kts`. Channel routing is *suffix-driven*: `0.1.0-beta.1` → `beta`, `0.1.0` → `default` (Stable). Do not add hardcoded channels.
- **Verify plugin CI retry.** The `Verify plugin` job in `.github/workflows/build.yml` is wrapped in `nick-fields/retry@v3` (2 attempts). Plugin Verifier 1.403 (pinned by `org.jetbrains.intellij.platform` 2.16.0) has a `jdk.nio.zipfs` channel-share race across worker threads — one worker's interrupt closes a shared `ZipFileSystem` and every sibling reading the same IDE-bundle JAR fails with `ClosedFileSystemException`. A fresh process avoids the poisoned channel. Remove the retry wrapper once the platform plugin ships a verifier release that fixes the race (track upstream).
