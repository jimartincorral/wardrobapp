package com.wardrobapp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

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

    val backgrounds = BackgroundRemover.at(settings.backgroundModel)
    if (backgrounds == null) {
        println("Removing backgrounds is off: no model at ${settings.backgroundModel ?: "WARDROBAPP_BACKGROUND_MODEL, which is not set"}.")
    }

    val profiles = ProfileRegistry(settings.dataDirectory, backgrounds = backgrounds)
    Runtime.getRuntime().addShutdownHook(Thread {
        profiles.close()
        backgrounds?.close()
    })

    frameStoredCutouts(settings.dataDirectory, profiles)

    // The phones' port first, without waiting, then the browser's, which
    // holds the process open. Two servers over the same wardrobes: see
    // SyncServer for why sync has a port of its own.
    settings.syncPort?.let { syncPort ->
        embeddedServer(CIO, port = syncPort) { wardrobeSync(profiles, settings.version) }.start(wait = false)
    }
    embeddedServer(CIO, port = settings.port) { wardrobeApi(profiles, settings) }.start(wait = true)
}

/**
 * Frame every cut-out stored before cut-outs were framed, once; see
 * CutoutFraming in :data and [frameLooseCutouts].
 *
 * On a thread of its own so the ports open without waiting for it: a
 * household's wardrobes hold a few hundred cut-outs at most, each a PNG to
 * decode and perhaps write, which is seconds on a Pi but seconds the browser
 * should not spend looking at a connection refused. A marker file in the
 * data directory says it has been through, since a pass that only decodes
 * every cut-out to find nothing to do is still every cut-out decoded on
 * every start. Written when the pass finishes, not when it starts, so a
 * server stopped halfway finishes next time.
 */
private fun frameStoredCutouts(dataDirectory: File, profiles: ProfileRegistry) {
    val marker = File(dataDirectory, CUTOUTS_FRAMED_MARKER)
    if (marker.isFile) return

    Thread({
        val framed = profiles.photoDirectories().sumOf { directory ->
            try {
                frameLooseCutouts(directory)
            } catch (e: Exception) {
                println("Framing the cut-outs in $directory failed: ${e.message}")
                0
            }
        }
        if (framed > 0) println("Framed $framed stored cut-outs around their garments.")
        try {
            marker.writeText("Cut-outs stored before this file existed have been framed around their garments.\n")
        } catch (e: Exception) {
            println("Could not write $marker: ${e.message}; the cut-outs will be checked again next start.")
        }
    }, "frame-cutouts").apply { isDaemon = true }.start()
}

/** The file whose presence says the stored cut-outs have been framed; see [frameStoredCutouts]. */
internal const val CUTOUTS_FRAMED_MARKER = "cutouts-framed"
