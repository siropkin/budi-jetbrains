package com.github.siropkin.budijetbrains.install

import org.junit.Test
import kotlin.test.assertEquals

class BudiInstallCommandsTest {
    @Test
    fun `macOS install command is the canonical Homebrew one-liner`() {
        val cmd = installCommandForPlatform(InstallPlatform.MACOS)
        // Security-sensitive: must not drift from siropkin/budi/README.md
        // and siropkin/budi-cursor/src/installCommands.ts. The notification
        // dialog renders this verbatim, so a typo here = a typo on every
        // user's onboarding screen.
        assertEquals("brew install siropkin/budi/budi", cmd.command)
        assertEquals("bash", cmd.shell)
    }

    @Test
    fun `Linux install command points at install-standalone_sh on github raw`() {
        val cmd = installCommandForPlatform(InstallPlatform.LINUX)
        assertEquals(
            "curl -fsSL https://raw.githubusercontent.com/siropkin/budi/main/scripts/install-standalone.sh | bash",
            cmd.command,
        )
        assertEquals("bash", cmd.shell)
    }

    @Test
    fun `Windows install command points at install-standalone_ps1 on github raw`() {
        val cmd = installCommandForPlatform(InstallPlatform.WINDOWS)
        assertEquals(
            "irm https://raw.githubusercontent.com/siropkin/budi/main/scripts/install-standalone.ps1 | iex",
            cmd.command,
        )
        assertEquals("powershell", cmd.shell)
    }

    @Test
    fun `upgradeCommandForPlatform uses brew upgrade on macOS, install path on Linux + Windows`() {
        assertEquals("brew upgrade siropkin/budi/budi", upgradeCommandForPlatform(InstallPlatform.MACOS))
        assertEquals(
            installCommandForPlatform(InstallPlatform.LINUX).command,
            upgradeCommandForPlatform(InstallPlatform.LINUX),
        )
        assertEquals(
            installCommandForPlatform(InstallPlatform.WINDOWS).command,
            upgradeCommandForPlatform(InstallPlatform.WINDOWS),
        )
    }
}
