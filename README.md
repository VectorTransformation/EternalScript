# EternalScript

EternalScript is a reloadable Kotlin scripting runtime for Paper. It compiles
all enabled `.eternal.kts` files as one K2 project, allowing declarations to be
shared across files while keeping ordinary Kotlin imports file-local.

The project also includes an IntelliJ IDEA plugin that provides Kotlin analysis,
completion, and navigation for an EternalScript workspace while the server is
offline.

## Current release

| Component | Version |
| --- | --- |
| EternalScript runtime | 2.1.2 |
| IntelliJ plugin | 2.1.3 |
| Paper API | 26.2 |
| Java | 25 |
| Kotlin | 2.4.10 |
| IntelliJ IDEA | 2026.2.1 (`262.9437.185`) |

## Features

- Shared top-level values, functions, classes, objects, interfaces, enums, and
  type aliases across script files.
- Normal Kotlin file-local imports with no implicit convenience imports.
- Typed Paper event listeners and reload-owned commands.
- `onLoad`, `onUnload`, `onDispose`, and owned-resource lifecycle management.
- Dependency-aware incremental recompilation and component reuse.
- Transactional generation replacement with rollback after activation failure.
- Persistent script enable and disable state through a leading `-` in a file or
  directory name.
- Compiled component cache with a fully stopped cache-hit restart path.
- English, Korean, Japanese, and Simplified Chinese message catalogs.
- A public Paper service API for other plugins.
- IntelliJ K2 completion and navigation backed by the live server environment.

## Installation

1. Install Java 25 and a compatible Paper 26.2 server.
2. Place `EternalScript-2.1.2.jar` in the server's `plugins` directory.
3. Start the server once.
4. Edit scripts under `plugins/EternalScript/scripts`.

The first start creates this workspace:

```text
plugins/EternalScript/
├─ scripts/                    # .eternal.kts source files
├─ libs/                       # additional JARs and class directories
├─ lang/                       # optional message catalog overrides
├─ data/storage.db             # Paper script persistent storage
├─ cache/scripts-v5/           # compiled component cache
├─ .eternalscript/ide/
│  └─ environment.properties  # IntelliJ environment manifest
└─ config.yml
```

When `scripts` is first created, EternalScript installs an active
`hello.eternal.kts` and a disabled `-example` directory. Existing scripts are
never overwritten. Enable the examples with:

```text
/es enable example
```

## Writing scripts

Only files ending in the lowercase `.eternal.kts` suffix are loaded. A file or
directory whose name begins with one `-` is disabled. Symbolic links are not
followed while discovering scripts.

EternalScript adds no implicit user-facing imports. Import Paper, Adventure,
plugin, library, and EternalScript API types exactly as normal Kotlin code does.

```kotlin
import org.bukkit.event.player.PlayerJoinEvent

var joinCount = 0

fun nextJoinMessage(player: String): String =
    "$player joined (#${++joinCount})"

onLoad {
    notify().success("Join script loaded")
}

on<PlayerJoinEvent> { event ->
    notify(event.player).info(nextJoinMessage(event.player.name))
}
```

Declarations are shared between enabled files in both directions. An import is
visible only in the file containing that import. Top-level initialization is
ordered by the actual declaration dependency graph; unrelated declarations use
logical path order as a deterministic tie-breaker. Eager initialization cycles
are rejected before the active generation changes.

### Lifecycle and resources

```kotlin
onLoad {
    // Runs when this script instance becomes active.
}

onUnload {
    // Runs before an active instance is removed or replaced.
}

val executor = own(java.util.concurrent.Executors.newSingleThreadExecutor()) {
    it.shutdownNow()
}

onDispose {
    // Runs once even when an evaluated candidate never becomes active.
}
```

`own(resource)` closes an `AutoCloseable`. The overload with a disposer supports
other resource types. Disposal callbacks run once in reverse registration order,
and one cleanup failure does not prevent the remaining cleanup attempts.

### Notifications (Paper)

User-facing chat messages use a notifier scoped to an Adventure `Audience`:

