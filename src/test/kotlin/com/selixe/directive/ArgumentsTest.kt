package com.selixe.directive

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ArgumentsTest {

    private enum class Mode { Survival, Creative }

    private fun context(vararg args: String) = CommandContext(TestActor(), command("test"), "test", args.toList())

    @Test
    fun `numbers parse`() {
        val context = context("10", "9000000000", "2.5")

        assertEquals(10, context.int(0))
        assertEquals(9_000_000_000, context.long(1))
        assertEquals(2.5, context.double(2))
    }

    @Test
    fun `decimal keeps its precision`() {
        assertEquals(BigDecimal("0.30"), context("0.30").decimal(0))
    }

    @Test
    fun `durations accept suffixes and combinations`() {
        assertEquals(30.seconds, parseDuration("30s"))
        assertEquals(5.minutes, parseDuration("5m"))
        assertEquals(2.hours, parseDuration("2h"))
        assertEquals(7.days, parseDuration("7d"))
        assertEquals(14.days, parseDuration("2w"))
        assertEquals(1.days + 12.hours, parseDuration("1d12h"))
        assertEquals(90.seconds, parseDuration("90"))
        assertEquals(2.hours, parseDuration("2H"))
    }

    @Test
    fun `invalid durations are rejected`() {
        assertNull(parseDuration(""))
        assertNull(parseDuration("forever"))
        assertNull(parseDuration("5x"))
        assertNull(parseDuration("1d junk"))
        assertNull(parseDuration("-5"))

        val failure = assertThrows<CommandFailure> { context("soon").duration(0) }
        assertTrue(failure.message!!.contains("1d12h"))
    }

    @Test
    fun `booleans accept common words`() {
        listOf("true", "yes", "on", "enable").forEach { assertEquals(true, context(it).boolean(0)) }
        listOf("false", "no", "off", "disable").forEach { assertEquals(false, context(it).boolean(0)) }
        assertNull(context("maybe").booleanOrNull(0))
    }

    @Test
    fun `enums match case insensitively and list options on failure`() {
        assertEquals(Mode.Creative, context("CREATIVE").enum<Mode>(0))

        val failure = assertThrows<CommandFailure> { context("hardcore").enum<Mode>(0, "mode") }
        assertEquals("'hardcore' is not a valid mode, choose one of survival, creative.", failure.message)
    }

    @Test
    fun `bad number names the argument`() {
        val failure = assertThrows<CommandFailure> { context("abc").int(0, "amount") }

        assertEquals("'abc' is not a whole number, amount must be something like 10.", failure.message)
    }

    @Test
    fun `missing argument is reported by name`() {
        val failure = assertThrows<CommandFailure> { context().string(0, "warp name") }

        assertEquals("Missing warp name.", failure.message)
    }

    @Test
    fun `text joins everything from an index`() {
        assertEquals("hello there world", context("Steve", "hello", "there", "world").text(1))
        assertThrows<CommandFailure> { context("Steve").text(1, "reason") }
    }

    @Test
    fun `uuids parse and bad ones are reported`() {
        val uuid = UUID.randomUUID()

        assertEquals(uuid, context(uuid.toString()).uuid(0))
        assertNull(context("not-a-uuid").uuidOrNull(0))
        assertThrows<CommandFailure> { context("not-a-uuid").uuid(0) }
    }

    @Test
    fun `optional accessors return null for missing input`() {
        val context = context()

        assertNull(context.intOrNull(0))
        assertNull(context.durationOrNull(0))
        assertNull(context.decimalOrNull(0))
        assertNull(context.enumOrNull<Mode>(0))
    }

}
