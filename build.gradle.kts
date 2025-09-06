plugins {
    val kotlinVersion = "2.2.10"

    `java-library`
    // https://kotlinlang.org/docs/releases.html
    kotlin("jvm") version kotlinVersion
    // https://plugins.gradle.org/plugin/io.papermc.paperweight.userdev
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
    // https://github.com/jpenilla/run-task
    id("xyz.jpenilla.run-paper") version "2.3.1"
    // https://github.com/jpenilla/resource-factory
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.0"
    // https://github.com/GradleUp/shadow
    id("com.gradleup.shadow") version "9.1.0"
}

group = "eternalScript"
val pluginVersion = "1.0.1"
val javaVersion = 21
val minecraftVersion = "1.21.8"
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
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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
        dependsOn("shadowJar")
    }
    jar {
        enabled = false
    }
    shadowJar {
        archiveClassifier = ""
        archiveVersion = pluginVersion
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
    main = "$group.${rootProject.name}"
    version = pluginVersion.let { it.ifEmpty { "1.0.0" } }
    apiVersion = minecraftVersion
}