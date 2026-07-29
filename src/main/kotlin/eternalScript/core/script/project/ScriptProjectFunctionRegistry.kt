package eternalScript.core.script.project

import eternalScript.api.script.EternalScriptEntry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.kotlinFunction

internal data class ScriptProjectFunctionInvocation(val value: Any?)

internal class ScriptProjectFunctionRegistry private constructor(
    private val functions: MutableMap<String, MutableList<Method>>
) {
    fun zeroArgumentNames(): List<String> =
        functions.asSequence()
            .filter { (_, overloads) -> overloads.any { method -> method.parameterCount == 0 } }
            .map { entry -> entry.key }
            .sorted()
            .toList()

    fun call(name: String, vararg args: Any?): ScriptProjectFunctionInvocation? {
        val candidates = functions[name]
            ?.filter { method -> method.accepts(args) }
            .orEmpty()
        if (candidates.isEmpty()) return null
        check(candidates.size == 1) {
            val signatures = candidates.joinToString { method ->
                method.parameterTypes.joinToString(
                    prefix = "${method.name}(",
                    postfix = ")",
                    transform = Class<*>::getTypeName
                )
            }
            "Ambiguous EternalScript function '$name': $signatures"
        }

        val method = candidates.single()
        val value = try {
            method.invoke(null, *args)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
        return ScriptProjectFunctionInvocation(
            if (method.returnType == java.lang.Void.TYPE) Unit else value
        )
    }

    fun clear() {
        functions.clear()
    }

    companion object {
        fun create(classes: Iterable<Class<*>>) = ScriptProjectFunctionRegistry(
            classes
                .asSequence()
                .distinct()
                .flatMap { type -> type.declaredMethods.asSequence() }
                .filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        Modifier.isStatic(method.modifiers) &&
                        !method.isSynthetic &&
                        !method.isAnnotationPresent(EternalScriptEntry::class.java) &&
                        (method.canAccess(null) || method.trySetAccessible())
                }
                .mapNotNull { method ->
                    val function = method.kotlinFunction
                        ?: return@mapNotNull method.multifileSourceName()
                            ?.let { sourceName -> sourceName to method }
                    if (
                        function.visibility != KVisibility.PUBLIC ||
                        function.isSuspend ||
                        function.typeParameters.any { parameter -> parameter.isReified }
                    ) {
                        return@mapNotNull null
                    }
                    function.name to method
                }
                .groupByTo(
                    linkedMapOf(),
                    keySelector = { (name, _) -> name },
                    valueTransform = { (_, method) -> method }
                )
                .mapValuesTo(linkedMapOf()) { (_, methods) ->
                    methods.sortedBy(Method::toGenericString).toMutableList()
                }
        )

        fun empty() = ScriptProjectFunctionRegistry(linkedMapOf())
    }
}

private fun Method.multifileSourceName(): String? {
    val metadata = declaringClass.getAnnotation(Metadata::class.java) ?: return null
    if (metadata.kind != KOTLIN_MULTIFILE_FACADE_KIND) return null
    if ('$' in name) return null
    if (parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation") return null
    return name
}

private fun Method.accepts(args: Array<out Any?>): Boolean =
    parameterCount == args.size &&
        parameterTypes.indices.all { index ->
            parameterTypes[index].accepts(args[index])
        }

private fun Class<*>.accepts(value: Any?): Boolean {
    if (value == null) return !isPrimitive
    return boxed().isInstance(value)
}

private fun Class<*>.boxed(): Class<*> = when (this) {
    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
    java.lang.Byte.TYPE -> java.lang.Byte::class.java
    java.lang.Character.TYPE -> java.lang.Character::class.java
    java.lang.Short.TYPE -> java.lang.Short::class.java
    java.lang.Integer.TYPE -> java.lang.Integer::class.java
    java.lang.Long.TYPE -> java.lang.Long::class.java
    java.lang.Float.TYPE -> java.lang.Float::class.java
    java.lang.Double.TYPE -> java.lang.Double::class.java
    java.lang.Void.TYPE -> java.lang.Void::class.java
    else -> this
}

private const val KOTLIN_MULTIFILE_FACADE_KIND = 4
