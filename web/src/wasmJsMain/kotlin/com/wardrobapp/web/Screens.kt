package com.wardrobapp.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.wardrobapp.api.HttpBulkAddSource
import com.wardrobapp.api.HttpGarmentDetailSource
import com.wardrobapp.api.HttpGarmentFormSource
import com.wardrobapp.api.HttpGarmentImporter
import com.wardrobapp.api.HttpHomeSource
import com.wardrobapp.api.HttpOutfitDetailSource
import com.wardrobapp.api.HttpOutfitEditSource
import com.wardrobapp.api.HttpOutfitsSource
import com.wardrobapp.api.HttpPhotos
import com.wardrobapp.api.HttpStatisticsSource
import com.wardrobapp.api.HttpStorageSource
import com.wardrobapp.api.HttpWardrobeSource
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.api.SyncPairing
import com.wardrobapp.data.ArchivePreview
import com.wardrobapp.data.BackupSummary
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.presentation.BULK_ADD_MINIMUM
import com.wardrobapp.presentation.BulkAddScreenModel
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.FirstStep
import com.wardrobapp.presentation.GarmentDetailScreenModel
import com.wardrobapp.presentation.GarmentFormScreenModel
import com.wardrobapp.presentation.HomeScreenModel
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.OutfitDetailScreenModel
import com.wardrobapp.presentation.OutfitEditScreenModel
import com.wardrobapp.presentation.OutfitsScreenModel
import com.wardrobapp.presentation.SettingsScreenModel
import com.wardrobapp.presentation.SettingsSource
import com.wardrobapp.presentation.StatisticsScreenModel
import com.wardrobapp.presentation.StorageFigures
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.WardrobeLink
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeScreenModel
import com.wardrobapp.presentation.firstStepsFor
import com.wardrobapp.ui.AppVersion
import com.wardrobapp.ui.isExpanded
import com.wardrobapp.ui.OutfitIdeas
import com.wardrobapp.ui.GarmentDetailPane
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.ui.BulkAddScreen
import com.wardrobapp.ui.GarmentDetailScreen
import com.wardrobapp.ui.GarmentFormScreen
import com.wardrobapp.ui.HomeScreen
import com.wardrobapp.ui.OutfitDetailScreen
import com.wardrobapp.ui.OutfitEditScreen
import com.wardrobapp.ui.OutfitsScreen
import com.wardrobapp.ui.SettingsScreen
import com.wardrobapp.ui.StatisticsScreen
import com.wardrobapp.ui.SyncPairingSection
import com.wardrobapp.ui.WardrobeScreen
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import com.wardrobapp.ui.ProfileSection

/** Every screen's source, answered by the server this page came from. */
class WebSources(http: HttpClient) {
    val home = HttpHomeSource(http)
    val wardrobe = HttpWardrobeSource(http)
    val outfits = HttpOutfitsSource(http)
    val outfitDetail = HttpOutfitDetailSource(http)
    val outfitEdit = HttpOutfitEditSource(http)
    val statistics = HttpStatisticsSource(http)
    val garmentDetail = HttpGarmentDetailSource(http)
    val garmentForm = HttpGarmentFormSource(http)
    val importer = HttpGarmentImporter(http)
    val bulkAdd = HttpBulkAddSource(http)
    val photos = BrowserPhotoWork(HttpPhotos(http))
    val server = HttpStorageSource(http)
    val settings = WebSettingsSource(server)
}

/**
 * Settings in the browser: the storage figures, and nothing that the screen
 * does not offer here. Backups and tidying are left off the screen (see
 * SettingsScreen), so nothing reaches the rest; it says so if it ever does.
 *
 * Over Nothing, because there is no archive to open and nowhere to write one:
 * the types say the restore and backup paths cannot be called, not just that
 * they are not.
 */
class WebSettingsSource(private val storage: HttpStorageSource) : SettingsSource<Nothing, Nothing> {
    override suspend fun storage(): StorageFigures = storage.storage()

