package eternalScript.core.script.generation

internal fun cleanup(
    failures: MutableList<Throwable>,
    block: () -> Unit
) {
    try {
        block()
    } catch (exception: Throwable) {
        failures.add(exception)
    }
}
