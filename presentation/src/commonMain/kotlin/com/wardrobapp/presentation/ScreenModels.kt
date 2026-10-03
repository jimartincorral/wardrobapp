package com.wardrobapp.presentation

import kotlin.coroutines.cancellation.CancellationException

/**
 * Screen models: what each screen's Android ViewModel used to do, in common code.
 *
 * The screens moved to Compose Multiplatform in :ui, but the logic that fills
 * them -- load this, react to that, report a failure -- stayed in :app, inside
 * androidx ViewModels, where the browser cannot run it. A screen model is that
 * logic with the two Android-specific things taken out and handed in instead:
 *
 *  - the coroutine scope it works in, which on the phone is the ViewModel's
 *    viewModelScope, so work still stops when the screen goes away; and
 *  - where its data comes from, as a small interface of suspend functions -- one
 *    per screen, named `<Screen>Source` -- which on the phone is the local
 *    database read off the main thread, and in the browser will be the Home
 *    Assistant server.
 *
 * The sources that read the wardrobe's own database are in jvmMain, in
 * DatabaseSources.kt: the phone and the Home Assistant server both hold a
 * database and both run on a JVM, and what a source decides on its own -- a new
 * row's id, the time it was written -- is the JVM's UUID and clock.
 *
 * The Android ViewModels stay, as thin wrappers that own the scope and keep the
 * API MainActivity already calls, so moving a model changes nothing the phone
 * shows. What it gains is tests: these run on a plain JVM in seconds, where the
 * ViewModels could only be reached through Robolectric.
 */

/**
 * The sentence to show for a failure that has none of its own.
 *
 * The ViewModels showed `e.message ?: e.javaClass.simpleName`; javaClass is the
 * JVM's, and the class's own simple name is the same answer on every platform.
 */
internal fun Throwable.readableMessage(): String =
    message ?: this::class.simpleName ?: toString()

/**
 * [block]'s result, or the exception it threw -- except cancellation, which is
 * how a scope says the screen has gone, and is not a failure to show anyone.
 *
 * The ViewModels caught Exception, cancellation included, and so wrote an error
 * into the state of a screen that no longer existed. Harmless there, since
 * nothing read it; rethrowing is what structured concurrency expects, and it
 * keeps a model's tests honest about what a cancelled load does.
 *
 * A Result rather than a callback and a null, because null is a real answer for
 * several sources -- an outfit that is not there -- and a failure must not be
 * mistaken for one.
 */
internal suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
