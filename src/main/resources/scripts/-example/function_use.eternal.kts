/**
 * The earlier function sees this later declaration, so both files form one atomic SCC.
 * Changing either file replaces both while unrelated scripts remain active.
 */

import eternalscript.api.script.notification.ScriptNotification
import java.time.Instant

val sharedExamplePrefix = "example: "
val sharedExampleLoadedAt: Instant = Instant.now()

onLoad {
    val example = nextSharedExample("ready")
    notify().success(
        ScriptNotification(
            title = "Shared example ready",
            details = listOf(
                "#${example.count}: ${example.message} at ${example.createdAt}",
                "Consumer initialized at $sharedExampleLoadedAt"
            ),
            hint = "Change either shared example file, then run /es reload example"
        )
    )
}
