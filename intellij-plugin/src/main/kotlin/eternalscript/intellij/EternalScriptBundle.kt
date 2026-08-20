package eternalscript.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE_NAME: String = "messages.EternalScriptBundle"

internal object EternalScriptBundle : DynamicBundle(BUNDLE_NAME) {
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE_NAME) key: String,
        vararg parameters: Any
    ): String = getMessage(key, *parameters)
}
