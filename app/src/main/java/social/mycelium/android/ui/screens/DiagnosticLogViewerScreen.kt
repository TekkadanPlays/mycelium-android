package social.mycelium.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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
import org.json.JSONObject
import social.mycelium.android.debug.DiagnosticLog
import social.mycelium.android.debug.EventLog

// ── Top-level view mode ─────────────────────────────────────────────────────

private enum class ViewMode(val label: String) {
    LOGS("Logs"),
    TIMELINE("Timeline"),
    STARTUP("Startup"),
    HEALTH("Relay Health"),
}

// ── Tier 1: Channel group tabs ──────────────────────────────────────────────

private enum class GroupTab(
    val label: String,
    val group: DiagnosticLog.ChannelGroup?,
    val tint: Color,
) {
    ALL("All", null, Color(0xFF90A4AE)),
    NETWORK("Network", DiagnosticLog.ChannelGroup.NETWORK, Color(0xFF26C6DA)),
    DATA("Data", DiagnosticLog.ChannelGroup.DATA, Color(0xFF66BB6A)),
    SYSTEM("System", DiagnosticLog.ChannelGroup.SYSTEM, Color(0xFF42A5F5)),
    PAYMENTS("Payments", DiagnosticLog.ChannelGroup.PAYMENTS, Color(0xFFFFA726)),
}

// ── Level filter ────────────────────────────────────────────────────────────

