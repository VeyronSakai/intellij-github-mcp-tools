import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    // 2025.3 runs on JBR 21
    jvmToolchain(21)
    compilerOptions {
        // Do not use Kotlin stdlib APIs newer than the one bundled in 2025.3
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins("org.jetbrains.plugins.github", "com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // `./gradlew verifyPlugin` checks the built plugin against every supported IDE line with the Plugin Verifier,
    // so internal GitHub plugin APIs that changed or disappeared are reported as compatibility problems.
    pluginVerification {
        // This plugin uses internal / experimental GitHub plugin APIs on purpose (checked per branch by hand),
        // so only real incompatibilities fail the build. Those usages are still listed in the report.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
        ides {
            providers.gradleProperty("pluginVerifierIdeVersions").get()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { create(IntelliJPlatformType.IntellijIdea, it) }
        }
    }
}

intellijPlatformTesting {
    runIde {
        // Runs an IDE installed on this machine (IntelliJ IDEA, Rider, PyCharm, ...) with the plugin.
        // Its path differs per machine and OS, so set `localIdePath` outside the repository:
        // in ~/.gradle/gradle.properties, or with `./gradlew runLocalIde -PlocalIdePath=<path>`.
        register("runLocalIde") {
            val localIdePath = providers.gradleProperty("localIdePath").filter { it.isNotBlank() }
            localPath.fileProvider(localIdePath.map { File(it) })
            task {
                doFirst {
                    if (!localIdePath.isPresent) {
                        throw GradleException(
                            "Set localIdePath to the installation of the IDE to run, e.g. in ~/.gradle/gradle.properties: " +
                                "localIdePath=/Applications/Rider.app (macOS), " +
                                "C:\\Program Files\\JetBrains\\IntelliJ IDEA 2026.2 (Windows), " +
                                "or ~/.local/share/JetBrains/Toolbox/apps/intellij-idea (Linux)"
                        )
                    }
                }
            }
        }
    }
}
