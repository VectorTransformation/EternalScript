import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

val javaVersion = providers.gradleProperty("eternalScriptJavaVersion").get().toInt()
val minecraftVersion = providers.gradleProperty("eternalScriptMinecraftVersion").get()
val paperBuild = providers.gradleProperty("eternalScriptPaperBuild").get()
val paperVersion = "$minecraftVersion.build.$paperBuild"
val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
val kotlinxVersion = providers.gradleProperty("eternalScriptKotlinxVersion").get()
val projectJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
}
val scriptSourceRoot = layout.projectDirectory.dir("src/main/kotlin")
val bundledScriptRoot = rootProject.layout.projectDirectory.dir("src/main/resources/scripts")
val scriptProjectTools = configurations.create("scriptProjectTools") {
    isCanBeConsumed = false
    extendsFrom(
        configurations.getByName("implementation"),
        configurations.getByName("runtimeOnly")
    )
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":eternalscript-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinxVersion")
    implementation("io.papermc.paper:paper-api:$paperVersion")

    scriptProjectTools(project(":"))
    scriptProjectTools(kotlin("compiler-embeddable", kotlinVersion))
    scriptProjectTools(kotlin("reflect", kotlinVersion))
    scriptProjectTools("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    scriptProjectTools("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
}

kotlin {
    jvmToolchain(javaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xuse-fir-lt=false")
    }
}

fun registerScriptCheck(
    name: String,
    taskDescription: String,
    sourceRoot: File,
    mode: String,
    emptyPolicy: String = "require-sources"
) =
    tasks.register<JavaExec>(name) {
        val compilerCache = layout.buildDirectory.dir("eternalScript-check/$name")
        group = "verification"
        description = taskDescription
        classpath = scriptProjectTools
        mainClass.set("eternalScript.core.script.project.ScriptProjectCheckTool")
        javaLauncher.set(projectJavaLauncher)
        args(
            sourceRoot.absolutePath,
            compilerCache.get().asFile.absolutePath,
            mode,
            emptyPolicy
        )
        inputs.files(
            fileTree(sourceRoot) {
                include("**/*.kt")
            }
        ).withPathSensitivity(PathSensitivity.RELATIVE).normalizeLineEndings()
        inputs.property("compilerSchema", 2)
        outputs.dir(compilerCache)
    }

val checkWorkspaceScripts = registerScriptCheck(
    "checkWorkspaceScripts",
    "Compiles the workspace Kotlin sources as one reloadable project without evaluating them.",
    scriptSourceRoot.asFile,
    "runtime"
).also { task ->
    task.configure {
        dependsOn(tasks.named("compileKotlin"))
    }
}
val checkBundledScripts = registerScriptCheck(
    "checkBundledScripts",
    "Compiles the plugin's bundled runtime Kotlin sources as one project without evaluating them.",
    bundledScriptRoot.asFile,
    "runtime",
    "allow-empty"
)
val checkBundledExamples = registerScriptCheck(
    "checkBundledExamples",
    "Compiles ignored bundled examples as a documentation-fixture project.",
    bundledScriptRoot.asFile,
    "ignored"
)

tasks.register("checkScripts") {
    group = "verification"
    description = "Checks both workspace and bundled EternalScript projects."
    dependsOn(checkWorkspaceScripts, checkBundledScripts, checkBundledExamples)
}

tasks.named("check") {
    dependsOn("checkScripts")
}
