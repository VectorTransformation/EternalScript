package eternalscript.messaging

import eternalscript.logging.EternalLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageLogLevelTest {
    @Test
    fun `message levels map to operational log levels`() {
        assertEquals(EternalLogLevel.INFO, MessageLevel.INFO.toLogLevel())
        assertEquals(EternalLogLevel.INFO, MessageLevel.SUCCESS.toLogLevel())
        assertEquals(EternalLogLevel.WARN, MessageLevel.WARNING.toLogLevel())
        assertEquals(EternalLogLevel.ERROR, MessageLevel.ERROR.toLogLevel())
    }
}
