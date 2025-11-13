package eternalScript.core.script.manager

import eternalScript.core.the.Root
import org.bukkit.event.Listener

class ScriptListenerManager : Listener {
    fun clear() {
        Root.unregister(this)
    }
}