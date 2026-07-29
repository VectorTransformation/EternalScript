# EternalScript Kotlin Workspace

This is a normal Kotlin/JVM module for code deployed to
`plugins/EternalScript/scripts/`.

Put sources under `src/main/kotlin`. Files use ordinary Kotlin packages,
imports, visibility, file-to-file references, navigation, and refactoring.
There is no custom `.kts` definition or `@file:Import`.

Files containing only shared declarations need no annotation. Runtime
registration belongs in a top-level `Script` extension:

```kotlin
package eternalScript.workspace

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script

@EternalScriptEntry
internal fun Script.configureProject() {
    enable {
        // setup
    }
}
```

Check the workspace, bundled project, and bundled examples without evaluating
them:

```powershell
gradle checkScripts --no-daemon --no-watch-fs --console=plain
```

Copy the checked `.kt` files to
`plugins/EternalScript/scripts/`, preserving package-related project content,
then run:

```text
/es reload all
```

Specifying one known source, such as `/es reload "hello.kt"`, also reloads the
complete project generation.
