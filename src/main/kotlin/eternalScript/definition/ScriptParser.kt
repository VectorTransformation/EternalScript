package eternalScript.definition

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.jvmName

class ScriptParser(kClass: KClass<*>) {
    val nestedCache = mutableMapOf<String, ScriptParser>()
    val propertyCache = mutableMapOf<String, KProperty<*>>()
    val functionCache = mutableMapOf<String, KFunction<*>>()

    init {
        kClass.nestedClasses.forEach {
            val name = it.jvmName
            nestedCache[name] = ScriptParser(it)
        }
        kClass.declaredMembers.forEach {
            val name = it.name
            when (it) {
                is KProperty -> propertyCache[name] = it
                is KFunction -> functionCache[name] = it
            }
        }
    }

    fun call(script: Script, function: String, vararg args: Any?): Any? {
        val kFunction = functionCache[function] ?: return null

        if (kFunction.parameters.size == 1) {
            return kFunction.call(script)
        } else if (args.isNotEmpty()) {
            return kFunction.call(script, *args)
        }

        return null
    }
}