package com.wardrobapp.data

import java.io.File

/**
 * How much disk the wardrobe's photos are using.
 *
 * The equivalent of `getTotalImageStorage` in `image-service.ts`, in bytes
 * rather than megabytes: what to do with the number is the screen's business,
 * and rounding here would mean the caller could not tell 0 from "not much".
 *
 * A missing directory is 0 rather than an error -- that is what a wardrobe with
 * no photos yet looks like, since the directory is created on first write.
 */
fun storedImageBytes(imagesDir: File): Long =
    imagesDir.listFiles()
        ?.filter { it.isFile }
        ?.sumOf { it.length() }
        ?: 0L
