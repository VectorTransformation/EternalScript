package eternalscript.api.script

abstract class Script {
    fun onLoad(block: () -> Unit) = block()

    inline fun <reified T> on(noinline block: (T) -> Unit) = Unit
}
