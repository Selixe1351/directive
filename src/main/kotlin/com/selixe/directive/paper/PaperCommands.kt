package com.selixe.directive.paper

import com.selixe.directive.Actor
import com.selixe.directive.Command
import com.selixe.directive.CommandContext
import com.selixe.directive.CommandDispatcher
import com.selixe.directive.CommandFailure
import com.selixe.directive.Messages
import com.selixe.directive.uuidOrNull
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Registers Directive commands on a Paper server.
 *
 * ```
 * val commands = PaperCommands(this)
 * commands.register(command("hello") { executes { it.reply("<green>Hi!") } })
 * ```
 *
 * Commands are added to the server at runtime, so do not declare them in `plugin.yml`.
 * Call [close] in `onDisable`.
 */
class PaperCommands(
    private val plugin: Plugin,
    messages: Messages = Messages(),
) : AutoCloseable {

    val dispatcher = CommandDispatcher(
        messages = messages,
        onRegister = ::registerWithServer,
        players = { Bukkit.getOnlinePlayers().map { it.name } },
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

    private fun registerWithServer(command: Command): AutoCloseable {
        val bukkitCommand = object : BukkitCommand(
            command.name,
            command.description,
            "/${command.name} ${command.usage}".trim(),
            command.aliases,
        ) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                return dispatcher.dispatch(PaperActor(sender), label, args.toList())
            }

            override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
                return dispatcher.complete(PaperActor(sender), alias, args.toList())
            }
        }
        command.permission?.let { bukkitCommand.permission = it }

        val prefix = plugin.name.lowercase()
        val map = Bukkit.getCommandMap()
        map.register(prefix, bukkitCommand)

        val labels = (listOf(command.name) + command.aliases).map { it.lowercase() }
        val displaced = mutableMapOf<String, org.bukkit.command.Command>()
        if (command.override) {
            val known = map.knownCommands
            labels.forEach { label ->
                known[label]?.takeIf { it !== bukkitCommand }?.let { displaced[label] = it }
                known[label] = bukkitCommand
            }
        }

        refreshClients()

        return AutoCloseable {
            bukkitCommand.unregister(map)
            val known = map.knownCommands
            labels.forEach { label ->
                if (known[label] === bukkitCommand) known.remove(label)
                known.remove("$prefix:$label")
            }
            displaced.forEach { (label, previous) -> known[label] = previous }
            refreshClients()
        }
    }

    private fun refreshClients() {
        runCatching { Bukkit.getOnlinePlayers().forEach { it.updateCommands() } }
    }

}

/** A Bukkit [CommandSender] as an [Actor]. Messages are MiniMessage. */
class PaperActor(val sender: CommandSender) : Actor {

    override val name: String get() = sender.name
    override val uuid: UUID? get() = (sender as? Player)?.uniqueId
    override val isConsole: Boolean get() = sender is ConsoleCommandSender
    override val handle: Any get() = sender

    override fun send(message: String) = sender.sendMessage(MiniMessage.miniMessage().deserialize(message))

    override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

}

/** The Bukkit sender that ran the command. */
val CommandContext.sender: CommandSender
    get() = actor.handle as CommandSender

/** The player that ran the command. Fails for the console. */
fun CommandContext.player(): Player =
    actor.handle as? Player ?: throw CommandFailure("Only players can use that.")

/** The online player named, or with the UUID, at [index]. */
fun CommandContext.player(index: Int, name: String = "player"): Player =
    playerOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not online, check the $name's name.")

fun CommandContext.playerOrNull(index: Int): Player? {
    val raw = arg(index) ?: return null
    return uuidOrNull(index)?.let(Bukkit::getPlayer) ?: Bukkit.getPlayerExact(raw)
}

/**
 * A player who has joined before, online or not, by name or UUID.
 *
 * Only uses the server's local data, so it never blocks on a web lookup.
 */
fun CommandContext.offlinePlayer(index: Int, name: String = "player"): OfflinePlayer {
    val raw = arg(index) ?: throw CommandFailure("Missing $name.")
    val player = uuidOrNull(index)?.let(Bukkit::getOfflinePlayer)?.takeIf { it.hasPlayedBefore() || it.isOnline }
        ?: Bukkit.getPlayerExact(raw)
        ?: Bukkit.getOfflinePlayerIfCached(raw)
    return player ?: throw CommandFailure("'$raw' has never joined this server.")
}
