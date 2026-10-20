package com.baer.cupola.feature.settings

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Live preview on a SurfaceView; implemented in `:app` over the shared renderer. Main thread only. */
interface PreviewRenderer {
    fun attach(surface: Surface)
    fun resize(width: Int, height: Int)
    fun detach()
}

/** Offscreen render for sharing; [renderRgba] returns tightly packed RGBA8, top row first. */
fun interface ShareRenderer {
    suspend fun renderRgba(width: Int, height: Int): ByteArray
}

data class AppInfo(val versionName: String)

data class CreditRow(val work: String, val licence: String, val credit: String)

data class Notice(val title: String, val text: String)

data class Credits(val rows: List<CreditRow>, val notices: List<Notice>)

fun interface CreditsRepository {
    suspend fun load(): Credits
}

/** credits.tsv: tab-separated, '#' comment lines, first remaining line is the header. */
object CreditsParser {
    fun parseRows(tsv: String): List<CreditRow> = tsv.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1)
        .map { it.split('\t') }
        .map { CreditRow(it[0], it[1], it[2]) }
}

@HiltViewModel
class CreditsViewModel @Inject constructor(private val repository: CreditsRepository) : ViewModel() {
    private val state = MutableStateFlow<Credits?>(null)
    val credits: StateFlow<Credits?> = state

    init {
        viewModelScope.launch { state.value = repository.load() }
    }
}
