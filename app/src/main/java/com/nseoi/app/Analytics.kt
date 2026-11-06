package com.nseoi.app

import java.util.Locale

enum class Bias { BULLISH, BEARISH, NEUTRAL }

enum class Flow(val label: String) {
    WRITING("Writing"), BUYING("Buying"), SHORT_COVERING("Short Covering"),
    LONG_UNWINDING("Long Unwinding"), NONE("No clear flow"),
}

data class OIStats(
    val callOi: Double, val putOi: Double,
    val callChg: Double, val putChg: Double, val pcr: Double?,
)

data class StrikeRank(
    val strike: Double, val changeOi: Double, val currentOi: Double,
    val priceChg: Double, val flow: Flow,
)

data class MaxPain(val strike: Double, val distance: Double, val distancePct: Double?)

data class SetupResult(
    val setup: String,
    val conditions: List<Pair<String, Boolean>>,
    val support: Double?,
    val resistance: Double?,
    val biasText: String,
    val commentary: String,
)

data class Analysis(
    val spec: InstrumentSpec,
    val snapshot: Snapshot,
    val stats: OIStats,
    val bias: Bias,
    val maxPain: MaxPain?,
    val supports: List<Pair<Double, Double>>,
    val resistances: List<Pair<Double, Double>>,
    val ceAdds: List<StrikeRank>,
    val peAdds: List<StrikeRank>,
    val setup: SetupResult,
) {
    val key: String get() = spec.key
    val label: String get() = spec.symbol
    val expiryText: String get() = fmtDate(snapshot.expiry)
    val spot: Double get() = snapshot.spot
}

private const val PCR_BULL = 1.10
private const val PCR_BEAR = 0.80

private fun legOf(row: StrikeRow, ce: Boolean): OptionLeg? = if (ce) row.ce else row.pe

fun lakhChange(v: Double): String = String.format(Locale.US, "%+,.1f lakh", v / 1e5)

fun computeStats(rows: List<StrikeRow>): OIStats {
    val ce = rows.mapNotNull { it.ce }
    val pe = rows.mapNotNull { it.pe }
    val callOi = ce.sumOf { it.oi }
    val putOi = pe.sumOf { it.oi }
    return OIStats(
        callOi, putOi, ce.sumOf { it.chgOi }, pe.sumOf { it.chgOi },
        if (callOi == 0.0) null else putOi / callOi,
    )
}

fun marketBias(s: OIStats): Bias {
    val pcr = s.pcr ?: return Bias.NEUTRAL
    if (pcr > PCR_BULL && s.putChg > s.callChg) return Bias.BULLISH
    if (pcr < PCR_BEAR && s.callChg > s.putChg) return Bias.BEARISH
    return Bias.NEUTRAL
}

fun classifyFlow(oiChange: Double, priceChange: Double): Flow {
    if (oiChange == 0.0 || priceChange == 0.0) return Flow.NONE
    val oiUp = oiChange > 0
    val priceUp = priceChange > 0
    return when {
        oiUp && !priceUp -> Flow.WRITING
        oiUp && priceUp -> Flow.BUYING
        !oiUp && priceUp -> Flow.SHORT_COVERING
        else -> Flow.LONG_UNWINDING
    }
}

fun topByChangeOi(rows: List<StrikeRow>, ce: Boolean, n: Int = 3): List<StrikeRank> =
    rows.mapNotNull { r ->
        val l = legOf(r, ce)
        if (l == null || l.chgOi <= 0) null
        else StrikeRank(r.strike, l.chgOi, l.oi, l.priceChg, classifyFlow(l.chgOi, l.priceChg))
    }.sortedByDescending { it.changeOi }.take(n)

fun topByOi(rows: List<StrikeRow>, ce: Boolean, n: Int = 3): List<Pair<Double, Double>> =
    rows.mapNotNull { r ->
        val l = legOf(r, ce)
        if (l != null && l.oi > 0) Pair(l.oi, r.strike) else null
    }.sortedWith(compareByDescending<Pair<Double, Double>> { it.first }.thenByDescending { it.second })
        .take(n).map { Pair(it.second, it.first) }

private fun pickLevel(rows: List<StrikeRow>, ce: Boolean, spot: Double, below: Boolean, byChange: Boolean): Double? {
    var bestStrike: Double? = null
    var bestValue = 0.0
    for (r in rows) {
        val l = legOf(r, ce) ?: continue
        if ((below && r.strike > spot) || (!below && r.strike < spot)) continue
        val v = if (byChange) l.chgOi else l.oi
        if (v > bestValue) { bestStrike = r.strike; bestValue = v }
    }
    return bestStrike
}

