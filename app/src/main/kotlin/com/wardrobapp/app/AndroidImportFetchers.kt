package com.wardrobapp.app

import android.content.Context
import android.net.Uri
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.net.ImportHttp
import java.io.File
import java.util.UUID

/**
 * Downloading one of a page's images into the wardrobe.
 *
 * Two steps, because the photo store reads through the content resolver and knows
 * nothing about the network: the bytes land in the cache first, then go through
 * exactly the path a photo picked from the gallery goes through -- decoded, turned
 * upright, scaled and re-encoded. An imported photo is therefore the same shape on
 * disk as every other one, which is what keeps backups and the storage figures
 * honest.
 *
 * The first step -- the request, its redirects and its size limit -- is
 * [ImportHttp], in :net, beside the page fetcher. Only the second needs Android,
 * so only the second is here.
 */
class AndroidImageFetcher(
    private val context: Context,
    private val http: ImportHttp,
    private val photos: AndroidPhotoStore,
    private val imageDirectory: String,
) : ImageFetcher {

    override fun download(url: String): String {
        val temporary = File.createTempFile("import-", null, context.cacheDir)

        return try {
            http.download(url, temporary)
            val stored = photos.store(Uri.fromFile(temporary), UUID.randomUUID().toString())
            resolveImageRef(stored, imageDirectory)
        } finally {
            // Whether or not it worked: this is a copy, and the wardrobe has its
            // own by now if anything is going to use it.
            temporary.delete()
        }
    }
}
