plugins {
    val kotlinVersion = "2.2.20"

    `java-library`
    // https://kotlinlang.org/docs/releases.html
    kotlin("jvm") version kotlinVersion
    // https://github.com/Kotlin/kotlinx.serialization
    kotlin("plugin.serialization") version kotlinVersion
    // https://plugins.gradle.org/plugin/io.papermc.paperweight.userdev
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    // https://github.com/jpenilla/run-task
    id("xyz.jpenilla.run-paper") version "3.0.0"
    // https://github.com/jpenilla/resource-factory
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
    // https://github.com/GradleUp/shadow
    id("com.gradleup.shadow") version "9.2.2"
}

group = "eternalScript"
val pluginVersion = "1.0.4"
val javaVersion = 21
val pluginApiVersion = "1.21.8"
val minecraftVersion = "1.21.10"
val minecraftHeapSize = 8
val minecraftArgs = listOf(
    "-Xmx${minecraftHeapSize}G",
    "-Xms${minecraftHeapSize}G",
    "-XX:+AlwaysPreTouch",
    "-XX:+DisableExplicitGC",
    "-XX:+ParallelRefProcEnabled",
    "-XX:+PerfDisableSharedMem",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseG1GC",
    "-XX:G1HeapRegionSize=8M",
    "-XX:G1HeapWastePercent=5",
    "-XX:G1MaxNewSizePercent=40",
    "-XX:G1MixedGCCountTarget=4",
    "-XX:G1MixedGCLiveThresholdPercent=90",
    "-XX:G1NewSizePercent=30",
    "-XX:G1RSetUpdatingPauseTimePercent=5",
    "-XX:G1ReservePercent=20",
    "-XX:InitiatingHeapOccupancyPercent=15",
    "-XX:MaxGCPauseMillis=200",
    "-XX:MaxTenuringThreshold=1",
    "-XX:SurvivorRatio=32",
    "-Dusing.aikars.flags=https://mcflags.emc.gs",
    "-Daikars.new.flags=true"
)

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("$minecraftVersion-R0.1-SNAPSHOT")
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly(kotlin("reflect"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    compileOnly(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks {
    runServer {
        minecraftVersion(minecraftVersion)
        jvmArgs(minecraftArgs)
    }
    compileJava {
        options.release = javaVersion
    }
    assemble {
        dependsOn(shadowJar)
    }
    jar {
        enabled = false
    }
    shadowJar {
        archiveClassifier = ""
        archiveVersion = pluginVersion()
        dependencies {
            exclude(dependency("org.jetbrains:annotations"))
        }
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

kotlin {
    jvmToolchain(javaVersion)
}

paperPluginYaml {
    name = rootProject.name
    main = pluginMain()
    version = pluginVersion()
    apiVersion = pluginApiVersion
    loader = "${pluginMain()}Loader"
}

fun pluginMain() = "$group.${rootProject.name}"

fun pluginVersion() = pluginVersion.let { it.ifEmpty { "1.0.0" } }