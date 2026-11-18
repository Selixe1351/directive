package com.selixe.directive

import java.util.UUID

class TestActor(
    private val permissions: Set<String> = emptySet(),
    override val isConsole: Boolean = false,
) : Actor {

    val messages = mutableListOf<String>()

    override val name = if (isConsole) "Console" else "Tester"
    override val uuid: UUID? = if (isConsole) null else UUID.randomUUID()
    override val handle: Any? = null

    override fun send(message: String) {
        messages += message
    }

    override fun hasPermission(permission: String) = permission in permissions

}
