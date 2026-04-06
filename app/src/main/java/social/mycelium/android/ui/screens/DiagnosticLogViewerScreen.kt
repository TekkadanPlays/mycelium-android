package social.mycelium.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import social.mycelium.android.debug.DiagnosticLog

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
                                text = { Text("Clear logs", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    DiagnosticLog.clearAll()
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
