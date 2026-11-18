package com.selixe.directive

import java.util.UUID

/**
 * Whoever ran a command: a player or the console.
 *
 * Keeps commands free of platform types, so the same command runs on Paper, on Velocity
 * and in a unit test.
 */
interface Actor {

    val name: String

    /** Null for the console. */
    val uuid: UUID?

    val isConsole: Boolean

    /** The platform's own sender object, such as Bukkit's `CommandSender`. */
    val handle: Any?

    /** Sends a MiniMessage-formatted message. */
    fun send(message: String)

    fun hasPermission(permission: String): Boolean
}
