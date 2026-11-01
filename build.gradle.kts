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
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins("org.jetbrains.plugins.github", "com.intellij.mcpServer")
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
}

intellijPlatformTesting {
    runIde {
        // The Rider installation differs per machine and OS, so set `riderLocalPath` outside the repository:
        // in ~/.gradle/gradle.properties, or with `./gradlew runRider -PriderLocalPath=<path>`.
        register("runRider") {
            val riderLocalPath = providers.gradleProperty("riderLocalPath")
            localPath.fileProvider(riderLocalPath.map { File(it) })
            task {
                doFirst {
                    if (!riderLocalPath.isPresent) {
                        throw GradleException(
                            "Set riderLocalPath to your Rider installation, e.g. in ~/.gradle/gradle.properties: " +
                                "riderLocalPath=/Applications/Rider.app (macOS), " +
                                "C:\\Program Files\\JetBrains\\JetBrains Rider 2026.2 (Windows), " +
                                "or ~/.local/share/JetBrains/Toolbox/apps/rider (Linux)"
                        )
                    }
                }
            }
        }
    }
}