```kotlin
import eternalscript.api.script.notification.ScriptNotification
import net.kyori.adventure.text.Component

notify().success("Plugin script loaded") // Paper console by default
notify(player).info("Profile loaded")
notify(player).success("Profile saved")
notify(player).warn("Using the default character")
notify(player).error("Profile save failed")

notify(player).success(
    ScriptNotification(
        title = Component.text("Profile saved"),
        details = listOf(Component.text("Coins: 100")),
        hint = Component.text("Use /profile to review it")
    )
)
```

`INFO`, `SUCCESS`, `WARN`, and `ERROR` select the default color when the supplied
component has no color. Existing Adventure styles, detail lines, and the hint
line are preserved. Calling `notify()` without an audience selects
`Bukkit.getConsoleSender()`. Notifications are sent only to the selected audience and
are never copied to the server log automatically; call `log.error` separately
when an operational record is required.

The former `feedback(...)`, `ScriptFeedbackLevel`, and `ScriptFeedbackMessage`
API has been removed. Existing Paper scripts must migrate to `notify(...)` and
`ScriptNotification` before they can be loaded by this runtime.

### Logging (Paper)

Paper scripts write operational messages through the script-scoped `log` API:

```kotlin
log.debug { "Building an expensive diagnostic message" }
log.info("Profile loaded")
log.warn("Using the default character")

try {
    error("example")
} catch (error: Throwable) {
    log.error("Profile save failed", error)
}
```

`DEBUG`, `INFO`, `WARN`, and `ERROR` are available. Every message includes the
logical script path, including messages written during top-level evaluation,
lifecycle callbacks, events, commands, and `storageTask`. The lazy `debug`
overload does not build its message while DEBUG logging is disabled. Messages
and stack traces use Paper's standard console and server log; EternalScript does
not create a separate log file.

### Persistent storage (Paper)

Paper scripts can store global, player, and world values in
`plugins/EternalScript/data/storage.db`. Declare stable namespace and key names
at the top level, then perform I/O inside `storageTask`:

```kotlin
import org.bukkit.event.player.PlayerJoinEvent

val profiles = storage("example.profiles")
val coins = longKey("coins", 0L)
val character = stringKey("character")

on<PlayerJoinEvent> { event ->
    storageTask {
        val profile = profiles.player(event.player.uniqueId)
        val balance = profile.update {
            val next = this[coins] + 100L
            this[coins] = next
            if (this[character] == null) this[character] = "starter"
            next
        }
        notify(event.player).success("Saved balance: $balance")
    }
}
```

`storageTask` starts and resumes on the Paper main thread; individual storage
operations use EternalScript's database thread and return only after commit.
Use `update` for atomic read-modify-write changes. Its non-suspending block runs
on the database thread, so it must not call Bukkit or Paper APIs. Uncaught task
failures are logged with the script path; use normal `try`/`catch` when the
script should show a custom notification. Storage operations at or above
`slow-storage-ms` produce a warning containing the script path, namespace,
scope type, operation, key name when available, and elapsed time. Stored values
and player or world UUIDs are not included.

Supported keys cover strings, booleans, integers, longs, doubles, UUIDs, byte
arrays, string lists and sets, and `JsonElement`. Assigning `null` to a nullable
key removes it. A stored type never converts automatically when a key changes
type. Storage survives script reloads and server restarts, while in-flight tasks
are cancelled when their script is unloaded.

### Commands

```kotlin
command("greet") {
    aliases("hello")
    permission("example.greet")
    tabCompleter { _, _, args ->
        val prefix = args.lastOrNull().orEmpty()
        listOf("world", "server").filter {
            it.startsWith(prefix, ignoreCase = true)
        }
    }
    executor { sender, label, args ->
        notify(sender).success("/$label ${args.joinToString(" ")}")
    }
}
```

Every script command must define an executor. Labels and aliases are registered
strictly: a collision with Paper, another plugin, or another active script
rejects activation instead of silently replacing the existing command.

## Reload model

Scripts are grouped into strongly connected components from their declaration
dependencies. Changing a provider recompiles its component and active reverse
dependents while unrelated components and instances remain active.

