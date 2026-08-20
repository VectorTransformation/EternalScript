pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()

    plugins {
        // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        id("org.jetbrains.intellij.platform") version "2.18.1"
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "EternalScript"

include(":ide-protocol")
include(":intellij-plugin")
