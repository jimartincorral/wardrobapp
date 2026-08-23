package com.wardrobapp.data

import java.io.File

/**
 * Where the wardrobe's files sit inside the app's private data directory.
 *
 * This exists because getting it wrong is invisible. Both apps share one data
 * directory once they share an application id, but sharing a *directory* is not
 * sharing a *file*: the React Native app's database lives under `files/SQLite/`,
 * because that is where expo-sqlite puts a database opened by bare name, while
 * `Context.getDatabasePath` -- the obvious Android answer -- resolves to
 * `databases/`, one directory over. An app that looked in the wrong one would
 * find no database at all, create an empty one, apply the schema to it, and show
 * an empty wardrobe with every photo still on disk. No error, no crash.
 *
 * So the layout is written down once, here, as something a test can assert,
 * rather than implied by whichever platform call each caller happened to reach
 * for.
 */

/** The directory expo-sqlite opens a bare-named database in. */
const val DATABASE_DIRNAME = "SQLite"

/**
 * The database's filename.
 *
 * The same string as [ARCHIVE_DB_FILENAME] and deliberately a separate constant:
 * one names a file on the device, the other an entry inside an archive, and a
 * backup format that later renamed its entry should not move the live database.
 */
const val DATABASE_FILENAME = "wardrobapp.db"

/**
 * The live wardrobe, given the app's private files directory.
 *
 * `filesDir` on Android; a temporary directory in tests. Nothing is created --
 * this only says where things are.
 */
fun wardrobeFilesIn(filesDir: File): WardrobeFiles = WardrobeFiles(
    databaseFile = File(File(filesDir, DATABASE_DIRNAME), DATABASE_FILENAME),
    imagesDir = File(filesDir, GARMENT_IMAGE_DIRNAME),
)
