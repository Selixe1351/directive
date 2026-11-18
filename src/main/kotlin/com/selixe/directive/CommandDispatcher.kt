package com.selixe.directive

import java.lang.System.Logger.Level
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/**
 * Matches input to commands, checks permissions, and runs them.
 *
 * Holds no platform types, so commands can be tested without a server. Platform
 * adapters such as `PaperCommands` create one and forward input to [dispatch] and [complete].
 */
class CommandDispatcher(
    var messages: Messages = Messages(),
    /** Called for each registered command so the platform can hook it in. The result undoes it. */
    private val onRegister: (Command) -> AutoCloseable = { AutoCloseable {} },
    /** Online player names, for `<player>` completion. */
    private val players: () -> List<String> = { emptyList() },
    /** Where commands marked `async` run. */
    private val asyncExecutor: Executor = Executors.newVirtualThreadPerTaskExecutor(),
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = System.getLogger("Directive")
    private val commands = ConcurrentHashMap<String, Command>()
    private val cooldowns = ConcurrentHashMap<Command, ConcurrentHashMap<UUID, Long>>()

    /**
     * Registers [command] under its name and aliases.
     *
     * Close the result to unregister it. A name that is already taken keeps its current command.
     */
    fun register(command: Command): AutoCloseable {
        val names = listOf(command.name) + command.aliases
        names.forEach { name ->
            val existing = commands.putIfAbsent(name.lowercase(), command)
            if (existing != null && existing !== command) {
                logger.log(Level.WARNING, "Command '/$name' is already registered, ignoring the duplicate")
            }
        }

        val handle = onRegister(command)

        return AutoCloseable {
            names.forEach { commands.remove(it.lowercase(), command) }
            cooldowns.remove(command)
            handle.close()
        }
    }

    fun registered(): List<Command> = commands.values.distinct()

    fun find(label: String): Command? = commands[label.lowercase()]

    /**
     * Runs the command for [label] with [args].
     *
     * Subcommands match from the left, so `/guild member add Steve` reaches `add` with `[Steve]`.
     * Permissions are checked at every level.
     *
     * @return false when no command has this label
     */
    fun dispatch(actor: Actor, label: String, args: List<String>): Boolean {
        val root = find(label) ?: return false

        var command = root
        var remaining = args
        val path = mutableListOf(label)
        while (true) {
            if (!allowed(actor, command)) {
                actor.send(messages.noPermission)
                return true
            }
            val next = remaining.firstOrNull()?.let { child(command, it) } ?: break
            command = next
            path += next.name
            remaining = remaining.drop(1)
        }

        val typed = path.joinToString(" ")
        val askedForHelp = remaining.firstOrNull()?.lowercase() in setOf("help", "?")
        if (command.subcommands.isNotEmpty() && (askedForHelp || !command.hasExecutor)) {
            help(actor, command, typed)
            return true
        }

        if (command.playerOnly && actor.isConsole) {
            actor.send(messages.playerOnly)
            return true
        }
        if (command.consoleOnly && !actor.isConsole) {
            actor.send(messages.consoleOnly)
            return true
        }

        val uuid = actor.uuid
        val cooldown = command.cooldown
        if (uuid != null && cooldown != null && command.cooldownBypassPermission?.let(actor::hasPermission) != true) {
            val readyAt = cooldowns[command]?.get(uuid) ?: 0
            val left = readyAt - clock.millis()
            if (left > 0) {
                actor.send(format(messages.cooldown, "time" to formatTime(left)))
                return true
            }
        }

        val context = CommandContext(actor, command, label, remaining)
        if (command.async) {
            asyncExecutor.execute { execute(context, typed, args) }
        } else {
            execute(context, typed, args)
        }
        return true
    }

    /**
     * Tab completions for the last argument in [args].
     *
     * Subcommand names first, then what the matched command offers for that position.
     */
    fun complete(actor: Actor, label: String, args: List<String>): List<String> {
        var command = find(label) ?: return emptyList()
        if (!allowed(actor, command)) return emptyList()

        var remaining = args
        while (remaining.size > 1) {
            val next = child(command, remaining.first())?.takeIf { allowed(actor, it) } ?: break
            command = next
            remaining = remaining.drop(1)
        }

        val prefix = remaining.lastOrNull().orEmpty()
        val index = (remaining.size - 1).coerceAtLeast(0)

        val subcommands = if (index == 0) {
            command.subcommands.filter { allowed(actor, it) }.map { it.name }
        } else {
            emptyList()
        }

        val arguments = try {
            command.completionsFor(CompletionContext(actor, command, label, remaining, index, prefix, players))
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Tab completion for '/$label' failed", e)
            emptyList()
        }

        return (subcommands + arguments)
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sorted()
    }

    private fun execute(context: CommandContext, typed: String, args: List<String>) {
        val command = context.command
        try {
            command.run(context)
            val uuid = context.actor.uuid
            val cooldown = command.cooldown
            if (uuid != null && cooldown != null) {
                cooldowns.computeIfAbsent(command) { ConcurrentHashMap() }[uuid] = clock.millis() + cooldown.inWholeMilliseconds
            }
        } catch (e: CommandFailure) {
            context.actor.send(format(messages.failure, "message" to (e.message ?: "")))
            if (command.usage.isNotBlank()) {
                context.actor.send(format(messages.usage, "usage" to "$typed ${command.usage}"))
            }
        } catch (e: Exception) {
            context.actor.send(messages.error)
            logger.log(Level.ERROR, "Command '/${context.label} ${args.joinToString(" ")}' failed", e)
        }
    }

    private fun help(actor: Actor, command: Command, label: String) {
        actor.send(format(messages.helpHeader, "label" to label, "description" to command.description))

        val visible = command.subcommands.filter { allowed(actor, it) }
        if (visible.isEmpty()) {
            actor.send(messages.helpEmpty)
            return
        }

        visible.forEach { sub ->
            actor.send(
                format(
                    messages.helpLine,
                    "label" to label,
                    "name" to sub.name,
                    "usage" to sub.usage,
                    "description" to sub.description
                )
            )
        }
    }

    private fun child(command: Command, candidate: String): Command? {
        return command.subcommands.firstOrNull {
            it.name.equals(candidate, ignoreCase = true) || it.aliases.any { alias -> alias.equals(candidate, ignoreCase = true) }
        }
    }

    private fun allowed(actor: Actor, command: Command): Boolean {
        val permission = command.permission ?: return true
        return actor.isConsole || actor.hasPermission(permission)
    }

    private fun format(template: String, vararg values: Pair<String, String>): String {
        return values.fold(template) { text, (key, value) -> text.replace("{$key}", value) }
    }

    private fun formatTime(millis: Long): String {
        return millis.milliseconds.toComponents { days, hours, minutes, seconds, _ ->
            val parts = mutableListOf<String>()
            if (days > 0) parts += "${days}d"
            if (hours > 0) parts += "${hours}h"
            if (minutes > 0) parts += "${minutes}m"
            if (days == 0L && hours == 0 && (seconds > 0 || minutes == 0)) parts += "${seconds.coerceAtLeast(1)}s"
            parts.joinToString(" ")
        }
    }

}
