package eternalScript.api.script

/**
 * Marks the single lifecycle-registration entry function in an ordinary
 * EternalScript Kotlin source file.
 *
 * The annotated declaration must be a top-level, non-suspending,
 * non-generic extension function on [eternalScript.core.script.Script]. It
 * must accept no value parameters and return [Unit].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class EternalScriptEntry
