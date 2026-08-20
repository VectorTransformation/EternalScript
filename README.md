# EternalScript

EternalScript is a Kotlin Scripting runtime for Paper. All `.eternal.kts`
sources are resolved together by a Kotlin K2 REPL pipeline, so every script can
directly use variables, classes, and functions declared in any other script,
regardless of logical path order. Kotlin `import` directives remain local to
the file that declares them.

## Requirements and installation

- Java 25
- Paper 26.2
- EternalScript 2.1.2

Place the EternalScript JAR in the server's `plugins` directory and start the
server. Runtime files are created under `plugins/EternalScript/`:

```text
EternalScript/
├─ scripts/        # .eternal.kts sources
├─ libs/           # additional JARs and class directories
├─ lang/           # optional schema-4 message overrides
├─ cache/          # runtime compiled-script cache
├─ .eternalscript/
│  └─ ide/
│     └─ environment.properties  # IDE protocol v3 environment manifest
└─ config.yml
```

The server writes only `environment.properties` for IDE support. It records the
IDE protocol version, stable environment ID, runtime and Kotlin versions,
environment fingerprint, relative script root, runtime classpath file URIs, and
the fixed convenience default imports.
The file is replaced atomically on first start and whenever that environment
changes. It contains no user declarations or compiled script output. `scripts`,
`libs`, `lang`, and `config.yml` are never overwritten by this process.

When `plugins/EternalScript/scripts` does not exist, the first start installs
the active `hello.eternal.kts` starter and the disabled `-example/` directory
once. Existing script directories are never overwritten. Run `/es load example`
to enable the command, event, shared-declaration, and lifecycle examples.

## IntelliJ Kotlin completion

Build the local IntelliJ plugin ZIP from this repository:

```powershell
.\gradlew.bat :intellij-plugin:buildPlugin
```

Install `intellij-plugin/build/distributions/EternalScript-2.1.3.zip` with
**Settings | Plugins | gear menu | Install Plugin from Disk**, restart IDEA,
and open any project containing the server-created
`.eternalscript/ide/environment.properties` manifest.
The plugin is intentionally limited to IntelliJ IDEA 2026.2.1 builds in the
`262.*` line and K2 mode. It is not published to Marketplace and has no
automatic updater. Use `:intellij-plugin:runIde` for a development sandbox.

Start the Paper server once so `environment.properties` exists. The plugin finds
manifests through the project index; it has no hardcoded `run/plugins` candidate.
The workspace root comes from the manifest location, and the validated relative
`scriptRoot` selects the actual scripts directory, so moving or copying a whole
workspace keeps the link valid. Multiple and nested workspaces are supported;
each file belongs only to its nearest script root. If a manifest is missing,
damaged, untrusted, unsafe, or incompatible, the plugin stops analysis for that
workspace and shows a localized English, Korean, Japanese, or Simplified Chinese
diagnostic. Once a valid manifest exists, the server may remain stopped while
scripts are edited.

Keep the `.eternal.kts` suffix and edit files below `scripts/`. `Ctrl+Space`
completion includes the EternalScript DSL, Paper API, installed plugin APIs,
classes from `libs`, and public or internal values, properties, overloads,
extension functions, classes, objects, interfaces, enums, and type aliases from
other scripts without explicit imports. External API types remain available for
normal Kotlin auto-import. `Ctrl+B`, quick definition,
`Alt+F7`, and `Shift+F6` are redirected to the real `.eternal.kts` declaration
and usages. Rename writes only the original script files and aborts before the
write action if the new name would collide with a shared declaration or make a
shared name ambiguous.

Document edits are collected for 300 ms and analyzed from the current unsaved
PSI document. Saving, `/es load`, `/es reload`, and a running server are not
required. A file-level declaration/reference index reanalyzes only the changed
file and affected consumers. If a document is temporarily malformed, that file
shows its normal Kotlin syntax errors while other files retain its last valid
shared ABI. Cyclic references work when Kotlin has an explicit type boundary;
recursive inference that Kotlin cannot determine remains an error.

