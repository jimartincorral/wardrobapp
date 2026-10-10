package com.wardrobapp.server

import com.wardrobapp.api.ServerVersion
import java.io.File

/**
 * How the server was asked to run, read from its environment.
 *
 * Environment variables because that is what a container is given: the Home
 * Assistant app's Dockerfile sets every one of these, and nothing else about
 * the server is configurable. Read in one place, and from a map rather than
 * from System.getenv directly, so what an unset or malformed variable comes to
 * is something a test can say.
 */
data class ServerSettings(
    /** `WARDROBAPP_DATA`: where the wardrobe lives. */
    val dataDirectory: File = File(DEFAULT_DATA),
    /** `WARDROBAPP_PORT`: the port to answer on. */
    val port: Int = DEFAULT_PORT,
    /** `WARDROBAPP_WEB`: the browser app's files, served from the root; null to serve the API alone. */
    val webDirectory: File? = null,
    /**
     * `WARDROBAPP_ALLOWED_CLIENTS`: the only addresses a request may come from,
     * comma-separated, or null to answer anyone who can reach the port.
     *
     * Home Assistant checks who is signed in before ingress forwards a request,
     * and ingress forwards from one address, 172.30.32.2. The app publishes no
     * port, but other apps on the same Docker network can still reach it, and
     * they have not been through that check. So the app sets this to the one
     * address that has, which is what Home Assistant's documentation asks of
     * every app using ingress.
     */
    val allowedClients: Set<String>? = null,
    /**
     * `WARDROBAPP_SYNC_PORT`: the port phones sync through, or `off` for none.
     *
     * A second server on its own port rather than more routes on the first,
     * because the first answers only Home Assistant's ingress and a phone does
     * not come through ingress. This one answers anybody who reaches it and
     * holds the pairing code, and nothing but sync -- no screens, no photos to
     * browse -- so a port opened for it opens nothing else. Home Assistant
     * keeps it closed until it is given a host port in the app's settings.
     */
    val syncPort: Int? = DEFAULT_SYNC_PORT,
    /** `WARDROBAPP_VERSION` and `WARDROBAPP_BUILD`: what Settings' About section shows. */
    val version: ServerVersion = ServerVersion.DEVELOPMENT,
    /**
     * `WARDROBAPP_RELEASE_NOTES`: the browser's What's new, by version, as the
     * release workflow wrote it into the image; null, or a file that is not
     * there, for a server built without one -- which then has nothing new to
     * say rather than failing to start.
     */
    val releaseNotes: File? = null,
    /**
     * `WARDROBAPP_BACKGROUND_MODEL`: the model BackgroundRemover runs, as the
     * build downloaded it into the image; null, or a file that is not there,
     * for a server that cannot remove backgrounds -- which then says so to the
     * browser, which does not offer it, rather than failing to start.
     */
    val backgroundModel: File? = null,
    /**
     * `WARDROBAPP_STYLE_MODEL` and `WARDROBAPP_STYLE_ANCHORS`: the image
     * model StyleEncoder embeds photos with and the anchors it reads
     * attributes with, as the build downloaded them into the image; null, or
     * files that are not there, for a server that does not learn style --
     * which says so to the browser rather than failing to start.
     */
    val styleModel: File? = null,
    val styleAnchors: File? = null,
    /**
     * `SUPERVISOR_TOKEN`: what Home Assistant gives an app to ask its
     * Supervisor about itself, when config.yaml asks for `hassio_api`; null
     * anywhere else. Used for one question -- which host port the sync port
     * is published on, see HostPorts -- and set by Home Assistant rather than
     * by the Dockerfile, which is why it alone has no `WARDROBAPP_` prefix.
     */
    val supervisorToken: String? = null,
) {
    companion object {
        /** The directory Home Assistant keeps for an app across updates and includes in its backups. */
        const val DEFAULT_DATA = "/data"

        /** The port the app's ingress is pointed at; config.yaml says the same, and a test holds them equal. */
        const val DEFAULT_PORT = 8099

        /**
         * The port phones sync through, inside the container; config.yaml offers
         * it to be mapped. The phone's own, which it assumes when somebody types
         * an address without one, so the two cannot drift apart.
         */
        const val DEFAULT_SYNC_PORT = com.wardrobapp.presentation.DEFAULT_SYNC_PORT

        fun from(environment: Map<String, String>): ServerSettings {
            fun value(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }

            // Refused rather than defaulted: a typo here would otherwise start a
            // server on a port nothing is pointed at, which looks like Home
            // Assistant failing rather than this.
            fun port(name: String): Int? = value(name)?.let { port ->
                port.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("$name is not a port: $port")
            }

            return ServerSettings(
                dataDirectory = File(value("WARDROBAPP_DATA") ?: DEFAULT_DATA),
                port = port("WARDROBAPP_PORT") ?: DEFAULT_PORT,
                syncPort = if (value("WARDROBAPP_SYNC_PORT").equals("off", ignoreCase = true)) {
                    null
                } else {
                    port("WARDROBAPP_SYNC_PORT") ?: DEFAULT_SYNC_PORT
                },
                webDirectory = value("WARDROBAPP_WEB")?.let(::File),
                allowedClients = value("WARDROBAPP_ALLOWED_CLIENTS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() },
                version = ServerVersion(
                    name = value("WARDROBAPP_VERSION") ?: ServerVersion.DEVELOPMENT.name,
                    build = value("WARDROBAPP_BUILD")?.toLongOrNull() ?: ServerVersion.DEVELOPMENT.build,
                ),
                releaseNotes = value("WARDROBAPP_RELEASE_NOTES")?.let(::File),
                backgroundModel = value("WARDROBAPP_BACKGROUND_MODEL")?.let(::File),
                styleModel = value("WARDROBAPP_STYLE_MODEL")?.let(::File),
                styleAnchors = value("WARDROBAPP_STYLE_ANCHORS")?.let(::File),
                supervisorToken = value("SUPERVISOR_TOKEN"),
            )
        }
    }
}
