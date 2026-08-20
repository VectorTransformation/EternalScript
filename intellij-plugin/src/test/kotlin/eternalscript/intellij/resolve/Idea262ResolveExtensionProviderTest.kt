package eternalscript.intellij.resolve

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class Idea262ResolveExtensionProviderTest : BasePlatformTestCase() {
    fun testModuleRelevanceUsesBaseScopeWithoutRecursingIntoRefinedScope() {
        val file = myFixture.addFileToProject("scripts/test.eternal.kts", "val value: Int = 1").virtualFile
        val refinedScopeCalls = AtomicInteger()
        val module = Proxy.newProxyInstance(
            KaModule::class.java.classLoader,
            arrayOf(KaModule::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBaseContentScope" -> GlobalSearchScope.fileScope(project, file)
                "getContentScope" -> {
                    refinedScopeCalls.incrementAndGet()
                    error("Refined content scope must not be queried by a resolve-extension provider")
                }
                else -> error("Unexpected KaModule method: ${method.name}")
            }
        } as KaModule

        assertTrue(moduleBaseContentScopeContains(module, file))
        assertEquals(0, refinedScopeCalls.get())
    }
}
