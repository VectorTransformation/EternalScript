package eternalScript.core.feedback

import eternalScript.core.feedback.UserFeedbackEvent.CacheClearFinished
import eternalScript.core.feedback.UserFeedbackEvent.ProjectCheckFinished
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.script.generation.GenerationDiagnosticPhase
import eternalScript.core.script.generation.ScriptProjectCheckOutcome
import eternalScript.core.script.generation.ScriptProjectCheckResult
import eternalScript.core.script.generation.ScriptProjectDiagnosticSummary
import eternalScript.core.script.generation.ScriptProjectReport
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizedUserFeedbackDeliveryTest {
    @Test
    fun `every command sender receives direct replies without routine log duplication`() {
        val replies = mutableListOf<Pair<CommandSender, String>>()
        val logs = mutableListOf<UserFeedbackMessage>()
        val delivery = delivery(replies, logs)
        val senders = listOf(
            proxy(CommandSender::class.java),
            proxy(ConsoleCommandSender::class.java),
            proxy(RemoteConsoleCommandSender::class.java),
            proxy(BlockCommandSender::class.java),
            proxy(Player::class.java)
        )

        senders.forEach { sender ->
            delivery.deliver(
                UserFeedbackTarget.Reply(sender),
                CacheClearFinished,
                "en_US"
            )
        }

        senders.forEach { sender ->
            assertEquals(2, replies.count { (target, _) -> target === sender })
        }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `player detail overflow is concise while complete details go only to the log`() {
        val replies = mutableListOf<Pair<CommandSender, String>>()
        val logs = mutableListOf<UserFeedbackMessage>()
        val delivery = delivery(replies, logs)
        val player = proxy(Player::class.java)
        val diagnostics = (1..7).map { index ->
            ScriptProjectDiagnosticSummary(
                phase = GenerationDiagnosticPhase.COMPILATION,
                sourceName = "source-$index.kt",
                line = index,
                column = 1,
                message = "failure-$index"
            )
        }

        delivery.deliver(
            UserFeedbackTarget.Reply(player),
            ProjectCheckFinished(
                sourceCount = 7,
                result = ScriptProjectCheckResult(
                    outcome = ScriptProjectCheckOutcome.FAILED,
                    report = ScriptProjectReport(diagnostics = diagnostics)
                )
            ),
            "en_US"
        )

        assertEquals(8, replies.size)
        assertEquals(7, logs.size)
        assertTrue(logs.all { it.stage == UserFeedbackStage.DETAIL })
        assertEquals(1, replies.count { (_, text) -> text.startsWith("truncated") })
    }

    @Test
    fun `server log and silent targets are explicit`() {
        val replies = mutableListOf<Pair<CommandSender, String>>()
        val logs = mutableListOf<UserFeedbackMessage>()
        val delivery = delivery(replies, logs)

        delivery.deliver(
            UserFeedbackTarget.ServerLog,
            UserFeedbackEvent.OperationFailed(
                kind = ScriptOperationKind.RELOAD,
                incidentId = "abcd1234"
            ),
            "en_US"
        )
        delivery.deliver(
            UserFeedbackTarget.Silent,
            CacheClearFinished,
            "en_US"
        )

        assertTrue(replies.isEmpty())
        assertEquals(2, logs.size)
        assertTrue(logs.first().internalFailure)
        assertEquals(UserFeedbackStage.NEXT_ACTION, logs.last().stage)
    }

    private fun delivery(
        replies: MutableList<Pair<CommandSender, String>>,
        logs: MutableList<UserFeedbackMessage>
    ) = LocalizedUserFeedbackDelivery(
        renderer = UserFeedbackTextRenderer { key, _ ->
            when (key) {
                "script.diagnostic.error" -> "%s %s %s %s %s"
                "script.diagnostic.phase.compilation" -> "compilation"
                "script.check.failed" -> "failed %s %s"
                "feedback.details.truncated" -> "truncated %s %s"
                "script.operation.failed" -> "failed %s %s"
                else -> key
            }
        },
        replySink = { sender, text -> replies += sender to text },
        logSink = { message, _ -> logs += message }
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type)
    ) { instance, method, arguments ->
        when (method.name) {
            "toString" -> type.simpleName
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === arguments?.firstOrNull()
            else -> primitiveDefault(method.returnType)
        }
    } as T

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
