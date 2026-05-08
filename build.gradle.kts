import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType").map { IntelliJPlatformType.fromCode(it) },
            providers.gradleProperty("platformVersion"),
        )
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // pluginUntilBuild deliberately empty — see gradle.properties.
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        // Signing secrets are optional for v0.1 (publishing to the
        // Marketplace does not require a signed plugin). When the env
        // vars are absent the signPlugin task no-ops; when they are
        // present (set in CI as repo secrets) the plugin is signed.
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Channel routed off the version suffix:
        //   0.1.0          → "default" (Stable)
        //   0.1.0-beta.1   → "beta"
        //   0.1.0-eap.3    → "eap"
        // Per issue #4: tag v0.1.x releases as `vX.Y.Z-beta.N` until a
        // friendly user confirms daemon-path detection on a non-dev
        // machine; after that, drop the suffix to promote to Stable.
        channels = providers.gradleProperty("pluginVersion").map { v ->
            listOf(v.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}
