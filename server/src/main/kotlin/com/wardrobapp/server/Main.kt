package com.wardrobapp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

/**
 * Start the server.
 *
 * Configured by environment, which is what a container is given:
 *
 *  - `WARDROBAPP_DATA`: where the wardrobe lives. `/data` by default, the
 *    directory Home Assistant keeps for an app across updates and includes in
 *    its backups.
 *  - `WARDROBAPP_PORT`: the port to answer on, 8099 by default -- the port the
 *    app's ingress is pointed at.
 *
 * CIO rather than Netty: it is Ktor's own engine, in Kotlin, with nothing
 * native to build for each of the architectures Home Assistant runs on, and a
 * household's wardrobe will never ask it for the throughput Netty is for.
 */
fun main() {
    val data = File(System.getenv("WARDROBAPP_DATA") ?: "/data")
    val port = System.getenv("WARDROBAPP_PORT")?.toIntOrNull() ?: 8099

    val wardrobe = ServerWardrobe(data)
    Runtime.getRuntime().addShutdownHook(Thread { wardrobe.close() })

    embeddedServer(CIO, port = port) { wardrobeApi(wardrobe) }.start(wait = true)
}
