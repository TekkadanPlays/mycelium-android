package social.mycelium.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.mycelium.android.debug.DiagnosticLog

private enum class ChannelTab(val label: String, val channel: DiagnosticLog.Channel?, val tint: Color) {
    ALL("All", null, Color(0xFF90A4AE)),
    STARTUP("Startup", DiagnosticLog.Channel.STARTUP, Color(0xFF42A5F5)),
    RELAY("Relay", DiagnosticLog.Channel.RELAY, Color(0xFF26C6DA)),
    FEED("Feed", DiagnosticLog.Channel.FEED, Color(0xFF66BB6A)),
    SYNC("Sync", DiagnosticLog.Channel.SYNC, Color(0xFFAB47BC)),
    STATE("State", DiagnosticLog.Channel.STATE, Color(0xFF5C6BC0)),
    AUTH("Auth", DiagnosticLog.Channel.AUTH, Color(0xFFFFCA28)),
    NOTIF("Notif", DiagnosticLog.Channel.NOTIFICATION, Color(0xFFEC407A)),
    WALLET("Wallet", DiagnosticLog.Channel.WALLET, Color(0xFFFFA726)),
    GENERAL("General", DiagnosticLog.Channel.GENERAL, Color(0xFF78909C)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogViewerScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(ChannelTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    var refreshTrigger by remember { mutableStateOf(0) }

    // Structured effect: auto-cancels the previous load when selectedTab or
    // refreshTrigger changes, preventing race conditions between concurrent reads.
    LaunchedEffect(selectedTab, refreshTrigger) {
        isLoading = true
        val ch = selectedTab.channel
        val lines = withContext(Dispatchers.IO) {
            if (ch != null) DiagnosticLog.readChannel(ch) else DiagnosticLog.readAll()
        }
        logLines = lines
        isLoading = false
        // Reset scroll so we don't exceed the new list's bounds
        listState.scrollToItem(0)
    }

    val filteredLines = remember(logLines, searchQuery) {
        val parsed = logLines.map { parseLine(it) }
        if (searchQuery.isBlank()) parsed
        else parsed.filter { it.raw.contains(searchQuery, ignoreCase = true) }
    }

    val showScrollFab by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            filteredLines.size > 20 && last < filteredLines.size - 3
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic Logs", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Outlined.Refresh, "Refresh")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val export = withContext(Dispatchers.IO) { DiagnosticLog.buildExport() }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Logs", export))
                            Toast.makeText(context, "Logs copied (${export.length} chars)", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, "Copy All")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val export = withContext(Dispatchers.IO) { DiagnosticLog.buildExport() }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, export)
                                putExtra(Intent.EXTRA_SUBJECT, "Mycelium diagnostic logs")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share logs"))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, "Share")
                    }
                    IconButton(onClick = {
                        DiagnosticLog.clearAll()
                        logLines = emptyList()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.DeleteSweep, "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = showScrollFab, enter = fadeIn(), exit = fadeOut()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(filteredLines.size) } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Outlined.KeyboardDoubleArrowDown, "Scroll to bottom")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Channel tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChannelTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    FilterChip(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tab.tint.copy(alpha = 0.2f),
                            selectedLabelColor = tab.tint,
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter logs…", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )

            // Stats ribbon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append("${filteredLines.size}")
                        }
                        append(" lines")
                        if (searchQuery.isNotBlank()) {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append(" (filtered)")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (isLoading) "Loading…" else selectedTab.channel?.filename ?: "all channels",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // Log entries
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                if (filteredLines.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("\uD83D\uDCCB", fontSize = 32.sp,
                                    modifier = Modifier.padding(bottom = 8.dp))
                                Text(
                                    if (searchQuery.isNotBlank()) "No matching lines"
                                    else "No logs in ${selectedTab.label}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (searchQuery.isNotBlank()) {
                                    Text("Try a different search term",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(filteredLines, key = { index, _ -> index }) { index, parsed ->
                    LogEntryRow(parsed, index, searchQuery)
                }
            }
        }
    }
}

// ── Parsed log entry ────────────────────────────────────────────────────────

private data class ParsedLogLine(
    val raw: String,
    val timestamp: String,
    val level: Char?,
    val channel: String,
    val source: String,
    val message: String,
    val isSessionMarker: Boolean,
)

private fun parseLine(raw: String): ParsedLogLine {
    if (raw.isBlank() || raw.contains("════") || raw.trimStart().startsWith("SESSION START")
        || raw.trimStart().startsWith("version=") || raw.trimStart().startsWith("sdk=")) {
        return ParsedLogLine(raw, "", null, "", "", raw.trim(), isSessionMarker = true)
    }
    val parts = raw.split(" | ")
    return when {
        parts.size >= 5 && parts[1].trim().length == 1 && parts[1].trim()[0] in "VDIWE" ->
            ParsedLogLine(raw, parts[0].trim(), parts[1].trim()[0],
                parts[2].trim(), parts[3].trim(), parts.drop(4).joinToString(" | "), false)
        parts.size >= 4 ->
            ParsedLogLine(raw, parts[0].trim(), 'I',
                parts[1].trim(), parts[2].trim(), parts.drop(3).joinToString(" | "), false)
        else -> ParsedLogLine(raw, "", null, "", "", raw, false)
    }
}

private fun levelColor(level: Char?): Color = when (level) {
    'V' -> Color(0xFF78909C); 'D' -> Color(0xFF42A5F5); 'I' -> Color(0xFF66BB6A)
    'W' -> Color(0xFFFFA726); 'E' -> Color(0xFFEF5350); else -> Color(0xFF90A4AE)
}

private fun levelLabel(level: Char?): String = when (level) {
    'V' -> "VRB"; 'D' -> "DBG"; 'I' -> "INF"; 'W' -> "WRN"; 'E' -> "ERR"; else -> "---"
}

// ── Log entry row ───────────────────────────────────────────────────────────

@Composable
private fun LogEntryRow(parsed: ParsedLogLine, index: Int, searchQuery: String) {
    if (parsed.isSessionMarker) {
        SessionMarkerRow(parsed)
        return
    }
    val evenBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val oddBg = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
    val accent = levelColor(parsed.level)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (index % 2 == 0) evenBg else oddBg)
            .drawBehind {
                drawRect(accent, Offset.Zero, Size(3.dp.toPx(), size.height))
            }
            .padding(start = 7.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(parsed.timestamp, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1, modifier = Modifier.width(72.dp).padding(top = 1.dp))
        // Level badge
        Text(levelLabel(parsed.level), fontSize = 8.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = accent, maxLines = 1,
            modifier = Modifier.background(accent.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp))
        Spacer(Modifier.width(4.dp))
        // Source tag
        if (parsed.source.isNotBlank()) {
            Text(parsed.source, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(90.dp).padding(top = 1.dp))
        }
        Spacer(Modifier.width(4.dp))
        // Message with search highlighting
        Text(text = highlightSearch(parsed.message, searchQuery), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            color = when (parsed.level) {
                'E' -> Color(0xFFEF5350); 'W' -> Color(0xFFFFA726)
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SessionMarkerRow(parsed: ParsedLogLine) {
    val text = parsed.message.ifBlank { parsed.raw }
    val isBanner = text.contains("════") || text.contains("SESSION START")
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = if (isBanner) 2.dp else 1.dp)
    ) {
        Text(text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (isBanner) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Search highlighting ─────────────────────────────────────────────────────

@Composable
private fun highlightSearch(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val hl = MaterialTheme.colorScheme.inversePrimary
    val fg = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        var start = 0
        val lower = text.lowercase()
        val lq = query.lowercase()
        while (start < text.length) {
            val idx = lower.indexOf(lq, start)
            if (idx == -1) { append(text.substring(start)); break }
            if (idx > start) append(text.substring(start, idx))
            withStyle(SpanStyle(background = hl, color = fg, fontWeight = FontWeight.Bold)) {
                append(text.substring(idx, idx + query.length))
            }
            start = idx + query.length
        }
    }
}
