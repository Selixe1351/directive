package com.selixe.directive

/**
 * Everything the dispatcher says, as MiniMessage text.
 *
 * Placeholders in braces are replaced before sending.
 */
data class Messages(
    val noPermission: String = "<red>You do not have permission to use that.",
    val playerOnly: String = "<red>Only players can use that.",
    val consoleOnly: String = "<red>That can only be run from the console.",
    /** `{message}` is the [CommandFailure] text. */
    val failure: String = "<red>{message}",
    /** `{usage}` is the full command line, such as `warp set <name>`. */
    val usage: String = "<gray>Usage: /{usage}",
    val error: String = "<red>Something went wrong running that command.",
    /** `{time}` is the time left, such as `1m 30s`. */
    val cooldown: String = "<red>Wait {time} before using that again.",
    /** `{label}` and `{description}`. */
    val helpHeader: String = "<gold>/{label} <gray>- <white>{description}",
    /** `{label}`, `{name}`, `{usage}` and `{description}`. */
    val helpLine: String = "<gray>  /{label} <gold>{name} <gray>{usage} <white>{description}",
    val helpEmpty: String = "<gray>  Nothing here you can use.",
)
