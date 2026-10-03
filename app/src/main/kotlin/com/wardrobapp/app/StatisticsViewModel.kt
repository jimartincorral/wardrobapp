package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.DatabaseStatisticsSource
import com.wardrobapp.presentation.StatisticsScreenModel
import com.wardrobapp.presentation.StatisticsScreenState
import com.wardrobapp.presentation.StatisticsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * What the wardrobe is made of, and how long the things you stop wearing lasted.
 *
 * Every tile and every bar's length comes from [statisticsView]; this reads the
 * counts, holds what the reader has opened, and decides nothing about the
 * arithmetic.
 *
 * It reads seven things where it used to read five, because `AnalyticsViewModel`
 * is gone: the retired count and the lifespans were the only numbers that screen
 * had of its own, and they are two more reads on the same trip rather than a
 * second model on a second page.
 *
 * What it does lives in StatisticsScreenModel, in common code, so the browser
 * can run it too; this is the Android half: the scope that ends with the screen,
 * and the phone's own database as where the numbers come from.
 */
class StatisticsViewModel(container: AppContainer) : ViewModel() {

    private val model = StatisticsScreenModel(
        scope = viewModelScope,
        source = DatabaseStatisticsSource(
            garments = container.garments,
            analytics = container.analytics,
            duplicates = container.duplicates,
            gaps = container.gaps,
            imageDirectory = container.imageDirectory,
            io = Dispatchers.IO,
        ),
    )

    val state: StateFlow<StatisticsScreenState> = model.state

    fun refresh() = model.refresh()
    fun onSectionTapped(section: StatisticsSection) = model.onSectionTapped(section)
    fun onCategoryTapped(category: String) = model.onCategoryTapped(category)
    fun onBrandSortChanged(sort: BrandSort) = model.onBrandSortChanged(sort)
}
