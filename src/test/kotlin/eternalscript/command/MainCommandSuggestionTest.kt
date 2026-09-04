package eternalscript.command

import com.mojang.brigadier.suggestion.SuggestionsBuilder
import org.bukkit.command.CommandSender
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainCommandSuggestionTest {
    @Test
    fun `matches an unquoted partial path before escaping the suggestion`() {
        val input = "/es enable h"
        val builder = SuggestionsBuilder(input, "/es enable ".length)

        suggestPaths(
            listOf("hello.eternal.kts", "world.eternal.kts"),
            builder
        )

        val suggestions = builder.build().list
        assertEquals(listOf("hello.eternal.kts"), suggestions.map { suggestion -> suggestion.text })
        assertEquals("/es enable hello.eternal.kts", suggestions.single().apply(input))
    }

    @Test
    fun `matches a quoted partial path and returns valid quoted syntax`() {
        val input = "/es enable \"combat/"
        val builder = SuggestionsBuilder(input, "/es enable ".length)

        suggestPaths(
            listOf("combat/a.eternal.kts", "other.eternal.kts"),
            builder
        )

        val suggestions = builder.build().list
        assertEquals(listOf("\"combat/a.eternal.kts\""), suggestions.map { suggestion -> suggestion.text })
        assertEquals("/es enable \"combat/a.eternal.kts\"", suggestions.single().apply(input))
    }

    @Test
    fun `matches paths case insensitively`() {
        val input = "/es disable HEL"
        val builder = SuggestionsBuilder(input, "/es disable ".length)

        suggestPaths(listOf("hello.eternal.kts"), builder)

        assertEquals(
            listOf("hello.eternal.kts"),
            builder.build().list.map { suggestion -> suggestion.text }
        )
    }

    @Test
    fun `active script list is sorted deduplicated and paged`() {
        val page = scriptListPage(
            listOf("z.eternal.kts", "A.eternal.kts", "a.eternal.kts", "b.eternal.kts"),
            requestedPage = 2,
            pageSize = 2
        )

        assertEquals(
            ScriptListPage(
                paths = listOf("b.eternal.kts", "z.eternal.kts"),
                page = 2,
                pages = 2,
                total = 4
            ),
            page
        )
    }

    @Test
    fun `active script list rejects pages outside the calculated range`() {
        assertNull(scriptListPage(listOf("one", "two"), requestedPage = 2, pageSize = 10))
        assertNull(scriptListPage(emptyList(), requestedPage = 2, pageSize = 10))
    }

    @Test
    fun `active script list rejects an invalid page size`() {
        assertFailsWith<IllegalArgumentException> {
            scriptListPage(listOf("one"), requestedPage = 1, pageSize = 0)
        }
    }

    @Test
    fun `management permissions allow operators admin and the exact action`() {
        assertTrue(canManage(sender(isOp = true), "eternalscript.command.reload"))
        assertTrue(
            canManage(
                sender(permissions = setOf("eternalscript.admin")),
                "eternalscript.command.reload"
            )
        )
        assertTrue(
            canManage(
                sender(permissions = setOf("eternalscript.command.reload")),
                "eternalscript.command.reload"
            )
        )
        assertFalse(
            canManage(
                sender(permissions = setOf("eternalscript.command.list")),
                "eternalscript.command.reload"
            )
        )
    }

    @Test
    fun `only an operation that is still pending receives an acknowledgement`() {
        val pending = CompletableFuture<String>()

        assertTrue(shouldAcknowledge(pending))
        assertFalse(shouldAcknowledge(CompletableFuture.completedFuture("busy")))

        pending.complete("done")
        assertFalse(shouldAcknowledge(pending))
    }

    private fun sender(
        isOp: Boolean = false,
        permissions: Set<String> = emptySet()
    ): CommandSender = Proxy.newProxyInstance(
        CommandSender::class.java.classLoader,
        arrayOf(CommandSender::class.java)
    ) { proxy, method, arguments ->
        when {
            method.name == "isOp" -> isOp
            method.name == "hasPermission" -> (arguments?.singleOrNull() as? String) in permissions
            method.name == "getName" -> "test-sender"
            method.name == "toString" -> "test-sender"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === arguments?.singleOrNull()
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    } as CommandSender
}
