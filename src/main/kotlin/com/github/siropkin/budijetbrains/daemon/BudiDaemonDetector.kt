package com.github.siropkin.budijetbrains.daemon

import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.TimeUnit

internal object BudiDaemonDetector {
    fun isBinaryInstalled(): Boolean {
        val command =
            if (SystemInfo.isWindows) listOf("where", "budi") else listOf("which", "budi")
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
