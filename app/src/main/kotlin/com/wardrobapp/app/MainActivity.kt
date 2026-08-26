package com.wardrobapp.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.canhub.cropper.CropImageContract
import com.wardrobapp.data.backupFilename
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.WardrobeLink
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.languageChoiceFor
import com.wardrobapp.presentation.languageTag
import com.wardrobapp.presentation.usesDarkColors
import java.io.File
import java.io.FileNotFoundException

/**
 * The one activity.
 *
 * An AppCompatActivity rather than a ComponentActivity for exactly one reason:
 * `AppCompatDelegate.setApplicationLocales` is how the language choice reaches
 * Android 12 and lower, and Google's own note is that with Compose it does not
 * work otherwise. Nothing else here uses AppCompat -- there is no action bar and
 * no AppCompat view in the tree.
 */
class MainActivity : AppCompatActivity() {

    /**
     * An address something outside the app asked it to import.
     *
     * Held here rather than in a ViewModel because it arrives as an intent, which
     * is the activity's business, and it is consumed by the garment form -- which
     * may not exist yet when it arrives.
     */
    private val pendingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.get(applicationContext)

        // Read before the first composition, not from within it: the colours have
        // to be right on the first frame, and a choice arriving afterwards is a
        // visible repaint of the whole app.
        val appearance = ThemePreference(this)

        // An address handed over from outside: a `wardrobapp://` link, or text
        // shared from a browser. A field rather than a local, because a second
        // link can arrive through onNewIntent while the app is already open.
        pendingLink.value = importUrlFrom(intent)

