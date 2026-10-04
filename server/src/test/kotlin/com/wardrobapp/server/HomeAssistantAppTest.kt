package com.wardrobapp.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home Assistant app's files, held to the server and to each other.
 *
 * Four files describe one container -- config.yaml, which Home Assistant reads;
 * the Dockerfile, which builds the image; the workflow, which builds and names
 * it; and the server's own defaults -- and every fact that two of them share is
 * a fact that can drift. None of the drift fails anywhere else: a port that
 * differs between config.yaml and the image installs cleanly and shows a blank
 * panel, an architecture listed but never built fails on somebody's Raspberry Pi
 * at install time, a version with no changelog entry is an update dialog with
 * nothing in it. So the shared facts are read out of the files and compared
 * here, where a contributor finds out in seconds.
 *
 * The YAML is read line by line rather than with a parser: these files are
 * small and flat on purpose, and a dependency to read five keys would be the
 * larger risk.
 */
class HomeAssistantAppTest {

    private val repository = File(
        System.getProperty("repositoryDir") ?: error("repositoryDir was not set; see server/build.gradle.kts"),
    )
    private val app = File(repository, "homeassistant/wardrobapp")
    private val config = File(app, "config.yaml").readLines()
    private val dockerfile = File(app, "Dockerfile").readText()
    private val workflow = File(repository, ".github/workflows/home-assistant.yml").readText()

    /** A top-level `key: value`, unquoted. */
    private fun setting(key: String): String? = config
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() }

    /** The items of a top-level list. */
    private fun list(key: String): List<String> = config
        .dropWhile { it != "$key:" }
        .drop(1)
        .takeWhile { it.startsWith("  - ") }
        .map { it.removePrefix("  - ").trim() }

    /** An `ENV NAME=value` in the Dockerfile, through its line continuations. */
    private fun environment(name: String): String? =
        Regex("""\b$name=(\S+)""").find(dockerfile)?.groupValues?.get(1)

    @Test
    fun `Home Assistant's ingress reaches the port the server answers on`() {
        assertEquals("true", setting("ingress"))
        assertEquals(ServerSettings.DEFAULT_PORT.toString(), setting("ingress_port"))
        assertEquals(ServerSettings.DEFAULT_PORT.toString(), environment("WARDROBAPP_PORT"))
    }

    @Test
    fun `nothing but ingress can reach the app`() {
        // The server refusing anything that did not come through ingress,
        // which forwards from this one address...
        assertEquals("172.30.32.2", environment("WARDROBAPP_ALLOWED_CLIENTS"))
        // ...and nothing on the host network.
        assertTrue(config.none { it.startsWith("host_network:") }, "config.yaml puts the app on the host network")
    }

    @Test
    fun `the one port offered is sync's, and it is closed until somebody opens it`() {
        // Every other port would be a way past ingress's sign-in. Sync's
        // answers only sync, and only with the pairing code (see SyncServer),
        // and Home Assistant leaves a port mapped to null closed.
        val ports = config.dropWhile { it != "ports:" }.drop(1).takeWhile { it.startsWith("  ") }.map { it.trim() }
        assertEquals(listOf("${ServerSettings.DEFAULT_SYNC_PORT}/tcp: null"), ports)
        assertEquals(ServerSettings.DEFAULT_SYNC_PORT.toString(), environment("WARDROBAPP_SYNC_PORT"))
    }

    @Test
    fun `the wardrobe is kept where Home Assistant keeps an app's data`() {
        assertEquals("/data", environment("WARDROBAPP_DATA"))
        assertEquals(ServerSettings.DEFAULT_DATA, environment("WARDROBAPP_DATA"))
    }

    @Test
    fun `every architecture the app offers has an image built for it`() {
        val offered = list("arch")
        assertEquals(setOf("aarch64", "amd64"), offered.toSet())
        for (arch in offered) {
            assertTrue("BUILD_ARCH=$arch" in workflow, "the workflow builds no image for $arch")
        }
        assertTrue(setting("image")!!.endsWith("-{arch}"), "the image name does not vary by architecture")
    }

    @Test
    fun `the image runs what Gradle builds, from where it builds it`() {
        val server = "server/build/install/wardrobapp-server"
        val web = "web/build/dist/wasmJs/productionExecutable"
        assertTrue("COPY $server " in dockerfile, "the Dockerfile does not copy $server")
        assertTrue("COPY $web " in dockerfile, "the Dockerfile does not copy $web")
        // The same two, let through the build context, or the COPY finds nothing.
        val ignore = File(repository, ".dockerignore").readText()
        assertTrue("!$server" in ignore && "!$web" in ignore, ".dockerignore keeps out what the image copies")
        // And the workflow builds both before it builds the image.
        assertTrue(":server:installDist" in workflow && ":web:wasmJsBrowserDistribution" in workflow)
        assertTrue("/opt/wardrobapp/bin/wardrobapp-server" in dockerfile)
        assertEquals("/opt/wardrobapp/web", environment("WARDROBAPP_WEB"))
    }

    @Test
    fun `the browser's release notes are written where the server reads them`() {
        // The workflow writes them into the install directory the image copies
        // to /opt/wardrobapp, after building it and before building the image.
        val written = "server/build/install/wardrobapp-server/release-notes.json"
        assertTrue("--web-history" in workflow && written in workflow, "the workflow does not write the notes")
        assertTrue(workflow.indexOf(written) > workflow.indexOf(":server:installDist"), "written before the build that would replace them")
        assertTrue(workflow.indexOf(written) < workflow.indexOf("docker/build-push-action"), "written after the image is built")
        assertTrue("fetch-depth: 0" in workflow, "the notes need the whole history")
        assertEquals("/opt/wardrobapp/release-notes.json", environment("WARDROBAPP_RELEASE_NOTES"))
    }

    @Test
    fun `the background model is where the build puts it`() {
        // build.gradle.kts copies the downloaded model into the distribution's
        // models/, the distribution is copied to /opt/wardrobapp, and a server
        // that looks anywhere else quietly stops offering to remove backgrounds.
        val build = File(repository, "server/build.gradle.kts").readText()
        val model = File(System.getProperty("backgroundModel")).name
        assertTrue("into(\"models\")" in build && "models/$model" in build, "the distribution does not carry models/$model")
        assertEquals("/opt/wardrobapp/models/$model", environment("WARDROBAPP_BACKGROUND_MODEL"))
    }

    @Test
    fun `the server knows its version from the build that made it`() {
        assertEquals("\${BUILD_VERSION}", environment("WARDROBAPP_VERSION"))
        assertTrue("BUILD_VERSION=\${{ steps.app.outputs.version }}" in workflow)
    }

    @Test
    fun `the version Home Assistant offers has a changelog entry`() {
        // Home Assistant shows CHANGELOG.md in the update dialog; a release
        // without its entry is a dialog with nothing to say.
        val version = setting("version")!!
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(version), "version $version is not x.y.z")
        val changelog = File(app, "CHANGELOG.md").readLines()
        assertTrue("## $version" in changelog, "CHANGELOG.md has no '## $version'")
    }

    @Test
    fun `the repository can be added to Home Assistant`() {
        val repositoryFile = File(repository, "repository.yaml")
        assertTrue(repositoryFile.isFile, "repository.yaml is missing")
        assertTrue(repositoryFile.readLines().any { it.startsWith("name:") })
        for (file in listOf("config.yaml", "Dockerfile", "README.md", "DOCS.md", "icon.png")) {
            assertTrue(File(app, file).isFile, "homeassistant/wardrobapp/$file is missing")
        }
    }
}
