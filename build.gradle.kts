plugins {
    `java-library`
    // https://kotlinlang.org/docs/releases.html
    kotlin("jvm")
    // https://plugins.gradle.org/plugin/io.papermc.paperweight.userdev
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    // https://github.com/jpenilla/run-task
    id("xyz.jpenilla.run-paper") version "3.0.2"
    // https://github.com/jpenilla/resource-factory
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
}

group = "eternalScript"
val pluginVersion = "2.0.0"
val javaVersion = 25
val pluginApiVersion = "26.2"
val minecraftVersion = "26.2"
val paperBuild = "87-stable"
val paperVersion = "$minecraftVersion.build.$paperBuild"
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
val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
val projectJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle(paperVersion)
    compileOnly(kotlin("stdlib-jdk8", kotlinVersion))
    compileOnly(kotlin("reflect", kotlinVersion))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly(kotlin("scripting-common", kotlinVersion))
    compileOnly(kotlin("scripting-jvm", kotlinVersion))
    compileOnly(kotlin("compiler-embeddable", kotlinVersion))
    compileOnly("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
    // Paper's Maven resolver does not use Gradle module variants. Declare the
    // JVM artifact directly so it wins over Kotlin compiler's older transitive
    // coroutines runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    testImplementation(kotlin("test", kotlinVersion))
    testImplementation(kotlin("compiler-embeddable", kotlinVersion))
    testImplementation(kotlin("scripting-common", kotlinVersion))
    testImplementation(kotlin("scripting-jvm", kotlinVersion))
    testImplementation("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
}

fun libraries(): String {
    val compileOnly = project.configurations.getByName("compileOnly").dependencies

    val library = compileOnly.mapNotNull { dependency ->
        val group = dependency.group ?: return@mapNotNull null
        val name = dependency.name
        val version = dependency.version ?: return@mapNotNull null

        "  - $group:$name:$version"
    }.joinToString("\n", "\n")

    return "libraries:$library"
}

fun addLibraries() {
    val paperPluginYml = layout.buildDirectory.file("resources/main/paper-plugin.yml").get().asFile
    if (!paperPluginYml.exists()) return

    paperPluginYml.writeText(paperPluginYml.readText().plus(libraries()))
}

tasks {
    runServer {
        minecraftVersion(minecraftVersion)
        javaLauncher.set(projectJavaLauncher)
        jvmArgs(minecraftArgs)
    }
    test {
        maxHeapSize = "1G"
    }
    register("checkScripts") {
        group = "verification"
        description = "Checks the workspace and bundled reloadable Kotlin projects without evaluating them."
        dependsOn(":script-workspace:checkScripts")
    }
    check {
        dependsOn("checkScripts")
    }
    processResources {
        doLast {
            addLibraries()
        }
    }
    compileJava {
        options.release = javaVersion
    }
    jar {
        version = pluginVersion()
    }
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
    foliaSupported = true
}

fun pluginMain() = "$group.${rootProject.name}"

fun pluginVersion() = pluginVersion.let { it.ifEmpty { "1.0.0" } }
