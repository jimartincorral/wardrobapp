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
 *  - `WARDROBAPP_WEB`: the browser app's files, `:web`'s distribution, served
 *    from the root. Unset, only the API is served.
 *
 * CIO rather than Netty: it is Ktor's own engine, in Kotlin, with nothing
 * native to build for each of the architectures Home Assistant runs on, and a
 * household's wardrobe will never ask it for the throughput Netty is for.
 */
fun main() {
    val data = File(System.getenv("WARDROBAPP_DATA") ?: "/data")
    val port = System.getenv("WARDROBAPP_PORT")?.toIntOrNull() ?: 8099
    val web = System.getenv("WARDROBAPP_WEB")?.let(::File)

    val wardrobe = ServerWardrobe(data)
    Runtime.getRuntime().addShutdownHook(Thread { wardrobe.close() })

    embeddedServer(CIO, port = port) { wardrobeApi(wardrobe, web) }.start(wait = true)
}