private enum class LevelFilter(val char: Char, val label: String, val color: Color) {
    VERBOSE('V', "Verbose", Color(0xFF78909C)),
    DEBUG('D', "Debug", Color(0xFF42A5F5)),
    INFO('I', "Info", Color(0xFF66BB6A)),
    WARN('W', "Warn", Color(0xFFFFA726)),
    ERROR('E', "Error", Color(0xFFEF5350)),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogViewerScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── State ────────────────────────────────────────────────────────────
    var viewMode by rememberSaveable { mutableStateOf(ViewMode.LOGS.name) }
    val activeViewMode = remember(viewMode) { ViewMode.entries.firstOrNull { it.name == viewMode } ?: ViewMode.LOGS }
    var selectedGroup by rememberSaveable { mutableStateOf(GroupTab.ALL.name) }
    var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var traceMode by rememberSaveable { mutableStateOf(false) }
    var enabledLevels by remember { mutableStateOf(LevelFilter.entries.map { it.char }.toSet()) }
    var rawLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    var refreshTrigger by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) { pendingExportContent = null; return@rememberLauncherForActivityResult }
        val content = pendingExportContent ?: return@rememberLauncherForActivityResult
        pendingExportContent = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    true
                } catch (_: Exception) { false }
            }
            Toast.makeText(
                context,
                if (ok) "Saved ${content.length} chars" else "Save failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val activeGroup = remember(selectedGroup) {
        GroupTab.entries.firstOrNull { it.name == selectedGroup } ?: GroupTab.ALL
    }

    val hasActiveFilters by remember(enabledLevels) {
        derivedStateOf { enabledLevels.size < LevelFilter.entries.size }
    }

    // ── Load raw lines when group or refresh changes ─────────────────────
    LaunchedEffect(selectedGroup, refreshTrigger) {
        isLoading = true
        val group = GroupTab.entries.firstOrNull { it.name == selectedGroup } ?: GroupTab.ALL
        val lines = withContext(Dispatchers.IO) {
            if (group.group != null) DiagnosticLog.readGroup(group.group)
            else DiagnosticLog.readAll()
        }
        rawLines = lines
        isLoading = false
        listState.scrollToItem(0)
    }

    // ── Parse + filter on Default thread ─────────────────────────────────
    var allParsed by remember { mutableStateOf<List<ParsedLogEntry>>(emptyList()) }
    LaunchedEffect(rawLines) {
        allParsed = withContext(Dispatchers.Default) {
            rawLines.map { parseLogEntry(it) }
        }
    }

    val sourceTags by remember(allParsed) {
        derivedStateOf {
            allParsed.filter { !it.isSessionMarker && it.source.isNotBlank() }
                .groupingBy { it.source }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key to it.value }
        }
    }

    val displayEntries by remember(allParsed, selectedSource, searchQuery, enabledLevels, traceMode) {
        derivedStateOf {
            var list = allParsed
            if (selectedSource != null) list = list.filter { it.source == selectedSource }
            if (enabledLevels.size < LevelFilter.entries.size) {
                list = list.filter { it.isSessionMarker || it.level in enabledLevels }
            }
            if (searchQuery.isNotBlank()) {
                list = list.filter { it.raw.contains(searchQuery, ignoreCase = true) }
            }
            list
        }
    }

    val traceGroups by remember(displayEntries, traceMode) {
        derivedStateOf<Map<String, List<ParsedLogEntry>>> {
            if (!traceMode) emptyMap()
            else displayEntries.filter { it.traceId != null }
                .groupBy { it.traceId!! }
                .toSortedMap()
        }
    }

    val showScrollFab by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            displayEntries.size > 20 && last < displayEntries.size - 3
        }
    }

    // Auto-focus search field when it becomes visible
    LaunchedEffect(searchVisible) {
        if (searchVisible) focusRequester.requestFocus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search logs…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            fontSize = 16.sp,
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    } else {
                        Text("Logs", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searchVisible) {
                            searchVisible = false
                            searchQuery = ""
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            if (searchVisible) Icons.Outlined.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            "Back"
                        )
                    }
                },
                actions = {
                    if (searchVisible && searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, "Clear search", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (!searchVisible) {
                        IconButton(onClick = { searchVisible = true }) {
                            Icon(Icons.Outlined.Search, "Search")
                        }
                    }
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Outlined.Refresh, "Refresh")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Group by trace") },
                                onClick = { traceMode = !traceMode },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.AccountTree, null,
                                        tint = if (traceMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (traceMode) {
                                        Text(
                                            "ON",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                "Log levels",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            LevelFilter.entries.forEach { lf ->
                                val enabled = lf.char in enabledLevels
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(lf.color, CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(lf.label)
                                        }
                                    },
                                    onClick = {
                                        enabledLevels = if (enabled) enabledLevels - lf.char
                                        else enabledLevels + lf.char
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = enabled,
                                            onCheckedChange = {
                                                enabledLevels = if (enabled) enabledLevels - lf.char
                                                else enabledLevels + lf.char
                                            }
                                        )
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text("Copy all") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val export = withContext(Dispatchers.IO) { DiagnosticLog.buildExport() }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Logs", export))
                                        Toast.makeText(context, "Copied ${export.length} chars", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val export = withContext(Dispatchers.IO) { DiagnosticLog.buildExport() }
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, export)
                                            putExtra(Intent.EXTRA_SUBJECT, "Mycelium diagnostic logs")
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share logs"))
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to file") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val export = withContext(Dispatchers.IO) { DiagnosticLog.buildExport() }
                                        pendingExportContent = export
                                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                            .format(java.util.Date())
                                        saveFileLauncher.launch("mycelium_logs_$ts.txt")
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear logs", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    DiagnosticLog.clearAll()
                                    social.mycelium.android.debug.EventLog.clear()
                                    rawLines = emptyList()
                                    allParsed = emptyList()
                                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteSweep, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
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
                    onClick = { scope.launch { listState.animateScrollToItem(displayEntries.size) } },
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
            // ── View mode tab row ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ViewMode.entries.forEach { mode ->
                    val selected = mode.name == viewMode
                    FilterChip(
                        selected = selected,
                        onClick = { viewMode = mode.name },
                        label = { Text(mode.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // ── Route to view mode ────────────────────────────────────────
            when (activeViewMode) {
                ViewMode.TIMELINE -> { EventTimelineView(); return@Column }
                ViewMode.STARTUP  -> { StartupWaterfallView(); return@Column }
                ViewMode.HEALTH   -> { RelayHealthView(); return@Column }
                ViewMode.LOGS     -> { /* fall through to existing log viewer below */ }
            }

            // ── Group chips + entry count ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupTab.entries.forEach { tab ->
                    val selected = tab.name == selectedGroup
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedGroup = tab.name
                            selectedSource = null
                        },
                        label = { Text(tab.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tab.tint.copy(alpha = 0.2f),
                            selectedLabelColor = tab.tint,
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
                Text(
                    if (isLoading) "…"
                    else "${displayEntries.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // ── Source tag chips (only when a specific group is selected) ──
            AnimatedVisibility(
                visible = activeGroup != GroupTab.ALL && sourceTags.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedSource == null,
                            onClick = { selectedSource = null },
                            label = {
                                Text("All", fontSize = 11.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = activeGroup.tint.copy(alpha = 0.15f),
                                selectedLabelColor = activeGroup.tint,
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    items(sourceTags) { (source, count) ->
                        FilterChip(
                            selected = source == selectedSource,
                            onClick = { selectedSource = if (selectedSource == source) null else source },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(source, fontSize = 11.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "$count",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = activeGroup.tint,
                                        modifier = Modifier
                                            .background(
                                                activeGroup.tint.copy(alpha = 0.15f),
                                                CircleShape
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = activeGroup.tint.copy(alpha = 0.15f),
                                selectedLabelColor = activeGroup.tint,
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // ── Active filter indicators ──────────────────────────────────
            AnimatedVisibility(
                visible = hasActiveFilters || traceMode || searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasActiveFilters) {
                        val hidden = LevelFilter.entries.filter { it.char !in enabledLevels }
                        Text(
                            "Hidden: ${hidden.joinToString(", ") { it.label }}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    if (traceMode) {
                        Text(
                            "${traceGroups.size} traces",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ── Log entries ──────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                if (displayEntries.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (searchQuery.isNotBlank()) "No matching entries"
                                else "No logs yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (traceMode && traceGroups.isNotEmpty()) {
                    val ungrouped = displayEntries.filter { it.traceId == null }
                    traceGroups.forEach { (traceId, entries) ->
                        item(key = "trace_header_$traceId") {
                            TraceGroupHeader(
                                traceId = traceId,
                                entries = entries,
                                tint = activeGroup.tint
                            )
                        }
                        itemsIndexed(entries, key = { i, e -> "trace_${traceId}_$i" }) { index, entry ->
                            LogEntryRow(entry, index, searchQuery, indented = true)
                        }
                    }
                    if (ungrouped.isNotEmpty()) {
                        item(key = "ungrouped_header") {
                            TraceGroupHeader(
                                traceId = "Ungrouped",
                                entries = ungrouped,
                                tint = Color(0xFF90A4AE)
                            )
                        }
                        itemsIndexed(ungrouped, key = { i, _ -> "ungrouped_$i" }) { index, entry ->
                            LogEntryRow(entry, index, searchQuery, indented = true)
                        }
                    }
                } else {
                    itemsIndexed(displayEntries, key = { index, _ -> index }) { index, entry ->
                        LogEntryRow(entry, index, searchQuery, indented = false)
                    }
                }
            }
        }
    }
}

// ── Parsed log entry ────────────────────────────────────────────────────────

private data class ParsedLogEntry(
    val raw: String,
    val timestamp: String,
    val level: Char?,
    val channel: String,
    val source: String,
    val message: String,
    val traceId: String?,
    val isSessionMarker: Boolean,
)

private val TRACE_PREFIX = Regex("""\[([^\]]+)] (.*)""")

private fun parseLogEntry(raw: String): ParsedLogEntry {
    if (raw.isBlank() || raw.contains("════") || raw.trimStart().startsWith("SESSION START")
        || raw.trimStart().startsWith("version=") || raw.trimStart().startsWith("sdk=")) {
        return ParsedLogEntry(raw, "", null, "", "", raw.trim(), null, isSessionMarker = true)
    }
    val parts = raw.split(" | ")
    val (channel, source, msgRaw, level) = when {
        parts.size >= 5 && parts[1].trim().length == 1 && parts[1].trim()[0] in "VDIWE" ->
            listOf(parts[2].trim(), parts[3].trim(), parts.drop(4).joinToString(" | "), parts[1].trim())
        parts.size >= 4 ->
            listOf(parts[1].trim(), parts[2].trim(), parts.drop(3).joinToString(" | "), "I")
        else -> return ParsedLogEntry(raw, "", null, "", "", raw, null, false)
    }
    val ts = parts[0].trim()
    val lvl = level.firstOrNull()

    val traceMatch = TRACE_PREFIX.matchEntire(msgRaw)
    val traceId = traceMatch?.groupValues?.get(1)
    val message = traceMatch?.groupValues?.get(2) ?: msgRaw

    return ParsedLogEntry(raw, ts, lvl, channel, source, message, traceId, false)
}

private fun levelColor(level: Char?): Color = when (level) {
    'V' -> Color(0xFF78909C); 'D' -> Color(0xFF42A5F5); 'I' -> Color(0xFF66BB6A)
    'W' -> Color(0xFFFFA726); 'E' -> Color(0xFFEF5350); else -> Color(0xFF90A4AE)
}

private fun levelLabel(level: Char?): String = when (level) {
    'V' -> "VRB"; 'D' -> "DBG"; 'I' -> "INF"; 'W' -> "WRN"; 'E' -> "ERR"; else -> "---"
}

// ── Trace group header ──────────────────────────────────────────────────────

@Composable
private fun TraceGroupHeader(
    traceId: String,
    entries: List<ParsedLogEntry>,
    tint: Color,
) {
    val first = entries.firstOrNull()?.timestamp ?: ""
    val last = entries.lastOrNull()?.timestamp ?: ""
    val span = if (first.isNotBlank() && last.isNotBlank() && first != last) "$first → $last" else first
    val warnCount = entries.count { it.level == 'W' }
    val errCount = entries.count { it.level == 'E' }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Outlined.AccountTree, null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
        Text(
            traceId,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (errCount > 0) {
            Text(
                "${errCount}E",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFEF5350),
                modifier = Modifier
                    .background(Color(0xFFEF5350).copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        if (warnCount > 0) {
            Text(
                "${warnCount}W",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFA726),
                modifier = Modifier
                    .background(Color(0xFFFFA726).copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            "${entries.size}",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = tint,
            modifier = Modifier
                .background(tint.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        Text(
            span,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}

// ── Log entry row ───────────────────────────────────────────────────────────

@Composable
private fun LogEntryRow(
    parsed: ParsedLogEntry,
    index: Int,
    searchQuery: String,
    indented: Boolean = false,
) {
    if (parsed.isSessionMarker) {
        SessionMarkerRow(parsed)
        return
    }
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val evenBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val oddBg = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
    val accent = levelColor(parsed.level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (index % 2 == 0) evenBg else oddBg)
            .drawBehind {
                val barX = if (indented) 8.dp.toPx() else 0f
                drawRect(accent, Offset(barX, 0f), Size(3.dp.toPx(), size.height))
            }
            .clickable { expanded = !expanded }
            .padding(
                start = if (indented) 14.dp else 7.dp,
                end = 4.dp, top = 3.dp, bottom = 3.dp
            )
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(parsed.timestamp, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1, modifier = Modifier.width(72.dp).padding(top = 1.dp))
            Text(levelLabel(parsed.level), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = accent, maxLines = 1,
                modifier = Modifier
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp))
            Spacer(Modifier.width(4.dp))
            if (parsed.source.isNotBlank()) {
                Text(parsed.source, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(90.dp).padding(top = 1.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = highlightSearch(parsed.message, searchQuery),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
                color = when (parsed.level) {
                    'E' -> Color(0xFFEF5350); 'W' -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded && parsed.traceId != null) {
            Text(
                "trace: ${parsed.traceId}",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 72.dp, top = 2.dp)
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier.padding(start = 72.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Copy",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Log", parsed.raw))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    parsed.channel,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun SessionMarkerRow(parsed: ParsedLogEntry) {
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

// ── Event JSON parsing ──────────────────────────────────────────────────────

private data class LogEvent(
    val ts: Long,
    val ev: String,
    val ch: String,
    val tag: String,
    val sid: String?,
    val data: Map<String, String>,  // all extra fields as strings for display
)

private fun parseEventJsonl(lines: List<String>): List<LogEvent> {
    val result = mutableListOf<LogEvent>()
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) continue
        try {
            val obj = JSONObject(trimmed)
            val ts  = obj.optLong("ts", 0L)
            val ev  = obj.optString("ev", "")
            val ch  = obj.optString("ch", "")
            val tag = obj.optString("tag", "")
            val sid = obj.optString("sid", "").takeIf { it.isNotEmpty() }
            if (ev.isEmpty() || ts == 0L) continue
            val skip = setOf("ts", "ev", "ch", "tag", "sid")
            val extra = mutableMapOf<String, String>()
            obj.keys().forEach { k -> if (k !in skip) extra[k] = obj.get(k).toString() }
            result.add(LogEvent(ts, ev, ch, tag, sid, extra))
        } catch (_: Exception) {}
    }
    return result
}

private fun fmtTs(tsMs: Long): String {
    val d = java.util.Date(tsMs)
    return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(d)
}

private fun fmtMs(ms: Long?): String {
    if (ms == null || ms < 0) return "—"
    return if (ms < 1000) "${ms}ms" else "${"%.1f".format(ms / 1000.0)}s"
}

private val CHANNEL_COLORS = mapOf(
    "STARTUP"  to Color(0xFF26C6DA),
    "RELAY"    to Color(0xFF42A5F5),
    "AUTH"     to Color(0xFFBA68C8),
    "FEED"     to Color(0xFF66BB6A),
    "WALLET"   to Color(0xFFFFA726),
    "SYNC"     to Color(0xFF90A4AE),
    "SYSTEM"   to Color(0xFF90A4AE),
)

private fun chColor(ch: String) = CHANNEL_COLORS[ch] ?: Color(0xFF90A4AE)

// ── Timeline view ────────────────────────────────────────────────────────────

@Composable
private fun EventTimelineView() {
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isLoading = true
        events = withContext(Dispatchers.IO) { parseEventJsonl(EventLog.readAll()) }
        isLoading = false
    }

    val filtered by remember(events, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) events
            else events.filter { e ->
                e.ev.contains(searchQuery, true) ||
                e.ch.contains(searchQuery, true) ||
                e.tag.contains(searchQuery, true) ||
                e.data.values.any { it.contains(searchQuery, true) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (searchQuery.isEmpty()) Text("Filter events…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 13.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (events.isEmpty()) "No events yet — use the app to generate events" else "No matching events",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            itemsIndexed(filtered) { i, ev ->
                val bg = if (i % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLowest
                         else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f)
                val accent = chColor(ev.ch)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .drawBehind { drawRect(accent, Offset(0f, 0f), Size(3.dp.toPx(), size.height)) }
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Timestamp
                    Text(
                        fmtTs(ev.ts),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.width(72.dp),
                    )
                    // Channel badge
                    Text(
                        ev.ch.take(7),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                        modifier = Modifier.width(56.dp),
                    )
                    // Event + data
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(ev.ev, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (ev.sid != null) {
                                Text("[${ev.sid}]", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                        if (ev.data.isNotEmpty()) {
                            Text(
                                ev.data.entries.take(4).joinToString("  ") { (k, v) ->
                                    val vDisplay = if (v.length > 40) v.take(37) + "…" else v
                                    "$k=$vDisplay"
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Startup waterfall view ────────────────────────────────────────────────────

private val PHASE_NAMES_MAP = mapOf(0 to "SETTINGS", 1 to "USER_STATE", 2 to "FEED", 3 to "ENRICHMENT", 4 to "BACKGROUND")
private val PHASE_COLORS = listOf(
    Color(0xFF26C6DA), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFFBA68C8),
)

@Composable
private fun StartupWaterfallView() {
    var events by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        events = withContext(Dispatchers.IO) { parseEventJsonl(EventLog.readAll()) }
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Find most recent session's phase events (after last startup.reset or session.start)
    val sessionEvents = buildList {
        var collecting = false
        for (e in events) {
            if (e.ev == "session.start" || e.ev == "startup.reset") { clear(); collecting = true }
            if (collecting && (e.ev.startsWith("startup.phase"))) add(e)
        }
    }

    data class PhaseRow(val phase: Int, val name: String, val startMs: Long, val endMs: Long?, val relayCount: Int)

    val starts = mutableMapOf<Int, LogEvent>()
    val rows = mutableListOf<PhaseRow>()
    for (e in sessionEvents) {
        when (e.ev) {
            "startup.phase_start" -> {
                val ph = e.data["phase"]?.toIntOrNull() ?: continue
                starts[ph] = e
            }
            "startup.phase_end" -> {
                val ph = e.data["phase"]?.toIntOrNull() ?: continue
                val start = starts[ph] ?: continue
                rows.add(PhaseRow(ph, PHASE_NAMES_MAP[ph] ?: "Phase $ph",
                    start.ts, e.ts, start.data["relay_count"]?.toIntOrNull() ?: 0))
            }
        }
    }
    // Add in-progress phases
    for ((ph, startEv) in starts) {
        if (rows.none { it.phase == ph }) {
            rows.add(PhaseRow(ph, PHASE_NAMES_MAP[ph] ?: "Phase $ph", startEv.ts, null,
                startEv.data["relay_count"]?.toIntOrNull() ?: 0))
        }
    }
    rows.sortBy { it.phase }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No startup events yet. Open the app fresh to generate startup telemetry.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp))
        }
        return
    }

    val sessionStartMs = rows.minOf { it.startMs }
    val sessionEndMs = rows.maxOf { it.endMs ?: (it.startMs + 5000L) }
    val totalMs = (sessionEndMs - sessionStartMs).coerceAtLeast(1L)

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("STARTUP WATERFALL", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        items(rows) { row ->
            val color = PHASE_COLORS.getOrElse(row.phase) { Color(0xFF90A4AE) }
            val elapsed = row.endMs?.let { it - row.startMs }
            val isComplete = row.endMs != null

            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Phase ${row.phase}  ${row.name}",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color,
                        fontWeight = FontWeight.Bold)
                    Text(
                        if (isComplete) fmtMs(elapsed) else "in progress…",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (isComplete) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Bar showing proportion of total session time used by this phase
                val fillFraction = (row.endMs?.let { (it - row.startMs).toFloat() / totalMs } ?: 0.3f).coerceIn(0.02f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(color.copy(alpha = 0.08f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fillFraction)
                            .fillMaxHeight()
                            .background(color.copy(alpha = if (isComplete) 0.65f else 0.3f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    )
                }
                if (row.relayCount > 0) {
                    Text("${row.relayCount} relays", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        item {
            val totalElapsed = rows.lastOrNull { it.endMs != null }?.let { it.endMs!! - sessionStartMs }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))
            Text(
                "Total cold start: ${fmtMs(totalElapsed)}",
                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Session start: ${fmtTs(sessionStartMs)}",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Relay health view ─────────────────────────────────────────────────────────

@Composable
private fun RelayHealthView() {
    var events by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        events = withContext(Dispatchers.IO) { parseEventJsonl(EventLog.readAll()) }
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    data class RelayStats(
        val url: String,
        var connects: Int = 0,
        var failures: Int = 0,
        val latencies: MutableList<Long> = mutableListOf(),
        var blocked: Boolean = false,
        var flagged: Boolean = false,
        var blockedHours: Int = 6,
        var eoseCount: Int = 0,
    ) {
        val avgLatency: Long? get() = if (latencies.isEmpty()) null else latencies.sum() / latencies.size
        val failRate: Float get() = if (connects == 0) 0f else failures.toFloat() / connects
    }

    val stats = mutableMapOf<String, RelayStats>()
    fun get(url: String) = stats.getOrPut(url) { RelayStats(url) }

    for (e in events) {
        val url = e.data["url"] ?: continue
        when (e.ev) {
            "relay.connecting"  -> get(url).connects++
            "relay.connected"   -> e.data["latency_ms"]?.toLongOrNull()?.let { get(url).latencies.add(it) }
            "relay.failed"      -> get(url).failures++
            "relay.flagged"     -> get(url).flagged = true
            "relay.unflagged"   -> get(url).flagged = false
            "relay.blocked"     -> { get(url).blocked = true; get(url).blockedHours = e.data["duration_hours"]?.toIntOrNull() ?: 6 }
            "relay.unblocked"   -> get(url).blocked = false
            "sub.eose"          -> get(url).eoseCount++
        }
    }

    if (stats.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No relay events yet. Connect to relays to generate health data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        return
    }

    val sorted = stats.values.sortedWith(
        compareByDescending<RelayStats> { it.blocked }.thenByDescending { it.flagged }.thenByDescending { it.connects }
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
        item {
            Text(
                "  ${sorted.size} relays observed  ·  ${sorted.count { it.blocked }} blocked  ·  ${sorted.count { it.flagged }} flagged",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
        items(sorted) { s ->
            val displayUrl = s.url.removePrefix("wss://").removePrefix("ws://")
            val statusColor = when {
                s.blocked -> Color(0xFFEF5350)
                s.flagged -> Color(0xFFFFA726)
                s.failRate > 0.3f -> Color(0xFFFFA726)
                else -> Color(0xFF66BB6A)
            }
            val statusLabel = when {
                s.blocked -> "BLOCKED (${s.blockedHours}h)"
                s.flagged -> "FLAGGED"
                s.failRate > 0.3f -> "degraded"
                else -> "OK"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(statusColor, Offset(0f, 0f), Size(3.dp.toPx(), size.height)) }
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(displayUrl, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    Text(statusLabel, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = statusColor,
                        fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    Text("connects: ${s.connects}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = dimColor)
                    Text("fails: ${s.failures}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (s.failures > 0) Color(0xFFFFA726) else dimColor)
                    Text("avg latency: ${fmtMs(s.avgLatency)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = dimColor)
                    Text("eose: ${s.eoseCount}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = dimColor)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        }
    }
}