A candidate is compiled and evaluated in staging. EternalScript changes the
active generation only after staging succeeds. Compile or evaluation failure
keeps the previous generation unchanged. If activation or `onLoad` fails, the
affected previous components are restored from their in-memory artifacts.

Listeners, commands, state, component classloaders, and owned resources belong
to the generation that created them. They are removed or disposed during
replacement, rollback, disable, and shutdown.

## Server commands

`/eternalscript` is the primary command and `/es` is its alias.

| Command | Purpose |
| --- | --- |
| `/es` | Show engine state, active count, current operation, and command usage. |
| `/es list [active\|disabled\|all] [page]` | List script targets; defaults to active scripts. |
| `/es check [target]` | Compile enabled disk sources without applying them. |
| `/es reload [target]` | Apply every enabled source, or reload one active target. |
| `/es enable <target>` | Persistently enable a disabled script or directory. |
| `/es disable <target>` | Unload a target and persist the leading `-` marker. |
| `/es cancel` | Cancel preparation or compilation without unloading active scripts. |
| `/es config reload` | Reload language, metrics, and catalog overrides. |

Only one mutation is accepted at a time. Compilation runs in the background and
the active generation continues handling events and commands until application
on the Paper main thread. Once application or lifecycle cleanup begins, another
mutation returns `BUSY`.

Operators can use every management command. Permission plugins may grant
`eternalscript.admin` or an individual permission:

```text
eternalscript.command.list
eternalscript.command.check
eternalscript.command.reload
eternalscript.command.enable
eternalscript.command.disable
eternalscript.command.cancel
eternalscript.command.config
```

## Configuration

The default `config.yml` is:

```yaml
language: en_US
metrics: true
cache: true
logging-level: INFO
slow-storage-ms: 500
```

Supported languages are `en_US`, `ko_KR`, `ja_JP`, and `zh_CN`. Unknown fields
and invalid values are reported by `/es config`; they are not silently migrated
or rewritten. `metrics` controls bStats reporting. `cache` controls compiled
component cache reads and writes; set it to `false` to always compile scripts
without deleting existing cache files. `logging-level` accepts
`DEBUG`, `INFO`, `WARN`, or `ERROR` without case sensitivity. DEBUG messages are
written to the standard server log with a `[DEBUG]` marker. `slow-storage-ms`
sets the persistent-storage warning threshold; set it to `0` to disable those
warnings. `/es config reload` applies the cache flag and both logging settings
immediately.

Optional catalog overrides belong in `plugins/EternalScript/lang`. They use
schema 4 and may replace a subset of known messages:

```json
{
  "_schema": 4,
  "_locale": "ko_KR",
  "messages": {
    "command.list.empty": "현재 실행 중인 스크립트가 없습니다"
  }
}
```

The filename and `_locale` must identify the same locale. An override must use
known keys and preserve the bundled message placeholders. Invalid overrides are
ignored without replacing the bundled catalog.

## Cache

Successful component artifacts are published under
`plugins/EternalScript/cache/scripts-v5`. An unchanged restart loads cached JARs
and the binary symbol index without compiling sources. Missing, stale, damaged,
or ABI-incompatible cache entries fall back to compilation. Other cache-format
directories are ignored and are not deleted automatically.

The cache fingerprint includes the plugin artifact, Kotlin/compiler ABI,
runtime classpath, additional libraries, source graph, JVM target, and relevant
environment versions. Adding or upgrading a plugin or library therefore makes
the old artifacts ineligible for reuse.

## IntelliJ IDEA plugin

Build the local plugin ZIP:

```powershell
.\gradlew.bat :intellij-plugin:buildPlugin
```

Install
`intellij-plugin/build/distributions/EternalScript-2.1.3.zip` through
**Settings | Plugins | Install Plugin from Disk**, restart IDEA, and open a
project containing the server-created `.eternalscript/ide/environment.properties`.

The plugin discovers manifests through the project index. The manifest provides
the validated script root and runtime classpath; no `run/plugins` location is
hardcoded. The server can remain stopped after a valid manifest has been
created.

The current IntelliJ integration provides:

- Kotlin completion for the EternalScript DSL, Paper, installed plugins,
  `libs`, and declarations from other scripts.
