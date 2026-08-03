## EternalScript: Hot-Reloadable Kotlin for Minecraft Paper

EternalScript compiles ordinary Kotlin source files at runtime and replaces the
active project without restarting the server. The project can register Paper
events, commands, lifecycle callbacks, coroutines, and scheduler tasks.

The current runtime target is Paper `26.2` with Java `25`. Generated IntelliJ
workspaces use Kotlin `2.4.10`, Gradle `9.6.1`, and the same Java `25` target
as the server compiler.

### Commands

All commands require server operator access.

* `/es` or `/es status`: Shows project state, source and entry counts, the
  current or last user action, workspace attention, and the next useful step.
* `/es check`: Compiles the complete project without initializing or
  activating it.
* `/es reload`: Compiles and atomically activates the complete project.
* `/es unload`: Stops and removes the active project.
* `/es list`: Lists the active `EternalScript` class entries.
* `/es workspace`: Shows whether the generated IntelliJ workspace is ready,
  needs action, or has an error.
* `/es workspace update`: Reconciles managed workspace files and refreshes its
  runtime dependency metadata.
* `/es config reload`: Reloads the plugin configuration.
* `/es cache clear`: Clears the compiled-project cache.

There is intentionally no per-file reload, check, or unload. All source files
form one Kotlin project and every operation uses one stable source snapshot.
Command replies are sent directly to the player, console, RCON, or other
sender that invoked the command. Accepted operations report one start, any
diagnostics, one terminal result, and one next action; routine replies are not
duplicated into the server log.

### Ordinary Kotlin Project Model

Every included `*.kt` file under `plugins/EternalScript/scripts/` is compiled
as part of one ordinary Kotlin/JVM module. Normal Kotlin rules apply:

* `package`, `import`, visibility, overload, class, object, function, property,
  and type semantics work as they do in a Gradle Kotlin project.
* Files in the same package can use each other's declarations directly.
* Files in different packages use ordinary imports.
* A source file may contain only shared declarations, but an active project
  must contain at least one concrete `EternalScript` subclass.

For example:

```kotlin
// state.kt
package my.server

var joinCount = 0

fun nextJoinCount(): Int = ++joinCount
```

```kotlin
// listeners.kt
package my.server

import eternalScript.api.script.EternalScript
import org.bukkit.event.player.PlayerJoinEvent

class PlayerEvents : EternalScript() {
    override fun onEnable() {
        event<PlayerJoinEvent> { event ->
            event.player.sendMessage("Join #${nextJoinCount()}")
        }
    }
}
```

Every concrete `EternalScript` subclass with a no-argument constructor is
created once per generation in fully qualified class-name order. Multiple
classes may be declared in one `.kt` file.

Keep ordinary top-level initializers free of external side effects. Kotlin
initializes JVM file facades when they are first used, so cross-file property
initializer order is not a lifecycle guarantee. Put Paper changes, I/O, and
task startup inside an entry's `onEnable`, event, command, or tracked-task
callback.

### Class-Based Scripts

No annotations are required. A project can contain any number of concrete
`EternalScript` subclasses, including multiple classes in one `.kt` file:

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import org.bukkit.event.player.PlayerJoinEvent

class PlayerEvents : EternalScript() {
    override fun onEnable() {
        event<PlayerJoinEvent> { event ->
            event.player.sendMessage("Welcome, ${event.player.name}")
        }
    }
}

class ServerCommands : EternalScript() {
    override fun onEnable() {
        command("hello") {
            executor { sender, _, _ -> sender.sendMessage("Hello") }
        }
    }
}
```

Every concrete subclass with a no-argument constructor is created once per
generation in fully qualified class-name order. `onEnable` and `onDisable`
are the lifecycle hooks; listeners and commands declared there are registered
after the enable hook completes.

### Lifecycle and Transactional Reload

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class Lifecycle : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(Component.text("EternalScript enabled"))
    }

    override fun onDisable() {
        Bukkit.getServer().broadcast(Component.text("EternalScript disabled"))
    }
}
```

EternalScript compiles and stages the complete replacement before switching
generations. If compilation, project initialization, or activation fails, the
previous working generation remains active or is restored.

The guarantee covers EternalScript-managed listeners, commands, tasks, and
generation resources. Arbitrary effects performed through Bukkit, another
plugin, the filesystem, or a network service cannot be rolled back
automatically. Make lifecycle setup and cleanup idempotent.

Use the script-owned `launch { ... }` and `async { ... }` functions for
coroutines. They are registered before they start and restore the generation
class loader on every resume.

Wrap Bukkit/Paper/Folia scheduler callbacks with `task { ... }`, then pass the
returned task handle to `track(...)`:

```kotlin
class ScheduledWork : EternalScript() {
    override fun onEnable() {
        track(
            plugin.server.scheduler.runTaskTimer(
                plugin,
                task {
                    // Runs only while this generation is active, with its class loader.
                },
                1L,
                20L
            )
        )
    }
}
```

`track(Job|BukkitTask|ScheduledTask)` owns cancellation
of an existing handle, but it cannot retrofit execution context around a
callback that was already submitted. Use `task { ... }` or the script-owned
coroutine functions when callback class loading matters. Managed work is
cancelled when the generation is unloaded, replaced, or cleaned up after failed
activation. Reload waits for already running managed callbacks for a bounded
period; if they do not drain, the replacement is aborted.

