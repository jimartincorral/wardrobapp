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
    /** `WARDROBAPP_VERSION` and `WARDROBAPP_BUILD`: what Settings' About section shows. */
    val version: ServerVersion = ServerVersion.DEVELOPMENT,
) {
    companion object {
        /** The directory Home Assistant keeps for an app across updates and includes in its backups. */
        const val DEFAULT_DATA = "/data"

        /** The port the app's ingress is pointed at; config.yaml says the same, and a test holds them equal. */
        const val DEFAULT_PORT = 8099

        fun from(environment: Map<String, String>): ServerSettings {
            fun value(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }

            return ServerSettings(
                dataDirectory = File(value("WARDROBAPP_DATA") ?: DEFAULT_DATA),
                port = value("WARDROBAPP_PORT")?.let { port ->
                    // Refused rather than defaulted: a typo here would otherwise
                    // start a server on a port nothing is pointed at, which looks
                    // like Home Assistant failing rather than this.
                    port.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw IllegalArgumentException("WARDROBAPP_PORT is not a port: $port")
                } ?: DEFAULT_PORT,
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
            )
        }
    }
}
