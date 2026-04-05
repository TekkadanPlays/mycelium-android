package social.mycelium.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.mycelium.android.debug.DiagnosticLog

private enum class ChannelTab(val label: String, val channel: DiagnosticLog.Channel?) {
    ALL("All", null),
    STARTUP("Startup", DiagnosticLog.Channel.STARTUP),
    RELAY("Relay", DiagnosticLog.Channel.RELAY),
    FEED("Feed", DiagnosticLog.Channel.FEED),
    SYNC("Sync", DiagnosticLog.Channel.SYNC),
    STATE("State", DiagnosticLog.Channel.STATE),
    AUTH("Auth", DiagnosticLog.Channel.AUTH),
    NOTIF("Notif", DiagnosticLog.Channel.NOTIFICATION),
    WALLET("Wallet", DiagnosticLog.Channel.WALLET),
    GENERAL("General", DiagnosticLog.Channel.GENERAL),
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

    fun loadLogs() {
        scope.launch {
            isLoading = true
            val lines = withContext(Dispatchers.IO) {
                if (selectedTab.channel != null) {
                    DiagnosticLog.readChannel(selectedTab.channel!!)
                } else {
                    DiagnosticLog.readAll()
                }
            }
            logLines = lines
            isLoading = false
        }
    }

    LaunchedEffect(selectedTab) { loadLogs() }

    val filteredLines = remember(logLines, searchQuery) {
        if (searchQuery.isBlank()) logLines
        else logLines.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic Logs") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadLogs() }) {
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
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search logs…", fontSize = 13.sp) },
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

            // Stats bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${filteredLines.size} lines" + if (searchQuery.isNotBlank()) " (filtered)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (isLoading) "Loading…" else selectedTab.channel?.filename ?: "all.log",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Log lines
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                if (filteredLines.isEmpty() && !isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (searchQuery.isNotBlank()) "No matching lines"
                                else "No logs in ${selectedTab.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(filteredLines, key = { "$selectedTab-${filteredLines.indexOf(it)}-${it.hashCode()}" }) { line ->
                    LogLineRow(line, searchQuery)
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: String, searchQuery: String) {
    val isSession = line.contains("SESSION START") || line.contains("════")
    val isError = line.contains(" | E |") || line.contains(" | W |")
    val isWarn = line.contains(" | W |")

    val bgColor by animateColorAsState(
        when {
            isSession -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isError && !isWarn -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            isWarn -> Color(0xFFFFF3E0).copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        label = "logLineBg"
    )

    val textColor = when {
        isSession -> MaterialTheme.colorScheme.primary
        isError && !isWarn -> MaterialTheme.colorScheme.error
        isWarn -> Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        // Timestamp column (fixed width)
        val parts = line.split(" | ", limit = 2)
        if (parts.size >= 2) {
            Text(
                parts[0],
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(80.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                parts[1],
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = textColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                line,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = textColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
