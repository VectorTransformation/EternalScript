# EternalScript Repository Verification Workspace

This module is a repository fixture used to compile the public script API,
cross-file Kotlin behavior, and bundled examples during the plugin build. It
is not the workspace generated for server users.

Put sources under `src/main/kotlin`. Files use ordinary Kotlin packages,
imports, visibility, file-to-file references, navigation, and refactoring.

Files containing only shared declarations are allowed, but an active project
must contain at least one concrete `EternalScript` subclass. Runtime
registration belongs in class lifecycle hooks:

```kotlin
package eternalScript.workspace

import eternalScript.api.script.EternalScript

class ProjectScript : EternalScript() {
    override fun onEnable() {
        // setup
    }
}
```

Multiple classes may be declared in the same Kotlin file:

```kotlin
import eternalScript.api.script.EternalScript

class FirstFeature : EternalScript() {
    override fun onEnable() {
        // setup
    }
}

class SecondFeature : EternalScript() {
    override fun onDisable() {
        // cleanup
    }
}
```

Concrete classes with no-argument constructors are discovered in fully
qualified class-name order and each receives its own listeners, commands, and
tasks.

Check the workspace, bundled project, and bundled examples without evaluating
them:

```powershell
gradle checkScripts --no-daemon --no-watch-fs --console=plain
```

For the actual user workflow, start a server once and open its generated
`plugins/EternalScript/` project. Edit `scripts/*.kt`, run `gradlew.bat check`
(or `/es check`), and apply the complete project with `/es reload`.
The runtime-equivalent checker reports one terminal outcome: `PASSED`,
`FAILED`, or `NO_SOURCES`; the last outcome is action-required rather than a
successful empty project.