Every successful reload creates new JVM classes and new `EternalScript`
instances. Ordinary top-level mutable properties are initialized again with
the new generation. Store data that must survive reloads in an explicit
persistent store.

### Events

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerEvents : EternalScript() {
    override fun onEnable() {
        event<PlayerJoinEvent> { event ->
            Bukkit.getServer().broadcast(
                Component.text("${event.player.name} joined the server")
            )
        }

        event<PlayerQuitEvent> { event ->
            Bukkit.getServer().broadcast(
                Component.text("${event.player.name} left the server")
            )
        }
    }
}
```

### Commands

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class Commands : EternalScript() {
    override fun onEnable() {
        command("test-command") {
            permission = "eternals.command.test"
            executor = { sender, _, _ ->
                Bukkit.getServer().broadcast(
                    Component.text("Command executed by ${sender.name}")
                )
            }
        }
    }
}
```

Registrations are accepted while the generation is being initialized or
enabled. Creating new listener or command registrations later from an active
callback is rejected so replacement and Folia ownership remain deterministic.
Lifecycle blocks run on the server global scheduler; entity- or region-bound
Folia work must use the corresponding scheduler.

### Project Sources

Runtime discovery includes files ending in the lowercase `.kt` extension. A file
is excluded if its name or any parent path segment starts with `-`. Excluded
directories are useful for bundled examples, but not for shared code required
by the active project.

An empty source snapshot does not silently unload the active generation; use
`/es unload` explicitly.

### Installation

1. Install Java 25 and run Paper 26.2 or a compatible Folia 26.2 build.
2. Download EternalScript from [Modrinth](https://modrinth.com/plugin/eternalscript).
3. Put the JAR in the server's `plugins` directory.
4. Start or restart the server once. EternalScript creates
   `plugins/EternalScript/scripts/` and makes `plugins/EternalScript/` an
   IntelliJ-ready Gradle project.

### Getting Started

Bundled examples are disabled by default. Open `plugins/EternalScript/` in
IntelliJ IDEA, then copy `scripts/-examples/hello.kt` to
`scripts/hello.kt`. The copied source uses the following public entry API:

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

class Hello : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(Component.text("Hello, world!"))
    }
}
```

On Windows, run `gradlew.bat check` from the generated project, then run
`/es reload` and `/es status` on the server. On Linux or macOS, use
`./gradlew check`.

### Kotlin Project Workspace

EternalScript generates a Gradle project directly in its server data folder:

```text
plugins/EternalScript/
├─ scripts/                 # Edit the same .kt files the server runs
│  └─ -examples/            # Opt-in examples; ignored until copied/renamed
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradlew
├─ gradlew.bat
├─ gradle/wrapper/
├─ WORKSPACE.md
├─ workspace.local.gradle.kts
└─ .eternalscript/
   ├─ runtime-classpath.txt
   ├─ manifest.json
   └─ conflicts/
```

The generated source set points straight at `scripts/**/*.kt`, so there is no
copy or deployment step between IntelliJ and the server. It applies the same
discovery rules as runtime: the extension must be lowercase `.kt`, and a file
is excluded when its name or any parent path segment starts with `-`.

EternalScript only writes workspace files; it never runs Gradle on the server.
Use the generated Wrapper from that directory:

```powershell
.\gradlew.bat check
```

On Linux or macOS, use `./gradlew check`. `compileKotlin` provides ordinary
Kotlin project diagnostics and IDE completion. `checkScripts` uses
EternalScript's runtime-equivalent project checker without evaluating or
activating the project. `check` runs both.

Both `/es check` and `checkScripts` treat an empty runtime source set as
`NO_SOURCES`, not as a successful compilation. Copy or create a lowercase
`scripts/*.kt` source and run the check again. The Gradle checker exits with
code `2` for `NO_SOURCES`, `1` for `FAILED`, and `0` for `PASSED`.

The dependency snapshot includes EternalScript, Paper, Kotlin, configured
`libs`, and API classpaths for plugins that are currently enabled. EternalScript
refreshes it automatically when plugins are enabled or disabled. If
`/es workspace` reports action required or an error, run
`/es workspace update`; reload the Gradle project in IntelliJ when that command
reports changed files. APIs from a plugin are available only while that plugin
is enabled. If a script references
the same class name through incompatible plugin class loaders, EternalScript
reports the plugins and class name before activation and keeps the previous
generation running.

Generated-file versions and hashes are recorded in
`.eternalscript/manifest.json`. An unmodified generated file can be upgraded
automatically. A modified file is preserved, and the replacement candidate is
written below `.eternalscript/conflicts/`. EternalScript never overwrites
`scripts`, `config.yml`, `libs`, `cache`, or `workspace.local.gradle.kts`.
Bundled language catalogs are replaced when their schema is obsolete so
removed feedback keys do not survive an upgrade; keep fully custom catalogs
under a separate language filename. Use the local Gradle file for
server-specific build customization.

The normal edit loop is:

```text
Install JAR and start the server
→ Open plugins/EternalScript in IntelliJ
→ Copy scripts/-examples/hello.kt to scripts/hello.kt once
→ Edit scripts/*.kt directly
→ Run gradlew.bat check or /es check
→ Run /es reload
→ Use /es status for the active state and next step
```

### Reload Cost

The Kotlin build-tools compiler keeps a project cache and reuses unchanged
compilation state. A changed file still produces a complete candidate
generation JAR because activation remains an all-or-nothing generation swap.
Compilation happens while the old generation remains available; publication
only begins after the replacement is ready.
