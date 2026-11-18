package com.selixe.directive

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CommandDispatcherTest {

    private val dispatcher = CommandDispatcher()

    @Test
    fun `unknown label is not handled`() {
        assertFalse(dispatcher.dispatch(TestActor(), "nothing", emptyList()))
    }

    @Test
    fun `subcommands match from the left and get the remaining arguments`() {
        var seen: List<String>? = null
        dispatcher.register(command("guild") {
            sub("member") {
                sub("add") { executes { seen = it.args } }
            }
        })

        assertTrue(dispatcher.dispatch(TestActor(), "guild", listOf("member", "add", "Steve")))
        assertEquals(listOf("Steve"), seen)
    }

    @Test
    fun `labels and aliases are case insensitive`() {
        var runs = 0
        dispatcher.register(command("guild", aliases = listOf("g")) {
            sub("create", aliases = listOf("new")) { executes { runs++ } }
        })

        dispatcher.dispatch(TestActor(), "GUILD", listOf("Create"))
        dispatcher.dispatch(TestActor(), "g", listOf("NEW"))

        assertEquals(2, runs)
    }

    @Test
    fun `permission is checked at the level that declares it`() {
        var ran = false
        dispatcher.register(command("guild") {
            sub("disband", permission = "guild.disband") { executes { ran = true } }
        })

        val denied = TestActor()
        dispatcher.dispatch(denied, "guild", listOf("disband"))
        assertFalse(ran)
        assertEquals(listOf(Messages().noPermission), denied.messages)

        dispatcher.dispatch(TestActor(setOf("guild.disband")), "guild", listOf("disband"))
        assertTrue(ran)
    }

    @Test
    fun `console passes every permission`() {
        var ran = false
        dispatcher.register(command("stop", permission = "server.stop") { executes { ran = true } })

        dispatcher.dispatch(TestActor(isConsole = true), "stop", emptyList())

        assertTrue(ran)
    }

    @Test
    fun `failure is shown with the usage line`() {
        dispatcher.register(command("pay", usage = "<player> <amount>") {
            executes { it.decimal(1, "amount") }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "pay", listOf("Steve", "lots"))

        assertEquals(2, actor.messages.size)
        assertTrue(actor.messages[0].contains("'lots' is not an amount"))
        assertEquals("<gray>Usage: /pay <player> <amount>", actor.messages[1])
    }

    @Test
    fun `usage line of a subcommand includes its name`() {
        dispatcher.register(command("warp") {
            sub("set", usage = "<name>") { executes { it.string(0, "name") } }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "warp", listOf("set"))

        assertEquals("<gray>Usage: /warp set <name>", actor.messages.last())
    }

    @Test
    fun `usage line uses the alias that was typed`() {
        dispatcher.register(command("ipban", aliases = listOf("banip"), usage = "<address>") {
            executes { it.string(0, "address") }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "banip", emptyList())

        assertEquals("<gray>Usage: /banip <address>", actor.messages.last())
    }

    @Test
    fun `usage line includes every subcommand level`() {
        dispatcher.register(command("guild") {
            sub("member") {
                sub("add", usage = "<player>") { executes { it.string(0, "player") } }
            }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "guild", listOf("member", "add"))

        assertEquals("<gray>Usage: /guild member add <player>", actor.messages.last())
    }

    @Test
    fun `unexpected exception shows a generic error`() {
        dispatcher.register(command("broken") { executes { error("database down") } })

        val actor = TestActor()
        dispatcher.dispatch(actor, "broken", emptyList())

        assertEquals(listOf(Messages().error), actor.messages)
    }

    @Test
    fun `player only and console only commands are enforced`() {
        dispatcher.register(command("fly", playerOnly = true) { executes { } })
        dispatcher.register(command("reload", consoleOnly = true) { executes { } })

        val console = TestActor(isConsole = true)
        dispatcher.dispatch(console, "fly", emptyList())
        assertEquals(listOf(Messages().playerOnly), console.messages)

        val player = TestActor()
        dispatcher.dispatch(player, "reload", emptyList())
        assertEquals(listOf(Messages().consoleOnly), player.messages)
    }

    @Test
    fun `container command shows help`() {
        dispatcher.register(command("warp", description = "Warps") {
            sub("set", usage = "<name>", description = "Create a warp") { executes { } }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "warp", emptyList())

        assertEquals("<gold>/warp <gray>- <white>Warps", actor.messages[0])
        assertTrue(actor.messages[1].contains("set"))
        assertTrue(actor.messages[1].contains("Create a warp"))
    }

    @Test
    fun `help is shown on request even when the command has a body`() {
        var ran = false
        dispatcher.register(command("warp") {
            sub("set") { executes { } }
            executes { ran = true }
        })

        dispatcher.dispatch(TestActor(), "warp", listOf("help"))

        assertFalse(ran)
    }

    @Test
    fun `help leaves out subcommands the sender cannot use`() {
        dispatcher.register(command("warp") {
            sub("go") { executes { } }
            sub("delete", permission = "warp.delete") { executes { } }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "warp", emptyList())

        assertEquals(2, actor.messages.size)
        assertFalse(actor.messages.any { it.contains("delete") })
    }

    @Test
    fun `unregistering removes the command and its aliases`() {
        val handle = dispatcher.register(command("home", aliases = listOf("h")) { executes { } })
        handle.close()

        assertNull(dispatcher.find("home"))
        assertNull(dispatcher.find("h"))
    }

    @Test
    fun `duplicate label keeps the first command`() {
        val first = command("spawn") { executes { } }
        dispatcher.register(first)
        dispatcher.register(command("spawn") { executes { } })

        assertTrue(dispatcher.find("spawn") === first)
    }

    @Test
    fun `cooldown blocks a second run until it expires`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val dispatcher = CommandDispatcher(clock = clock)
        var runs = 0
        dispatcher.register(command("kit", cooldown = 5.minutes) { executes { runs++ } })

        val actor = TestActor()
        dispatcher.dispatch(actor, "kit", emptyList())
        dispatcher.dispatch(actor, "kit", emptyList())
        assertEquals(1, runs)
        assertEquals("<red>Wait 5m before using that again.", actor.messages.last())

        now = now.plusSeconds(90)
        dispatcher.dispatch(actor, "kit", emptyList())
        assertEquals("<red>Wait 3m 30s before using that again.", actor.messages.last())

        now = now.plusSeconds(210)
        dispatcher.dispatch(actor, "kit", emptyList())
        assertEquals(2, runs)
    }

    @Test
    fun `cooldown applies per sender and can be bypassed`() {
        var runs = 0
        dispatcher.register(command("kit", permission = "kit.use", cooldown = 5.minutes) { executes { runs++ } })

        val first = TestActor(setOf("kit.use"))
        val second = TestActor(setOf("kit.use"))
        val bypass = TestActor(setOf("kit.use", "kit.use.bypasscooldown"))
        repeat(2) {
            dispatcher.dispatch(first, "kit", emptyList())
            dispatcher.dispatch(second, "kit", emptyList())
            dispatcher.dispatch(bypass, "kit", emptyList())
        }

        assertEquals(4, runs)
    }

    @Test
    fun `failed run does not start the cooldown`() {
        var runs = 0
        dispatcher.register(command("kit", cooldown = 5.minutes) {
            executes {
                runs++
                throw CommandFailure("Inventory full.")
            }
        })

        val actor = TestActor()
        dispatcher.dispatch(actor, "kit", emptyList())
        dispatcher.dispatch(actor, "kit", emptyList())

        assertEquals(2, runs)
    }

    @Test
    fun `async command runs on another thread`() {
        val latch = CountDownLatch(1)
        var thread: Thread? = null
        dispatcher.register(command("lookup", async = true) {
            executes {
                thread = Thread.currentThread()
                latch.countDown()
            }
        })

        dispatcher.dispatch(TestActor(), "lookup", emptyList())

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(thread !== Thread.currentThread())
    }

    @Test
    fun `messages can be replaced`() {
        val dispatcher = CommandDispatcher(messages = Messages(noPermission = "<red>Nope."))
        dispatcher.register(command("secret", permission = "secret.use") { executes { } })

        val actor = TestActor()
        dispatcher.dispatch(actor, "secret", emptyList())

        assertEquals(listOf("<red>Nope."), actor.messages)
    }

    @Test
    fun `subcommand names complete at the first position only`() {
        dispatcher.register(command("warp") {
            sub("set", usage = "<name>") { executes { } }
            sub("delete") { executes { } }
        })

        assertEquals(listOf("delete", "set"), dispatcher.complete(TestActor(), "warp", listOf("")))
        assertEquals(listOf("set"), dispatcher.complete(TestActor(), "warp", listOf("s")))
        assertEquals(emptyList(), dispatcher.complete(TestActor(), "warp", listOf("set", "")))
    }

    @Test
    fun `usage drives completion`() {
        val dispatcher = CommandDispatcher(players = { listOf("Steve", "Alex") })
        dispatcher.register(command("punish", usage = "<player> <ban|mute> [duration] <boolean>") { executes { } })

        val actor = TestActor()
        assertEquals(listOf("Alex", "Steve"), dispatcher.complete(actor, "punish", listOf("")))
        assertEquals(listOf("ban"), dispatcher.complete(actor, "punish", listOf("Steve", "b")))
        assertEquals(listOf("1d", "1h"), dispatcher.complete(actor, "punish", listOf("Steve", "ban", "1")))
        assertEquals(listOf("true"), dispatcher.complete(actor, "punish", listOf("Steve", "ban", "1d", "t")))
    }

    @Test
    fun `custom completer answers for its position`() {
        dispatcher.register(command("warp", usage = "<name>") {
            completes { if (it.index == 0) listOf("spawn", "shop") else emptyList() }
            executes { }
        })

        assertEquals(listOf("shop", "spawn"), dispatcher.complete(TestActor(), "warp", listOf("s")))
    }

    @Test
    fun `completion hides what the sender cannot use`() {
        dispatcher.register(command("warp") {
            sub("go") { executes { } }
            sub("delete", permission = "warp.delete") { executes { } }
        })
        dispatcher.register(command("admin", permission = "admin") { executes { } })

        assertEquals(listOf("go"), dispatcher.complete(TestActor(), "warp", listOf("")))
        assertEquals(emptyList(), dispatcher.complete(TestActor(), "admin", listOf("")))
    }

    @Test
    fun `failing completer does not break typing`() {
        dispatcher.register(command("warp") {
            sub("go") { executes { } }
            completes { error("lookup failed") }
        })

        assertEquals(listOf("go"), dispatcher.complete(TestActor(), "warp", listOf("")))
    }

}