- Analysis from the current unsaved document after a short debounce.
- Dependency-aware reanalysis of changed files and affected consumers.
- `Ctrl+B` and quick-definition navigation to the original `.eternal.kts`
  declaration.
- Multiple isolated workspaces, including nested workspace roots.

Disabled paths remain ordinary IntelliJ project sources and participate in
analysis. The leading `-` controls Paper runtime activation only.

The integration targets K2 and the exact IDEA build `262.9437.185`. It uses an
experimental `KaResolveExtension` integration, is not published to Marketplace,
and has no automatic updater. Rebuild, verify, and reinstall it when changing
the IDE module or supported IDEA build.

## Compatibility with other plugins

Normal Paper plugins can coexist with EternalScript. Script compilation and
runtime resolution can see classes exposed by installed plugin classloaders, so
an installed plugin API does not need to be copied into `libs`.

Resolution prefers EternalScript's Paper and runtime classloader, then installed
plugin classloaders in server plugin order, then `libs`. Do not use that order
to select between duplicate fully qualified class names. Duplicate classes can
cause compilation ambiguity, `LinkageError`, or `ClassCastException`.

Kotlin APIs need additional care. If another plugin embeds an independent
`kotlin/**` runtime and exposes Kotlin runtime types across its public boundary,
the script and plugin may see different class identities. Prefer Java-facing
plugin APIs using JDK or Paper types. When Kotlin types must cross the boundary,
plugin authors should provide one compatible Kotlin runtime through an explicit
Paper classpath relationship instead of shading unrelated copies.

Do not put another installed plugin, EternalScript itself, or copies of
EternalScript runtime libraries in `plugins/EternalScript/libs`. After changing
the server plugin set, restart the server and validate startup, `/es reload`,
and shutdown logs. A Gradle build alone does not validate Paper classloader or
plugin lifecycle compatibility.

## External plugin API

Another Paper plugin can control EternalScript through `EternalScriptApi`.
Compile against the runtime JAR without shading it:

```kotlin
dependencies {
    compileOnly(files("libs/EternalScript-2.1.2.jar"))
}
```

Declare EternalScript as a required Paper server dependency of the consuming
plugin:

```yaml
dependencies:
  server:
    EternalScript:
      load: BEFORE
      required: true
      join-classpath: true
```

Then obtain the registered service:

```kotlin
import eternalscript.api.EternalScriptApi
import eternalscript.api.enableAwait

suspend fun enableCombatScript() {
    val api = EternalScriptApi.getOrNull() ?: return
    val result = api.enableAwait("combat/main.eternal.kts")
    logger.info("${result.status} @ revision ${result.revision}")
}
```

The API exposes `snapshot`, `check`, `reload`, `recompile`, `enable`, `disable`, and `cancel`.
Mutation methods return `CompletionStage<ScriptOperationResult>`. Kotlin callers
may use the corresponding `*Await` extensions. Domain failures are returned as
`SUCCESS`, `NO_CHANGE`, `BUSY`, `NOT_FOUND`, `INVALID_PATH`, `FAILED`,
`CANCELLED`, or `DISABLED`.

`recompile` is intentionally API-only: it recompiles the current in-memory
source texts without rereading disk. The server command for applying disk edits
is `/es reload`.

## Build and verification

Run the complete static release gate:

```powershell
.\gradlew.bat releaseCheck --rerun-tasks --no-daemon --console=plain
```

`releaseCheck` builds and tests the runtime, IDE protocol, and IntelliJ plugin;
creates the shaded runtime JAR and IntelliJ distribution; and runs Plugin
Verifier against the supported IDEA build.

The `runServer` task starts the Paper test server.

Release artifacts:

```text
build/libs/EternalScript-2.1.2.jar
intellij-plugin/build/distributions/EternalScript-2.1.3.zip
```

The static gate does not prove server behavior. Before publishing a runtime
release, separately validate a Paper cold start, script load and reload,
intentional rollback, fully stopped cache-hit restart, and clean shutdown.

## License

EternalScript is available under the [MIT License](LICENSE).
