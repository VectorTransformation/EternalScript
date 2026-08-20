package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.runtime.ReplStateBridge
import java.lang.reflect.InvocationTargetException

internal sealed interface BatchEvaluationResult {
    data class Success(
        val scripts: List<BatchEvaluatedScript>,
        val state: ReplStateBridge.StateTable,
        val classLoader: ClassLoader
    ) : BatchEvaluationResult

    data class Failure(val diagnostic: SharedReplDiagnostic) : BatchEvaluationResult
}

internal object BatchK2Evaluator {
    fun evaluate(
        generation: BatchCompiledGeneration,
        parentClassLoader: ClassLoader,
        baseState: ReplStateBridge.StateTable = ReplStateBridge.StateTable()
    ): BatchEvaluationResult {
        val classLoader = BatchMemoryClassLoader(generation.outputFiles, parentClassLoader)
        val evaluated = mutableListOf<BatchEvaluatedScript>()
        return try {
            ReplStateBridge.stage(baseState) { state ->
                val scriptsByPath = generation.scripts.associateBy { script -> script.source.name }
                generation.graph.initializationOrder.forEach { path ->
                    val script = requireNotNull(scriptsByPath[path]) {
                        "The compiled generation has no script for initialization path: $path"
                    }
                    val type = classLoader.loadClass(script.className)
                    val instance = type.getField("INSTANCE").get(null)
                    val eval = type.methods.singleOrNull { method -> method.name == "\$\$eval" }
                        ?: error("Generated script has no \$\$eval method: ${script.className}")
                    val scriptDsl = RuntimeScript()
                    ReplStateBridge.beginEvaluation(script.stateKey)
                    try {
                        try {
                            when (eval.parameterCount) {
                                0 -> eval.invoke(instance)
                                1 -> eval.invoke(instance, scriptDsl)
                                else -> error(
                                    "Generated script has unsupported \$\$eval parameters: ${eval.parameterTypes.joinToString()}"
                                )
                            }
                        } catch (error: InvocationTargetException) {
                            throw error.targetException
                        }
                    } finally {
                        ReplStateBridge.endEvaluation(script.stateKey)
                    }
                    val value = script.resultFieldName?.let { fieldName ->
                        type.getDeclaredField(fieldName).apply { trySetAccessible() }.get(instance)
                    }
                    ReplStateBridge.markReady(script.stateKey)
                    evaluated += BatchEvaluatedScript(script, instance, scriptDsl, value)
                }
                BatchEvaluationResult.Success(evaluated, state, classLoader)
            }
        } catch (error: Throwable) {
            BatchEvaluationResult.Failure(
                SharedReplDiagnostic(
                    evaluated.lastOrNull()?.compiled?.source?.name
                        ?: generation.scripts.getOrNull(evaluated.size)?.source?.name
                        ?: "<batch-evaluation>",
                    error.message ?: error.javaClass.name,
                    cause = error
                )
            )
        }
    }
}

private class RuntimeScript : Script()

private class BatchMemoryClassLoader(
    private val outputFiles: Map<String, ByteArray>,
    parent: ClassLoader
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = outputFiles[path] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
