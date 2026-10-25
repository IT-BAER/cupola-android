package com.baer.cupola.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baer.cupola.orbit.OrbitModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

data class SettingsUiState(
    val settings: Settings = Settings(),
    val homeCity: CityEntry? = null,
    /** False until the repository delivered its first value. */
    val loaded: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    cities: CityRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(it, it.homeCityId?.let { id -> cities.byId(id) }, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setRestDirection(value: RestDirection) = launch { repository.setRestDirection(value) }
    fun setLookAroundStrength(value: Float) = launch { repository.setLookAroundStrength(value) }
    fun setIdleFloat(value: Boolean) = launch { repository.setIdleFloat(value) }
    fun setInterior(value: Boolean) = launch { repository.setInterior(value) }
    fun setBloom(value: Boolean) = launch { repository.setBloom(value) }
    fun setWindowGlare(value: Boolean) = launch { repository.setWindowGlare(value) }
    fun setReflections(value: Boolean) = launch { repository.setReflections(value) }
    fun setDust(value: Boolean) = launch { repository.setDust(value) }
    fun clearHomeCity() = launch { repository.setHomeCity(null) }
    fun setOnlineOrbitUpdates(value: Boolean) = launch { repository.setOnlineOrbitUpdates(value) }

    /** View and effect settings back to [Settings] defaults; the home city and the download choice stay. */
    fun resetToDefaults() = launch {
        val d = Settings()
        repository.setRestDirection(d.restDirection)
        repository.setLookAroundStrength(d.lookAroundStrength)
        repository.setIdleFloat(d.idleFloat)
        repository.setInterior(d.interior)
        repository.setBloom(d.bloom)
        repository.setWindowGlare(d.windowGlare)
        repository.setReflections(d.reflections)
        repository.setDust(d.dust)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

data class HomeCitySearchUiState(
    val query: String = "",
    val results: List<CityEntry> = emptyList(),
    val selectedId: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeCitySearchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cities: CityRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    private val results: Flow<List<CityEntry>> = query
        .debounce(DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q -> flow { emit(if (q.isEmpty()) emptyList() else cities.search(q, MAX_RESULTS)) } }

    val uiState: StateFlow<HomeCitySearchUiState> = combine(query, results, settings.settings) { q, r, s ->
        HomeCitySearchUiState(q, r, s.homeCityId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeCitySearchUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun select(city: CityEntry) {
        viewModelScope.launch { settings.setHomeCity(city.id) }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_RESULTS = 20
    }
}

data class InfoUiState(val snapshot: InfoSnapshot? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InfoViewModel @Inject constructor(
    settings: SettingsRepository,
    private val tles: TleRepository,
    private val cities: CityRepository,
    private val clock: Clock,
    @Named(COMPUTE_DISPATCHER) private val compute: CoroutineDispatcher,
) : ViewModel() {
    val uiState: StateFlow<InfoUiState> = settings.settings
        .map { it.homeCityId }
        .distinctUntilChanged()
        .flatMapLatest { homeId -> snapshots(homeId) }
        .map { InfoUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InfoUiState())

    private fun snapshots(homeId: String?): Flow<InfoSnapshot> = flow {
        val metadata = tles.metadata()
        val logic = InfoLogic(OrbitModel(tles.tle()), cities)
        val home = homeId?.let { cities.byId(it) }
        while (true) {
            // A failed propagation keeps the previous snapshot on screen.
            runCatching { logic.snapshot(clock.instant(), metadata, home) }.onSuccess { emit(it) }
            delay(REFRESH_MS)
        }
    }.flowOn(compute)

    companion object {
        const val COMPUTE_DISPATCHER = "compute"
        const val REFRESH_MS = 30_000L
    }
}
