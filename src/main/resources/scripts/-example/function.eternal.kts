/**
 * Declarations are shared with every enabled script; imports remain local to this file.
 * This earlier provider deliberately references a value declared in the later consumer.
 */

import java.time.Instant

var sharedExampleCount = 0

data class SharedExample(
    val message: String,
    val count: Int,
    val createdAt: Instant
)

fun nextSharedExample(message: String): SharedExample =
    SharedExample("$sharedExamplePrefix$message", ++sharedExampleCount, Instant.now())
