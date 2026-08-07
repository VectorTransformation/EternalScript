# EternalScript

Hot-reload an ordinary Kotlin project on Paper or Folia without restarting the
Minecraft server.

Paper/Folia 서버를 재시작하지 않고 일반 Kotlin 프로젝트를 검사하고 안전하게
교체할 수 있습니다.

[Download on Modrinth](https://modrinth.com/plugin/eternalscript) ·
[English documentation](https://github.com/VectorTransformation/EternalScript/wiki) ·
[한국어 문서](https://github.com/VectorTransformation/EternalScript/wiki/Home-ko) ·
[Report an issue](https://github.com/VectorTransformation/EternalScript/issues)

> Current source target: EternalScript 2.0.0 · Paper/Folia 26.2 · Java 25 ·
> Kotlin 2.4.10 · Gradle 9.6.1

## Why EternalScript?

- Write normal `.kt` files with standard packages, imports, classes, functions,
  and cross-file references.
- Organize one project into as many lifecycle-managed `EternalScript` entry
  classes as you need.
- Compile and stage the complete replacement while the last working generation
  remains available.
- Register Paper events, commands, coroutines, and scheduler tasks through a
  lifecycle-aware API.
- Open the generated server workspace directly in IntelliJ IDEA—there is no
  separate copy or deployment step.
- Compile against public APIs from other plugins that are currently enabled on
  the server.
- Receive focused command feedback in English, Korean, Japanese, or Simplified
  Chinese.

## Five-minute start

1. Run Paper 26.2 or a compatible Folia 26.2 build with Java 25.
2. Put the EternalScript JAR in the server's `plugins` directory and start the
   server once.
3. Open `plugins/EternalScript/` as a Gradle project in IntelliJ IDEA.
4. Copy `scripts/-examples/hello.kt` to `scripts/hello.kt`.
5. Run `gradlew.bat check` on Windows or `./gradlew check` on Linux/macOS.
6. Run `/es check` on the server to validate its live plugin classloaders.
7. Run `/es reload`, then `/es status` on the server.

Your first source can be this small:

```kotlin
package my.server

import eternalScript.api.script.EternalScript
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent

class Hello : EternalScript() {
    override fun onEnable() {
        Bukkit.getServer().broadcast(Component.text("Hello, world!"))

        event<PlayerJoinEvent> { event ->
            event.player.sendMessage("Welcome, ${event.player.name}")
        }
    }
}
```

The normal edit loop is:

```text
Edit scripts/*.kt
→ run gradlew.bat check
→ run /es check for live server classloader validation
→ run /es reload
→ use /es status for the current state and next action
```

## Project model

Every included lowercase `plugins/EternalScript/scripts/**/*.kt` file belongs
to one ordinary Kotlin/JVM module. Files in the same package can use each
other's declarations directly; files in different packages use normal Kotlin
imports.

Every concrete `EternalScript` subclass with an accessible no-argument
constructor becomes one managed entry. Entries are enabled in fully qualified
class-name order and disabled in reverse order. Declaration-only files and
abstract base classes are allowed, but activation requires at least one
concrete entry.

There is intentionally no per-file check, reload, or unload. A command always
acts on one stable snapshot of the complete project. The current API also does
not use entry annotations or script-specific import directives.

Source discovery rules are deliberately simple:

- The extension must be lowercase `.kt`.
- A file is ignored when its name or any parent path segment starts with `-`.
- Ignored files are not available as shared project code.
- An empty included source set reports `NO_SOURCES`; it does not silently unload
  the active generation.

See the [Script API guide](https://github.com/VectorTransformation/EternalScript/wiki/Script)
or [스크립트 API 안내](https://github.com/VectorTransformation/EternalScript/wiki/Script-ko)
for lifecycle, command, coroutine, task, and Folia examples.

## Commands

All EternalScript administration commands require server operator access.
`/eternalscript` is the full command and `/es` is its alias.

| Command | Purpose |
| --- | --- |
| `/es` or `/es status` | Show sources, active entries, current or last operation, workspace state, and the next useful action |
| `/es check` | Compile the complete project without evaluating or activating it |
| `/es reload` | Compile and transactionally activate the complete project |
| `/es unload` | Stop and remove the active project |
| `/es list` | List active `EternalScript` entry classes |
| `/es workspace` | Inspect the generated workspace and dependency snapshot |
| `/es workspace update` | Reconcile managed workspace files and refresh dependency metadata |
| `/es config reload` | Reload configuration and language catalogs |
| `/es cache clear` | Clear the incremental compilation cache |

Accepted operations reply to the player, console, RCON, or other sender that
started them. Feedback reports the start, relevant diagnostics, one terminal
result, and one next action instead of duplicating routine messages into the
server log.

## Reload safety

`/es reload` compiles and stages a complete candidate before publication. If
compilation, initialization, or activation fails, EternalScript keeps or safely
restores the last working generation.

EternalScript owns listeners, commands, entry coroutines, and tracked scheduler
tasks registered through its API. On replacement or unload it blocks new
callbacks, drains running work, cancels tracked work, invokes `onDisable`, and
closes generation resources.

Cancellation is a one-way boundary. If tracked work does not stop before the
deadline, EternalScript keeps that generation blocked in cleanup state instead
of reopening it without its cancelled work or closing its classloader while
code is still running. Wait for the work to finish, then retry `/es unload`.

That boundary cannot automatically undo world changes, file writes, network
requests, database updates, or arbitrary state changes made through another
plugin. Keep external setup and cleanup idempotent, release external resources
in `onDisable`, and persist state that must survive reloads explicitly.

## Generated workspace

The plugin creates an IntelliJ-ready project in its own data directory:

```text
plugins/EternalScript/
├─ scripts/                     # Kotlin sources run by the server
│  └─ -examples/                # Ignored examples to copy or rename
├─ libs/                        # Additional compile/runtime JARs
├─ lang/                        # Installed language catalogs
├─ cache/                       # Incremental compiler data
├─ config.yml
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradlew
├─ gradlew.bat
├─ gradle/wrapper/
├─ WORKSPACE.md
├─ workspace.local.gradle.kts   # User-owned Gradle additions
└─ .eternalscript/
   ├─ runtime-classpath.txt
   ├─ manifest.json
   └─ conflicts/
```

EternalScript writes workspace metadata but never launches Gradle on the
server. Run the generated Wrapper yourself:

```powershell
.\gradlew.bat check
```

`compileKotlin` provides normal Kotlin diagnostics and IDE completion.
`checkScripts` performs compile-only validation without evaluating or
activating the project. Because it runs outside the server, it cannot reproduce
live plugin class-loader identity checks; run `/es check` before `/es reload`.
`check` runs both Gradle checks. The script checker reports `PASSED` with exit
code `0`, `FAILED` with `1`, and `NO_SOURCES` with `2`; Gradle treats a non-zero
checker result as a failed task.

Managed workspace files are upgraded only while unmodified. User-owned files
and directories—including `scripts`, `config.yml`, `libs`, `cache`, and
`workspace.local.gradle.kts`—are preserved. Replacement candidates for edited
managed files are written below `.eternalscript/conflicts/`.

## Other plugin APIs

Scripts can import and call public APIs from plugins that are currently enabled
on the server. EternalScript uses the same enabled-plugin classpath snapshot for
runtime compilation, generated workspace metadata, and generation class
loading.

- Prefer the target plugin's documented provider or Bukkit service.
- The inherited `plugin` property is the EternalScript plugin instance, not the
  plugin being integrated.
- Run `/es workspace update` and refresh Gradle when IntelliJ needs updated
  dependency metadata.
- `workspace.local.gradle.kts` can add IDE repositories or `compileOnly`
  artifacts, but it does not install a runtime dependency on the server.
- If a referenced plugin dependency is disabled, EternalScript unloads the
  generation that depends on it.

Read [Plugin Integration](https://github.com/VectorTransformation/EternalScript/wiki/Plugin-Integration)
or [다른 플러그인 연동](https://github.com/VectorTransformation/EternalScript/wiki/Plugin-Integration-ko)
before retaining external services or registering resources outside the
EternalScript API.

## Documentation

| Topic | English | 한국어 |
| --- | --- | --- |
| Overview | [Home](https://github.com/VectorTransformation/EternalScript/wiki) | [홈](https://github.com/VectorTransformation/EternalScript/wiki/Home-ko) |
| Installation and first project | [Getting Started](https://github.com/VectorTransformation/EternalScript/wiki/Getting-Started) | [시작하기](https://github.com/VectorTransformation/EternalScript/wiki/Getting-Started-ko) |
| Project and runtime API | [Script API](https://github.com/VectorTransformation/EternalScript/wiki/Script) | [스크립트 API](https://github.com/VectorTransformation/EternalScript/wiki/Script-ko) |
| Administrative commands | [Commands](https://github.com/VectorTransformation/EternalScript/wiki/Command) | [명령어](https://github.com/VectorTransformation/EternalScript/wiki/Command-ko) |
| IntelliJ and Gradle workspace | [Workspace](https://github.com/VectorTransformation/EternalScript/wiki/Workspace) | [작업공간](https://github.com/VectorTransformation/EternalScript/wiki/Workspace-ko) |
| Other plugin APIs | [Plugin Integration](https://github.com/VectorTransformation/EternalScript/wiki/Plugin-Integration) | [다른 플러그인 연동](https://github.com/VectorTransformation/EternalScript/wiki/Plugin-Integration-ko) |
| Errors and recovery | [Troubleshooting](https://github.com/VectorTransformation/EternalScript/wiki/Troubleshooting) | [문제 해결](https://github.com/VectorTransformation/EternalScript/wiki/Troubleshooting-ko) |

Bundled server feedback languages are `en_US`, `ko_KR`, `ja_JP`, and `zh_CN`.
Set `lang` in `plugins/EternalScript/config.yml`, then run
`/es config reload`.

## Build from source

Building EternalScript requires Java 25. On Windows:

```powershell
.\gradlew.bat build
```

On Linux or macOS:

```bash
./gradlew build
```

The plugin JAR is written to `build/libs/`.

## License

EternalScript is available under the [MIT License](LICENSE).
