package com.wardrobapp.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.wardrobapp.api.HttpProfiles
import com.wardrobapp.api.Profiles
import com.wardrobapp.api.Routes
import com.wardrobapp.api.speakWardrobe
import com.wardrobapp.presentation.profileToOpen
import com.wardrobapp.ui.ProfileEntry
import com.wardrobapp.ui.ProfilePickerScreen
import com.wardrobapp.ui.WardrobappTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.w3c.dom.url.URL

/**
 * What Settings needs to show the open profile and move between them.
 *
 * Gathered here rather than handed down as eight parameters, and built by
 * [ProfileGate], which owns the answers: the screens below it only ask.
 */
class ProfileControls(
    val current: ProfileEntry,
    val all: List<ProfileEntry>,
    val isYours: Boolean,
    val signedIn: Boolean,
    val switchTo: (String) -> Unit,
    val rename: (String) -> Unit,
    val makeYours: () -> Unit,
    val create: (String) -> Unit,
    /** Delete the profile showing, and open another. */
    val delete: () -> Unit,
)

/**
 * Which wardrobe the browser shows, before it shows anything.
 *
 * Asks the server for its profiles at the page's root, with [root], the one
 * client that is not inside a profile. Then either opens one -- see
 * profileToOpen for which -- or asks whose wardrobe this is. The app itself is
 * given a client whose base is that profile's path, so every request it makes,
 * and every photo it loads, is that profile's without a screen ever naming it.
 *
 * Keyed on the profile, so switching starts the app again from Home with
 * nothing of the last profile's on screen -- no list, no half-filled form, no
 * model still loading the other wardrobe.
 */
@Composable
fun ProfileGate(root: HttpClient) {
    val profiles = remember(root) { HttpProfiles(root) }
    val scope = rememberCoroutineScope()

    var answer by remember { mutableStateOf<Profiles?>(null) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableIntStateOf(0) }

    LaunchedEffect(asked) {
        failed = false
        try {
            val read = profiles.list()
            answer = read
            val ids = read.profiles.map { it.id }
            if (open == null || open !in ids) {
                open = profileToOpen(ids, read.yours, read.signedIn, LastProfile.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    fun show(id: String) {
        LastProfile.id = id
        open = id
    }

    /** Do [change] to the profiles, then read them again; a failure shows on the picker, or is retried. */
    fun change(then: (suspend () -> Unit)? = null, change: suspend HttpProfiles.() -> Unit) {
        scope.launch {
            try {
                profiles.change()
                then?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
            asked++
        }
    }

    val read = answer
    val id = open
    if (read == null || id == null) {
        // Nothing until the first answer: a picker that flashed up and
        // vanished as the person's own profile arrived would be worse.
        if (read == null && !failed) return
        WardrobappTheme(ThemePreference.choice) {
            ProfilePickerScreen(
                profiles = read?.profiles.orEmpty().map { ProfileEntry(it.id, it.name) },
                failed = failed,
                onPick = { picked ->
                    if (read?.signedIn == true) change { makeYours(picked) }
                    show(picked)
                },
                onCreate = { name ->
                    change { show(create(name, yours = read?.signedIn == true).id) }
                },
                onRetry = { asked++ },
            )
        }
        return
    }

    val entries = read.profiles.map { ProfileEntry(it.id, it.name) }
    // A profile made a moment ago is opened before the list that has it
    // arrives; until it does there is nothing to name, so nothing is shown.
    val current = entries.firstOrNull { it.id == id } ?: return
    val controls = ProfileControls(
        current = current,
        all = entries,
        isYours = read.yours == id,
        signedIn = read.signedIn,
        switchTo = ::show,
        rename = { name -> change { rename(id, name) } },
        makeYours = { change { makeYours(id) } },
        create = { name -> change { show(create(name, yours = false).id) } },
        delete = {
            change {
                delete(id)
                // Straight to the next, before the list is read again, so the
                // app keyed on the deleted one is not left asking a server
                // that no longer has it. Chosen as on opening the page, with
                // the deleted one out of it: this person's own if they have
                // another, otherwise the picker (signed in) or the only one
                // left. Not remembered, the way picking one is; the next
                // visit chooses for itself again.
                open = profileToOpen(
                    ids = read.profiles.map { it.id } - id,
                    yours = read.yours.takeIf { it != id },
                    signedIn = read.signedIn,
                    lastOpened = LastProfile.id.takeIf { it != id },
                )
            }
        },
    )

    key(id) {
        val http = remember {
            HttpClient(Js) { speakWardrobe(URL(Routes.profileBase(id), document.baseURI).href) }
        }
        DisposableEffect(http) { onDispose { http.close() } }
        WebApp(http, controls)
    }
}
