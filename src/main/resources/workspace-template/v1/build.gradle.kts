import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
}

group = "eternalScript.workspace"
version = "1"

repositories {
    mavenCentral()
}

val scriptsRoot = layout.projectDirectory.dir("scripts").asFile
val runtimeClasspathFile =
    layout.projectDirectory.file(".eternalscript/runtime-classpath.txt").asFile
val runtimeClasspathEntries = providers.provider {
    if (!runtimeClasspathFile.isFile) {
        emptyList()
    } else {
        runtimeClasspathFile.readLines(Charsets.UTF_8)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::file)
    }
}

dependencies {
    compileOnly(files(runtimeClasspathEntries))
}

kotlin {
    jvmToolchain(25)
    sourceSets {
        main {
            kotlin.setSrcDirs(listOf(scriptsRoot))
            kotlin.include("**/*.kt")
            kotlin.exclude { element ->
                !element.path.endsWith(".kt") ||
                    element.path.split('/').any { part -> part.startsWith("-") }
            }
        }
    }
}

val workspaceJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

val checkScripts = tasks.register<JavaExec>("checkScripts") {
    group = "verification"
    description =
        "Compiles scripts with EternalScript's project compiler without server classloader checks."
    classpath = files(runtimeClasspathEntries)
    mainClass.set("eternalScript.core.script.project.ScriptProjectCheckTool")
    args(
        scriptsRoot.absolutePath,
        layout.projectDirectory.dir(".eternalscript/check-cache").asFile.absolutePath,
        "runtime",
        "require-sources"
    )
    javaLauncher.set(workspaceJavaLauncher)
    doFirst {
        require(runtimeClasspathEntries.get().isNotEmpty()) {
            "EternalScript runtime classpath is empty. Start the server or run /es workspace update."
        }
    }
}

tasks.named("check") {
    dependsOn(checkScripts)
}

val localWorkspaceBuild = layout.projectDirectory.file("workspace.local.gradle.kts").asFile
if (localWorkspaceBuild.isFile) {
    apply(from = localWorkspaceBuild)
}