    override suspend fun tidy(onProgress: (done: Int, total: Int) -> Unit): MaintenanceSummary =
        throw UnsupportedOperationException("Tidying photos is not available in the browser.")

    override suspend fun backup(destination: Nothing, onProgress: (copied: Int, total: Int) -> Unit): BackupSummary =
        destination

    override suspend fun preview(archive: Nothing): ArchivePreview = archive

    override suspend fun restore(archive: Nothing, withSettings: Boolean) = archive
}

/**
 * Each screen, given its model and told where its buttons go.
 *
 * MainActivity's private composables, one for one, without the Android: a
 * model comes from the entry rather than from a ViewModelStore, state is
 * collected as plain state, and "back" is the navigator's.
 */
class Screens(
    private val sources: WebSources,
    private val navigator: Navigator,
    /** The open profile, read when Settings is drawn, so a rename shows without rebuilding every screen. */
    private val profile: () -> ProfileControls,
    /** Open the wardrobe showing a query, and -- on a desktop -- with a garment open beside the grid. */
    private val openWardrobe: (WardrobeQuery?, String?) -> Unit,
    private val buildOutfitAround: (String) -> Unit,
) {
    @Composable
    fun Show(
        entry: Entry,
        theme: ThemeChoice,
        onThemeSelected: (ThemeChoice) -> Unit,
        arrival: WardrobeQuery?,
        arrivingGarment: String?,
        onArrivalApplied: () -> Unit,
        outfitSeed: String?,
        onSeedApplied: () -> Unit,
    ) {
        when (val destination = entry.destination) {
            Destination.Home -> Home(entry)
            Destination.Wardrobe -> Wardrobe(entry, arrival, arrivingGarment, onArrivalApplied)
            Destination.Outfits -> Outfits(entry, outfitSeed, onSeedApplied)
            Destination.Statistics -> Statistics(entry)
            Destination.Settings -> Settings(entry, theme, onThemeSelected)
            is Destination.Garment -> GarmentDetail(entry, destination.id)
            is Destination.Outfit -> OutfitDetail(entry, destination.id)
            Destination.OutfitBuild -> OutfitEdit(entry, outfitId = null)
            is Destination.OutfitEdit -> OutfitEdit(entry, destination.id)
            is Destination.GarmentAdd -> GarmentForm(entry, garmentId = null, wanted = destination.wanted)
            is Destination.GarmentEdit -> GarmentForm(entry, garmentId = destination.id, wanted = null)
            Destination.BulkAdd -> BulkAdd(entry)
        }
    }

    @Composable
    private fun Home(entry: Entry) {
        val model = entry.model { HomeScreenModel(it, sources.home) }
        val state by model.state.collectAsState()

        var flags by remember { mutableStateOf(FirstStepFlags.dismissed to FirstStepFlags.bulkAddUsed) }
        entry.onReturn = {
            model.refresh()
            flags = FirstStepFlags.dismissed to FirstStepFlags.bulkAddUsed
        }
        val (dismissed, bulkAddUsed) = flags

        // As on the phone: a count not known yet is absent, not zero.
        val known = !state.loading && state.error == null
        val steps = firstStepsFor(
            dismissed = dismissed,
            garments = state.items.takeIf { known },
            bulkAddUsed = bulkAddUsed,
            ratedOutfits = state.rated.takeIf { known },
        )

        LaunchedEffect(steps.isComplete) {
            if (steps.isComplete && !dismissed) {
                FirstStepFlags.dismissed = true
                flags = true to bulkAddUsed
            }
        }

        // What the desktop's Home shows besides the counts: the newest garments,
        // and a couple of outfit ideas. Asked for only once the window is wide
        // enough to show them, so a phone-width browser makes the one request
        // for the counts it always made; and kept on the entry, so the window
        // narrowing and widening again does not ask the engine twice.
        val expanded = isExpanded()
        var recent by remember { mutableStateOf(emptyList<GarmentRecord>()) }
        val caption = remember { BrowserWardrobeView().caption }
        val ideas = if (expanded) {
            entry.keep("ideas") { OutfitsScreenModel(entry.scope, sources.outfits).also { it.generate() } }
        } else {
            null
        }

        // Read again each time the counts finish loading, which is every return
        // to Home: a garment added from the rail, or one edited from here, is
        // in the row the next time it is seen.
        LaunchedEffect(expanded, state.loading) {
            if (!expanded || state.loading) return@LaunchedEffect
            runCatching { sources.wardrobe.garments(WardrobeQuery()) }
                .onSuccess { recent = it.take(RECENT_GARMENTS) }
        }

        HomeScreen(
            state = state,
            firstSteps = steps.takeIf { it.isVisible },
            onFirstStepsDismissed = {
                FirstStepFlags.dismissed = true
                flags = true to bulkAddUsed
            },
            onFirstStep = { step ->
                when (step) {
                    FirstStep.GARMENT -> navigator.open(Destination.GarmentAdd())
                    FirstStep.BULK_ADD -> navigator.open(Destination.BulkAdd)
                    FirstStep.RATE -> navigator.switchTo(Destination.Outfits)
                }
            },
            onAddRequested = { navigator.open(Destination.GarmentAdd()) },
            onWardrobeRequested = { openWardrobe(WardrobeQuery.showing(null), null) },
            onArchivedRequested = { openWardrobe(WardrobeQuery.showing(WardrobeLink.Retired), null) },
            onOutfitsRequested = { navigator.switchTo(Destination.Outfits) },
            onStatisticsRequested = { navigator.switchTo(Destination.Statistics) },
            onSettingsRequested = { navigator.switchTo(Destination.Settings) },
            onRetry = model::refresh,
            recent = recent,
            caption = caption,
            onRecentOpened = { openWardrobe(WardrobeQuery.showing(null), it) },
            outfitIdeas = if (ideas == null) null else { {
                val ideasState by ideas.state.collectAsState()

                OutfitIdeas(
                    state = ideasState,
                    onSave = ideas::onSaveRequested,
                    onRate = ideas::onRated,
                    onKeep = ideas::onKeepRequested,
                    onKeepDismissed = ideas::onKeepDismissed,
                    onGarmentOpened = { openWardrobe(WardrobeQuery.showing(null), it) },
                )
            } },
        )
    }

    @Composable
    private fun Wardrobe(
        entry: Entry,
        arrival: WardrobeQuery?,
        arrivingGarment: String?,
        onArrivalApplied: () -> Unit,
    ) {
        val model = entry.model { WardrobeScreenModel(it, sources.wardrobe, BrowserWardrobeView()) }
        val state by model.state.collectAsState()

        // The desktop's: the garment open beside the grid, and its model. On the
        // entry rather than remembered, so neither a tab switch nor the window
        // dropping below the breakpoint and back closes it. A phone-width
        // window keeps it and ignores it.
        val expanded = isExpanded()
        val selection = entry.keep("selection") { mutableStateOf<String?>(null) }
        val pane = entry.keep("pane") { PaneModel<GarmentDetailScreenModel>(entry.scope) }
        var panelOpen by remember { mutableStateOf(FilterPanelPreference.open) }

        entry.onReturn = {
            model.refresh()
            // Back from editing the garment in the pane: it may have changed.
            pane.current?.refresh()
        }

        LaunchedEffect(arrival, arrivingGarment) {
            if (arrival != null) {
                model.onQueryRequested(arrival)
                if (arrivingGarment != null) selection.value = arrivingGarment
                onArrivalApplied()
            }
        }

        val selected = selection.value.takeIf { expanded }

        WardrobeScreen(
            state = state,
            onSearchChanged = model::onSearchChanged,
            onSortToggled = model::onSortToggled,
            onRetry = model::refresh,
            // Beside the grid on a desktop, as a screen of its own on a phone.
            onGarmentOpened = { id ->
                if (expanded) selection.value = id else navigator.open(Destination.Garment(id))
            },
            onAddRequested = { navigator.open(Destination.GarmentAdd()) },
            onBulkAddRequested = { navigator.open(Destination.BulkAdd) },
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
            onCaptionSelected = model::onCaptionSelected,
            selectedGarmentId = selected,
            // Kept showing while it is open even if it drops out of the list --
            // retired, or filtered away -- so what somebody was looking at does
            // not vanish from under them; closing it is theirs to do.
            detailPane = if (selected == null) null else { {
                GarmentPane(
                    garmentId = selected,
                    pane = pane,
                    onClosed = { selection.value = null },
                    onWardrobeChanged = model::refresh,
                )
            } },
            filterPanelOpen = panelOpen,
            onFilterPanelToggled = {
                panelOpen = !panelOpen
                FilterPanelPreference.open = panelOpen
            },
        )
    }

    /**
     * One garment, in the pane beside the desktop wardrobe: [GarmentDetail]'s
     * wiring, with closing the pane where that has going back.
     */
    @Composable
    private fun GarmentPane(
        garmentId: String,
        pane: PaneModel<GarmentDetailScreenModel>,
        onClosed: () -> Unit,
        onWardrobeChanged: () -> Unit,
    ) {
        val model = pane.modelFor(garmentId) { GarmentDetailScreenModel(it, sources.garmentDetail, garmentId) }
        val state by model.state.collectAsState()

        // A delete closes the pane, as it closes the screen on a phone; the grid
        // beside it is read again either way, since it showed the garment.
        LaunchedEffect(state.deleted) {
            if (state.deleted) {
                onClosed()
                onWardrobeChanged()
            }
        }

        // Retiring and returning a garment change which list it belongs in, so
        // the grid beside it is read again when that answer changes -- not when
        // it is first known, which is only the pane loading.
        val available = state.view?.isAvailable
        var lastAvailable by remember(garmentId) { mutableStateOf(available) }
        LaunchedEffect(available) {
            if (available != null && lastAvailable != null && available != lastAvailable) onWardrobeChanged()
            if (available != null) lastAvailable = available
        }

        GarmentDetailPane(
            state = state,
            onClose = onClosed,
            onPhotoSelected = model::onPhotoSelected,
            onEdit = { navigator.open(Destination.GarmentEdit(garmentId)) },
            onRetry = model::refresh,
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onBuildOutfit = { buildOutfitAround(garmentId) },
            onRetire = model::onRetireRequested,
            onReturnToWardrobe = model::onReturnedToWardrobe,
            onDelete = model::onDeleteRequested,
            onConfirmed = model::onConfirmed,
            onConfirmationDismissed = model::onConfirmationDismissed,
            onActionErrorDismissed = model::onActionErrorDismissed,
        )
    }

    @Composable
    private fun Outfits(entry: Entry, seedGarmentId: String?, onSeedApplied: () -> Unit) {
        val model = entry.model { OutfitsScreenModel(it, sources.outfits) }
        val state by model.state.collectAsState()
        entry.onReturn = model::refresh

        LaunchedEffect(seedGarmentId) {
            if (seedGarmentId != null) {
                model.onSeedRequested(seedGarmentId)
                onSeedApplied()
            }
        }

        // The photos the desktop's saved list draws its thumbnails from, which a
        // saved outfit does not carry: every garment, retired ones included,
        // since an outfit saved last year may hold something no longer worn.
        // Read again whenever the saved list changes, so an outfit kept from a
        // suggestion a moment ago has its pictures.
        val expanded = isExpanded()
        var photos by remember { mutableStateOf(emptyMap<String, String>()) }
        LaunchedEffect(expanded, state.saved) {
            if (!expanded) return@LaunchedEffect
            val wanted = state.saved.flatMap { it.garmentIds }.toSet()
            if (wanted.isEmpty() || wanted.all { it in photos }) return@LaunchedEffect
            runCatching { sources.wardrobe.garments(WardrobeQuery(includeRetired = true)) }
                .onSuccess { garments -> photos = garments.associate { it.id to it.displayImage } }
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
            onGarmentOpened = { navigator.open(Destination.Garment(it)) },
            onOutfitOpened = { navigator.open(Destination.Outfit(it)) },
            onBuildRequested = { navigator.open(Destination.OutfitBuild) },
            garmentPhotos = photos,
        )
    }

    @Composable
    private fun Statistics(entry: Entry) {
        val model = entry.model { StatisticsScreenModel(it, sources.statistics) }
        val state by model.state.collectAsState()
        entry.onReturn = model::refresh

        StatisticsScreen(
            state = state,
            onCategoryTapped = model::onCategoryTapped,
            onLinkRequested = { openWardrobe(WardrobeQuery.showing(it), null) },
            onGarmentOpened = { navigator.open(Destination.Garment(it)) },
            onBrandSortChanged = model::onBrandSortChanged,
            onSectionTapped = model::onSectionTapped,
            onGapAddRequested = { navigator.open(Destination.GarmentAdd(wanted = it)) },
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun Settings(entry: Entry, theme: ThemeChoice, onThemeSelected: (ThemeChoice) -> Unit) {
        val model = entry.model { SettingsScreenModel(it, sources.settings) }
        val state by model.state.collectAsState()
        entry.onReturn = model::refresh

        // The server's, which is the Home Assistant app's version: the page
        // comes with it and has none of its own. Shown as the development
        // build's until it answers, which on a working server is at once.
        val version by produceState(ServerVersion.DEVELOPMENT.asAppVersion()) {
            runCatching { sources.server.version() }.onSuccess { value = it.asAppVersion() }
        }

        // What a phone needs to pair: asked once, and replaced when a new code
        // is made.
        var pairing by remember { mutableStateOf<SyncPairing?>(null) }
        var pairingKnown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching { sources.server.pairing() }.onSuccess {
                pairing = it
                pairingKnown = true
            }
        }

        SettingsScreen(
            state = state,
            version = version,
            // The browser's, and not this app's to override; see SettingsScreen.
            language = LanguageChoice.SYSTEM,
            onLanguageSelected = null,
            theme = theme,
            onThemeSelected = onThemeSelected,
            onBackupRequested = null,
            onBackupDismissed = model::onBackupDismissed,
            onRestoreRequested = model::onRestoreRequested,
            onRestoreConfirmed = {},
            onArchiveConfirmed = { withSettings -> model.onRestoreConfirmed(withSettings) },
            onRestoreDismissed = model::onRestoreDismissed,
            onTidyRequested = null,
            onTidyDismissed = model::onTidyDismissed,
            onRetry = model::refresh,
            cloudSection = null,
            profileSection = {
                val controls = profile()
                ProfileSection(
                    current = controls.current,
                    profiles = controls.all,
                    isYours = controls.isYours,
                    signedIn = controls.signedIn,
                    onSwitch = controls.switchTo,
                    onRename = controls.rename,
                    onMakeYours = controls.makeYours,
                    onCreate = controls.create,
                    onDelete = controls.delete,
                )
            },
            // Left out until the server has answered, so a slow answer does
            // not read as "sync is off"; a pairing of null then means it is.
            syncSection = if (!pairingKnown) null else { {
                SyncPairingSection(
                    code = pairing?.code,
                    port = pairing?.port,
                    onResetConfirmed = {
                        entry.scope.launch {
                            runCatching { sources.server.resetPairing() }.onSuccess { pairing = it }
                        }
                    },
                )
            } },
        )
    }

    @Composable
    private fun GarmentDetail(entry: Entry, garmentId: String) {
        val model = entry.model { GarmentDetailScreenModel(it, sources.garmentDetail, garmentId) }
        val state by model.state.collectAsState()
        entry.onReturn = model::refresh

        LaunchedEffect(state.deleted) {
            if (state.deleted) navigator.back()
        }

        GarmentDetailScreen(
            state = state,
            onBack = navigator::back,
            onPhotoSelected = model::onPhotoSelected,
            onEdit = { navigator.open(Destination.GarmentEdit(garmentId)) },
            onRetry = model::refresh,
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onBuildOutfit = { buildOutfitAround(garmentId) },
            onRetire = model::onRetireRequested,
            onReturnToWardrobe = model::onReturnedToWardrobe,
            onDelete = model::onDeleteRequested,
            onConfirmed = model::onConfirmed,
            onConfirmationDismissed = model::onConfirmationDismissed,
            onActionErrorDismissed = model::onActionErrorDismissed,
        )
    }

    @Composable
    private fun OutfitDetail(entry: Entry, outfitId: String) {
        val model = entry.model { OutfitDetailScreenModel(it, sources.outfitDetail, outfitId) }
        val state by model.state.collectAsState()
        entry.onReturn = model::refresh

        LaunchedEffect(state.deleted) {
            if (state.deleted) navigator.back()
        }

        OutfitDetailScreen(
            state = state,
            onBack = navigator::back,
            onGarmentOpened = { navigator.open(Destination.Garment(it)) },
            onRate = model::onRated,
            onEdit = { navigator.open(Destination.OutfitEdit(outfitId)) },
            onDelete = model::onDeleteRequested,
            onDeleteConfirmed = model::onDeleteConfirmed,
            onDeleteDismissed = model::onDeleteDismissed,
            onRetry = model::refresh,
        )
    }

    @Composable
    private fun OutfitEdit(entry: Entry, outfitId: String?) {
        val model = entry.model { OutfitEditScreenModel(it, sources.outfitEdit, outfitId) }
        val state by model.state.collectAsState()

        LaunchedEffect(state.saved) {
            if (state.saved) navigator.back()
        }

        OutfitEditScreen(
            state = state,
            isEditing = model.isEditing,
            onBack = navigator::back,
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
    private fun GarmentForm(entry: Entry, garmentId: String?, wanted: PhantomGarment?) {
        val model = entry.model {
            GarmentFormScreenModel(it, sources.photos, sources.garmentForm, sources.importer, garmentId, wanted)
        }
        val state by model.state.collectAsState()

        LaunchedEffect(state.saved) {
            if (state.saved) navigator.back()
        }

        GarmentFormScreen(
            state = state,
            isEditing = model.isEditing,
            brandSuggestions = model::suggestionsFor,
            onBack = navigator::back,
            // The browser's picker, and a phone browser's camera. The photo is
            // stored as picked: there is no crop screen here (see PhotoTools).
            onAddPhoto = { entry.scope.launch { pickPhotos(multiple = false).firstOrNull()?.let(model::onPhotoPicked) } },
            onTakePhoto = {
                entry.scope.launch { pickPhotos(multiple = false, capture = true).firstOrNull()?.let(model::onPhotoPicked) }
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

    @Composable
    private fun BulkAdd(entry: Entry) {
        val model = entry.model { BulkAddScreenModel(it, sources.photos, sources.bulkAdd) }
        val state by model.state.collectAsState()

        // Recorded as it happens, for the first-steps card; see MainActivity.
        val drawerful = state.queue.added >= BULK_ADD_MINIMUM
        LaunchedEffect(drawerful) {
            if (drawerful) FirstStepFlags.bulkAddUsed = true
        }

        BulkAddScreen(
            state = state,
            onBack = navigator::back,
            onChoosePhotos = {
                entry.scope.launch {
                    val picked = pickPhotos(multiple = true).take(BulkAddState.MAX_PHOTOS)
                    if (picked.isNotEmpty()) model.onPhotosPicked(picked)
                }
            },
            onCategorySelected = model::onCategorySelected,
            onSubcategoryToggled = model::onSubcategoryToggled,
            onBrandChanged = model::onBrandChanged,
            // Not offered here; see PhotoTools.
            onCrop = {},
            onRemoveBackground = model::onRemoveBackground,
            onUndoBackground = model::onUndoBackground,
            onSave = model::onSaveRequested,
            onSkip = model::onSkipRequested,
            onErrorDismissed = model::onErrorDismissed,
        )
    }
}

private fun ServerVersion.asAppVersion() = AppVersion(name = name, code = build)

/** How many of the newest garments the desktop's Home shows; see HomeScreen. */
private const val RECENT_GARMENTS = 6
