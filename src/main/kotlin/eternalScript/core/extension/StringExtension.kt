package eternalScript.core.extension

import net.kyori.adventure.text.minimessage.MiniMessage

fun String.toComponent() = MiniMessage.miniMessage().deserialize(this)

fun String.wrap(affix: CharSequence = "\"") = "$affix$this$affix"

fun String.unwrap(affix: CharSequence = "\"") = removeSurrounding(affix)
