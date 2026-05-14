package com.github.siropkin.budijetbrains.install

import com.intellij.openapi.util.SystemInfo

/**
 * Canonical budi install / upgrade commands (siropkin/budi#314,
 * siropkin/budi-cursor#51). Mirrors the commands published on
 * https://getbudi.dev and in `siropkin/budi/README.md`.
 *
 * Hard-coded on purpose: resolving "install command of the day" over
 * the network at onboarding time would introduce a cold-start
 * dependency on the public site that could fail in exactly the
 * environments the welcome notification is meant to help (corporate
 * machines behind strict firewalls).
 *
 * If the canonical command changes in the main repo, bump this file in
 * lockstep with `siropkin/budi-cursor/src/installCommands.ts` so the
 * three surfaces (site, VS Code/Cursor extension, JetBrains plugin) do
 * not drift.
 */

internal enum class InstallPlatform { MACOS, LINUX, WINDOWS }

internal data class InstallCommand(
    val platform: InstallPlatform,
    /** Short display label. */
    val label: String,
    /** Shell in which the command must run. */
    val shell: String,
    /** Exact command shown to the user — copy-pasteable into a terminal. */
    val command: String,
)

internal val MACOS_COMMAND = InstallCommand(
    platform = InstallPlatform.MACOS,
    label = "macOS",
    shell = "bash",
    command = "brew install siropkin/budi/budi",
)

internal val LINUX_COMMAND = InstallCommand(
    platform = InstallPlatform.LINUX,
    label = "Linux",
    shell = "bash",
    command = "curl -fsSL https://raw.githubusercontent.com/siropkin/budi/main/scripts/install-standalone.sh | bash",
)

internal val WINDOWS_COMMAND = InstallCommand(
    platform = InstallPlatform.WINDOWS,
    label = "Windows (PowerShell)",
    shell = "powershell",
    command = "irm https://raw.githubusercontent.com/siropkin/budi/main/scripts/install-standalone.ps1 | iex",
)

internal fun installCommandForPlatform(platform: InstallPlatform): InstallCommand = when (platform) {
    InstallPlatform.MACOS -> MACOS_COMMAND
    InstallPlatform.LINUX -> LINUX_COMMAND
    InstallPlatform.WINDOWS -> WINDOWS_COMMAND
}

/**
 * Platform-appropriate upgrade command for an already-installed daemon
 * (siropkin/budi-cursor#51). On macOS this is `brew upgrade` against
 * the Homebrew tap. Linux/Windows installers overwrite in place, so
 * the install command doubles as the upgrade path.
 *
 * Surfaced alongside `budi update` in the upgrade prompt: that path is
 * preferred when the daemon is reachable, this one is the platform
 * fallback for the broken-daemon case.
 */
internal fun upgradeCommandForPlatform(platform: InstallPlatform): String = when (platform) {
    InstallPlatform.MACOS -> "brew upgrade siropkin/budi/budi"
    InstallPlatform.LINUX, InstallPlatform.WINDOWS -> installCommandForPlatform(platform).command
}

/** Detect the current host OS via IntelliJ's `SystemInfo`. */
internal fun currentInstallPlatform(): InstallPlatform = when {
    SystemInfo.isWindows -> InstallPlatform.WINDOWS
    SystemInfo.isMac -> InstallPlatform.MACOS
    else -> InstallPlatform.LINUX
}
