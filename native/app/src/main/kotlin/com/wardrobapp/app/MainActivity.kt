package com.wardrobapp.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.LocaleListCompat
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
import com.wardrobapp.data.backupFilename
import com.wardrobapp.presentation.languageChoiceFor
import com.wardrobapp.presentation.languageTag
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.get(applicationContext)

        setContent {
            WardrobappTheme {
                val navigator = rememberNavController()
                val entry by navigator.currentBackStackEntryAsState()
                val route = entry?.destination?.route

                Scaffold(
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
                    NavHost(
                        navController = navigator,
                        startDestination = HOME,
                        modifier = Modifier.padding(insets),
                    ) {
                        composable(HOME) {
                            Home(
                                container = container,
                                onAddRequested = { navigator.navigate(GARMENT_ADD) },
                                // Tabs are switched to, not pushed: pushing one
                                // would stack a second copy of a screen the bar
                                // is already showing as selected.
                                onOutfitsRequested = { navigator.switchTo(OUTFITS) },
                                onAnalyticsRequested = { navigator.switchTo(ANALYTICS) },
                                onStatisticsRequested = { navigator.navigate(STATISTICS) },
                                onSettingsRequested = { navigator.navigate(SETTINGS) },
                            )
                        }

                        composable(WARDROBE) {
                            Wardrobe(
                                container = container,
                                onGarmentOpened = { navigator.openGarment(it) },
                                onAddRequested = { navigator.navigate(GARMENT_ADD) },
                                onSettingsRequested = { navigator.navigate(SETTINGS) },
                            )
                        }

                        composable(SETTINGS) {
                            Settings(container, navigator = navigator)
                        }

                        composable(OUTFITS) {
                            Outfits(
                                container = container,
                                onGarmentOpened = { navigator.openGarment(it) },
                                onOutfitOpened = { navigator.navigate("$OUTFIT/${Uri.encode(it)}") },
                            )
                        }

                        composable("$OUTFIT/{$OUTFIT_ID}") { backStackEntry ->
                            OutfitDetail(
                                container = container,
                                outfitId = backStackEntry.arguments?.getString(OUTFIT_ID).orEmpty(),
                                navigator = navigator,
                            )
                        }

                        composable(ANALYTICS) {
                            Analytics(
                                container = container,
                                onStatisticsRequested = { navigator.navigate(STATISTICS) },
                            )
                        }

                        composable(STATISTICS) {
                            Statistics(container, navigator = navigator)
                        }

                        composable(GARMENT_ADD) {
                            GarmentForm(container, garmentId = null, navigator = navigator)
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
        onOutfitsRequested: () -> Unit,
        onAnalyticsRequested: () -> Unit,
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
            onOutfitsRequested = onOutfitsRequested,
            onAnalyticsRequested = onAnalyticsRequested,
            onStatisticsRequested = onStatisticsRequested,
            onSettingsRequested = onSettingsRequested,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Wardrobe(
        container: AppContainer,
        onGarmentOpened: (String) -> Unit,
        onAddRequested: () -> Unit,
        onSettingsRequested: () -> Unit,
    ) {
        val model: WardrobeViewModel = viewModel(
            factory = viewModelFactory { initializer { WardrobeViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        WardrobeScreen(
            state = state,
            onSearchChanged = model::onSearchChanged,
            onSortToggled = model::onSortToggled,
            onRetry = model::refresh,
            onGarmentOpened = onGarmentOpened,
            onAddRequested = onAddRequested,
            onSettingsRequested = onSettingsRequested,
            onFiltersToggled = model::onFiltersToggled,
            onFiltersCleared = model::onFiltersCleared,
            onBrandChanged = model::onBrandChanged,
            onSizeChanged = model::onSizeChanged,
            onCategoryTapped = model::onCategoryTapped,
            onSubcategoryTapped = model::onSubcategoryTapped,
            onSeasonTapped = model::onSeasonTapped,
            onOccasionTapped = model::onOccasionTapped,
            onColorTapped = model::onColorTapped,
            onRetiredToggled = model::onRetiredToggled,
        )
    }

    @Composable
    private fun Settings(container: AppContainer, navigator: NavHostController) {
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
            onBack = { navigator.popBackStack() },
            // Named the way the app that ships names its own backups: its
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
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Outfits(
        container: AppContainer,
        onGarmentOpened: (String) -> Unit,
        onOutfitOpened: (String) -> Unit,
    ) {
        val model: OutfitsViewModel = viewModel(
            factory = viewModelFactory { initializer { OutfitsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        OutfitsScreen(
            state = state,
            onSeasonTapped = model::onSeasonTapped,
            onOccasionTapped = model::onOccasionTapped,
            onGenerate = model::generate,
            onSave = model::onSaveRequested,
            onRate = model::onRated,
            onPinToggled = model::onPinToggled,
            onDeleteRequested = model::onDeleteRequested,
            onDeleteConfirmed = model::onDeleteConfirmed,
            onDeleteDismissed = model::onDeleteDismissed,
            onGarmentOpened = onGarmentOpened,
            onOutfitOpened = onOutfitOpened,
        )
    }

    @Composable
    private fun GarmentForm(
        container: AppContainer,
        garmentId: String?,
        navigator: NavHostController,
    ) {
        val model: GarmentFormViewModel = viewModel(
            factory = viewModelFactory {
                initializer { GarmentFormViewModel(container, garmentId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // Whether the next photo replaces the selected one or joins the strip.
        // Held here because a launcher callback cannot be told anything the launch
        // did not put somewhere first.
        var replaceCurrent by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> uri?.let { model.onPhotoPicked(it, replaceCurrent) } }

        // Where the camera app is about to write. Kept across the launch because
        // TakePicture reports only success, not the URI it was given.
        var capture by remember { mutableStateOf<Uri?>(null) }

        val camera = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { taken ->
            val target = capture
            capture = null
            if (taken && target != null) model.onPhotoPicked(target, replaceCurrent)
        }

        // Leaving is the activity's business, not the model's: the model reports
        // that it saved, and this is what that means for the back stack.
        LaunchedEffect(state.saved) {
            if (state.saved) navigator.popBackStack()
        }

        GarmentFormScreen(
            state = state,
            isEditing = model.isEditing,
            brandSuggestions = model::suggestionsFor,
            onBack = { navigator.popBackStack() },
            onAddPhoto = {
                replaceCurrent = false
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onTakePhoto = {
                replaceCurrent = false
                capture = newCaptureUri()
                capture?.let(camera::launch)
            },
            onRetakePhoto = {
                replaceCurrent = true
                capture = newCaptureUri()
                capture?.let(camera::launch)
            },
            onPhotoMoved = model::onPhotoMoved,
            onCoverSelected = model::onCoverSelected,
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
            onDelete = model::onDeleteRequested,
            onDeleteConfirmed = model::onDeleteConfirmed,
            onDeleteDismissed = model::onDeleteDismissed,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Analytics(container: AppContainer, onStatisticsRequested: () -> Unit) {
        val model: AnalyticsViewModel = viewModel(
            factory = viewModelFactory { initializer { AnalyticsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        RefreshOnReturn(model::refresh)

        AnalyticsScreen(
            state = state,
            onStatisticsRequested = onStatisticsRequested,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Statistics(container: AppContainer, navigator: NavHostController) {
        val model: StatisticsViewModel = viewModel(
            factory = viewModelFactory { initializer { StatisticsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        StatisticsScreen(
            state = state,
            onBack = { navigator.popBackStack() },
            onCategoryTapped = model::onCategoryTapped,
            onBrandSortChanged = model::onBrandSortChanged,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun GarmentDetail(
        container: AppContainer,
        garmentId: String,
        navigator: NavHostController,
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
     * A file for a camera app to write the next photo into, as a content URI.
     *
     * In the cache, because it only has to live until the photo pipeline has read,
     * turned and scaled it into the wardrobe -- and if the app dies in between,
     * Android is welcome to the space.
     *
     * Through FileProvider rather than a `file://` URI: passing one of those to
     * another app has thrown FileUriExposedException since Android 7, and this app
     * supports 24.
     */
    private fun newCaptureUri(): Uri? = runCatching {
        val directory = File(cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("capture-", ".jpg", directory)
        FileProvider.getUriForFile(this, "$packageName.photos", file)
    }.getOrNull()

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
        const val ANALYTICS = "analytics"
        const val STATISTICS = "statistics"
        const val SETTINGS = "settings"
        const val GARMENT = "garment"
        const val OUTFIT = "outfit"
        const val OUTFIT_ID = "outfitId"
        // Deliberately not "garment/add" and "garment/edit": the detail route is
        // "garment/{garmentId}", which would match both of them as an id, and
        // whether it wins is down to how the matcher ranks a literal segment
        // against an argument. Distinct prefixes leave nothing to rank.
        const val GARMENT_ADD = "add-garment"
        const val GARMENT_EDIT = "edit-garment"
        const val GARMENT_ID = "garmentId"

        val TABS = listOf(
            Tab(HOME, R.string.tab_home, Icons.Filled.Home),
            Tab(WARDROBE, R.string.tab_wardrobe, Icons.Filled.List),
            Tab(OUTFITS, R.string.tab_outfits, Icons.Filled.Star),
            Tab(ANALYTICS, R.string.tab_analytics, Icons.Filled.Info),
        )
    }
}
