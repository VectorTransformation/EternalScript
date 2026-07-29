@file:JvmName("EternalScriptMultifileProbe")
@file:JvmMultifileClass

package eternalScript.core.script.project

import eternalScript.api.script.EternalScriptEntry
import eternalScript.core.script.Script

fun multifilePing(): Int = 42

@EternalScriptEntry
fun Script.multifileEntryProbe() = Unit
