package eternalScript.core.script.project

import eternalScript.api.script.EternalScript
import eternalScript.core.script.classloading.ScriptGenerationClassLoader
import eternalScript.core.script.classloading.withThreadContextClassLoader
import eternalScript.core.script.generation.GenerationRuntimeHandle
import eternalScript.core.script.runtime.ManagedScriptRuntime
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import kotlin.script.experimental.api.*

/**
 * Turns a compiled project artifact into a staged script generation.
 * Activation, replacement, and rollback remain owned by ScriptManager.
 */
internal class ScriptGenerationEvaluator {
    fun evaluate(
        compiledScript: CompiledScript
    ): ResultWithDiagnostics<EvaluationResult> {
        if (compiledScript !is KotlinModuleCompiledScript) {
            return ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    ScriptDiagnostic.unspecifiedError,
                    "ScriptGenerationEvaluator cannot evaluate this compiled artifact.",
                    ScriptDiagnostic.Severity.ERROR,
                    PROJECT_SCRIPT_NAME,
                    null,
                    null
                )
            )
        }

        val loader = ScriptGenerationClassLoader(
            (
                listOf(compiledScript.generationJar) +
                    compiledScript.runtimeDependencyFiles
                ).map { path -> path.toUri().toURL() }.toTypedArray(),
            compiledScript.classpathSnapshot.parentClassLoader,
            compiledScript.classpathSnapshot,
            compiledScript.ownedClassNames,
            compiledScript.pluginDependencyNames
        )
        val runtimes = mutableListOf<ManagedScriptRuntime>()
        var runtimeResource: GenerationRuntimeHandle? = null
        var runtimeTransferred = false
        return try {
            withThreadContextClassLoader(loader) {
                val scriptTypes = discoverEternalScriptTypes(
                    compiledScript.ownedClassNames,
                    loader
                )

                scriptTypes.forEach { type ->
                    runtimes += ManagedScriptRuntime(instantiateEternalScript(type))
                }

                check(runtimes.isNotEmpty()) {
                    "An EternalScript project did not create any script instances."
                }
                val resource = GenerationRuntimeHandle(
                    loader,
                    compiledScript.generationJar,
                    runtimes.toList()
                )
                runtimeResource = resource
                val runtime = ScriptProjectRuntime(runtimes.toList(), resource)
                val result = ResultWithDiagnostics.Success(
                    EvaluationResult(
                        ResultValue.Unit(ScriptProjectRuntime::class, runtime),
                        ScriptEvaluationConfiguration()
                    )
                )
                runtimeTransferred = true
                result
            }
        } catch (exception: Throwable) {
            val failure = exception.unwrapReflectionFailure()
            runtimes.forEach { staged ->
                runCatching(staged::disposeRuntime)
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            if (!runtimeTransferred) {
                runCatching {
                    runtimeResource?.close() ?: loader.close()
                }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            ResultWithDiagnostics.Failure(
                compiledScript.project.evaluationDiagnostic(failure)
            )
        }
    }
}

internal fun discoverEternalScriptTypes(
    classNames: Iterable<String>,
    loader: ClassLoader
): List<Class<out EternalScript>> =
    classNames
        .asSequence()
        .sorted()
        .map { className -> Class.forName(className, false, loader) }
        .filter { type ->
            EternalScript::class.java.isAssignableFrom(type) &&
                type != EternalScript::class.java &&
                !Modifier.isAbstract(type.modifiers) &&
                !type.isInterface &&
                !type.isEnum &&
                !type.isAnnotation &&
                !type.isSynthetic
        }
        .map { type -> type.asSubclass(EternalScript::class.java) }
        .sortedBy { type -> type.name }
        .toList()

private fun instantiateEternalScript(
    type: Class<out EternalScript>
): EternalScript {
    val constructor = type.getDeclaredConstructor()
    check(constructor.trySetAccessible()) {
        "EternalScript entry ${type.name} must have an accessible no-argument constructor."
    }
    return constructor.newInstance()
}

private fun ScriptProjectSource.evaluationDiagnostic(
    exception: Throwable
): ScriptDiagnostic {
    val position = runtimePosition(exception)
    val start = position?.let { SourceCode.Position(it.line, it.column) }
    return ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedException,
        exception.message ?: exception.javaClass.name,
        ScriptDiagnostic.Severity.ERROR,
        position?.sourceName ?: PROJECT_SCRIPT_NAME,
        start?.let { SourceCode.Location(it) },
        exception
    )
}

private fun Throwable.unwrapReflectionFailure(): Throwable {
    var current = this
    while (true) {
        current = when (current) {
            is InvocationTargetException -> current.targetException ?: return current
            is ExceptionInInitializerError -> current.exception ?: return current
            else -> return current
        }
    }
}
