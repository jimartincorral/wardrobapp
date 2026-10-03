package com.wardrobapp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Start the server, configured by its environment; see ServerSettings for
 * what each variable does.
 *
 * CIO rather than Netty: it is Ktor's own engine, in Kotlin, with nothing
 * native to build for each of the architectures Home Assistant runs on, and a
 * household's wardrobe will never ask it for the throughput Netty is for.
 */
fun main() {
    val settings = ServerSettings.from(System.getenv())

    val wardrobe = ServerWardrobe(settings.dataDirectory)
    Runtime.getRuntime().addShutdownHook(Thread { wardrobe.close() })

    embeddedServer(CIO, port = settings.port) { wardrobeApi(wardrobe, settings) }.start(wait = true)
}
