package eternalScript.core.script.project

import eternalScript.core.script.classpath.ScriptClassIdentityConflict
import eternalScript.core.script.classpath.ScriptClassIdentityConflictException
import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import eternalScript.core.script.classloading.isParentFirstScriptClass
import java.io.DataInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.jar.JarFile

internal data class ScriptClassReferenceAnalysis(
    val declaredClassNames: Set<String>,
    val referencedClassNames: Set<String>,
    val pluginOwnerNames: Set<String>
)

internal object ScriptClassReferenceAnalyzer {
    fun analyze(
        generationJar: Path,
        classpathSnapshot: ScriptPluginClasspathSnapshot
    ): ScriptClassReferenceAnalysis {
        val contents = readJarClassReferences(generationJar)
        val pluginOwners = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        val conflicts = mutableListOf<ScriptClassIdentityConflict>()
        contents.referencedClassNames
            .asSequence()
            .filterNot(contents.declaredClassNames::contains)
            .forEach { className ->
                try {
                    if (isParentFirstScriptClass(className)) {
                        classpathSnapshot.parentClassLoader
                            .resolvedClass(className)
                            ?.let { parentType ->
                                val owners = classpathSnapshot.ownerNamesForResolvedClass(
                                    className,
                                    parentType
                                )
                                if (owners.isNotEmpty()) {
                                    classpathSnapshot.resolvePluginClass(className)
                                    pluginOwners.addAll(owners)
                                }
                                return@forEach
                            }
                    }
                    classpathSnapshot.resolvePluginClass(className)
                        ?.ownerNames
                        ?.let(pluginOwners::addAll)
                } catch (exception: ScriptClassIdentityConflictException) {
                    conflicts += exception.conflicts
                }
            }
        if (conflicts.isNotEmpty()) {
            throw ScriptClassIdentityConflictException(conflicts.distinct())
        }
        return ScriptClassReferenceAnalysis(
            declaredClassNames = contents.declaredClassNames,
            referencedClassNames = contents.referencedClassNames,
            pluginOwnerNames = pluginOwners
        )
    }
}

private fun ClassLoader.resolvedClass(className: String): Class<*>? =
    try {
        loadClass(className)
    } catch (_: ClassNotFoundException) {
        null
    }

internal data class JarClassReferences(
    val declaredClassNames: Set<String>,
    val referencedClassNames: Set<String>
)

internal fun readJarClassReferences(generationJar: Path): JarClassReferences {
    val declared = sortedSetOf<String>()
    val referenced = sortedSetOf<String>()
    JarFile(generationJar.toFile()).use { jar ->
        jar.entries().asSequence()
            .filter { entry -> !entry.isDirectory && entry.name.endsWith(".class") }
            .sortedBy { entry -> entry.name }
            .forEach { entry ->
                declared += entry.name
                    .removeSuffix(".class")
                    .replace('/', '.')
                jar.getInputStream(entry).use { input ->
                    referenced += readClassReferences(input)
                }
            }
    }
    return JarClassReferences(declared, referenced)
}

private fun readClassReferences(input: InputStream): Set<String> {
    val data = DataInputStream(input.buffered())
    check(data.readInt() == CLASS_FILE_MAGIC) {
        "Generated output contains an invalid JVM class file."
    }
    data.readUnsignedShort()
    data.readUnsignedShort()
    val constantPoolSize = data.readUnsignedShort()
    val utf8 = arrayOfNulls<String>(constantPoolSize)
    val classNameIndices = mutableListOf<Int>()
    val literalStringIndices = mutableSetOf<Int>()
    var index = 1
    while (index < constantPoolSize) {
        when (val tag = data.readUnsignedByte()) {
            CONSTANT_UTF8 -> utf8[index] = data.readUTF()
            CONSTANT_INTEGER, CONSTANT_FLOAT -> data.readInt()
            CONSTANT_LONG, CONSTANT_DOUBLE -> {
                data.readLong()
                index++
            }
            CONSTANT_CLASS -> classNameIndices += data.readUnsignedShort()
            CONSTANT_STRING -> literalStringIndices += data.readUnsignedShort()
            CONSTANT_FIELD_REF,
            CONSTANT_METHOD_REF,
            CONSTANT_INTERFACE_METHOD_REF,
            CONSTANT_NAME_AND_TYPE,
            CONSTANT_DYNAMIC,
            CONSTANT_INVOKE_DYNAMIC -> {
                data.readUnsignedShort()
                data.readUnsignedShort()
            }
            CONSTANT_METHOD_HANDLE -> {
                data.readUnsignedByte()
                data.readUnsignedShort()
            }
            CONSTANT_METHOD_TYPE,
            CONSTANT_MODULE,
            CONSTANT_PACKAGE -> data.readUnsignedShort()
            else -> error("Unsupported JVM constant-pool tag $tag")
        }
        index++
    }

    val references = sortedSetOf<String>()
    classNameIndices
        .mapNotNull(utf8::getOrNull)
        .mapNotNullTo(references, ::classConstantName)
    utf8.forEachIndexed { utfIndex, value ->
        if (value == null || utfIndex in literalStringIndices) return@forEachIndexed
        DESCRIPTOR_CLASS.findAll(value).forEach { match ->
            match.groupValues[1]
                .replace('/', '.')
                .takeIf(::isBinaryClassName)
                ?.let(references::add)
        }
    }
    return references
}

private fun classConstantName(value: String): String? {
    val internalName = when {
        value.startsWith("[L") && value.endsWith(";") ->
            value.substring(2, value.length - 1)
        value.startsWith("[") -> return null
        else -> value
    }
    return internalName
        .replace('/', '.')
        .takeIf(::isBinaryClassName)
}

private fun isBinaryClassName(value: String): Boolean =
    value.isNotBlank() &&
        value.none { character ->
            character == '/' ||
                character == ';' ||
                character == '[' ||
                character == '(' ||
                character == ')'
        }

private val DESCRIPTOR_CLASS = Regex("""L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+);""")

private const val CLASS_FILE_MAGIC = 0xCAFEBABE.toInt()
private const val CONSTANT_UTF8 = 1
private const val CONSTANT_INTEGER = 3
private const val CONSTANT_FLOAT = 4
private const val CONSTANT_LONG = 5
private const val CONSTANT_DOUBLE = 6
private const val CONSTANT_CLASS = 7
private const val CONSTANT_STRING = 8
private const val CONSTANT_FIELD_REF = 9
private const val CONSTANT_METHOD_REF = 10
private const val CONSTANT_INTERFACE_METHOD_REF = 11
private const val CONSTANT_NAME_AND_TYPE = 12
private const val CONSTANT_METHOD_HANDLE = 15
private const val CONSTANT_METHOD_TYPE = 16
private const val CONSTANT_DYNAMIC = 17
private const val CONSTANT_INVOKE_DYNAMIC = 18
private const val CONSTANT_MODULE = 19
private const val CONSTANT_PACKAGE = 20
