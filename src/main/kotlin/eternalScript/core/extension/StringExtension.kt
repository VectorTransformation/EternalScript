package eternalScript.core.extension

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.security.MessageDigest

fun String.toComponent() = MiniMessage.miniMessage().deserialize(this)

fun String.toTranslatable() = Component.translatable(this)

fun String.wrap(affix: CharSequence = "\"") = "$affix$this$affix"

fun String.unwrap(affix: CharSequence = "\"") = removeSurrounding(affix)

fun String.tag(vararg tags: String): String {
    val prefix = tags.joinToString(separator = "") { "<$it>" }

    val suffix = tags.reversed().joinToString(separator = "") { "</${it.split(":").firstOrNull() ?: it}>" }

    return "$prefix$this$suffix"
}

fun String.toSHA256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHexString()