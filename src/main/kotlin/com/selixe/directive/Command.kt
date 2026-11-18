package com.selixe.directive

import kotlin.time.Duration

/**
 * A command and its subcommands, described without any platform types.
 *
 * Build one with [command]:
 *
 * ```
 * command("warp", description = "Teleport to a warp") {
 *     sub("set", usage = "<name>", permission = "warp.set") {
 *         executes { context -> warps.set(context.string(0, "name")) }
 *     }
 *     executes { context -> context.reply("Pick a warp") }
 * }
 * ```
 */
class Command(
    val name: String,
    val aliases: List<String> = emptyList(),
    val permission: String? = null,
    val description: String = "",
    /** Arguments after the command name, such as `<player> [duration] <reason>`. Also drives tab completion. */
    val usage: String = "",
    val playerOnly: Boolean = false,
    val consoleOnly: Boolean = false,
    /** Minimum time between two runs by the same player. */
    val cooldown: Duration? = null,
    /** Runs the command on a background thread instead of the calling one. */
    val async: Boolean = false,
    /** Takes the label even when another plugin already registered it. */
    val override: Boolean = false,
) {

    private val children = mutableListOf<Command>()
    private var executor: ((CommandContext) -> Unit)? = null
    private var completer: ((CompletionContext) -> List<String>)? = null

    val subcommands: List<Command> get() = children

    /** False for a command that only groups subcommands. Running it shows its help page. */
    val hasExecutor: Boolean get() = executor != null

    /** The permission for skipping [cooldown], `<permission>.bypasscooldown`, or null without a permission. */
    val cooldownBypassPermission: String? get() = permission?.let { "$it.bypasscooldown" }

    fun executes(block: (CommandContext) -> Unit): Command = apply { executor = block }

    /**
     * Supplies completions for the argument being typed.
     *
     * Only needed for values that must be looked up, such as warp names. Player names,
     * `a|b` choices and booleans already complete from [usage].
     */
    fun completes(block: (CompletionContext) -> List<String>): Command = apply { completer = block }

    fun sub(command: Command): Command = apply { children += command }

    fun sub(
        name: String,
        aliases: List<String> = emptyList(),
        permission: String? = null,
        description: String = "",
        usage: String = "",
        playerOnly: Boolean = false,
        consoleOnly: Boolean = false,
        cooldown: Duration? = null,
        async: Boolean = false,
        block: Command.() -> Unit,
    ): Command = sub(
        Command(name, aliases, permission, description, usage, playerOnly, consoleOnly, cooldown, async).apply(block)
    )

    fun run(context: CommandContext) {
        val body = executor ?: throw CommandFailure("There is nothing to run here.")
        body(context)
    }

    fun completionsFor(context: CompletionContext): List<String> {
        return completer?.invoke(context) ?: usageCompletions(context)
    }

    private fun usageCompletions(context: CompletionContext): List<String> {
        val token = usage.split(' ').filter { it.isNotBlank() }.getOrNull(context.index)
            ?.trim('<', '>', '[', ']') ?: return emptyList()

        if ('|' in token) return token.split('|').map { it.trim() }.filter { it.isNotEmpty() }

        return when (token.lowercase()) {
            "player", "target" -> context.players()
            "boolean" -> listOf("true", "false")
            "duration" -> listOf("30m", "1h", "1d", "7d", "30d")
            else -> emptyList()
        }
    }

}

/** Builds a [Command]. See [Command] for an example. */
fun command(
    name: String,
    aliases: List<String> = emptyList(),
    permission: String? = null,
    description: String = "",
    usage: String = "",
    playerOnly: Boolean = false,
    consoleOnly: Boolean = false,
    cooldown: Duration? = null,
    async: Boolean = false,
    override: Boolean = false,
    block: Command.() -> Unit = {},
): Command {
    return Command(name, aliases, permission, description, usage, playerOnly, consoleOnly, cooldown, async, override)
        .apply(block)
}

/** One run of a command. */
class CommandContext(
    val actor: Actor,
    val command: Command,
    /** The name or alias that was typed. */
    val label: String,
    /** Arguments after the matched subcommand. */
    val args: List<String>,
) {

    fun reply(message: String) = actor.send(message)

    fun arg(index: Int): String? = args.getOrNull(index)

    /** Arguments from [from] onwards, joined with spaces. */
    fun rest(from: Int): String = args.drop(from).joinToString(" ")
}

/** One tab completion request for the argument at [index]. */
class CompletionContext(
    val actor: Actor,
    val command: Command,
    val label: String,
    /** Arguments of the matched command, including the one being typed. */
    val args: List<String>,
    val index: Int,
    /** What has been typed so far. The dispatcher filters by it, so completers can return everything. */
    val prefix: String,
    private val onlinePlayers: () -> List<String> = { emptyList() },
) {

    fun arg(index: Int): String? = args.getOrNull(index)

    /** Names of online players, from the platform. */
    fun players(): List<String> = onlinePlayers()
}

/**
 * Throw from a command to tell the sender what went wrong.
 *
 * The message is shown as is, followed by the usage line when the command has one.
 */
class CommandFailure(message: String) : Exception(message)