fun computeMaxPain(rows: List<StrikeRow>, spot: Double): MaxPain? {
    if (rows.isEmpty()) return null
    var bestK = rows[0].strike
    var bestTotal = Double.MAX_VALUE
    for (k in rows) {
        var total = 0.0
        for (s in rows) {
            val d = k.strike - s.strike
            if (d > 0) total += d * (s.ce?.oi ?: 0.0)
            else if (d < 0) total += -d * (s.pe?.oi ?: 0.0)
        }
        if (total < bestTotal) { bestTotal = total; bestK = k.strike }
    }
    val dist = bestK - spot
    return MaxPain(bestK, dist, if (spot != 0.0) dist / spot * 100 else null)
}

private fun changeAt(rows: List<StrikeRow>, strike: Double, ce: Boolean): Double {
    for (r in rows) if (r.strike == strike) return legOf(r, ce)?.chgOi ?: 0.0
    return 0.0
}

private fun biasText(setup: String, spot: Double, support: Double?, resistance: Double?): String {
    var position: Double? = null
    if (support != null && resistance != null && resistance > support) {
        position = (spot - support) / (resistance - support)
    }
    return when (setup) {
        "BULLISH SETUP" -> if (position != null && position >= 0.5) "Bullish to Neutral" else "Bullish"
        "BEARISH SETUP" -> if (position != null && position <= 0.5) "Bearish to Neutral" else "Bearish"
        else -> "Neutral (range-bound)"
    }
}

private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)

fun evaluateSetup(rows: List<StrikeRow>, spot: Double, stats: OIStats, prevPcr: Double?): SetupResult {
    val ceAdd = topByChangeOi(rows, true)
    val peAdd = topByChangeOi(rows, false)
    val pcr = stats.pcr
    val pcrUp = pcr != null && prevPcr != null && pcr > prevPcr
    val pcrDown = pcr != null && prevPcr != null && pcr < prevPcr

    val bullish = listOf(
        "Strong PE writing" to (peAdd.isNotEmpty() && peAdd[0].flow == Flow.WRITING),
        "CE unwinding" to (stats.callChg < 0),
        "PCR increasing" to pcrUp,
    )
    val bearish = listOf(
        "Strong CE writing" to (ceAdd.isNotEmpty() && ceAdd[0].flow == Flow.WRITING),
        "PE unwinding" to (stats.putChg < 0),
        "PCR decreasing" to pcrDown,
    )
    val setup = when {
        bullish.all { it.second } -> "BULLISH SETUP"
        bearish.all { it.second } -> "BEARISH SETUP"
        else -> "NO CLEAR SETUP"
    }

    var support = pickLevel(rows, false, spot, true, true) ?: pickLevel(rows, false, spot, true, false)
    var resistance = pickLevel(rows, true, spot, false, true) ?: pickLevel(rows, true, spot, false, false)
    if (support == null) support = topByOi(rows, false, 1).firstOrNull()?.first
    if (resistance == null) resistance = topByOi(rows, true, 1).firstOrNull()?.first

    val bt = biasText(setup, spot, support, resistance)
    val parts = mutableListOf<String>()
    if (support != null) {
        parts.add(
            if (changeAt(rows, support, false) > 0) "Heavy Put writing at ${f0(support)} indicates strong support."
            else "Highest Put OI at ${f0(support)} acts as support."
        )
    }
    if (resistance != null) {
        parts.add(
            if (changeAt(rows, resistance, true) > 0) "Heavy Call writing at ${f0(resistance)} indicates strong resistance."
            else "Highest Call OI at ${f0(resistance)} acts as resistance."
        )
    }
    if (support != null && resistance != null && resistance > support) {
        parts.add("Expected trading range:\n${f0(support)} – ${f0(resistance)}")
    }
    parts.add("Bias:\n$bt")
    val flows = mutableListOf<String>()
    if (ceAdd.isNotEmpty()) flows.add("CE OI added at ${f0(ceAdd[0].strike)} (${lakhChange(ceAdd[0].changeOi)})")
    if (peAdd.isNotEmpty()) flows.add("PE OI added at ${f0(peAdd[0].strike)} (${lakhChange(peAdd[0].changeOi)})")
    if (flows.isNotEmpty()) parts.add(flows.joinToString("; ") + ".")
    val title = setup.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }
    parts.add("Spot ${String.format(Locale.US, "%,.2f", spot)} · Setup status: $title.")

    return SetupResult(setup, bullish + bearish, support, resistance, bt, parts.joinToString("\n\n"))
}

fun analyze(snapshot: Snapshot, prevPcr: Double?): Analysis {
    val rows = snapshot.rows
    val stats = computeStats(rows)
    val setup = evaluateSetup(rows, snapshot.spot, stats, prevPcr)
    return Analysis(
        spec = snapshot.spec,
        snapshot = snapshot,
        stats = stats,
        bias = marketBias(stats),
        maxPain = computeMaxPain(rows, snapshot.spot),
        supports = topByOi(rows, false),
        resistances = topByOi(rows, true),
        ceAdds = topByChangeOi(rows, true),
        peAdds = topByChangeOi(rows, false),
        setup = setup,
    )
}
