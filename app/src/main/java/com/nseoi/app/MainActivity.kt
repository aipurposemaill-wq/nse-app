package com.nseoi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val GREEN = Color(0xFF16A34A)
private val RED = Color(0xFFDC2626)
private val GRAY = Color(0xFF6B7280)
private val AMBER = Color(0xFFF59E0B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

// ----------------------------------------------------------- formatting
private fun f2(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%,.2f", v)
private fun f0(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%,.0f", v)
private fun lakh(v: Double) = String.format(Locale.US, "%,.2f L", v / 1e5)
private fun lakhS(v: Double) = String.format(Locale.US, "%+,.2f L", v / 1e5)

private fun biasColor(b: Bias) = when (b) { Bias.BULLISH -> GREEN; Bias.BEARISH -> RED; Bias.NEUTRAL -> GRAY }
private fun setupColor(s: String) = when (s) { "BULLISH SETUP" -> GREEN; "BEARISH SETUP" -> RED; else -> GRAY }
private fun statusColor(s: String) = when {
    s == "Live" -> GREEN
    s == "Market Closed" || s == "Auto-refresh off" -> GRAY
    else -> AMBER
}

// ------------------------------------------------------------- widgets
@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(Locale.ROOT), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            pair.forEach { (l, v) -> Metric(l, v, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(text) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(text) }
}

@Composable
private fun BreadthBox(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp, horizontal = 10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = color)
        Text("$value", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun BreadthRow(b: Breadth?) {
    if (b == null) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BreadthBox("Advances", b.advances, GREEN, Modifier.weight(1f))
        BreadthBox("Declines", b.declines, RED, Modifier.weight(1f))
        BreadthBox("Unchanged", b.unchanged, GRAY, Modifier.weight(1f))
    }
}

@Composable
private fun RankList(title: String, ranks: List<StrikeRank>) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    if (ranks.isEmpty()) {
        Text("No positive change-in-OI additions.", fontSize = 12.sp)
    } else {
        ranks.forEach { r ->
            Text(
                "${f0(r.strike)}  ·  ${lakhS(r.changeOi)}  ·  OI ${lakh(r.currentOi)}  ·  ${r.flow.label}",
                fontSize = 12.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- root
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "📈 NSE Option Chain Analytics", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Dashboard", "Intraday", "Settings").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> DashboardScreen(st, vm::refreshNow)
            1 -> IntradayScreen(st, selected) { selected = it }
            else -> SettingsScreen(st.settings, vm::applySettings)
        }
    }
}

// ----------------------------------------------------------- dashboard
@Composable
fun DashboardScreen(st: UiState, onRefresh: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(st.status, statusColor(st.status))
                Spacer(Modifier.width(8.dp))
                Text("Last refresh:\n${st.lastRefresh}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Button(onClick = onRefresh, enabled = !st.loading) { Text(if (st.loading) "Loading…" else "Refresh") }
            }
        }
        if (st.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (st.status == "Market Closed") {
            item { Text("Market is closed. Showing the last collected data. Tap Refresh to fetch now.", fontSize = 13.sp) }
        }
        items(st.errors.entries.toList()) { e ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
            ) { Text("${e.key}: ${e.value}", Modifier.padding(12.dp), fontSize = 12.sp, color = Color(0xFF78350F)) }
        }
        if (st.results.isNotEmpty()) item { SummaryCard(st.results) }
        items(st.results, key = { it.key }) { CardView(it) }
        if (st.results.isEmpty() && !st.loading && st.errors.isEmpty()) {
            item { Text("No data yet. Tap Refresh.") }
        }
        item {
            Text(
                "Data sourced from NSE India. May be delayed. For educational and personal use only. " +
                    "This app does not provide financial or trading advice.",
                fontSize = 11.sp, color = GRAY,
            )
        }
    }
}

@Composable
private fun SummaryCard(results: List<Analysis>) {
    val withPcr = results.filter { it.stats.pcr != null }
    val hi = withPcr.maxByOrNull { it.stats.pcr ?: 0.0 }
    val lo = withPcr.minByOrNull { it.stats.pcr ?: 0.0 }
    fun score(r: Analysis) = r.stats.putChg - r.stats.callChg
    val bull = results.maxByOrNull { score(it) }
    val bear = results.minByOrNull { score(it) }
    fun line(t: String, r: Analysis?) = if (r == null) "$t: —" else "$t: ${r.label} (PCR ${f2(r.stats.pcr)})"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(line("Highest PCR", hi), fontSize = 13.sp)
            Text(line("Lowest PCR", lo), fontSize = 13.sp)
            Text(line("Most Bullish", bull), fontSize = 13.sp)
            Text(line("Most Bearish", bear), fontSize = 13.sp)
        }
    }
}

