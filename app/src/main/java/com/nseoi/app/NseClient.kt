package com.nseoi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class NseException(message: String) : Exception(message)

private class MemCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = store.clear()
}

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

class NseClient {
    private val base = "https://www.nseindia.com"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val jar = MemCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var lastCall = 0L
    private var warmedAt = 0L

    private suspend fun throttle() {
        val wait = 1500L - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
    }

    private fun pageRequest(url: String): Request = Request.Builder().url(url)
        .header("User-Agent", ua)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun apiRequest(url: HttpUrl): Request = Request.Builder().url(url)
        .header("User-Agent", ua)
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Referer", "$base/option-chain")
        .header("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun warmUp() {
        jar.clear()
        for (u in listOf(base, "$base/option-chain")) {
            throttle()
            withContext(Dispatchers.IO) {
                http.newCall(pageRequest(u)).execute().use { it.body?.string() }
            }
        }
        warmedAt = System.currentTimeMillis()
    }

    private suspend fun safeWarmUp() {
        try { warmUp() } catch (e: IOException) { /* retry on next attempt */ }
    }

    private suspend fun getJson(path: String, params: Map<String, String>): JSONObject = mutex.withLock {
        if (warmedAt == 0L || System.currentTimeMillis() - warmedAt > 300_000L) safeWarmUp()
        val url = (base + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        var last = "unknown error"
        for (attempt in 1..4) {
            throttle()
            try {
                val result = withContext(Dispatchers.IO) {
                    http.newCall(apiRequest(url)).execute().use { resp ->
                        Pair(resp.code, resp.body?.string() ?: "")
                    }
                }
                val code = result.first
                val body = result.second
                if (code == 200) {
                    try {
                        return@withLock JSONObject(body)
                    } catch (e: JSONException) {
                        last = "Non-JSON response (possible bot block)"
                        safeWarmUp()
                    }
                } else if (code == 401 || code == 403) {
                    last = "HTTP $code: session rejected"
                    safeWarmUp()
                } else if (code == 429 || code >= 500) {
                    last = "HTTP $code: server busy"
                } else {
                    throw NseException("HTTP $code for $path")
                }
            } catch (e: IOException) {
                last = "Network error: ${e.message}"
            }
            if (attempt < 4) delay(2000L * attempt)
        }
        throw NseException("NSE request failed after 4 attempts: $last")
    }

    private suspend fun listExpiries(symbol: String): List<LocalDate> {
        val json = getJson("/api/option-chain-contract-info", mapOf("symbol" to symbol))
        val arr = json.optJSONArray("expiryDates")
            ?: json.optJSONObject("records")?.optJSONArray("expiryDates")
        val out = sortedSetOf<LocalDate>()
        if (arr != null) {
            for (i in 0 until arr.length()) parseDate(arr.optString(i))?.let { out.add(it) }
        }
        return out.toList()
    }

    suspend fun getOptionChain(spec: InstrumentSpec, expiryMode: String): Snapshot {
        val expiries = listExpiries(spec.symbol)
        val local = ZonedDateTime.now(IST)
        val expiry = selectExpiry(expiries, spec, expiryMode, local.toLocalDate())
        val payload = getJson(
            "/api/option-chain-v3",
            mapOf(
                "type" to (if (spec.isIndex) "Indices" else "Equity"),
                "symbol" to spec.symbol,
                "expiry" to fmtDate(expiry),
            ),
        )
        return parseChain(payload, spec, expiry, expiries, local)
    }

    suspend fun getBreadth(symbol: String): Breadth? {
        val indexName = BREADTH_INDEX_NAMES[symbol] ?: return null
        val json = getJson("/api/equity-stockIndices", mapOf("index" to indexName))
        val adv = json.optJSONObject("advance") ?: return null
        return Breadth(
            num(adv.opt("advances")).toInt(),
            num(adv.opt("declines")).toInt(),
            num(adv.opt("unchanged")).toInt(),
        )
    }
}

fun selectExpiry(available: List<LocalDate>, spec: InstrumentSpec, mode: String, today: LocalDate): LocalDate {
    if (available.isEmpty()) throw NseException("NSE returned no expiries for ${spec.symbol}")
    spec.expiry?.let {
        if (it !in available) {
            val listed = available.take(10).joinToString(", ") { d -> fmtDate(d) }
            throw NseException("Expiry ${fmtDate(it)} is not listed for ${spec.symbol}. Listed: $listed")
        }
        return it
    }
    val sorted = available.sorted()
    val upcoming = sorted.filter { !it.isBefore(today) }.ifEmpty { sorted }
    return if (mode == "next" && upcoming.size > 1) upcoming[1] else upcoming[0]
}

private fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.replace(",", "").toDoubleOrNull() ?: 0.0
    else -> 0.0
}

private fun leg(raw: JSONObject?): OptionLeg? {
    if (raw == null || raw.length() == 0) return null
    return OptionLeg(
        oi = num(raw.opt("openInterest")),
        chgOi = num(raw.opt("changeinOpenInterest")),
        volume = num(raw.opt("totalTradedVolume")),
        ltp = num(raw.opt("lastPrice")),
        priceChg = num(raw.opt("change")),
        iv = num(raw.opt("impliedVolatility")),
    )
}

private fun itemExpiry(item: JSONObject): LocalDate? {
    for (key in listOf("expiryDate", "expiryDates")) {
        parseDate(item.optString(key, ""))?.let { return it }
    }
    for (side in listOf("CE", "PE")) {
        parseDate(item.optJSONObject(side)?.optString("expiryDate", ""))?.let { return it }
    }
    return null
}

fun parseChain(
    payload: JSONObject,
    spec: InstrumentSpec,
    expiry: LocalDate,
    expiries: List<LocalDate>,
    local: ZonedDateTime,
): Snapshot {
    val records = payload.optJSONObject("records")
    val data = records?.optJSONArray("data")
    if (records == null || data == null || data.length() == 0) {
        val keys = payload.keys().asSequence().take(8).toList()
        throw NseException("NSE returned no option-chain rows for ${spec.symbol} (response keys: $keys)")
    }
    var underlying = num(records.opt("underlyingValue"))
    val rows = mutableListOf<StrikeRow>()
    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val ie = itemExpiry(item)
        if (ie != null && ie != expiry) continue
        val ce = item.optJSONObject("CE")
        val pe = item.optJSONObject("PE")
        if (underlying == 0.0) {
            underlying = num(ce?.opt("underlyingValue")).takeIf { it != 0.0 }
                ?: num(pe?.opt("underlyingValue"))
        }
        rows.add(StrikeRow(num(item.opt("strikePrice")), leg(ce), leg(pe)))
    }
    if (rows.isEmpty()) throw NseException("No strikes found for ${spec.symbol} expiry ${fmtDate(expiry)}")
    rows.sortBy { it.strike }
    val ts = records.optString("timestamp", "").ifEmpty { local.toString() }
    return Snapshot(spec, expiry, expiries, underlying, ts, local, rows)
}
