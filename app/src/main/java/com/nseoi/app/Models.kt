package com.nseoi.app

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

val INDEX_SYMBOLS = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50", "NIFTYFPI")
const val DEFAULT_INSTRUMENTS = "NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, NIFTYNXT50, NIFTYFPI"
const val MAX_INSTRUMENTS = 20

val DATE_FMT: DateTimeFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH)

fun fmtDate(d: LocalDate): String = d.format(DATE_FMT)

fun parseDate(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    return try { LocalDate.parse(s.trim(), DATE_FMT) } catch (e: Exception) { null }
}

data class OptionLeg(
    val oi: Double, val chgOi: Double, val volume: Double,
    val ltp: Double, val priceChg: Double, val iv: Double,
)

data class StrikeRow(val strike: Double, val ce: OptionLeg?, val pe: OptionLeg?)

data class InstrumentSpec(val symbol: String, val expiry: LocalDate?) {
    val isIndex: Boolean get() = symbol in INDEX_SYMBOLS
    val key: String get() = if (expiry != null) "NSE:$symbol@${fmtDate(expiry)}" else "NSE:$symbol"
}

data class Snapshot(
    val spec: InstrumentSpec,
    val expiry: LocalDate,
    val expiries: List<LocalDate>,
    val spot: Double,
    val nseTimestamp: String,
    val localTime: ZonedDateTime,
    val rows: List<StrikeRow>,
)

private val SYMBOL_RE = Regex("^[A-Z0-9&\\-]{1,20}$")

fun parseInstrumentList(text: String, limit: Int = MAX_INSTRUMENTS): List<InstrumentSpec> {
    val tokens = text.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val out = LinkedHashMap<String, InstrumentSpec>()
    for (t in tokens) {
        var raw = t.uppercase(Locale.ROOT)
        if (raw.contains(":")) {
            val ex = raw.substringBefore(":")
            if (ex != "NSE") throw IllegalArgumentException("Unsupported exchange '$ex'")
            raw = raw.substringAfter(":")
        }
        var expiry: LocalDate? = null
        if (raw.contains("@")) {
            val et = raw.substringAfter("@").trim()
            expiry = parseDate(et)
                ?: throw IllegalArgumentException("Bad expiry '$et'. Use DD-Mon-YYYY, e.g. 28-Nov-2026")
            raw = raw.substringBefore("@")
        }
        val sym = raw.trim()
        if (!SYMBOL_RE.matches(sym)) throw IllegalArgumentException("Invalid symbol '$sym'")
        val spec = InstrumentSpec(sym, expiry)
        out.putIfAbsent(spec.key, spec)
    }
    if (out.isEmpty()) throw IllegalArgumentException("At least one instrument is required")
    if (out.size > limit) throw IllegalArgumentException("Maximum $limit instruments allowed; got ${out.size}")
    return out.values.toList()
}