@Composable
private fun CardView(r: Analysis) {
    val s = r.stats
    val mp = r.maxPain
    val mpText = if (mp == null) "—"
    else "${f0(mp.strike)} (${String.format(Locale.US, "%+,.0f", mp.distance)} pts)"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Expiry ${r.expiryText}", fontSize = 12.sp, color = GRAY)
                }
                Pill(r.bias.name, biasColor(r.bias))
            }
            BreadthRow(r.breadth)
            MetricGrid(
                listOf(
                    "Underlying" to f2(r.spot),
                    "PCR" to f2(s.pcr),
                    "Total Call OI" to lakh(s.callOi),
                    "Total Put OI" to lakh(s.putOi),
                    "CE Change OI" to lakhS(s.callChg),
                    "PE Change OI" to lakhS(s.putChg),
                    "Max Pain" to mpText,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Supports: " + (r.supports.mapIndexed { i, p -> "S${i + 1} ${f0(p.first)}" }.joinToString(", ").ifEmpty { "—" }),
                fontSize = 13.sp,
            )
            Text(
                "Resistances: " + (r.resistances.mapIndexed { i, p -> "R${i + 1} ${f0(p.first)}" }.joinToString(", ").ifEmpty { "—" }),
                fontSize = 13.sp,
            )
            RankList("Top 3 CE OI additions", r.ceAdds)
            RankList("Top 3 PE OI additions", r.peAdds)
            Text("NSE time: ${r.snapshot.nseTimestamp}", fontSize = 11.sp, color = GRAY, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ------------------------------------------------------------ intraday
@Composable
fun IntradayScreen(st: UiState, selectedKey: String?, onSelect: (String) -> Unit) {
    if (st.results.isEmpty()) {
        Text("No analysis yet. Open the Dashboard and tap Refresh.", Modifier.padding(16.dp))
        return
    }
    val r = st.results.firstOrNull { it.key == selectedKey } ?: st.results.first()
    val setup = r.setup
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.results.forEach { a -> Choice(a.label, a.key == r.key) { onSelect(a.key) } }
        }
        Spacer(Modifier.height(12.dp))
        Text("${r.label} · Expiry ${r.expiryText}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Pill(setup.setup, setupColor(setup.setup))
        BreadthRow(r.breadth)
        Spacer(Modifier.height(8.dp))
        MetricGrid(
            listOf(
                "Support" to f0(setup.support),
                "Resistance" to f0(setup.resistance),
                "Expected range" to (if (setup.support == null || setup.resistance == null) "—"
                else "${f0(setup.support)} – ${f0(setup.resistance)}"),
                "Bias" to setup.biasText,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text("Setup conditions", fontWeight = FontWeight.SemiBold)
        setup.conditions.forEach { (name, ok) -> Text("${if (ok) "✅" else "⬜"} $name", fontSize = 14.sp) }
        RankList("Top CE additions", r.ceAdds)
        RankList("Top PE additions", r.peAdds)
        Spacer(Modifier.height(12.dp))
        Text("Commentary", fontWeight = FontWeight.SemiBold)
        Text(setup.commentary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("Not financial advice. For educational use only.", fontSize = 11.sp, color = GRAY)
    }
}

// ------------------------------------------------------------ settings
@Composable
fun SettingsScreen(current: AppSettings, onApply: (AppSettings) -> String?) {
    var instruments by remember(current) { mutableStateOf(current.instruments) }
    var minutes by remember(current) { mutableIntStateOf(current.refreshMinutes) }
    var marketOnly by remember(current) { mutableStateOf(current.marketOnly) }
    var streaming by remember(current) { mutableStateOf(current.streaming) }
    var expiryMode by remember(current) { mutableStateOf(current.expiryMode) }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(
            value = instruments, onValueChange = { instruments = it },
            label = { Text("Instruments (comma separated, max 20)") },
            supportingText = { Text("Examples: NIFTY, BANKNIFTY, RELIANCE, SBIN, NIFTY@28-Nov-2026") },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Spacer(Modifier.height(16.dp))
        Text("Refresh interval (minutes)", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REFRESH_CHOICES.forEach { m -> Choice("$m", minutes == m) { minutes = m } }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Market hours only (Mon–Fri 09:15–15:30 IST)", Modifier.weight(1f))
            Switch(checked = marketOnly, onCheckedChange = { marketOnly = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic refresh ON", Modifier.weight(1f))
            Switch(checked = streaming, onCheckedChange = { streaming = it })
        }
        Spacer(Modifier.height(16.dp))
        Text("Expiry (when none is given)", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Nearest", expiryMode == "nearest") { expiryMode = "nearest" }
            Choice("Next", expiryMode == "next") { expiryMode = "next" }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            val err = onApply(AppSettings(instruments, minutes, marketOnly, streaming, expiryMode))
            message = err ?: "Settings applied."
        }, modifier = Modifier.fillMaxWidth()) { Text("Apply settings") }
        if (message.isNotEmpty()) Text(message, Modifier.padding(top = 8.dp), color = if (message == "Settings applied.") GREEN else RED)
    }
}
