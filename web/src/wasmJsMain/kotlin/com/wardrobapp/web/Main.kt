package com.wardrobapp.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.wardrobapp.api.speakWardrobe
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document

/**
 * Start the browser app.
 *
 * Every request goes relative to the page's own address, which is where the
 * server that served it answers: under Home Assistant, a path ingress chose for
 * this installation, which the app never needs to know.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val http = HttpClient(Js) { speakWardrobe(document.baseURI) }
    ComposeViewport(document.body!!) { WebApp(http) }
}
