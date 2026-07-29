package eternalScript.core.script.project

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptProjectFunctionRegistryTest {
    private fun registry() = ScriptProjectFunctionRegistry.create(
        listOf(Class.forName("${javaClass.packageName}.ScriptProjectFunctionRegistryTestKt"))
    )

    @Test
    fun `discovers zero argument exported functions`() {
        assertEquals(
            listOf(
                "kotlinSourceName",
                "nullable",
                "throwsUserException",
                "unitFunction",
                "zero"
            ),
            registry().zeroArgumentNames()
        )
    }

    @Test
    fun `invokes the compatible overload`() {
        val registry = registry()

        assertEquals(8, registry.call("overload", 4)?.value)
        assertEquals("AB", registry.call("overload", "ab")?.value)
        assertNull(registry.call("missing"))
    }

    @Test
    fun `preserves a null return as a successful invocation`() {
        val result = registry().call("nullable")

        assertEquals(ScriptProjectFunctionInvocation(null), result)
    }

    @Test
    fun `normalizes a void return to Kotlin Unit`() {
        assertEquals(
            ScriptProjectFunctionInvocation(Unit),
            registry().call("unitFunction")
        )
    }

    @Test
    fun `duplicate facade inputs do not duplicate overloads`() {
        val facade = Class.forName("${javaClass.packageName}.ScriptProjectFunctionRegistryTestKt")
        val registry = ScriptProjectFunctionRegistry.create(listOf(facade, facade))

        assertEquals(8, registry.call("overload", 4)?.value)
    }

    @Test
    fun `uses the Kotlin source name for JvmName functions`() {
        val registry = registry()

        assertEquals("renamed", registry.call("kotlinSourceName")?.value)
        assertNull(registry.call("renamedInBytecode"))
    }

    @Test
    fun `invokes a function through the public multifile facade`() {
        val facade = Class.forName(
            "${javaClass.packageName}.EternalScriptMultifileProbe"
        )
        assertTrue(Modifier.isPublic(facade.modifiers))

        val registry = ScriptProjectFunctionRegistry.create(listOf(facade))

        assertEquals(listOf("multifilePing"), registry.zeroArgumentNames())
        assertEquals(42, registry.call("multifilePing")?.value)
        assertNull(registry.call("multifileEntryProbe", null))
    }

    @Test
    fun `compiled entry validation detects a marker missed by source discovery`() {
        val facade = Class.forName("${javaClass.packageName}.ScriptProjectFunctionRegistryTestKt")

        validateCompiledEntryPoints(listOf(facade), expectedCount = 1)
        assertFailsWith<IllegalStateException> {
            validateCompiledEntryPoints(listOf(facade), expectedCount = 0)
        }
    }

    @Test
    fun `does not expose non-public suspend reified or property accessors`() {
        val registry = registry()

        assertNull(registry.call("entryProbe", null))
        assertNull(registry.call("internalFunction"))
        assertNull(registry.call("privateExport"))
        assertNull(registry.call("suspendFunction"))
        assertNull(registry.call("reifiedFunction"))
        assertNull(registry.call("getExportedProperty"))
    }

    @Test
    fun `unwraps user exceptions from reflection`() {
        assertFailsWith<ExportProbeException> {
            registry().call("throwsUserException")
        }
    }

    @Test
    fun `rejects ambiguous overloads deterministically`() {
        assertFailsWith<IllegalStateException> {
            registry().call("ambiguous", 4)
        }
    }

    @Test
    fun `clear releases all reflected methods`() {
        val registry = registry()

        registry.clear()

        assertEquals(emptyList(), registry.zeroArgumentNames())
        assertNull(registry.call("zero"))
    }
}

fun zero(): Int = 7

@EternalScriptEntry
fun Script.entryProbe() = Unit

fun nullable(): String? = null

fun unitFunction() = Unit

@JvmName("renamedInBytecode")
fun kotlinSourceName(): String = "renamed"

fun throwsUserException(): Nothing = throw ExportProbeException()

fun overload(value: Int): Int = value * 2

fun overload(value: String): String = value.uppercase()

fun ambiguous(value: Number): String = "number:$value"

fun ambiguous(value: Int): String = "int:$value"

private fun privateExport(): String = "hidden"

internal fun internalFunction(): String = "internal"

suspend fun suspendFunction(): String = "suspend"

inline fun <reified T> reifiedFunction(): String = T::class.java.name

val exportedProperty: String = "property"

private class ExportProbeException : RuntimeException()
