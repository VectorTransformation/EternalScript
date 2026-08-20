package eternalscript.scripting.runtime

import eternalscript.api.script.command.ScriptCommandBuilder
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class ScriptCommandRegistryTest {
    @Test
    fun `command definition rejects a missing executor`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScriptCommandBuilder("missing-executor").definition()
        }

        assertEquals("Script command 'missing-executor' must define an executor", error.message)
    }

    @Test
    fun `permission denial is handled visibly without invoking command callbacks`() {
        val executed = AtomicBoolean()
        val completed = AtomicBoolean()
        val definition = ScriptCommandBuilder("protected").apply {
            permission("eternalscript.test.protected")
            tabCompleter { _, _, _ ->
                completed.set(true)
                listOf("secret")
            }
            executor { _, _, _ -> executed.set(true) }
        }.definition()
        val context = ScriptExecutionContext(ReplStateBridge.StateTable()) {}
        val permissionDenied: Component = Component.text("Permission denied")
        val command = ScriptCommand(definition, ScriptExecutionHandle(context)).apply {
            permissionMessage(permissionDenied)
            activate()
        }
        val messages = mutableListOf<Component>()
        try {
            val denied = sender(hasPermission = false, messages)

            assertTrue(command.execute(denied, "protected", emptyArray()))
            assertEquals(permissionDenied, messages.single())
            assertFalse(executed.get())
            assertEquals(emptyList(), command.tabComplete(denied, "protected", emptyArray()))
            assertFalse(completed.get())

            val allowed = sender(hasPermission = true, mutableListOf())
            assertTrue(command.execute(allowed, "protected", emptyArray()))
            assertTrue(executed.get())
        } finally {
            context.retire()
        }
    }

    private fun sender(hasPermission: Boolean, messages: MutableList<Component>): CommandSender =
        Proxy.newProxyInstance(
            CommandSender::class.java.classLoader,
            arrayOf(CommandSender::class.java)
        ) { proxy, method, arguments ->
            when {
                method.name == "hasPermission" -> hasPermission
                method.name == "sendMessage" && arguments?.singleOrNull() is Component -> {
                    messages += arguments.single() as Component
                    null
                }
                method.name == "getName" -> "test-sender"
                method.name == "toString" -> "test-sender"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === arguments?.singleOrNull()
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                method.returnType == Float::class.javaPrimitiveType -> 0F
                method.returnType == Double::class.javaPrimitiveType -> 0.0
                method.returnType == Short::class.javaPrimitiveType -> 0.toShort()
                method.returnType == Byte::class.javaPrimitiveType -> 0.toByte()
                method.returnType == Char::class.javaPrimitiveType -> 0.toChar()
                else -> null
            }
        } as CommandSender
}
