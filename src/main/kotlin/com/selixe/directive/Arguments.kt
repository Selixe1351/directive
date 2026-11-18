package com.selixe.directive

import java.math.BigDecimal
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Typed access to command arguments.
 *
 * The required versions throw CommandFailure with a message that names the argument and
 * shows a valid example. The OrNull versions return null for missing or invalid input.
 */

fun CommandContext.string(index: Int, name: String = "argument"): String =
    arg(index) ?: throw CommandFailure("Missing $name.")

fun CommandContext.int(index: Int, name: String = "number"): Int =
    intOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a whole number, $name must be something like 10.")

fun CommandContext.long(index: Int, name: String = "number"): Long =
    longOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a whole number, $name must be something like 10.")

fun CommandContext.double(index: Int, name: String = "number"): Double =
    doubleOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a number, $name must be something like 10.5.")

/** For money and other values that must not lose precision. */
fun CommandContext.decimal(index: Int, name: String = "amount"): BigDecimal =
    decimalOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not an amount, $name must be something like 100 or 99.50.")

fun CommandContext.boolean(index: Int, name: String = "value"): Boolean =
    booleanOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a valid $name, use true or false.")

fun CommandContext.uuid(index: Int, name: String = "uuid"): UUID =
    uuidOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a valid $name.")

/** Accepts `30s`, `5m`, `2h`, `7d`, `2w`, combinations like `1d12h`, and a bare number as seconds. */
fun CommandContext.duration(index: Int, name: String = "duration"): Duration =
    durationOrNull(index) ?: throw CommandFailure("'${arg(index).orEmpty()}' is not a valid $name, use something like 30m, 2h, 7d or 1d12h.")

inline fun <reified T : Enum<T>> CommandContext.enum(index: Int, name: String = "option"): T =
    enumOrNull<T>(index) ?: throw CommandFailure(
        "'${arg(index).orEmpty()}' is not a valid $name, choose one of " +
            enumValues<T>().joinToString(", ") { it.name.lowercase() } + "."
    )

/** Everything from [from] onwards, for reasons and messages. */
fun CommandContext.text(from: Int, name: String = "message"): String =
    rest(from).ifBlank { throw CommandFailure("Missing $name.") }

fun CommandContext.intOrNull(index: Int): Int? = arg(index)?.toIntOrNull()

fun CommandContext.longOrNull(index: Int): Long? = arg(index)?.toLongOrNull()

fun CommandContext.doubleOrNull(index: Int): Double? = arg(index)?.toDoubleOrNull()

fun CommandContext.decimalOrNull(index: Int): BigDecimal? = arg(index)?.toBigDecimalOrNull()

fun CommandContext.booleanOrNull(index: Int): Boolean? = when (arg(index)?.lowercase()) {
    "true", "yes", "on", "enable", "enabled" -> true
    "false", "no", "off", "disable", "disabled" -> false
    else -> null
}

fun CommandContext.uuidOrNull(index: Int): UUID? =
    arg(index)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

fun CommandContext.durationOrNull(index: Int): Duration? = arg(index)?.let(::parseDuration)

inline fun <reified T : Enum<T>> CommandContext.enumOrNull(index: Int): T? {
    val raw = arg(index) ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
}

private val durationPart = Regex("(\\d+)([smhdw])")

/** Parses `30s`, `5m`, `2h`, `7d`, `2w`, combinations like `1d12h`, and a bare number as seconds. */
fun parseDuration(input: String): Duration? {
    val raw = input.trim().lowercase()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }

    var total = Duration.ZERO
    var position = 0
    while (position < raw.length) {
        val match = durationPart.matchAt(raw, position) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        total += when (match.groupValues[2]) {
            "s" -> amount.seconds
            "m" -> amount.minutes
            "h" -> amount.hours
            "d" -> amount.days
            else -> (amount * 7).days
        }
        position = match.range.last + 1
    }
    return total
}
