package eternalscript.intellij.model

import kotlin.test.Test
import kotlin.test.assertEquals

internal class EternalScriptImportPlannerTest {
    @Test
    fun `extracts the name introduced by a file-local import`() {
        assertEquals("Date", EternalScriptImportPlanner.importedName("java.util.Date"))
        assertEquals("Id", EternalScriptImportPlanner.importedName("java.util.UUID as Id"))
        assertEquals(null, EternalScriptImportPlanner.importedName("java.time.*"))
    }
}
