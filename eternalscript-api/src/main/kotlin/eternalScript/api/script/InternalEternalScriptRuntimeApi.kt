package eternalScript.api.script

/**
 * Marks the narrow integration surface used by the EternalScript plugin.
 * Script projects must use [EternalScript] rather than calling this API.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for the EternalScript runtime."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalEternalScriptRuntimeApi
