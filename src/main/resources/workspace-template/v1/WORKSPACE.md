# EternalScript Server Workspace

This directory is both the IntelliJ/Gradle workspace and the live script
project used by the server. Edit `scripts/**/*.kt` directly; there is no copy
or deployment step.

## First project

1. Open this directory (`plugins/EternalScript`) as a Gradle project in
   IntelliJ IDEA.
2. Copy `scripts/-examples/hello.kt` to `scripts/hello.kt`. Paths whose name
   starts with `-` are examples and are not loaded by the server.
3. Run `gradlew.bat check` on Windows or `./gradlew check` on Linux/macOS.
4. Run `/es check` on the server to validate its live plugin classloaders.
5. Run `/es reload` on the server, then `/es status`.

## Normal edit loop

```text
Edit scripts/*.kt
→ run gradlew.bat check
→ run /es check for live server classloader validation
→ run /es reload
→ run /es status when you need the active state
```

`compileKotlin` provides normal Kotlin diagnostics and IDE completion.
`checkScripts` uses the same Kotlin project compiler as the server, but it is a
compile-only check: a separate Gradle process cannot reproduce the server's live
plugin classloader identities. Run `/es check` before `/es reload` for that
server-side validation. `check` runs both local compilation tasks. An empty
runtime source set is reported as `NO_SOURCES` and fails the check until a
lowercase `scripts/*.kt` source is added.

Only lowercase `.kt` files are included. A file is ignored when its name or
any parent path segment starts with `-`.

## Dependencies and workspace maintenance

EternalScript automatically refreshes the workspace classpath when server
plugins are enabled or disabled. If `/es workspace` reports action required or
an error, run `/es workspace update`, inspect any conflict candidate below
`.eternalscript/conflicts/`, and reload the Gradle project in IntelliJ when the
command reports changed workspace files.

Add server-specific repositories or `compileOnly` dependencies to
`workspace.local.gradle.kts`. EternalScript creates this file once and never
overwrites it.

Managed workspace files are upgraded only when they are still unmodified. If
you edited one, EternalScript preserves it and writes the new template below
`.eternalscript/conflicts/<schema>/`.
