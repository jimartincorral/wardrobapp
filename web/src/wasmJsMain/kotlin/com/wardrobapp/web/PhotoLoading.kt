package com.wardrobapp.web

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.map.Mapper
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.Options
import com.wardrobapp.api.Routes
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import org.w3c.dom.url.URL

/**
 * Teach Coil, which every screen draws photos with, where the photos are.
 *
 * On the phone a photo reference is a `file://` path, which Coil reads from
 * disk. Here it is `photos/<name>`, relative to the page, because the server
 * reads the rows that way so the same reference works under whatever path Home
 * Assistant's ingress serves the app from (see ServerWardrobe). Coil knows
 * nothing about the page, so a relative reference would be taken for a file
 * that is not there and draw nothing; [PageRelativePhotos] makes it the full
 * address first, and the Ktor fetcher -- the network module :ui leaves out,
 * on purpose, for the phone -- fetches it like any other request the page
 * makes, Home Assistant's session included.
 */
@Composable
fun ProvidePhotoLoading() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(PageRelativePhotos)
                add(KtorNetworkFetcherFactory(httpClient = { HttpClient(Js) }))
            }
            .build()
    }
}

/**
 * A server photo reference, as the address the page would load it from.
 *
 * `p/<profile>/photos/<name>` -- each profile's photos under its own path --
 * or `photos/<name>` from a server answering one wardrobe at its root.
 */
private object PageRelativePhotos : Mapper<String, String> {
    override fun map(data: String, options: Options): String? =
        if (PHOTO_REF.containsMatchIn(data)) URL(data, document.baseURI).href else null
}

private val PHOTO_REF = Regex("""^(p/[^/]+/)?${Regex.escape(Routes.PHOTO_FILES)}""")
