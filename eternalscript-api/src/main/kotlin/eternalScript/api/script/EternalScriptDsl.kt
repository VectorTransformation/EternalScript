package eternalScript.api.script

/** Keeps nested EternalScript configuration receivers from leaking into each other. */
@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class EternalScriptDsl
