## EternalScript: A Kotlin Scripting Plugin for Minecraft Paper Servers

EternalScript allows you to dynamically load and manage code at runtime on your Minecraft Paper server without requiring restarts. It supports diverse customization features like script lifecycle management, event handling, and custom commands.

### Commands

Here are the available commands for EternalScript:

* `/es` or `/es status`: Shows the loader state and script counts.
* `/es reload all`: Reloads every script in `plugins/EternalScript/scripts/`.
* `/es reload <script>`: Reloads one script (e.g., `/es reload "hello.kt"`). Passing an imported file path reloads only the scripts that depend on it (e.g., `/es reload "-shared/common.kt"`).
* `/es unload all`: Unloads every currently loaded script.
* `/es unload <script>`: Unloads one script (e.g., `/es unload "hello.kt"`).
* `/es list`: Lists all currently loaded scripts.
* `/es check all`: Compiles every script without executing it.
* `/es check <script>`: Compiles one script without executing it.
* `/es config reload`: Reloads the plugin configuration.
* `/es cache clear`: Clears the compiled-script cache.

### Script Lifecycle

Control how your scripts behave, including performing initial setup when loaded and cleaning up resources when unloaded.

```kotlin
enable {
    // This code runs when the script is loaded/enabled.
    Bukkit.broadcastMessage("Eternal Script: Script loaded!")
}

disable {
    // This code runs when the script is unloaded/disabled.
    Bukkit.broadcastMessage("Eternal Script: Script unloaded!")
}
```

Reloads are transactional. If the replacement script fails during `enable`, EternalScript cleans it up and re-enables the previous working instance. Coroutine `Job` and Bukkit `BukkitTask` instances passed to `track(...)` are cancelled automatically during unload, replacement, and failed-reload cleanup.

### Event Handling

Easily register and handle events directly within your scripts to add custom logic.

```kotlin
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

event<PlayerJoinEvent> { event ->
    Bukkit.broadcastMessage("${event.player.name} joined the server!")
}

event<PlayerQuitEvent> { event ->
    Bukkit.broadcastMessage("${event.player.name} left the server.")
}
```

### Custom Commands

Define and register your own in-game commands directly from your scripts.

```kotlin
import org.bukkit.Bukkit

command("test-command") {
    // Players need this permission to execute the command.
    permission = "eternals.command.test"
    executor = { sender, label, args ->
        // This code runs when the command is executed.
        Bukkit.broadcastMessage("Command executed by: ${sender.name}")
    }
}
```

### Script Inclusion/Exclusion Rules

Control which scripts the plugin processes.

* `*.kt | *.kts`: Includes all Kotlin files (`.kt`) and Kotlin script files (`.kts`).
* `-*/ *.kt | -*.kt`: Ignores specific folders or files (e.g., excludes scripts in subfolders or specific files).
* `!*/* .kt | !*.kt`: Loads specific folders or files synchronously (e.g., processes them before other scripts at server startup).

### Installation

It's quick and easy to get EternalScript up and running on your server:

1.  Download the latest version of **EternalScript** from [Modrinth](https://modrinth.com/plugin/eternalscript).
2.  Upload the downloaded file to your Minecraft server's `plugins` folder.
3.  Start or restart your server to load EternalScript.

### Getting Started

Experience the power of EternalScript by writing and running your first Kotlin script:

1.  After installation, a `plugins/EternalScript/scripts/` folder will be created.
2.  Write your Kotlin script file with a `.kt` extension inside this `scripts` folder.
3.  From the server console, use `/es reload "[script].kt"` to load one script or `/es reload all` to load every script in the folder.

### Simple "Hello World" Example

Create a `hello.kt` file in the `plugins/EternalScript/scripts/` folder and enter the following content:

```kotlin
// plugins/EternalScript/scripts/hello.kt

enable {
    Bukkit.broadcastMessage("Hello, world!")
}
```

Then, execute `/es reload "hello.kt"` from the server console, and you'll see the message appear in the game chat.
