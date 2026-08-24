package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two counts and a set of doors.
 *
 * No pure module behind this one, deliberately: there is no arithmetic to get
 * wrong. Both counts are single queries that Analytics and Settings already read,
 * and everything else on the screen is navigation.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val items: Long = 0,
        val archived: Long = 0,
        /**
         * Reported rather than swallowed.
         *
         * The React Native screen logs a failure to the console and leaves both
         * counts at zero, which reads as an empty wardrobe -- the one thing a
         * wardrobe app must not say when it cannot tell.
         */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val counts = withContext(Dispatchers.IO) {
                    container.garments.availableCount() to container.garments.unavailableCount()
                }
                _state.update {
                    it.copy(loading = false, items = counts.first, archived = counts.second, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
