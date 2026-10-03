package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.DatabaseWardrobeSource
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeScreenModel
import com.wardrobapp.presentation.WardrobeScreenState
import com.wardrobapp.presentation.WardrobeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * The wardrobe list.
 *
 * Holds state and moves work off the main thread. It decides nothing: which
 * garments a filter keeps and in what order they appear comes from
 * :presentation, and the reading from :data.
 *
 * What it does lives in WardrobeScreenModel, in common code, so the browser can
 * run it too; this is the Android half: the scope that ends with the screen, the
 * phone's own database as where the garments come from, and SharedPreferences
 * as where this device's choice of layout is kept.
 */
class WardrobeViewModel(container: AppContainer) : ViewModel() {

    private val model = WardrobeScreenModel(
        scope = viewModelScope,
        source = DatabaseWardrobeSource(container.garments, Dispatchers.IO),
        settings = container.wardrobeView,
    )

    val state: StateFlow<WardrobeScreenState> = model.state

    fun onFiltersToggled() = model.onFiltersToggled()
    fun onSearchChanged(search: String) = model.onSearchChanged(search)
    fun onBrandTapped(brand: String) = model.onBrandTapped(brand)
    fun onSizeTapped(size: String) = model.onSizeTapped(size)
    fun onCategoryTapped(id: String) = model.onCategoryTapped(id)
    fun onSubcategoryTapped(id: String) = model.onSubcategoryTapped(id)
    fun onSeasonTapped(season: Season) = model.onSeasonTapped(season)
    fun onOccasionTapped(occasion: Occasion) = model.onOccasionTapped(occasion)
    fun onColorTapped(color: String) = model.onColorTapped(color)
    fun onRetiredToggled() = model.onRetiredToggled()
    fun onViewSelected(choice: WardrobeView) = model.onViewSelected(choice)
    fun onCaptionSelected(choice: GarmentCaption) = model.onCaptionSelected(choice)
    fun onSortToggled() = model.onSortToggled()
    fun onFiltersCleared() = model.onFiltersCleared()
    fun onQueryRequested(query: WardrobeQuery) = model.onQueryRequested(query)
    fun refresh() = model.refresh()
}
