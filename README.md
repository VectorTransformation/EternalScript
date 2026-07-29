## EternalScript: Hot-Reloadable Kotlin for Minecraft Paper

EternalScript compiles ordinary Kotlin source files at runtime and replaces the
active project without restarting the server. The project can register Paper
events, commands, lifecycle callbacks, coroutines, and scheduler tasks.

### Commands

* `/es` or `/es status`: Shows the active generation and its source-file count.
* `/es reload all`: Compiles and replaces the complete project.
* `/es reload <source>`: Validates an existing `*.kt` path, then reloads the
  complete project.
* `/es unload all`: Unloads the active generation.
* `/es list`: Lists the source files in the active generation.
* `/es check all`: Compiles the complete project without activating it.
* `/es check <source>`: Validates the source path, then checks the complete
  project.
* `/es config reload`: Reloads the plugin configuration.
* `/es cache clear`: Clears the compiled-project cache.

Individual files are not independent runtime instances. `/es reload a.kt`
still prepares and atomically replaces one complete project generation, and
`/es unload <source>` is therefore unsupported.

### Ordinary Kotlin Project Model

Every included `*.kt` file under `plugins/EternalScript/scripts/` is compiled
as part of one ordinary Kotlin/JVM module. Normal Kotlin rules apply:

* `package`, `import`, visibility, overload, class, object, function, property,
  and type semantics work as they do in a Gradle Kotlin project.
* Files in the same package can use each other's declarations directly.
* Files in different packages use ordinary imports.
* `@file:Import` is not used.
* A source file containing only shared declarations needs no EternalScript
  annotation.

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

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.event.player.PlayerJoinEvent

@EternalScriptEntry
internal fun Script.configureListeners() {
    event<PlayerJoinEvent> { event ->
        event.player.sendMessage("Join #${nextJoinCount()}")
    }
}
```

A file that registers lifecycle work declares an `@EternalScriptEntry`
function; declaration-only files need none. Each file may declare at most one
entry. An entry is a top-level public or internal, non-suspending extension on
`Script`; it has no value or type parameters and returns `Unit`. Entry
functions run in normalized source-path order when a new generation is
created. Import `EternalScriptEntry` and `Script` directly (an import alias is
allowed), or use their fully qualified names. Wildcard imports and Kotlin
`typealias` declarations are intentionally not used to identify entry markers.

Keep ordinary top-level initializers free of external side effects. Kotlin
initializes JVM file facades when they are first used, so cross-file property
initializer order is not a lifecycle guarantee. Put Paper changes, I/O, and
task startup inside an entry's `enable`, event, command, or tracked-task
callback.

### Lifecycle and Transactional Reload

```kotlin
package my.server

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit

@EternalScriptEntry
internal fun Script.configureLifecycle() {
    enable {
        Bukkit.broadcastMessage("EternalScript enabled")
    }

    disable {
        Bukkit.broadcastMessage("EternalScript disabled")
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

Pass coroutine `Job`, Bukkit `BukkitTask`, and Paper/Folia `ScheduledTask`
instances to `track(...)`. They are cancelled when the generation is unloaded,
replaced, or cleaned up after failed activation. Reload waits for already
running managed callbacks for a bounded period; if they do not drain, the
replacement is aborted.

Every successful reload creates new JVM classes and a new `Script` instance.
Ordinary top-level mutable properties are initialized again with the new
generation. Store data that must survive reloads in an explicit persistent
store.

### Events

```kotlin
package my.server

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

@EternalScriptEntry
internal fun Script.configurePlayerEvents() {
    event<PlayerJoinEvent> { event ->
        Bukkit.broadcastMessage("${event.player.name} joined the server")
    }

    event<PlayerQuitEvent> { event ->
        Bukkit.broadcastMessage("${event.player.name} left the server")
    }
}
```

### Commands

```kotlin
package my.server

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit

@EternalScriptEntry
internal fun Script.configureCommands() {
    command("test-command") {
        permission = "eternals.command.test"
        executor = { sender, _, _ ->
            Bukkit.broadcastMessage("Command executed by ${sender.name}")
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

Generic `.kts` and the former `.eternal.kts` format are ignored and reported
as legacy sources. Empty snapshots do not silently unload the active
generation; use `/es unload all` explicitly.

#### Migrating old script sources

Older executable `.kt` files are discovered immediately, but their script-only
top-level statements are not valid in an ordinary Kotlin file. They need the
same entry-function conversion as renamed `.kts` files.

1. Rename each `.kts` or `.eternal.kts` runtime source to `.kt`; keep an
   existing `.kt` name.
2. Add normal package declarations and imports.
3. Wrap executable DSL statements in an annotated `Script` extension:

   ```kotlin
   @EternalScriptEntry
   internal fun Script.configureProject() {
       enable { /* ... */ }
       event<MyEvent> { /* ... */ }
   }
   ```

4. Remove `@file:Import`; put shared declarations in ordinary `.kt` files and
   access them through their package or imports.
5. Move required helpers out of paths whose segments begin with `-`.
6. Run `/es check all`, fix normal Kotlin compiler errors, then reload.

The deprecated `Script.script(String)` and `ScriptManager.script(String)`
compatibility shims return the same current generation for every known source.
Do not retain the returned value across a reload. Similarly, the source
argument to `functions(source)` and `call(source, ...)` is only a
known-source membership guard in project mode.

### Installation

1. Download EternalScript from [Modrinth](https://modrinth.com/plugin/eternalscript).
2. Put the JAR in the server's `plugins` directory.
3. Start or restart the server once to create
   `plugins/EternalScript/scripts/`.

### Getting Started

Create `plugins/EternalScript/scripts/hello.kt`:

```kotlin
package my.server

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit

@EternalScriptEntry
internal fun Script.configureHello() {
    enable {
        Bukkit.broadcastMessage("Hello, world!")
    }
}
```

Run `/es reload "hello.kt"` or `/es reload all`. Both commands compile and
activate the complete project.

### Kotlin Project Workspace

The `script-workspace` module is a normal Gradle Kotlin module. Open the
repository root in IntelliJ IDEA and edit files under
`script-workspace/src/main/kotlin`. Package navigation, file-to-file
references, refactoring, completion, and normal compiler diagnostics require
no custom Kotlin Scripting definition or IDE-local script configuration.

Run all workspace and bundled-project checks with:

```powershell
gradle checkScripts --no-daemon --no-watch-fs --console=plain
```

The checker and Paper runtime use the same source discovery, entry validation,
module generation, and Kotlin compiler backend. The checker compiles without
evaluating or activating the project.

### Reload Cost

The Kotlin build-tools compiler keeps a project cache and reuses unchanged
compilation state. A changed file still produces a complete candidate
generation JAR because activation remains an all-or-nothing generation swap.
Compilation happens while the old generation remains available; publication
only begins after the replacement is ready.
