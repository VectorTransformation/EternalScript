/**
 *
 * lifecycle
 *
 */

package eternalScript.examples

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import org.bukkit.Bukkit

@EternalScriptEntry
internal fun Script.configureLifecycleExample() {
    enable {
        Bukkit.broadcastMessage("enable: script")
    }

    disable {
        Bukkit.broadcastMessage("disable: script")
    }
}
