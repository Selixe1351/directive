package com.selixe.directive.velocity

import com.selixe.directive.Actor
import com.selixe.directive.Command
import com.selixe.directive.CommandContext
import com.selixe.directive.CommandDispatcher
import com.selixe.directive.CommandFailure
import com.selixe.directive.Messages
import com.selixe.directive.uuidOrNull
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.UUID

/**
 * Registers Directive commands on a Velocity proxy.
 *
 * ```
 * val commands = VelocityCommands(proxy, this)
 * commands.register(command("hello") { executes { it.reply("<green>Hi!") } })
 * ```
 */
class VelocityCommands(
    private val proxy: ProxyServer,
    private val plugin: Any,
    messages: Messages = Messages(),
) : AutoCloseable {

    val dispatcher = CommandDispatcher(
        messages = messages,
        onRegister = ::registerWithProxy,
        players = { proxy.allPlayers.map { it.username } },
    )

    private val handles = mutableListOf<AutoCloseable>()

    /** Registers [command]. Close the result to unregister it early. */
    fun register(command: Command): AutoCloseable {
        val handle = dispatcher.register(command)
        synchronized(handles) { handles += handle }
        return handle
    }

    fun register(vararg commands: Command) = commands.forEach(::register)

    /** Unregisters every command registered here. */
    override fun close() {
        synchronized(handles) {
            handles.forEach { runCatching { it.close() } }
            handles.clear()
        }
    }

    private fun registerWithProxy(command: Command): AutoCloseable {
        val meta = proxy.commandManager.metaBuilder(command.name)
            .aliases(*command.aliases.toTypedArray())
            .plugin(plugin)
            .build()

        val handler = object : SimpleCommand {
            override fun execute(invocation: SimpleCommand.Invocation) {
                dispatcher.dispatch(VelocityActor(invocation.source(), proxy), invocation.alias(), invocation.arguments().toList())
            }

            override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
                val args = invocation.arguments().toMutableList()
                if (args.isEmpty()) args += ""
                return dispatcher.complete(VelocityActor(invocation.source(), proxy), invocation.alias(), args)
            }

            override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
                val permission = command.permission ?: return true
                return invocation.source().hasPermission(permission)
            }
        }

        proxy.commandManager.register(meta, handler)
        return AutoCloseable { proxy.commandManager.unregister(meta) }
    }

}

/** A Velocity [CommandSource] as an [Actor]. Messages are MiniMessage. */
class VelocityActor(val source: CommandSource, val proxy: ProxyServer) : Actor {

    override val name: String get() = (source as? Player)?.username ?: "Console"
    override val uuid: UUID? get() = (source as? Player)?.uniqueId
    override val isConsole: Boolean get() = source is ConsoleCommandSource
    override val handle: Any get() = source

    override fun send(message: String) = source.sendMessage(MiniMessage.miniMessage().deserialize(message))

    override fun hasPermission(permission: String): Boolean = source.hasPermission(permission)

}

/** The Velocity source that ran the command. */
val CommandContext.source: CommandSource
    get() = actor.handle as CommandSource

/** The player that ran the command. Fails for the console. */
fun CommandContext.player(): Player =
    actor.handle as? Player ?: throw CommandFailure("Only players can use that.")

/** The online player named, or with the UUID, at [index]. */
fun CommandContext.player(index: Int, name: String = "player"): Player =
    playerOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not online, check the $name's name.")

fun CommandContext.playerOrNull(index: Int): Player? {
    val raw = arg(index) ?: return null
    val proxy = (actor as? VelocityActor)?.proxy ?: return null
    val byUuid = uuidOrNull(index)?.let { proxy.getPlayer(it).orElse(null) }
    return byUuid ?: proxy.getPlayer(raw).orElse(null)
}