        setContent {
            // The one piece of app state held here rather than in a ViewModel.
            // It has to wrap the theme, and the theme wraps every screen -- so
            // there is nothing below it for a model to belong to.
            var theme by remember { mutableStateOf(appearance.choice) }

            WardrobappTheme(theme) {
                // The status and navigation bar icons, which are drawn by the
                // system and so are not covered by the colour scheme above.
                // `enableEdgeToEdge()` decides their appearance from the device's
                // dark mode setting, which is the wrong answer as soon as this app
                // is told to override it: dark icons on a dark app, unreadable.
                SystemBarIcons(dark = theme.usesDarkColors(isSystemInDarkTheme()))

                val navigator = rememberNavController()
                val entry by navigator.currentBackStackEntryAsState()
                val route = entry?.destination?.route

                // What the wardrobe should be showing when something else opens it:
                // a category tapped in the statistics chart, or the archived count
                // on the home screen. Held here rather than put in the route,
                // because the route is what the bottom bar matches the selected tab
                // on -- "wardrobe?category=tops" is a different string, and the bar
                // would go blank on arrival. Consumed once, by the wardrobe.
                var arrival by remember { mutableStateOf<WardrobeQuery?>(null) }

                fun openWardrobe(query: WardrobeQuery?) {
                    arrival = query
                    navigator.switchTo(WARDROBE)
                }

                // The garment the outfits tab should build around when something
                // else sends you there. Beside the navigator for the same reason
                // the wardrobe's arrival is: the bottom bar matches the selected
                // tab on the route, so the id cannot travel as part of one.
                var outfitSeed by remember { mutableStateOf<String?>(null) }

                fun buildOutfitAround(garmentId: String) {
                    outfitSeed = garmentId
                    navigator.switchTo(OUTFITS)
                }

                Scaffold(
                    // Lift the whole app above the keyboard.
                    //
                    // `adjustResize` in the manifest used to be enough, but it
                    // resizes the *window*, and `enableEdgeToEdge` above tells the
                    // window manager this app draws behind the system bars -- so
                    // the window no longer shrinks and the keyboard covers whatever
                    // was at the bottom of the screen, which on the form is the
                    // field being typed into.
                    //
                    // Here rather than on each screen: every screen is composed
                    // inside this one, so one padding lifts all of them, and a
                    // screen that grows a text field later cannot forget to do it.
                    // Applying the inset also *consumes* it, which is what stops
                    // the navigation bar below from adding its own padding on top --
                    // that would leave a bar-shaped gap between the keyboard and
                    // the content.
                    modifier = Modifier.imePadding(),
                    // Only on the top-level destinations: a garment's detail is
                    // somewhere you came *from* one of them, so the bar would
                    // offer to take you sideways out of it.
                    bottomBar = {
                        if (TABS.any { it.route == route }) {
                            NavigationBar {
                                for (tab in TABS) {
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = { navigator.switchTo(tab.route) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(stringResource(tab.labelRes)) },
                                    )
                                }
                            }
                        }
                    },
                ) { insets ->
                    // Asked once per launch, above every screen, because a newer
                    // build is not about the screen you happen to be on. The model
                    // does the asking when it is created; until it has an answer
                    // this composes nothing.
                    val updates: UpdateViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                UpdateViewModel(
                                    updates = AndroidAppUpdates(applicationContext),
                                    skipped = SkippedUpdate(applicationContext),
                                    installedVersionCode = appVersion().code,
                                )
                            }
                        },
                    )
                    val updateState by updates.state.collectAsStateWithLifecycle()

                    UpdateNotice(
                        state = updateState,
                        onInstall = updates::onInstallRequested,
                        onSkip = updates::onSkipRequested,
                        onDismiss = updates::onDismissed,
                        onFailureDismissed = updates::onFailureDismissed,
                    )

                    // Routed once, when it arrives. Not consumed here -- the form
                    // is what asks about it, and it has to exist first.
                    LaunchedEffect(pendingLink.value) {
                        // Single top: a link tapped while the form is already open
                        // should not stack a second copy of it.
                        if (pendingLink.value != null) {
                            navigator.navigate(GARMENT_ADD) { launchSingleTop = true }
                        }
                    }

                    NavHost(
                        navController = navigator,
                        startDestination = HOME,
                        modifier = Modifier.padding(insets),
                    ) {
                        composable(HOME) {
                            Home(
                                container = container,
                                onAddRequested = { navigator.navigate(GARMENT_ADD) },
                                // The plain wardrobe for what is in use, and the
                                // wardrobe with retired garments shown for the
                                // number that counts exactly those.
                                onWardrobeRequested = { openWardrobe(WardrobeQuery.showing(null)) },
                                onArchivedRequested = {
                                    openWardrobe(WardrobeQuery.showing(WardrobeLink.Retired))
                                },
                                // Tabs are switched to, not pushed: pushing one
                                // would stack a second copy of a screen the bar
                                // is already showing as selected.
                                onOutfitsRequested = { navigator.switchTo(OUTFITS) },
                                onStatisticsRequested = { navigator.switchTo(STATISTICS) },
                                onSettingsRequested = { navigator.switchTo(SETTINGS) },
                            )
                        }

                        composable(WARDROBE) {
                            Wardrobe(
                                container = container,
                                arrival = arrival,
                                onArrivalApplied = { arrival = null },
                                onGarmentOpened = { navigator.openGarment(it) },
                                onAddRequested = { navigator.navigate(GARMENT_ADD) },
                                onBulkAddRequested = { navigator.navigate(GARMENT_BULK_ADD) },
                            )
                        }

                        composable(SETTINGS) {
                            Settings(
                                container = container,
                                navigator = navigator,
                                theme = theme,
                                onThemeSelected = { choice ->
                                    // Stored and applied. Both, because the
                                    // preference is what the next launch reads and
                                    // the state is what this composition draws
                                    // from -- and unlike the language, nothing
                                    // recreates the activity to bridge them.
                                    appearance.choice = choice
                                    theme = choice
                                },
                            )
                        }

                        composable(OUTFITS) {
                            Outfits(
                                seedGarmentId = outfitSeed,
                                onSeedApplied = { outfitSeed = null },
                                container = container,
                                onGarmentOpened = { navigator.openGarment(it) },
                                onOutfitOpened = { navigator.navigate("$OUTFIT/${Uri.encode(it)}") },
                                onBuildRequested = { navigator.navigate(OUTFIT_BUILD) },
                            )
                        }

                        composable("$OUTFIT/{$OUTFIT_ID}") { backStackEntry ->
                            OutfitDetail(
                                container = container,
                                outfitId = backStackEntry.arguments?.getString(OUTFIT_ID).orEmpty(),
                                navigator = navigator,
                            )
                        }

                        composable(STATISTICS) {
                            Statistics(
                                container = container,
                                // A number counted here, shown as the garments
                                // behind it. What each link means is
                                // `WardrobeQuery.showing`'s business.
                                onLinkRequested = { openWardrobe(WardrobeQuery.showing(it)) },
                                onGarmentOpened = { navigator.openGarment(it) },
                            )
                        }

                        composable(GARMENT_ADD) {
                            GarmentForm(
                                container = container,
                                garmentId = null,
                                navigator = navigator,
                                // Taken rather than read: an address is offered
                                // once, and a second visit to this screen should
                                // not re-open the confirmation for a link that has
                                // already been answered.
                                sharedLink = pendingLink,
                            )
                        }

                        composable(GARMENT_BULK_ADD) {
                            BulkAdd(container = container, navigator = navigator)
                        }

                        composable(OUTFIT_BUILD) {
                            OutfitEdit(container = container, outfitId = null, navigator = navigator)
                        }

                        composable("$OUTFIT_EDIT/{$OUTFIT_ID}") { backStackEntry ->
                            OutfitEdit(
                                container = container,
                                outfitId = backStackEntry.arguments?.getString(OUTFIT_ID).orEmpty(),
                                navigator = navigator,
                            )
                        }

                        composable("$GARMENT_EDIT/{$GARMENT_ID}") { backStackEntry ->
                            GarmentForm(
                                container = container,
                                garmentId = backStackEntry.arguments?.getString(GARMENT_ID).orEmpty(),
                                navigator = navigator,
                            )
                        }

                        composable("$GARMENT/{$GARMENT_ID}") { backStackEntry ->
                            // Absent only if a route were built wrong, which the
                            // call sites rule out -- but a crash on a malformed
                            // link is not the answer either, so the detail model
                            // reports it as a garment that is not there.
                            GarmentDetail(
                                container = container,
                                garmentId = backStackEntry.arguments?.getString(GARMENT_ID).orEmpty(),
                                navigator = navigator,
                                onBuildOutfit = { buildOutfitAround(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Home(
        container: AppContainer,
        onAddRequested: () -> Unit,
        onWardrobeRequested: () -> Unit,
        onArchivedRequested: () -> Unit,
        onOutfitsRequested: () -> Unit,
        onStatisticsRequested: () -> Unit,
        onSettingsRequested: () -> Unit,
    ) {
        val model: HomeViewModel = viewModel(
            factory = viewModelFactory { initializer { HomeViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        HomeScreen(
            state = state,
            onAddRequested = onAddRequested,
            onWardrobeRequested = onWardrobeRequested,
            onArchivedRequested = onArchivedRequested,
            onOutfitsRequested = onOutfitsRequested,
            onStatisticsRequested = onStatisticsRequested,
            onSettingsRequested = onSettingsRequested,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Wardrobe(
        container: AppContainer,
        /** What another screen asked this one to show, if anything. */
        arrival: WardrobeQuery?,
        onArrivalApplied: () -> Unit,
        onGarmentOpened: (String) -> Unit,
        onAddRequested: () -> Unit,
        onBulkAddRequested: () -> Unit,
    ) {
        val model: WardrobeViewModel = viewModel(
            factory = viewModelFactory { initializer { WardrobeViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        // Applied once and then forgotten, so returning to this tab later shows the
        // wardrobe as it was left rather than re-applying a filter from a tap that
        // happened three screens ago. The model is reused across visits -- that is
        // what `restoreState` buys -- so nothing else clears it.
        LaunchedEffect(arrival) {
            if (arrival != null) {
                model.onQueryRequested(arrival)
                onArrivalApplied()
            }
        }

        WardrobeScreen(
            state = state,
            onSearchChanged = model::onSearchChanged,
            onSortToggled = model::onSortToggled,
            onRetry = model::refresh,
            onGarmentOpened = onGarmentOpened,
            onAddRequested = onAddRequested,
            onBulkAddRequested = onBulkAddRequested,
            onFiltersToggled = model::onFiltersToggled,
            onFiltersCleared = model::onFiltersCleared,
            onBrandTapped = model::onBrandTapped,
            onSizeTapped = model::onSizeTapped,
            onCategoryTapped = model::onCategoryTapped,
            onSubcategoryTapped = model::onSubcategoryTapped,
            onSeasonTapped = model::onSeasonTapped,
            onOccasionTapped = model::onOccasionTapped,
            onColorTapped = model::onColorTapped,
            onRetiredToggled = model::onRetiredToggled,
            onViewSelected = model::onViewSelected,
        )
    }

    @Composable
    private fun Settings(
        container: AppContainer,
        navigator: NavHostController,
        theme: ThemeChoice,
        onThemeSelected: (ThemeChoice) -> Unit,
    ) {
        val model: SettingsViewModel = viewModel(
            factory = viewModelFactory { initializer { SettingsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // The system document picker, so a backup can come from anywhere the
        // user can reach -- Downloads, a cloud provider, a file a friend sent --
        // without the app holding storage permissions. Nothing is granted or
        // asked for: the picker hands back a URI for the one document chosen.
        val opener = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                // Dismissed. Not a failure, and not worth a dialog.
                model.onRestoreDismissed()
            } else {
                model.onArchivePicked {
                    contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException(
                            getString(R.string.error_file_unreadable)
                        )
                }
            }
        }

        // The other direction: the picker creates the file and hands back a URI
        // to write into, so the app never needs to know or name a directory --
        // and the user's own choice of folder is the one it lands in.
        val creator = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri == null) {
                model.onBackupDismissed()
            } else {
                model.onBackupDestinationPicked {
                    contentResolver.openOutputStream(uri)
                        ?: throw FileNotFoundException(
                            getString(R.string.error_file_unwritable)
                        )
                }
            }
        }

        SettingsScreen(
            state = state,
            // Read from AppCompat rather than held in this app's own state, so
            // the picker cannot disagree with Android's per-app language screen
            // when the choice is changed there instead of here.
            language = languageChoiceFor(
                AppCompatDelegate.getApplicationLocales().toLanguageTags()
            ),
            theme = theme,
            onThemeSelected = onThemeSelected,
            onLanguageSelected = { choice ->
                // AppCompat persists this itself -- that is what the manifest's
                // locales service with autoStoreLocales is for -- and on Android
                // 12 and lower it recreates the activity, which is what makes the
                // screen come back in the new language. Nothing is written to
                // user_preferences: two records of one setting is how they end up
                // disagreeing.
                AppCompatDelegate.setApplicationLocales(
                    choice.languageTag
                        ?.let { LocaleListCompat.forLanguageTags(it) }
                        ?: LocaleListCompat.getEmptyLocaleList()
                )
            },
            // Remembered: it is a PackageManager query, and asking again on every
            // recomposition is an IPC round trip for a string that cannot change
            // while the app is running.
            version = remember { appVersion() },
            // Named the way the app this replaced names its own backups: its
            // Settings screen lists them by that prefix, so one written here
            // into the same folder shows up there.
            onBackupRequested = { creator.launch(backupFilename(System.currentTimeMillis())) },
            onBackupDismissed = model::onBackupDismissed,
            onRestoreRequested = model::onRestoreRequested,
            // Providers disagree about what a .zip is: some report
            // application/zip, some application/octet-stream, and some nothing
            // at all. Filtering on the type would hide the user's own backup
            // from them, so every type is offered and the archive decides.
            onRestoreConfirmed = { opener.launch(arrayOf("*/*")) },
            onRestoreDismissed = model::onRestoreDismissed,
            onTidyRequested = model::onTidyRequested,
            onTidyDismissed = model::onTidyDismissed,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Outfits(
        container: AppContainer,
        /** A garment another screen asked this one to build around, if any. */
        seedGarmentId: String?,
        onSeedApplied: () -> Unit,
        onGarmentOpened: (String) -> Unit,
        onOutfitOpened: (String) -> Unit,
        onBuildRequested: () -> Unit,
    ) {
        val model: OutfitsViewModel = viewModel(
            factory = viewModelFactory { initializer { OutfitsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        // Applied once and then forgotten, so coming back to this tab later shows
        // the batch as it was left rather than re-running a request from a garment
        // screen three visits ago. Asking for it also generates, since arriving on
        // a screen that shows nothing until you tap a button would answer "what
        // goes with this?" with a blank page.
        LaunchedEffect(seedGarmentId) {
            if (seedGarmentId != null) {
                model.onSeedRequested(seedGarmentId)
                onSeedApplied()
            }
        }

        OutfitsScreen(
            state = state,
            onSeasonTapped = model::onSeasonTapped,
            onOccasionTapped = model::onOccasionTapped,
            onGenerate = model::generate,
            onSeedCleared = model::onSeedCleared,
            onKeep = model::onKeepRequested,
            onKeepDismissed = model::onKeepDismissed,
            onArchivedToggled = model::onArchivedToggled,
            onSave = model::onSaveRequested,
            onRate = model::onRated,
            onPinToggled = model::onPinToggled,
            onDeleteRequested = model::onDeleteRequested,
            onDeleteConfirmed = model::onDeleteConfirmed,
            onDeleteDismissed = model::onDeleteDismissed,
            onGarmentOpened = onGarmentOpened,
            onOutfitOpened = onOutfitOpened,
            onBuildRequested = onBuildRequested,
        )
    }

    @Composable
    private fun GarmentForm(
        container: AppContainer,
        garmentId: String?,
        navigator: NavHostController,
        /** An address from outside, waiting to be offered. Null when adding normally. */
        sharedLink: MutableState<String?>? = null,
    ) {
        val model: GarmentFormViewModel = viewModel(
            factory = viewModelFactory {
                initializer { GarmentFormViewModel(container, garmentId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Every photo comes in through here, from the gallery or from the camera:
        // both launchers below hand their result to the crop screen, and only what
        // comes back from it is stored. The ratio is fixed at 3:4, which is the
        // shape every screen shows a garment photo in -- see cropTo3by4.
        val cropOutput = remember { cropDestination() }
        val colors = MaterialTheme.colorScheme
        val cropper = rememberLauncherForActivityResult(CropImageContract()) { result ->
            when {
                result.isSuccessful -> result.uriContent?.let(model::onPhotoPicked)
                // Cancelling is not a failure: it means no photo, the same as
                // backing out of the picker, and it says so by having no error.
                result.error != null -> model.onCropFailed()
                else -> Unit
            }
        }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> uri?.let { cropper.launch(cropTo3by4(it, cropOutput, colors)) } }

        // Where the camera will write, minted per composition rather than per tap:
        // the contract needs the destination before it launches, and a file that
        // changed between launching and answering would leave the photo unread.
        val destination = remember { cameraDestination() }
        val camera = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { taken ->
            // False means dismissed, or a camera app that wrote nothing. Neither is
            // a failure worth a dialog; the file is cleaned up either way once the
            // photo has been stored.
            if (taken) cropper.launch(cropTo3by4(destination, cropOutput, colors))
        }

        // Leaving is the activity's business, not the model's: the model reports
        // that it saved, and this is what that means for the back stack.
        LaunchedEffect(state.saved) {
            if (state.saved) navigator.popBackStack()
        }

        // Handed to the model once and then cleared, so rotating the phone or
        // coming back to this screen does not ask about the same link again.
        val pending = sharedLink?.value
        LaunchedEffect(pending) {
            if (pending != null) {
                model.onSharedLinkReceived(pending)
                // Safe-call assignment: `pending` being non-null implies the
                // state is there, but only to a reader.
                sharedLink?.value = null
            }
        }

        GarmentFormScreen(
            state = state,
            isEditing = model.isEditing,
            brandSuggestions = model::suggestionsFor,
            onBack = { navigator.popBackStack() },
            onAddPhoto = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onTakePhoto = {
                try {
                    camera.launch(destination)
                } catch (_: ActivityNotFoundException) {
                    // A device with no camera app at all. Rare, and better said
                    // than crashed on -- which is what launching an unhandled
                    // intent does.
                    model.onCameraUnavailable()
                }
            },
            onPhotoSelected = model::onPhotoSelected,
            onPhotoRemoved = model::onPhotoRemoved,
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onCategorySelected = model::onCategorySelected,
            onSubcategoryToggled = model::onSubcategoryToggled,
            onSeasonToggled = model::onSeasonToggled,
            onColorToggled = model::onColorToggled,
            onBrandChanged = model::onBrandChanged,
            onSizeChanged = model::onSizeChanged,
            onTagsChanged = model::onTagsChanged,
            onSave = { model.onSaveRequested() },
            onSaveAnyway = { model.onSaveRequested(force = true) },
            onDuplicatesDismissed = model::onDuplicateWarningDismissed,
            onErrorDismissed = model::onErrorDismissed,
            onImportUrlChanged = model::onImportUrlChanged,
            onImportRequested = model::onImportRequested,
            onSharedLinkConfirmed = model::onSharedLinkConfirmed,
            onSharedLinkDismissed = model::onSharedLinkDismissed,
            onImportProblemDismissed = model::onImportProblemDismissed,
        )
    }

    /**
     * Cataloguing several garments at once.
     *
     * The picker takes many photos and none of them goes through the crop screen on
     * the way in: thirty crop screens in a row is the tedium this exists to remove.
     * Cropping is offered per garment in the queue instead, beside background
     * removal, so it is a tap where it is wanted rather than a gate on every photo.
     */
    @Composable
    private fun BulkAdd(container: AppContainer, navigator: NavHostController) {
        val model: BulkAddViewModel = viewModel(
            factory = viewModelFactory { initializer { BulkAddViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(BulkAddState.MAX_PHOTOS)
        ) { uris -> model.onPhotosPicked(uris) }

        // The same crop screen the form uses, at the same fixed 3:4. Minted per
        // composition, as there: the contract needs its destination before it
        // launches.
        val cropOutput = remember { cropDestination() }
        val colors = MaterialTheme.colorScheme
        val cropper = rememberLauncherForActivityResult(CropImageContract()) { result ->
            when {
                result.isSuccessful -> result.uriContent?.let(model::onPhotoCropped)
                // Cancelling is not a failure: it means the photo is fine as it is.
                result.error != null -> model.onCropFailed()
                else -> Unit
            }
        }

        BulkAddScreen(
            state = state,
            onBack = { navigator.popBackStack() },
            onChoosePhotos = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onCategorySelected = model::onCategorySelected,
            onSubcategoryToggled = model::onSubcategoryToggled,
            onBrandChanged = model::onBrandChanged,
            // Always the photo rather than the cut-out on top of it: a crop of a
            // cut-out is a crop of a smaller photo, and the framing being chosen is
            // the garment's own.
            onCrop = {
                state.queue.current?.let { draft ->
                    cropper.launch(cropTo3by4(draft.imageUri.toUri(), cropOutput, colors))
                }
            },
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onSave = model::onSaveRequested,
            onSkip = model::onSkipRequested,
            onErrorDismissed = model::onErrorDismissed,
        )
    }

    /**
     * Building an outfit by hand, or changing one already saved.
     *
     * One screen and one model for both, told apart by whether there is an id --
     * the same shape as the garment form, and for the same reason: two of them
     * would be two that drift.
     */
    @Composable
    private fun OutfitEdit(
        container: AppContainer,
        outfitId: String?,
        navigator: NavHostController,
    ) {
        val model: OutfitEditViewModel = viewModel(
            factory = viewModelFactory {
                initializer { OutfitEditViewModel(container, outfitId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Leaving is the activity's business: the model reports that it wrote the
        // row, and this is what that means for the back stack.
        LaunchedEffect(state.saved) {
            if (state.saved) navigator.popBackStack()
        }

        OutfitEditScreen(
            state = state,
            isEditing = model.isEditing,
            onBack = { navigator.popBackStack() },
            onNameChanged = model::onNameChanged,
            onSearchChanged = model::onSearchChanged,
            onGarmentToggled = model::onGarmentToggled,
            onOccasionTapped = model::onOccasionTapped,
            onSeasonTapped = model::onSeasonTapped,
            onSave = model::onSaveRequested,
            onErrorDismissed = model::onErrorDismissed,
        )
    }

    @Composable
    private fun OutfitDetail(
        container: AppContainer,
        outfitId: String,
        navigator: NavHostController,
    ) {
        val model: OutfitDetailViewModel = viewModel(
            factory = viewModelFactory {
                initializer { OutfitDetailViewModel(container, outfitId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Leaving is the activity's business, not the model's, as with the form
        // reporting that it saved and the garment detail reporting a deletion.
        LaunchedEffect(state.deleted) {
            if (state.deleted) navigator.popBackStack()
        }

        OutfitDetailScreen(
            state = state,
            onBack = { navigator.popBackStack() },
            onGarmentOpened = { navigator.openGarment(it) },
            onRate = model::onRated,
            onEdit = { navigator.navigate("$OUTFIT_EDIT/${Uri.encode(outfitId)}") },
            onDelete = model::onDeleteRequested,
            onDeleteConfirmed = model::onDeleteConfirmed,
            onDeleteDismissed = model::onDeleteDismissed,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Statistics(
        container: AppContainer,
        onLinkRequested: (WardrobeLink?) -> Unit,
        onGarmentOpened: (String) -> Unit,
    ) {
        val model: StatisticsViewModel = viewModel(
            factory = viewModelFactory { initializer { StatisticsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Re-read on the way back, as the analytics tab did: retiring a garment
        // from its detail screen moves two of these tiles, and a stale page would
        // disagree with the wardrobe you just came from.
        RefreshOnReturn(model::refresh)

        StatisticsScreen(
            state = state,
            onCategoryTapped = model::onCategoryTapped,
            onLinkRequested = onLinkRequested,
            onGarmentOpened = onGarmentOpened,
            onBrandSortChanged = model::onBrandSortChanged,
            onSectionTapped = model::onSectionTapped,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun GarmentDetail(
        container: AppContainer,
        garmentId: String,
        navigator: NavHostController,
        onBuildOutfit: (String) -> Unit,
    ) {
        val model: GarmentDetailViewModel = viewModel(
            factory = viewModelFactory {
                initializer { GarmentDetailViewModel(container, garmentId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Leaving is the activity's business, not the model's: the model reports
        // that the garment is gone, and this is what that means for the back
        // stack. Same shape as the form reporting that it saved.
        LaunchedEffect(state.deleted) {
            if (state.deleted) navigator.popBackStack()
        }

        GarmentDetailScreen(
            state = state,
            onBack = { navigator.popBackStack() },
            onPhotoSelected = model::onPhotoSelected,
            onEdit = { navigator.navigate("$GARMENT_EDIT/${Uri.encode(garmentId)}") },
            onRetry = model::refresh,
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onBuildOutfit = { onBuildOutfit(garmentId) },
            onRetire = model::onRetireRequested,
            onReturnToWardrobe = model::onReturnedToWardrobe,
            onDelete = model::onDeleteRequested,
            onConfirmed = model::onConfirmed,
            onConfirmationDismissed = model::onConfirmationDismissed,
            onActionErrorDismissed = model::onActionErrorDismissed,
        )
    }

    /**
     * Re-read the database whenever a screen comes back to the front.
     *
     * The tab screens' models outlive a trip away from them -- that is what
     * `saveState` is for -- so without this, a garment added, edited or restored
     * elsewhere would not appear until the app was restarted. It showed up first
     * as a garment saved from the form and still missing from the list behind it.
     *
     * The first resume reloads what the model's own construction already loaded.
     * That is a wasted query against a local database, taken deliberately: the
     * alternative is for a screen to depend on the effect firing to show
     * anything at all.
     */
    @Composable
    private fun RefreshOnReturn(refresh: () -> Unit) {
        LifecycleResumeEffect(Unit) {
            refresh()
            onPauseOrDispose { }
        }
    }

    /**
     * Light or dark icons in the system bars.
     *
     * The bars themselves are transparent -- that is what `enableEdgeToEdge` did --
     * so what is left to decide is whether the clock and the back gesture hint are
     * drawn dark (for a light app) or light (for a dark one). That call is made
     * from the app's own resolved theme rather than the device's, which is the
     * whole point: a phone in light mode showing this app in dark would otherwise
     * put dark icons on a dark background.
     *
     * A SideEffect because it mutates the window, which is not Compose state: it
     * has to run after a successful composition and re-run whenever the answer
     * changes.
     */
    @Composable
    private fun SystemBarIcons(dark: Boolean) {
        SideEffect {
            WindowInsetsControllerCompat(window, window.decorView).run {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    /**
     * A file for the camera to write into, as a URI it is allowed to use.
     *
     * In the cache, because this is the full-resolution original and the wardrobe
     * keeps its own scaled copy: once the photo is stored this file is spent. One
     * fixed name rather than one per capture, so the next photo overwrites it and
     * at most one is ever lying around for the system to reclaim.
     *
     * Through FileProvider because handing a camera app a `file://` path has thrown
     * FileUriExposedException since Android 7.
     */
    private fun cameraDestination(): Uri {
        val directory = File(cacheDir, "camera").also { it.mkdirs() }
        val file = File(directory, "capture.jpg")
        return FileProvider.getUriForFile(this, "$packageName.camera", file)
    }

    /**
     * A file for the crop screen to write into, on the same terms.
     *
     * Its own directory rather than the camera's, so a capture and the cropped copy
     * of it are never the same file being read and written at once. Otherwise the
     * reasoning above applies unchanged: spent once stored, one fixed name, and a
     * provider URI because a `file://` one handed to an activity is a
     * FileUriExposedException waiting to happen.
     */
    private fun cropDestination(): Uri {
        val directory = File(cacheDir, "crop").also { it.mkdirs() }
        val file = File(directory, "cropped.jpg")
        return FileProvider.getUriForFile(this, "$packageName.camera", file)
    }

    /**
     * A second link, while the app is already open.
     *
     * Without this the activity keeps the intent it was created with and a link
     * tapped now does nothing -- the launcher brings the existing task forward
     * rather than starting a new activity. Setting the intent is what makes the
     * value read in `onCreate` see it, since that read happens again on
     * recreation.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importUrlFrom(intent)?.let { pendingLink.value = it }
    }

    /**
     * The address an intent is asking to import, if any.
     *
     * Two shapes, because there are two ways in. A `wardrobapp://...?importUrl=`
     * link is what the app this replaced handles, and keeping it means a QR code or a
     * link that works there works here. A plain `ACTION_SEND` of text is the one
     * people actually use: the share sheet from a browser, which the manifest also
     * declares.
     *
     * Nothing is validated here -- that is the form's job, and it has to be, since
     * a refusal is something to show a person rather than to swallow at the door.
     */
    private fun importUrlFrom(intent: Intent?): String? {
        if (intent == null) return null

        val fromDeepLink = intent.data
            ?.takeIf { it.scheme == APP_SCHEME }
            ?.getQueryParameter(IMPORT_URL)
        if (!fromDeepLink.isNullOrBlank()) return fromDeepLink

        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) return shared
        }

        return null
    }

    /** What the installed package says this build is. */
    private fun appVersion(): AppVersion {
        val info = packageManager.getPackageInfo(packageName, 0)
        return AppVersion(
            name = info.versionName ?: "unknown",
            // Through the compat helper because the long form arrived in API 28
            // and this app supports 24.
            code = PackageInfoCompat.getLongVersionCode(info),
        )
    }

    /**
     * Open a garment.
     *
     * The id is encoded rather than concatenated: it is opaque, and one
     * containing a separator would otherwise navigate somewhere else entirely.
     */
    private fun NavHostController.openGarment(id: String) {
        navigate("$GARMENT/${Uri.encode(id)}")
    }

    /**
     * Move between tabs without stacking them.
     *
     * Without this, switching tabs four times leaves four entries on the stack
     * and back walks through the history instead of leaving -- and each visit
     * builds a second copy of the screen's state.
     */
    private fun NavHostController.switchTo(route: String) {
        navigate(route) {
            popUpTo(graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // The label is a resource id rather than a string because this list is built
    // once, outside any composition, and a string would freeze the language it was
    // built in.
    private data class Tab(
        val route: String,
        @StringRes val labelRes: Int,
        val icon: ImageVector,
    )

    private companion object {
        const val HOME = "home"
        const val WARDROBE = "wardrobe"
        const val OUTFITS = "outfits"
        const val STATISTICS = "statistics"
        const val SETTINGS = "settings"
        const val GARMENT = "garment"
        const val OUTFIT = "outfit"
        const val OUTFIT_ID = "outfitId"
        // Deliberately not "garment/add" and "garment/edit": the detail route is
        // "garment/{garmentId}", which would match both of them as an id, and
        // whether it wins is down to how the matcher ranks a literal segment
        // against an argument. Distinct prefixes leave nothing to rank.
        // Built by hand rather than suggested, and changing one already saved.
        // Distinct prefixes for the same reason the garment routes have them: the
        // outfit detail route is "outfit/{outfitId}", which would match a literal
        // segment as an id.
        const val OUTFIT_BUILD = "build-outfit"
        const val OUTFIT_EDIT = "edit-outfit"

        const val GARMENT_ADD = "add-garment"
        const val GARMENT_BULK_ADD = "add-garments"
        const val GARMENT_EDIT = "edit-garment"
        const val GARMENT_ID = "garmentId"

        /**
         * The scheme and parameter the app this replaced already answers to.
         *
         * Kept identical on purpose: a QR code or a saved link made for the app
         * this replaced opens this one the same way, so nothing anyone has lying
         * around stopped working when the app changed underneath it.
         */
        const val APP_SCHEME = "wardrobapp"
        const val IMPORT_URL = "importUrl"

        val TABS = listOf(
            Tab(HOME, R.string.tab_home, Icons.Filled.Home),
            Tab(WARDROBE, R.string.tab_wardrobe, Icons.Filled.List),
            Tab(OUTFITS, R.string.tab_outfits, Icons.Filled.Star),
            Tab(STATISTICS, R.string.tab_statistics, Icons.Filled.Info),
            // A tab rather than somewhere you go and come back from. What is in
            // it -- the theme, the language, backups, storage -- is not a
            // detour off one screen, and it was reached through a gear on the
            // wardrobe's bar, which put it behind a screen it has nothing to do
            // with.
            Tab(SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
        )
    }
}
