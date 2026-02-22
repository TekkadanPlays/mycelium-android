package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.activity.compose.BackHandler
import social.mycelium.android.data.DiscoveredRelay
import social.mycelium.android.data.RelayType
import social.mycelium.android.repository.Nip66RelayDiscoveryRepository

/**
 * NIP-66 Relay Discovery screen with multi-dimensional filtering.
 * Mirrors nostr.watch capabilities: filter by type, software, country,
 * supported NIPs, payment/auth requirements, and text search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayDiscoveryScreen(
    onBackClick: () -> Unit,
    onRelayClick: (String) -> Unit = {},
    selectionMode: Boolean = false,
    preSelectedUrls: List<String> = emptyList(),
    onConfirmSelection: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val discoveredRelays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()
    val isLoading by Nip66RelayDiscoveryRepository.isLoading.collectAsState()
    val hasFetched by Nip66RelayDiscoveryRepository.hasFetched.collectAsState()
    val context = LocalContext.current

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // ── Filter state ──
    // In selection mode (choosing indexers), auto-filter to Search/Indexer relays
    var selectedTypes by remember { mutableStateOf(if (selectionMode) setOf(RelayType.SEARCH) else emptySet<RelayType>()) }
    var selectedSoftware by remember { mutableStateOf(emptySet<String>()) }
    var selectedCountries by remember { mutableStateOf(emptySet<String>()) }
    var selectedNips by remember { mutableStateOf(emptySet<Int>()) }
    var filterPaymentRequired by remember { mutableStateOf<Boolean?>(null) }
    var filterAuthRequired by remember { mutableStateOf<Boolean?>(null) }
    var filterHasNip11 by remember { mutableStateOf<Boolean?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }

    // ── Selection state (only active when selectionMode = true) ──
    var selectedUrls by remember { mutableStateOf(preSelectedUrls.toSet()) }

    // Back gesture in selection mode saves selection and pops
    if (selectionMode) {
        BackHandler {
            onConfirmSelection(selectedUrls.toList())
        }
    }

    // ── Sort mode ──
    var sortMode by remember { mutableStateOf("rtt") } // "rtt", "monitors", "name"
    var showAll by remember { mutableStateOf(false) }

    val allRelays = remember(discoveredRelays, sortMode) {
        when (sortMode) {
            "rtt" -> discoveredRelays.values.sortedBy { it.bestRtt ?: Int.MAX_VALUE }
            "monitors" -> discoveredRelays.values.sortedByDescending { it.monitorCount }
            "name" -> discoveredRelays.values.sortedBy { (it.name ?: it.url).lowercase() }
            else -> discoveredRelays.values.toList()
        }
    }

    // ── Build dynamic filter options from actual data ──
    val softwareOptions = remember(allRelays) {
        allRelays.mapNotNull { it.softwareShort }.distinct().sorted()
    }
    val countryOptions = remember(allRelays) {
        allRelays.mapNotNull { it.countryCode }.distinct().sorted()
    }
    val nipOptions = remember(allRelays) {
        allRelays.flatMap { it.supportedNips }.distinct().sorted()
    }
    val typeOptions = remember(allRelays) {
        allRelays.flatMap { it.types }.distinct().sortedBy { it.ordinal }
    }

    // ── Apply all filters ──
    val allFilteredRelays = remember(
        allRelays, selectedTypes, selectedSoftware, selectedCountries,
        selectedNips, filterPaymentRequired, filterAuthRequired, filterHasNip11, searchQuery
    ) {
        allRelays.filter { relay ->
            // Type filter (OR: relay matches if it has ANY of the selected types)
            (selectedTypes.isEmpty() || relay.types.any { it in selectedTypes }) &&
            // Software filter (OR)
            (selectedSoftware.isEmpty() || relay.softwareShort in selectedSoftware) &&
            // Country filter (OR)
            (selectedCountries.isEmpty() || relay.countryCode in selectedCountries) &&
            // NIP filter (AND: relay must support ALL selected NIPs)
            (selectedNips.isEmpty() || selectedNips.all { it in relay.supportedNips }) &&
            // Boolean filters
            (filterPaymentRequired == null || relay.paymentRequired == filterPaymentRequired) &&
            (filterAuthRequired == null || relay.authRequired == filterAuthRequired) &&
            (filterHasNip11 == null || relay.hasNip11 == filterHasNip11) &&
            // Text search
            (searchQuery.isBlank() || relay.url.contains(searchQuery, ignoreCase = true) ||
                relay.name?.contains(searchQuery, ignoreCase = true) == true ||
                relay.description?.contains(searchQuery, ignoreCase = true) == true ||
                relay.softwareShort?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    // Cap display to 50 unless user requests all (perf: avoids composing 500+ rows)
    val displayLimit = if (showAll) Int.MAX_VALUE else 50
    val filteredRelays = remember(allFilteredRelays, displayLimit) {
        allFilteredRelays.take(displayLimit)
    }
    val hasMore = allFilteredRelays.size > displayLimit

    // Pre-compute type counts once (avoid per-chip iteration)
    val typeCounts = remember(allFilteredRelays) {
        val counts = mutableMapOf<RelayType, Int>()
        allFilteredRelays.forEach { relay -> relay.types.forEach { t -> counts[t] = (counts[t] ?: 0) + 1 } }
        counts
    }
    val totalTypeCounts = remember(allRelays) {
        val counts = mutableMapOf<RelayType, Int>()
        allRelays.forEach { relay -> relay.types.forEach { t -> counts[t] = (counts[t] ?: 0) + 1 } }
        counts
    }

    // Pre-compute filter section counts as maps (avoids O(n) per chip)
    val softwareCounts = remember(allFilteredRelays) {
        val counts = mutableMapOf<String, Int>()
        allFilteredRelays.forEach { relay -> relay.softwareShort?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        counts
    }
    val countryCounts = remember(allFilteredRelays) {
        val counts = mutableMapOf<String, Int>()
        allFilteredRelays.forEach { relay -> relay.countryCode?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        counts
    }
    val nipCounts = remember(allFilteredRelays) {
        val counts = mutableMapOf<String, Int>()
        allFilteredRelays.forEach { relay -> relay.supportedNips.forEach { nip -> val k = nip.toString(); counts[k] = (counts[k] ?: 0) + 1 } }
        counts
    }

    val activeFilterCount = remember(
        selectedTypes, selectedSoftware, selectedCountries, selectedNips,
        filterPaymentRequired, filterAuthRequired, filterHasNip11, selectionMode
    ) {
        var count = 0
        // In selection mode, SEARCH is the baseline — don't count it as a user filter
        val baseTypes = if (selectionMode) setOf(RelayType.SEARCH) else emptySet<RelayType>()
        if (selectedTypes.isNotEmpty() && selectedTypes != baseTypes) count++
        if (selectedSoftware.isNotEmpty()) count++
        if (selectedCountries.isNotEmpty()) count++
        if (selectedNips.isNotEmpty()) count++
        if (filterPaymentRequired != null) count++
        if (filterAuthRequired != null) count++
        if (filterHasNip11 != null) count++
        count
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            if (selectionMode) {
                FloatingActionButton(
                    onClick = { onConfirmSelection(selectedUrls.toList()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Confirm selection")
                }
            }
        },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = if (selectionMode) "choose indexers" else "discover relays",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectionMode) onConfirmSelection(selectedUrls.toList())
                            else onBackClick()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (activeFilterCount > 0) {
                            FilledTonalButton(
                                onClick = {
                                    // In selection mode, preserve the SEARCH base filter
                                    selectedTypes = if (selectionMode) setOf(RelayType.SEARCH) else emptySet()
                                    selectedSoftware = emptySet()
                                    selectedCountries = emptySet()
                                    selectedNips = emptySet()
                                    filterPaymentRequired = null
                                    filterAuthRequired = null
                                    filterHasNip11 = null
                                    searchQuery = ""
                                },
                                modifier = Modifier.padding(end = 4.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("Clear $activeFilterCount", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { showFilters = !showFilters }) {
                            BadgedBox(
                                badge = {
                                    if (activeFilterCount > 0) {
                                        Badge { Text("$activeFilterCount") }
                                    }
                                }
                            ) {
                                Icon(
                                    if (showFilters) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                                    contentDescription = "Filters"
                                )
                            }
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            // ── Summary stats ──
            item(key = "summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiscoveryStatPill("Total", "${allRelays.size}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    DiscoveryStatPill(
                        "Showing",
                        if (hasMore) "${filteredRelays.size}/${allFilteredRelays.size}" else "${filteredRelays.size}",
                        MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)
                    )
                    val nip11Count = allRelays.count { it.hasNip11 }
                    DiscoveryStatPill("NIP-11", "$nip11Count", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                    val swCount = softwareOptions.size
                    DiscoveryStatPill("Software", "$swCount", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
            }

            // ── Type filter chips (always visible) ──
            item(key = "type_chips") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // "All" chip — hidden in selection mode (SEARCH is the locked baseline)
                    if (!selectionMode) {
                        item {
                            FilterChip(
                                selected = selectedTypes.isEmpty(),
                                onClick = { selectedTypes = emptySet() },
                                label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    items(typeOptions) { type ->
                        val count = typeCounts[type] ?: 0
                        val totalCount = totalTypeCounts[type] ?: 0
                        FilterChip(
                            selected = type in selectedTypes,
                            onClick = {
                                // In selection mode, don't allow deselecting SEARCH (it's the baseline)
                                if (selectionMode && type == RelayType.SEARCH && type in selectedTypes) return@FilterChip
                                selectedTypes = if (type in selectedTypes) selectedTypes - type
                                else selectedTypes + type
                            },
                            label = {
                                Text(
                                    if (count == totalCount) "${type.displayName} ($count)"
                                    else "${type.displayName} ($count/$totalCount)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Sort mode chips ──
            item(key = "sort_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Sort, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("rtt" to "RTT", "monitors" to "Monitors", "name" to "Name").forEach { (mode, label) ->
                        FilterChip(
                            selected = sortMode == mode,
                            onClick = { sortMode = mode; showAll = false },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Expandable filter panel ──
            item(key = "filter_panel") {
                AnimatedVisibility(
                    visible = showFilters,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        // Search
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    "Search by name, URL, software...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search, null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Clear, null, Modifier.size(20.dp))
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))

                        // Boolean toggles row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BooleanFilterChip("NIP-11", filterHasNip11) { filterHasNip11 = it }
                            BooleanFilterChip("Payment", filterPaymentRequired) { filterPaymentRequired = it }
                            BooleanFilterChip("Auth", filterAuthRequired) { filterAuthRequired = it }
                        }
                        Spacer(Modifier.height(12.dp))

                        // Software filter
                        if (softwareOptions.isNotEmpty()) {
                            ExpandableFilterSection(
                                title = "Software",
                                icon = Icons.Outlined.Code,
                                options = softwareOptions,
                                selectedOptions = selectedSoftware,
                                onToggle = { sw ->
                                    selectedSoftware = if (sw in selectedSoftware) selectedSoftware - sw
                                    else selectedSoftware + sw
                                },
                                counts = softwareCounts
                            )
                        }

                        // Country filter
                        if (countryOptions.isNotEmpty()) {
                            ExpandableFilterSection(
                                title = "Country",
                                icon = Icons.Outlined.Public,
                                options = countryOptions,
                                selectedOptions = selectedCountries,
                                onToggle = { cc ->
                                    selectedCountries = if (cc in selectedCountries) selectedCountries - cc
                                    else selectedCountries + cc
                                },
                                counts = countryCounts,
                                formatLabel = { cc -> countryCodeToFlag(cc) + " " + countryName(cc) }
                            )
                        }

                        // Supported NIPs filter
                        if (nipOptions.isNotEmpty()) {
                            ExpandableFilterSection(
                                title = "Supported NIPs",
                                icon = Icons.Outlined.Checklist,
                                options = nipOptions.map { it.toString() },
                                selectedOptions = selectedNips.map { it.toString() }.toSet(),
                                onToggle = { nipStr ->
                                    val nip = nipStr.toIntOrNull() ?: return@ExpandableFilterSection
                                    selectedNips = if (nip in selectedNips) selectedNips - nip
                                    else selectedNips + nip
                                },
                                counts = nipCounts,
                                formatLabel = { "NIP-$it" }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            // ── Loading / Empty ──
            if (isLoading && allRelays.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(16.dp))
                            Text("Discovering relays...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (hasFetched && allRelays.isEmpty()) {
                item(key = "empty") {
                    EmptyDiscoveryState(context)
                }
            } else if (filteredRelays.isEmpty() && allRelays.isNotEmpty()) {
                item(key = "no_results") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("No relays match filters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Try adjusting your filter criteria",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── Results header ──
            if (filteredRelays.isNotEmpty()) {
                item(key = "results_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val totalCount = allFilteredRelays.size
                        Text(
                            text = if (selectionMode)
                                "$totalCount relay${if (totalCount != 1) "s" else ""} \u00b7 ${selectedUrls.size} selected"
                            else
                                "$totalCount relay${if (totalCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (selectionMode) {
                            val allFilteredSelected = allFilteredRelays.all { it.url in selectedUrls }
                            TextButton(
                                onClick = {
                                    selectedUrls = if (allFilteredSelected)
                                        selectedUrls - allFilteredRelays.map { it.url }.toSet()
                                    else
                                        selectedUrls + allFilteredRelays.map { it.url }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (allFilteredSelected) "Deselect All" else "Select All",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // ── Relay list ──
            items(
                items = filteredRelays,
                key = { it.url },
                contentType = { "relay_row" }
            ) { relay ->
                DiscoveredRelayRow(
                    relay = relay,
                    onClick = {
                        if (selectionMode) {
                            selectedUrls = if (relay.url in selectedUrls)
                                selectedUrls - relay.url else selectedUrls + relay.url
                        } else {
                            onRelayClick(relay.url)
                        }
                    },
                    isSelected = if (selectionMode) relay.url in selectedUrls else null,
                    onToggleSelection = if (selectionMode) {
                        {
                            selectedUrls = if (relay.url in selectedUrls)
                                selectedUrls - relay.url else selectedUrls + relay.url
                        }
                    } else null
                )
            }

            // ── Show All button when capped ──
            if (hasMore) {
                item(key = "show_all") {
                    TextButton(
                        onClick = { showAll = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            "Show all ${allFilteredRelays.size} relays",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ── Filter Components ──

@Composable
private fun BooleanFilterChip(
    label: String,
    value: Boolean?,
    onValueChange: (Boolean?) -> Unit
) {
    FilterChip(
        selected = value != null,
        onClick = {
            onValueChange(
                when (value) {
                    null -> true
                    true -> false
                    false -> null
                }
            )
        },
        label = {
            Text(
                when (value) {
                    null -> label
                    true -> "$label: Yes"
                    false -> "$label: No"
                },
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            when (value) {
                true -> Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                false -> Icon(Icons.Filled.Close, null, Modifier.size(14.dp))
                null -> {}
            }
        }
    )
}

@Composable
private fun ExpandableFilterSection(
    title: String,
    icon: ImageVector,
    options: List<String>,
    selectedOptions: Set<String>,
    onToggle: (String) -> Unit,
    counts: Map<String, Int>,
    formatLabel: (String) -> String = { it },
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded || selectedOptions.isNotEmpty()) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "chevron_$title"
    )

    Column {
        // Header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { expanded = !expanded },
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (selectedOptions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "${selectedOptions.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Filled.ExpandMore, null,
                    Modifier.size(16.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Options (flow layout as horizontal scrolling chips)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { option ->
                    val count = counts[option] ?: 0
                    val isSelected = option in selectedOptions
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggle(option) },
                        label = {
                            Text(
                                "${formatLabel(option)} ($count)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

// ── Relay Row ──

@Composable
private fun DiscoveredRelayRow(
    relay: DiscoveredRelay,
    onClick: () -> Unit,
    isSelected: Boolean? = null,
    onToggleSelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val rawName = relay.name
        ?: relay.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    val displayName = if (relay.paymentRequired) "$rawName  $" else rawName
    val iconUrl = relay.icon

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox (only in selection mode)
            if (isSelected != null) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection?.invoke() },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(4.dp))
            }

            // Relay icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(40.dp)
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(iconUrl)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = displayName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Outlined.Public, null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Name
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // URL (if name differs from URL)
                if (relay.name != null) {
                    Text(
                        text = relay.url.removePrefix("wss://").removePrefix("ws://"),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(3.dp))

                // Metadata tags row: [Flag] [Type tags] [Software] — single line, overflow clipped
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clipToBounds()
                ) {
                    // Country flag (compact — just the flag emoji)
                    relay.countryCode?.let { cc ->
                        MetadataChip(countryCodeToFlag(cc), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Type tags
                    relay.types.take(2).forEach { type ->
                        MetadataChip(type.displayName, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    if (relay.types.size > 2) {
                        MetadataChip("+${relay.types.size - 2}", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Auth badge
                    if (relay.authRequired) {
                        MetadataChip("Auth", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    // Software (least important — last)
                    relay.softwareShort?.let { sw ->
                        MetadataChip(sw, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }

                // RTT info
                relay.avgRttOpen?.let { rtt ->
                    Spacer(Modifier.height(2.dp))
                    val rttColor = when {
                        rtt < 500 -> Color(0xFF66BB6A)
                        rtt < 1000 -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "${rtt}ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = rttColor
                    )
                }
            }

            // Monitor count + NIP count
            Column(horizontalAlignment = Alignment.End) {
                if (relay.monitorCount > 0) {
                    Text(
                        text = "${relay.monitorCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (relay.monitorCount == 1) "monitor" else "monitors",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (relay.supportedNips.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${relay.supportedNips.size} NIPs",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ── Small Components ──

@Composable
private fun MetadataChip(text: String, backgroundColor: Color, textColor: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun DiscoveryStatPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyDiscoveryState(context: android.content.Context) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Explore, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("No relays discovered yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Relay monitors haven't published discovery data to your connected relays",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                Nip66RelayDiscoveryRepository.init(context)
                Nip66RelayDiscoveryRepository.fetchRelayDiscovery(
                    Nip66RelayDiscoveryRepository.MONITOR_RELAYS,
                    emptyList()
                )
            }
        ) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

/** Convert ISO 3166-1 alpha-2 country code to flag emoji. */
private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return code
    val first = Character.codePointAt(code.uppercase(), 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(code.uppercase(), 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

/** Map ISO 3166-1 alpha-2 country code to human-readable name. */
private fun countryName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code

private val COUNTRY_NAMES = mapOf(
    "AD" to "Andorra", "AE" to "UAE", "AF" to "Afghanistan", "AG" to "Antigua & Barbuda",
    "AL" to "Albania", "AM" to "Armenia", "AO" to "Angola", "AR" to "Argentina",
    "AT" to "Austria", "AU" to "Australia", "AZ" to "Azerbaijan", "BA" to "Bosnia",
    "BB" to "Barbados", "BD" to "Bangladesh", "BE" to "Belgium", "BG" to "Bulgaria",
    "BH" to "Bahrain", "BJ" to "Benin", "BN" to "Brunei", "BO" to "Bolivia",
    "BR" to "Brazil", "BS" to "Bahamas", "BT" to "Bhutan", "BW" to "Botswana",
    "BY" to "Belarus", "BZ" to "Belize", "CA" to "Canada", "CD" to "DR Congo",
    "CF" to "Central African Rep.", "CG" to "Congo", "CH" to "Switzerland",
    "CI" to "Ivory Coast", "CL" to "Chile", "CM" to "Cameroon", "CN" to "China",
    "CO" to "Colombia", "CR" to "Costa Rica", "CU" to "Cuba", "CV" to "Cape Verde",
    "CY" to "Cyprus", "CZ" to "Czechia", "DE" to "Germany", "DJ" to "Djibouti",
    "DK" to "Denmark", "DM" to "Dominica", "DO" to "Dominican Rep.", "DZ" to "Algeria",
    "EC" to "Ecuador", "EE" to "Estonia", "EG" to "Egypt", "ES" to "Spain",
    "ET" to "Ethiopia", "FI" to "Finland", "FJ" to "Fiji", "FR" to "France",
    "GA" to "Gabon", "GB" to "United Kingdom", "GE" to "Georgia", "GH" to "Ghana",
    "GM" to "Gambia", "GN" to "Guinea", "GR" to "Greece", "GT" to "Guatemala",
    "GY" to "Guyana", "HK" to "Hong Kong", "HN" to "Honduras", "HR" to "Croatia",
    "HT" to "Haiti", "HU" to "Hungary", "ID" to "Indonesia", "IE" to "Ireland",
    "IL" to "Israel", "IN" to "India", "IQ" to "Iraq", "IR" to "Iran",
    "IS" to "Iceland", "IT" to "Italy", "JM" to "Jamaica", "JO" to "Jordan",
    "JP" to "Japan", "KE" to "Kenya", "KG" to "Kyrgyzstan", "KH" to "Cambodia",
    "KR" to "South Korea", "KW" to "Kuwait", "KZ" to "Kazakhstan", "LA" to "Laos",
    "LB" to "Lebanon", "LI" to "Liechtenstein", "LK" to "Sri Lanka", "LR" to "Liberia",
    "LT" to "Lithuania", "LU" to "Luxembourg", "LV" to "Latvia", "LY" to "Libya",
    "MA" to "Morocco", "MC" to "Monaco", "MD" to "Moldova", "ME" to "Montenegro",
    "MG" to "Madagascar", "MK" to "N. Macedonia", "ML" to "Mali", "MM" to "Myanmar",
    "MN" to "Mongolia", "MO" to "Macau", "MT" to "Malta", "MU" to "Mauritius",
    "MV" to "Maldives", "MW" to "Malawi", "MX" to "Mexico", "MY" to "Malaysia",
    "MZ" to "Mozambique", "NA" to "Namibia", "NE" to "Niger", "NG" to "Nigeria",
    "NI" to "Nicaragua", "NL" to "Netherlands", "NO" to "Norway", "NP" to "Nepal",
    "NZ" to "New Zealand", "OM" to "Oman", "PA" to "Panama", "PE" to "Peru",
    "PG" to "Papua New Guinea", "PH" to "Philippines", "PK" to "Pakistan",
    "PL" to "Poland", "PR" to "Puerto Rico", "PS" to "Palestine", "PT" to "Portugal",
    "PY" to "Paraguay", "QA" to "Qatar", "RO" to "Romania", "RS" to "Serbia",
    "RU" to "Russia", "RW" to "Rwanda", "SA" to "Saudi Arabia", "SC" to "Seychelles",
    "SD" to "Sudan", "SE" to "Sweden", "SG" to "Singapore", "SI" to "Slovenia",
    "SK" to "Slovakia", "SL" to "Sierra Leone", "SN" to "Senegal", "SO" to "Somalia",
    "SR" to "Suriname", "SV" to "El Salvador", "SY" to "Syria", "TH" to "Thailand",
    "TJ" to "Tajikistan", "TM" to "Turkmenistan", "TN" to "Tunisia", "TR" to "Turkey",
    "TT" to "Trinidad & Tobago", "TW" to "Taiwan", "TZ" to "Tanzania",
    "UA" to "Ukraine", "UG" to "Uganda", "US" to "United States", "UY" to "Uruguay",
    "UZ" to "Uzbekistan", "VE" to "Venezuela", "VN" to "Vietnam", "ZA" to "South Africa",
    "ZM" to "Zambia", "ZW" to "Zimbabwe"
)
