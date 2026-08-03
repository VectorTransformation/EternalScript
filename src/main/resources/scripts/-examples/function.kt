package eternalScript.examples

import net.kyori.adventure.text.Component

fun joinMessage(name: String) = Component.text("join: $name")

fun quitMessage(name: String) = Component.text("quit: $name")