Disabled paths still receive the Script DSL, external API completion, and
declarations exported by enabled scripts, so a `-test.eternal.kts` or a script
below a `-directory` can be edited before it is enabled. Their own declarations
do not enter the shared IDE model, matching the Paper runtime where disabled
scripts are not compiled, evaluated, or loaded. Renaming the path to remove the
leading `-` publishes its declarations; disabling it removes those exports.
Multiple workspaces are isolated by workspace identity, and nested paths are
owned by the nearest registered root.

All declaration overlays live in IntelliJ memory and its system cache. The
plugin does not create source `.kt` files, completion JARs, component models,
or decompiler navigation targets in the project. During migration, the server
removes only recognized old generated model files and old Gradle-workspace files
whose hashes still match its management manifest. User-modified files are
preserved and reported; `scripts`, `libs`, `lang`, and `config.yml` are never
touched.

The **Tools | EternalScript** menu can reread environments, open the current
manifest, reveal its script root, and copy diagnostics containing workspace ID,
analysis version, changed-file and ABI counts, configuration reloads,
cancellations, and processing time.

The IDE integration uses the experimental K2 `KaResolveExtension` SPI behind a
262-specific facade. Protocol v3 is an immediate compatibility break: update
the EternalScript runtime JAR and the 2.1.3 IntelliJ ZIP together. Rebuild and
reinstall the ZIP after changing the IDE module or moving to another IDEA build
line. See the
[Kotlin custom scripting overview](https://kotlinlang.org/docs/custom-script-deps-tutorial.html)
and [JetBrains IntelliJ Platform plugin documentation](https://plugins.jetbrains.com/docs/intellij/welcome.html).

## Script files and shared declarations

Only lowercase `.eternal.kts` files are loaded. A file or directory whose name
starts with `-` is ignored. File annotations are not required.

Declarations are shared in both directions. Explicit imports, import aliases,
and star imports follow normal Kotlin rules and affect only their declaring
file. Paper, installed plugin APIs, and `plugins/EternalScript/libs` remain on
the compilation and runtime classpaths, so their other types use a normal
explicit import or fully qualified name.

The fixed convenience imports are `Bukkit`, `PlayerJoinEvent`,
`PlayerQuitEvent`, `ScriptFeedbackLevel`, and `ScriptFeedbackMessage`. This
small public list is independent of which plugins or library JARs happen to be
installed.

Logical path order is only a deterministic tie-breaker. Top-level values are
initialized after the values they actually depend on, even when the provider
has a later path. Unrelated scripts use logical path order. Eager initialization
cycles are rejected before the active generation is changed.

```kotlin
// 00-state.eternal.kts
import java.time.Instant

var joinCount = 0
data class JoinSummary(val player: String, val count: Int, val time: Instant)

fun nextJoin(player: String): JoinSummary =
    JoinSummary("$joinPrefix$player", ++joinCount, Instant.now())
```

```kotlin
// 10-join.eternal.kts
import java.time.Instant
import net.kyori.adventure.text.Component

// The earlier function can use this later declaration. Imports stay local to
// this file. Bukkit and PlayerJoinEvent are fixed convenience imports.
val joinPrefix = "player:"
val scriptStartedAt: Instant = Instant.now()

on<PlayerJoinEvent> { event ->
    val summary = nextJoin(event.player.name)
    Bukkit.broadcast(Component.text("${summary.player} joined (#${summary.count})"))
}
```

Command and API paths omit the physical `scripts/` prefix. For example, the
file `plugins/EternalScript/scripts/combat/a.eternal.kts` has the logical path
`combat/a.eternal.kts`. Absolute paths, `..`, backslashes, and other extensions
are rejected.

## Lifecycle, events, and commands

```kotlin
import org.bukkit.event.EventPriority

onLoad {
    feedback(Bukkit.getConsoleSender(), "Script loaded", ScriptFeedbackLevel.SUCCESS)
}

onUnload {
    feedback(Bukkit.getConsoleSender(), "Script unloaded")
}

// AutoCloseable resources are closed automatically.
val input = own(java.io.ByteArrayInputStream(byteArrayOf()))

// Other resources can provide their own disposer.
val worker = own(java.util.concurrent.Executors.newSingleThreadExecutor()) { executor ->
    executor.shutdownNow()
}

onDispose {
    // Always runs for this evaluated instance, even if activation fails.
}

on<PlayerQuitEvent>(priority = EventPriority.MONITOR) { event ->
    feedback(Bukkit.getConsoleSender(), "${event.player.name} left")
}

command("example-command") {
    aliases("example")
    permission(null)
    tabCompleter { _, _, args ->
        val current = args.lastOrNull().orEmpty()
        listOf("hello", "world").filter { it.startsWith(current, ignoreCase = true) }
    }
    executor { sender, label, args ->
        feedback(
            sender,
            ScriptFeedbackMessage(
                title = "Example command completed",
                details = listOf("/$label ${args.joinToString()}"),
                hint = "Try /$label hello"
            ),
            ScriptFeedbackLevel.SUCCESS
        )
    }
}
```

`onLoad` runs whenever a compiled script instance is activated, even when no
player is online. If a provider changes and an active consumer is replaced, the
consumer can run `onUnload` and `onLoad` again as part of that atomic replacement.

Scripts that reference each other form one atomic strongly connected component
(SCC). A change recompiles that component and every component that depends on
it; unrelated components and their instances stay active. The candidate is
compiled and evaluated into staging first. Existing affected scripts then run
`onUnload` in reverse initialization order, their listeners and commands are
removed, the staged state is published, and `onLoad` runs in initialization
order. A compile or staging failure leaves the active generation untouched. An
activation or `onLoad` failure restores only the affected components from their
previous in-memory artifacts.

Listeners and commands are owned by EternalScript and are removed during
unload or rollback. Use `own(resource)` for `AutoCloseable` values,
`own(resource) { ... }` for other resource types, or `onDispose { ... }` for
custom cleanup. Disposal runs exactly once in reverse registration order for a
normal unload, a rejected candidate, and a failed activation. Every cleanup is
attempted even when an earlier one fails. `onUnload` remains the place for
active-only lifecycle behavior and runs before owned resources are disposed.

Every script command must define an `executor`; a command without one is
rejected during evaluation instead of registering a silent no-op. Permission
checks use Bukkit's configured permission-denied message, and unauthorized
senders cannot execute or tab-complete the command.

### Script feedback

`feedback` gives scripts the same consistent Adventure output used by the
plugin. A recipient may be any Adventure `Audience`, including a player or the
console. The available levels are `INFO`, `SUCCESS`, `WARNING`, and `ERROR`.
Use the string overload for literal text or `ScriptFeedbackMessage` for a title,
detail lines, and an optional hint. String values are never parsed as MiniMessage
markup; pass Adventure `Component` values when deliberate styling is required.

The feedback types are fixed default imports, so normal scripts do not need
import statements:

```kotlin
feedback(player, "Profile saved", ScriptFeedbackLevel.SUCCESS)

feedback(
    player,
    ScriptFeedbackMessage(
        title = "Profile could not be saved",
        details = listOf("File: profiles/${player.uniqueId}.json"),
        hint = "Try again or contact an administrator"
    ),
    ScriptFeedbackLevel.ERROR
)
```

## Commands

`/eternalscript` is the primary command and `/es` is its alias. The command
is visible to command senders. Operators can use every management action.
Permission plugins can grant all actions with `eternalscript.admin`, or grant
only one action with `eternalscript.command.<action>` where `action` is
`help`, `reload`, `compile`, `load`, `unload`, `clear`, `config`, `list`, or
`status`. An unauthorized action returns an explicit permission error.

- `/es` or `/es help` shows the built-in command help.
- `/es reload` rereads enabled source files from disk, recompiles them, and
  replaces the complete generation.
- `/es compile` forcibly recompiles the current in-memory sources. Use
  `/es reload` after editing a source file outside the server.
- `/es load "path.eternal.kts"` removes a leading `-` from a script and loads
  or replaces it together with the enabled providers it needs. A directory
  target removes `-` from that directory only and recursively loads its
  enabled descendants. Loading an already active target replaces it even when
  its source text is unchanged, running `onUnload` and `onLoad` again for that
  target and any affected active consumers while retaining unrelated scripts.
- `/es unload "path.eternal.kts"` unloads a script and adds `-` to its file
  name. A directory target unloads its active descendants and adds `-` to that
  directory only. Nested `-` files and directories keep their names and remain
  disabled.
- `/es clear` cancels the current script operation, rejects reentrant mutations
  until cleanup finishes, and unloads every active script without renaming files.
- `/es config` reloads the `language` and `metrics` settings.
- `/es list [page]` lists active logical paths in pages of ten.
- `/es status` reports whether the runtime is starting, ready, or disabled,
  the active-script count, and any script operation currently in progress.

Compilation requested by a command runs in the background. The existing
generation continues handling events and commands until the result is applied
on the Paper main thread. The sender receives an immediate request
acknowledgement followed by the final result. Only one normal mutation is
accepted at a time.

A single leading `-` on the target's final path segment is reserved as the
persistent disabled marker. Commands accept either the logical path (`combat`)
or its disabled spelling (`-combat`); names beginning with `--` are invalid.
If both enabled and disabled spellings exist, the command is rejected. A
compile, evaluation, activation, cancellation, or shutdown failure restores a
name changed by the command.

For a consumer `hello.eternal.kts` that references declarations from
`test.eternal.kts`:

- Loading `hello` also loads an enabled inactive `test`, with `test` initialized
  first. A `-test.eternal.kts` remains explicitly disabled and makes the load
  fail without changing `hello`'s disabled state.
- Replacing `test` may replace an already active `hello`, but it does not load
  an inactive consumer merely because that consumer references `test`.
- Unloading `test` is rejected while an active `hello` outside the target uses
  it. Unload the consumers first or unload a directory that contains both.

## Configuration and cache

Default `config.yml` values are:

```yaml
language: en_US
metrics: true
```

`language` selects the message catalog (`en_US`, `ko_KR`, `ja_JP`, or `zh_CN`), and `metrics`
controls bStats reporting. Scripts and additional libraries always use
`plugins/EternalScript/scripts` and `plugins/EternalScript/libs` respectively.
`metrics` must be a YAML boolean. Invalid values and unknown top-level fields are
reported by `/es config` instead of being accepted silently. A redundant legacy
`lang` field is removed after its value has been migrated to `language`.
A successfully activated component graph is cached under
`plugins/EternalScript/cache/scripts-v5`. An unchanged cache-hit startup loads
component JARs and their binary symbol index with zero compilation; no compiler
warmup pass is required. Missing, stale, ABI-mismatched, or damaged cache data
falls back to a cold compile. A later successful cache publish also removes
temporary object directories left by an interrupted publish.

The batch REPL backend is coupled to the exact Kotlin compiler version declared
by `kotlinVersion`. Its adapter ABI, runtime compiler libraries, and regression
tests must be updated together. Do not update Kotlin independently or use an EAP
compiler in a release build; validate bidirectional declarations, file-local imports, dependency
closures, component-cache restart, rollback, and a Paper cold restart first.

## Feedback catalogs and system logs

All plugin-owned command responses and runtime messages pass through one
feedback system. Player and command-sender output uses a single branded header
followed by optional detail and recovery-hint lines. Console output uses the
Paper logger without a duplicate plugin prefix and assigns lifecycle events to
`INFO`, final cache or cleanup problems to `WARN`, and
failed operations or diagnostics to `ERROR`. Compiler diagnostic text remains
unchanged so exact Kotlin errors can still be searched; its phase and location
wrapper is localized.

Bundled English, Korean, Japanese, and Simplified Chinese catalogs are loaded
from the plugin JAR. Files under
`plugins/EternalScript/lang/` are optional partial overrides and are not created
or overwritten by the plugin. They use schema 4 and named placeholders:

```json
{
  "_schema": 4,
  "_locale": "ko_KR",
  "messages": {
    "command.list.empty": "현재 실행 중인 스크립트가 없습니다"
  }
}
```

The filename and `_locale` must identify the same locale. Override keys must be
known and must preserve exactly the named placeholders from the bundled entry.
Catalogs are parsed and validated completely before replacing the active set;
an invalid override is ignored with its exact filename and reason while the
bundled catalog stays active. Legacy flat catalogs using `%s` are deliberately
ignored and reported, rather than being partially interpreted as schema 4.
The configured `language` must match a bundled catalog or a valid external
catalog. A blank, non-string, or unknown value falls back to `en_US` and is
reported as a configuration issue instead of silently claiming success.
On first reload after upgrading from the legacy plugin, an existing `lang`
value is migrated to `language` without changing the selected locale.
`/es config` reloads configuration and these overrides without restarting the
server.

## External plugin API

This section is only for authors of another Java or Kotlin Paper plugin that
calls `EternalScriptApi`. Normal `.eternal.kts` scripts do **not** add a Gradle
dependency, copy an EternalScript JAR into their source directory, or import
the external-plugin API.

EternalScript is distributed as one plugin JAR, not as a separate API module.
Put that JAR in the consuming plugin project's `libs` directory and add it only
to the compile classpath:

```kotlin
dependencies {
    compileOnly(files("libs/EternalScript-2.1.2.jar"))
    // Also declare the normal Paper API or paperweight dependency used by
    // this consuming plugin project.
}
```

Do not shade EternalScript into the consuming plugin. At runtime, install the
same EternalScript JAR as a server plugin and declare it as a required Paper
server dependency:

```yaml
dependencies:
  server:
    EternalScript:
      load: BEFORE
      required: true
      join-classpath: true
```

`compileOnly` means the consuming plugin's compiler can resolve the API while
the class itself is supplied by the enabled EternalScript plugin at runtime.
`join-classpath: true` gives the consuming Paper plugin access to that API
classloader.

### Java

```java
import eternalscript.api.EternalScriptApi;
import eternalscript.api.ScriptOperationResult;
import eternalscript.api.ScriptSnapshot;

import java.util.concurrent.CompletionStage;

EternalScriptApi api = EternalScriptApi.get();
ScriptSnapshot snapshot = api.snapshot();
CompletionStage<ScriptOperationResult> pending = api.reload();

pending.thenAccept(result ->
    getLogger().info("reload=" + result.getStatus()
        + ", revision=" + result.getRevision())
);
```

`get()` throws a clear exception when the service is unavailable;
`getOrNull()` returns `null`. Mutation methods return
`CompletionStage<ScriptOperationResult>` and normal domain failures are
reported through `SUCCESS`, `NO_CHANGE`, `BUSY`, `NOT_FOUND`, `INVALID_PATH`,
`FAILED`, `CANCELLED`, or `DISABLED` rather than exceptional completion.

### Kotlin

```kotlin
import eternalscript.api.EternalScriptApi
import eternalscript.api.loadAwait

suspend fun loadCombatScript() {
    val api = EternalScriptApi.getOrNull() ?: return
    val snapshot = api.snapshot()
    val result = api.loadAwait("combat/a.eternal.kts")
    logger.info("${snapshot.state} -> ${result.status} @ ${result.revision}")
}
```

Kotlin also provides `reloadAwait`, `recompileAwait`, `unloadAwait`, and
`clearAwait`. Cancelling the coroutine wait does not cancel an operation that
the shared engine has already accepted. `reload()` applies only detected disk
changes and their reverse dependents, while `recompile()` forces a complete
compiler/evaluator generation.

## Build

```powershell
.\gradlew.bat test check --rerun-tasks
.\gradlew.bat shadowJar :intellij-plugin:verifyPlugin :intellij-plugin:buildPlugin
```

The wrapper stays on Gradle 9.7 because `run-paper` 3.1.0 targets the Gradle 9.7
plugin API. Kotlin 2.4.10 currently declares full Gradle support through 9.5,
so Gradle, the Kotlin plugin, `run-paper`, and the compiler adapter must be
validated and upgraded together rather than changed independently.

The runtime artifact is `build/libs/EternalScript-2.1.2.jar`. The local IDEA
installation artifact is
`intellij-plugin/build/distributions/EternalScript-2.1.3.zip`.
