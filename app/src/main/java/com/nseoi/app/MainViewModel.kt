package com.nseoi.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AppSettings(
    val instruments: String = DEFAULT_INSTRUMENTS,
    val refreshMinutes: Int = 5,
    val marketOnly: Boolean = true,
    val streaming: Boolean = true,
    val expiryMode: String = "nearest",
)

data class UiState(
    val results: List<Analysis> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val lastRefresh: String = "—",
    val status: String = "Starting",
    val loading: Boolean = false,
    val settings: AppSettings = AppSettings(),
)

val REFRESH_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60)

fun isMarketOpen(now: ZonedDateTime = ZonedDateTime.now(IST)): Boolean {
    if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
    val t = now.toLocalTime()
    return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30))
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("nse_settings", Context.MODE_PRIVATE)
    private val client = NseClient()
    private val refreshLock = Mutex()
    private val pcrHistory = mutableMapOf<String, MutableList<Pair<String, Double?>>>()
    private var loop: Job? = null

    private val _state = MutableStateFlow(UiState(settings = loadSettings()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { startLoop() }

    private fun loadSettings() = AppSettings(
        instruments = prefs.getString("instruments", DEFAULT_INSTRUMENTS) ?: DEFAULT_INSTRUMENTS,
        refreshMinutes = prefs.getInt("refresh", 5),
        marketOnly = prefs.getBoolean("marketOnly", true),
        streaming = prefs.getBoolean("streaming", true),
        expiryMode = prefs.getString("expiryMode", "nearest") ?: "nearest",
    )

    private fun startLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            var first = true
            while (isActive) {
                val s = _state.value.settings
                if (first || (s.streaming && (!s.marketOnly || isMarketOpen()))) {
                    refreshAll()
                } else {
                    _state.update { it.copy(status = if (!s.streaming) "Auto-refresh off" else "Market Closed") }
                }
                first = false
                delay(s.refreshMinutes * 60_000L)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refreshAll() }
    }

    fun applySettings(s: AppSettings): String? {
        try {
            parseInstrumentList(s.instruments)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Invalid instruments"
        }
        prefs.edit()
            .putString("instruments", s.instruments)
            .putInt("refresh", s.refreshMinutes)
            .putBoolean("marketOnly", s.marketOnly)
            .putBoolean("streaming", s.streaming)
            .putString("expiryMode", s.expiryMode)
            .apply()
        _state.update { it.copy(settings = s) }
        startLoop()
        return null
    }

    private suspend fun refreshAll() {
        if (!refreshLock.tryLock()) return
        try {
            val settings = _state.value.settings
            val specs = try {
                parseInstrumentList(settings.instruments)
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(errors = mapOf("Settings" to (e.message ?: "Bad instruments"))) }
                return
            }
            _state.update { it.copy(loading = true) }
            val results = LinkedHashMap<String, Analysis>()
            _state.value.results.forEach { r -> if (specs.any { it.key == r.key }) results[r.key] = r }
            val errors = LinkedHashMap<String, String>()

            for (spec in specs) {
                try {
                    val snap = client.getOptionChain(spec, settings.expiryMode)
                    val histKey = "${spec.symbol} ${fmtDate(snap.expiry)}"
                    val hist = pcrHistory.getOrPut(histKey) { mutableListOf() }
                    val previous = hist.lastOrNull { it.first != snap.nseTimestamp }?.second
                    var breadthNote: String? = null
                    val breadth = try {
                        client.getBreadth(spec.symbol)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        breadthNote = e.message ?: e.toString()
                        null
                    }
                    val analysis = analyze(snap, previous).copy(
                        breadth = breadth ?: _state.value.results.firstOrNull { it.key == spec.key }?.breadth,
                        breadthNote = if (breadth != null) null else breadthNote,
                    )
                    if (hist.isEmpty() || hist.last().first != snap.nseTimestamp) {
                        hist.add(Pair(snap.nseTimestamp, analysis.stats.pcr))
                    }
                    results[spec.key] = analysis
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[spec.key] = e.message ?: e.toString()
                }
                val ordered = specs.mapNotNull { results[it.key] }
                _state.update { it.copy(results = ordered, errors = LinkedHashMap(errors)) }
            }

            val stamp = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd-MMM HH:mm:ss", Locale.ENGLISH))
            _state.update {
                it.copy(
                    lastRefresh = "$stamp IST",
                    status = if (isMarketOpen()) "Live" else "Live (off-hours)",
                    loading = false,
                )
            }
        } finally {
            _state.update { it.copy(loading = false) }
            refreshLock.unlock()
        }
    }

    override fun onCleared() {
        loop?.cancel()
        super.onCleared()
    }
}
