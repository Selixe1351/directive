# Directive

A small Kotlin command framework for Paper and Velocity. Write commands once, without platform types, and test them without a server. Java 21+.

## Install

```groovy
repositories {
    maven { url 'https://repo.selixe.com/repository/api/' }
}

dependencies {
    implementation 'com.selixe.directive:directive:1.0.0'
}
```

## Example

```kotlin
val warp = command("warp", description = "Teleport to a warp") {
    sub("set", usage = "<name>", permission = "warp.set", playerOnly = true) {
        executes { context ->
            warps.set(context.string(0, "name"), context.player().location)
            context.reply("<green>Warp set.")
        }
    }

    sub("give", usage = "<player> <amount>", permission = "warp.give") {
        executes { context ->
            val target = context.player(0)
            val amount = context.int(1, "amount")
            context.reply("<green>Gave $amount to ${target.name}.")
        }
    }

    sub("go", usage = "<name>", cooldown = 30.seconds) {
        completes { warps.names() }
        executes { context -> warps.teleport(context.player(), context.string(0, "name")) }
    }
}
```

Paper:

```kotlin
class MyPlugin : JavaPlugin() {

    private val commands = PaperCommands(this)

    override fun onEnable() {
        commands.register(warp)
    }

    override fun onDisable() {
        commands.close()
    }

}
```

Velocity: `VelocityCommands(proxy, this)`, used the same way.

Do not list the commands in `plugin.yml`. They are registered at runtime.

## Features

- Nested subcommands and aliases, matched case-insensitively.
- Permissions on any level. Players only see what they can use, in help and tab completion.
- Help pages built from descriptions and usage, shown for `/warp`, `/warp help` and `/warp ?`.
- Tab completion from the usage string: `<player>` completes online players, `<ban|mute>` the listed words, `<boolean>` and `[duration]` common values. Add `completes { }` for anything else.
- Typed arguments with clear errors: `int`, `long`, `double`, `decimal`, `boolean`, `uuid`, `duration`, `enum`, `text`, plus `OrNull` versions.
- Platform arguments: `player()`, `player(index)` and, on Paper, `offlinePlayer(index)`.
- Throw `CommandFailure("message")` to show an error followed by the usage line.
- Unexpected exceptions show a short error to the sender and are logged with the full stack trace.
- `playerOnly`, `consoleOnly`, per-player `cooldown` with a `<permission>.bypasscooldown` permission, and `async` to run on a background thread.
- Every message is MiniMessage and can be replaced through `Messages`.
- Commands can be unregistered at runtime. `override = true` takes a label another plugin already uses.

## Messages

```kotlin
PaperCommands(this, Messages(noPermission = "<red>You can't do that."))
```

## Durations

`30s`, `5m`, `2h`, `7d`, `2w`, combinations like `1d12h`, or a number of seconds.

## Testing

`CommandDispatcher` has no platform types. Register commands on it and call `dispatch` with your own `Actor`:

```kotlin
val dispatcher = CommandDispatcher()
dispatcher.register(warp)
dispatcher.dispatch(testActor, "warp", listOf("set", "spawn"))
```

## License

[MIT](LICENSE)
