package com.aliothmoon.maameow.manager

/** Root managers may start su in an isolated mount namespace. */
internal fun rootMountCommand(command: String): String {
    val quoted = "'" + command.replace("'", "'\"'\"'") + "'"
    return "if nsenter -t 1 -m -- true 2>/dev/null; then exec nsenter -t 1 -m -- sh -c $quoted; else $command; fi"
}
