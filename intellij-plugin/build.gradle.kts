import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.vectortransformation.eternalscript"
val idePluginVersion = providers.gradleProperty("idePluginVersion").get()
val javaVersion = providers.gradleProperty("javaVersion").map(String::toInt).get()
val ideaVersion = providers.gradleProperty("ideaVersion").get()
val ideaBuild = providers.gradleProperty("ideaBuild").get()
version = idePluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":ide-protocol"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(ideaVersion)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    // 2.18.1 builds Ant classpaths with ':' in instrumentCode, which breaks
    // Windows drive-letter paths. This plugin has no GUI forms to instrument.
    instrumentCode = false
    projectName = "EternalScript"

    pluginConfiguration {
        name = "EternalScript"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = ideaBuild
            untilBuild = ideaBuild
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

kotlin {
    explicitApi()
    jvmToolchain(javaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaVersion.toString())
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint"
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
