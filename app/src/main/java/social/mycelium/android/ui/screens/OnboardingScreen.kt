package social.mycelium.android.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.DiscoveredRelay
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayType
import social.mycelium.android.data.UserRelay
import social.mycelium.android.repository.Nip65RelayListRepository
import social.mycelium.android.repository.Nip66RelayDiscoveryRepository
import social.mycelium.android.repository.RelayStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-login onboarding screen with intelligent relay discovery:
 *
 * 1. LOADING_INDEXERS — wait for NIP-66 relay monitor data (fetched at app init)
 * 2. SELECT_INDEXERS — user picks from low-RTT indexers; can deselect or go fully manual
 * 3. SEARCHING_NIP65 — query selected indexers for kind-10002, stream results
 * 4. REVIEW_RELAYS — user verifies the discovered config before we apply it
 * 5. SAVING — apply confirmed config
 * 6. READY — navigate to dashboard
 *
 * The user always has agency: they can confirm, edit, skip, or go fully manual.
 */
private enum class OnboardingPhase {
    LOADING_INDEXERS,      // Waiting for NIP-66 relay monitor data
    SELECT_INDEXERS,       // User picks indexers from NIP-66 results
    SEARCHING_NIP65,       // Querying indexers for kind-10002
    REVIEW_RELAYS,         // User reviews discovered relay config
    NIP65_NOT_FOUND,       // No relay list found — manual setup
    SAVING,                // Applying confirmed config
    READY,                 // All set, about to navigate
    ERROR                  // Something went wrong
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    hexPubkey: String,
    onComplete: () -> Unit,
    onOpenRelayDiscovery: () -> Unit,
    /** Navigate to relay manager with optional outbox/inbox prefill from NIP-65 event. */
    onOpenRelayManager: (outboxUrls: List<String>, inboxUrls: List<String>) -> Unit,
    /** Navigate to Relay Discovery in selection mode for picking indexer relays. */
    onOpenRelayDiscoverySelection: (preSelectedUrls: List<String>) -> Unit = {},
    /** URLs returned from the discovery selection screen. */
    returnedIndexerUrls: List<String> = emptyList(),
    /** Navigate to relay log screen for a specific relay URL. */
    onRelayLogClick: (relayUrl: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageManager = remember(context) { RelayStorageManager(context) }

    // Restore persisted onboarding state if available
    val savedPhase = remember(hexPubkey) {
        if (hexPubkey.isNotBlank()) storageManager.loadOnboardingPhase(hexPubkey) else null
    }
    val savedIndexers = remember(hexPubkey) {
        if (hexPubkey.isNotBlank()) storageManager.loadOnboardingSelectedIndexers(hexPubkey) else emptySet()
    }

    var phase by remember {
        // Only restore phases that can be meaningfully resumed (user was interacting).
        // Transient phases (LOADING, SEARCHING, SAVING, READY) restart from scratch
        // to avoid visible phase jumping on re-entry.
        val restorable = setOf("SELECT_INDEXERS", "REVIEW_RELAYS", "NIP65_NOT_FOUND")
        val initial = savedPhase?.takeIf { it in restorable }?.let {
            try { OnboardingPhase.valueOf(it) } catch (_: Exception) { null }
        } ?: OnboardingPhase.LOADING_INDEXERS
        mutableStateOf(initial)
    }
    var statusText by remember {
        mutableStateOf(
            if (phase == OnboardingPhase.SELECT_INDEXERS) "Choose which indexers to query for your relay list"
            else "Preparing indexer relays\u2026"
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // NIP-66 indexer discovery state — all Search-type relays sorted by RTT
    var allIndexers by remember { mutableStateOf<List<DiscoveredRelay>>(emptyList()) }
    // Which indexer URLs the user has selected (pre-checked: top 5 by RTT).
    // If returnedIndexerUrls is non-empty (user just returned from discovery selection),
    // apply it immediately — no LaunchedEffect race.
    var selectedIndexerUrls by remember {
        val initial = if (returnedIndexerUrls.isNotEmpty()) returnedIndexerUrls.toSet() else savedIndexers
        mutableStateOf(initial)
    }
    // Ref for debounced persistence — updated on every toggle WITHOUT triggering
    // recomposition of the parent (which would cascade through AnimatedContent).
    val latestSelectionRef = remember { androidx.compose.runtime.mutableStateOf(selectedIndexerUrls) }
    // Track whether we consumed returned indexers (to skip overwrite in hexPubkey effect)
    val hasReturnedIndexers = returnedIndexerUrls.isNotEmpty()

    // Persist phase changes to survive app interruption
    LaunchedEffect(phase) {
        if (hexPubkey.isNotBlank()) {
            storageManager.saveOnboardingPhase(hexPubkey, phase.name)
        }
    }

    // Persist selected indexer URLs via ref (debounced, no recomposition)
    LaunchedEffect(Unit) {
        snapshotFlow { latestSelectionRef.value }
            .collect { urls ->
                if (hexPubkey.isNotBlank() && urls.isNotEmpty()) {
                    delay(500)
                    storageManager.saveOnboardingSelectedIndexers(hexPubkey, urls)
                }
            }
    }

    // Multi-source NIP-65 results — collected lazily in the phases that need them
    // (SEARCHING_NIP65, REVIEW_RELAYS). NOT collected at top level to avoid
    // recomposing the entire screen during SELECT_INDEXERS when user toggles relays.
    // LaunchedEffect(hexPubkey) reads .value directly and is unaffected.

    // The "best" result chosen for review (latest created_at)
    var chosenResult by remember { mutableStateOf<Nip65RelayListRepository.Nip65SourceResult?>(null) }

    // Custom relay input
    var showCustomInput by remember { mutableStateOf(false) }
    var customRelayUrl by remember { mutableStateOf("") }
    var addedCustomRelays by remember { mutableStateOf<List<String>>(emptyList()) }

    // ── Main initialization effect — wait for NIP-66 then show indexer selection ──
    LaunchedEffect(hexPubkey) {
        Log.d("OnboardingScreen", "LaunchedEffect START for pubkey=${hexPubkey.take(8)}")
        if (hexPubkey.isBlank()) {
            Log.w("OnboardingScreen", "Blank pubkey, exiting LaunchedEffect")
            return@LaunchedEffect
        }

        // If multi-source results already exist for this pubkey (user navigated away
        // and came back, e.g. from relay_log or relay manager), restore to the
        // correct phase. If they were on REVIEW_RELAYS, go back there directly.
        val cachedResults = Nip65RelayListRepository.multiSourceResults.value
        val cachedPubkey = Nip65RelayListRepository.multiSourcePubkey
        val cachedDone = Nip65RelayListRepository.multiSourceDone.value
        if (cachedPubkey == hexPubkey && cachedDone && cachedResults.isNotEmpty()) {
            Log.d("OnboardingScreen", "Restoring cached multi-source results for ${hexPubkey.take(8)} (${cachedResults.size} results)")
            val best = cachedResults.maxByOrNull { it.createdAt }!!
            chosenResult = best
            val statuses = Nip65RelayListRepository.multiSourceStatuses.value
            val successCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
            // Restore to REVIEW_RELAYS if that's where the user was (e.g. back from relay manager)
            if (savedPhase == "REVIEW_RELAYS") {
                val uniqueTimestamps = cachedResults.map { it.createdAt }.distinct()
                statusText = if (uniqueTimestamps.size > 1)
                    "Found ${cachedResults.size} sources — review your relay configuration"
                else "Found your relay configuration"
                phase = OnboardingPhase.REVIEW_RELAYS
            } else {
                statusText = "Search complete — $successCount responded"
                phase = OnboardingPhase.SEARCHING_NIP65
            }
            return@LaunchedEffect
        }
        // Also restore if search is still in progress
        if (cachedPubkey == hexPubkey && !cachedDone && Nip65RelayListRepository.multiSourceTotal.value > 0) {
            Log.d("OnboardingScreen", "Restoring in-progress multi-source search for ${hexPubkey.take(8)}")
            statusText = "Searching ${Nip65RelayListRepository.multiSourceTotal.value} relays…"
            phase = OnboardingPhase.SEARCHING_NIP65
            return@LaunchedEffect
        }

        // If returning from discovery selection or resuming SELECT_INDEXERS,
        // just refresh the indexer list without overwriting user's selections.
        // hasReturnedIndexers means user just came back from discovery — selection
        // was already applied in the remember{} initializer above.
        val isReturning = hasReturnedIndexers ||
            (savedPhase == "SELECT_INDEXERS" && savedIndexers.isNotEmpty())
        if (isReturning) {
            Log.d("OnboardingScreen", "Returning to SELECT_INDEXERS (fromDiscovery=$hasReturnedIndexers, selections=${selectedIndexerUrls.size})")
            // Populate allIndexers from NIP-66 cache
            val allDiscovered = Nip66RelayDiscoveryRepository.discoveredRelays.value
            val indexers = allDiscovered.values
                .filter { relay -> relay.isSearch }
                .sortedBy { it.bestRtt ?: Int.MAX_VALUE }
            allIndexers = indexers
            // Only merge relay manager URLs when NOT returning from discovery
            // (discovery returns a complete replacement set; merging would undo deselections)
            if (!hasReturnedIndexers) {
                val storedIndexerUrls = storageManager.loadIndexerRelays(hexPubkey)
                    .map { it.url.trim().removeSuffix("/") }.toSet()
                if (storedIndexerUrls.isNotEmpty()) {
                    val merged = selectedIndexerUrls + storedIndexerUrls
                    if (merged.size > selectedIndexerUrls.size) {
                        selectedIndexerUrls = merged
                        Log.d("OnboardingScreen", "Merged relay manager indexers into selection")
                    }
                }
            }
            phase = OnboardingPhase.SELECT_INDEXERS
            statusText = "Choose which indexers to query for your relay list"
            Log.d("OnboardingScreen", "Refreshed indexer list: ${indexers.size} relays, ${selectedIndexerUrls.size} selected")
            return@LaunchedEffect
        }

        // Reset transient state for fresh run
        Log.d("OnboardingScreen", "Starting onboarding flow (savedPhase=$savedPhase, savedIndexers=${savedIndexers.size})")
        phase = OnboardingPhase.LOADING_INDEXERS
        statusText = "Preparing indexer relays\u2026"
        errorMessage = null
        allIndexers = emptyList()
        chosenResult = null
        showCustomInput = false
        addedCustomRelays = emptyList()

        // NIP-66 is initialized globally in MainActivity — wait for it to finish.
        withContext(Dispatchers.IO) {
            var waited = 0L
            Log.d("OnboardingScreen", "Waiting for NIP-66 data (hasFetched=${Nip66RelayDiscoveryRepository.hasFetched.value}, isLoading=${Nip66RelayDiscoveryRepository.isLoading.value})")

            while (waited < 15_000L &&
                (!Nip66RelayDiscoveryRepository.hasFetched.value || Nip66RelayDiscoveryRepository.isLoading.value)
            ) {
                delay(300)
                waited += 300
                if (waited % 3000 == 0L) {
                    Log.d("OnboardingScreen", "Still waiting... ${waited/1000}s (hasFetched=${Nip66RelayDiscoveryRepository.hasFetched.value}, isLoading=${Nip66RelayDiscoveryRepository.isLoading.value})")
                }
            }

            val timedOut = waited >= 15_000L
            val allDiscovered = Nip66RelayDiscoveryRepository.discoveredRelays.value
            Log.d("OnboardingScreen", "NIP-66 wait complete: timedOut=$timedOut, discovered ${allDiscovered.size} total relays")

            // Indexers need to support NIP-65 (kind 10002) for relay lists and kind 0 for profiles.
            // Look for relays with NIP-65 support or general PUBLIC_OUTBOX relays.
            val indexers = allDiscovered.values
                .filter { relay -> relay.isSearch }
                .sortedBy { it.bestRtt ?: Int.MAX_VALUE }

            allIndexers = indexers
            // Restore saved indexer selections, or pre-select top 5 by RTT
            if (savedIndexers.isNotEmpty()) {
                selectedIndexerUrls = savedIndexers
            } else {
                selectedIndexerUrls = indexers.take(5).map { it.url }.toSet()
            }
            Log.d("OnboardingScreen", "NIP-66: found ${indexers.size} indexer relays (from ${allDiscovered.size} total)")

            if (indexers.isEmpty()) {
                // No NIP-66 data — allow manual input
                Log.w("OnboardingScreen", "No indexer relays found, offering manual setup")
                phase = OnboardingPhase.NIP65_NOT_FOUND
                statusText = if (timedOut) "Discovery timed out — add indexers manually"
                            else "No indexer relays found — add manually"
            } else {
                phase = OnboardingPhase.SELECT_INDEXERS
                statusText = "Choose which indexers to query for your relay list"
            }
        }
    }

    // ── Start NIP-65 search with selected indexers ──
    val searchScope = rememberCoroutineScope()
    fun startNip65Search(indexerUrls: List<String>) {
        if (indexerUrls.isEmpty()) {
            phase = OnboardingPhase.NIP65_NOT_FOUND
            statusText = "No indexers selected"
            return
        }

        // Persist selected indexers to relay manager immediately so they
        // appear in the relay manager even before the search finishes.
        val newIndexerRelays = indexerUrls.map { UserRelay(url = it, read = true, write = false) }
        val existingIndexers = storageManager.loadIndexerRelays(hexPubkey)
        val existingUrls = existingIndexers.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
        val toAdd = newIndexerRelays.filter { it.url.trim().removeSuffix("/").lowercase() !in existingUrls }
        if (toAdd.isNotEmpty()) {
            storageManager.saveIndexerRelays(hexPubkey, existingIndexers + toAdd)
            Log.d("OnboardingScreen", "Saved ${toAdd.size} indexer relays")
        }

        phase = OnboardingPhase.SEARCHING_NIP65
        statusText = "Searching ${indexerUrls.size} relays…"

        searchScope.launch(Dispatchers.IO) {
            try {
                Nip65RelayListRepository.fetchRelayListMultiSource(hexPubkey, indexerUrls)

                // Wait for multi-source search to complete. With batched processing
                // (20 relays per batch, 8s timeout each), large sets take longer.
                // 500 relays = 25 batches ≈ 210s worst case. Cap at 5 minutes.
                var waited = 0L
                while (!Nip65RelayListRepository.multiSourceDone.value && waited < 300_000L) {
                    delay(500)
                    waited += 500
                }

                val results = Nip65RelayListRepository.multiSourceResults.value
                val statuses = Nip65RelayListRepository.multiSourceStatuses.value
                val successCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
                val failCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.FAILED || it.status == Nip65RelayListRepository.IndexerQueryStatus.TIMEOUT }
                Log.d("OnboardingScreen", "Multi-source: $successCount success, $failCount failed/timeout, ${results.size} with data")

                if (results.isNotEmpty()) {
                    val best = results.maxByOrNull { it.createdAt }!!
                    chosenResult = best
                    statusText = "Search complete — $successCount responded"
                } else {
                    statusText = "No relay configuration found"
                }
                // Stay on SEARCHING_NIP65 — user reviews results and clicks to proceed
            } catch (e: Exception) {
                Log.e("OnboardingScreen", "NIP-65 search failed: ${e.message}", e)
                statusText = "Search failed — configure manually"
            }
        }
    }

    // Helper to proceed from search results to review or not-found
    fun proceedFromSearch() {
        val results = Nip65RelayListRepository.multiSourceResults.value
        if (results.isNotEmpty()) {
            val best = results.maxByOrNull { it.createdAt }!!
            chosenResult = best
            val uniqueTimestamps = results.map { it.createdAt }.distinct()
            statusText = if (uniqueTimestamps.size > 1)
                "Found ${results.size} sources — review your relay configuration"
            else "Found your relay configuration"
            phase = OnboardingPhase.REVIEW_RELAYS
        } else {
            phase = OnboardingPhase.NIP65_NOT_FOUND
            statusText = "No relay configuration found"
        }
    }

    // ── Auto-complete when user returns from relay discovery/manager with relays configured ──
    LaunchedEffect(phase) {
        if (phase != OnboardingPhase.NIP65_NOT_FOUND && phase != OnboardingPhase.SELECT_INDEXERS) return@LaunchedEffect
        while (phase == OnboardingPhase.NIP65_NOT_FOUND || phase == OnboardingPhase.SELECT_INDEXERS) {
            delay(500)
            val categories = withContext(Dispatchers.IO) { storageManager.loadCategories(hexPubkey) }
            val hasRelays = categories.any { it.relays.isNotEmpty() }
            if (hasRelays) {
                if (hexPubkey.isNotBlank()) storageManager.clearOnboardingState(hexPubkey)
                phase = OnboardingPhase.READY
                statusText = "Relays configured"
                delay(700)
                onComplete()
                return@LaunchedEffect
            }
        }
    }

    // ── Save confirmed relay config ──
    fun saveRelayConfig(result: Nip65RelayListRepository.Nip65SourceResult) {
        phase = OnboardingPhase.SAVING
        statusText = "Saving your relay configuration…"

        searchScope.launch(Dispatchers.IO) {
            // Disconnect all existing websocket connections so the feed will
            // exclusively connect to the confirmed outbox relays on dashboard load.
            social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().requestDisconnect()

            // Apply to singleton state
            Nip65RelayListRepository.applyMultiSourceResult(result)

            // Save to storage
            val outboxRelays = result.writeRelays.map { UserRelay(url = it, read = false, write = true) }
            val inboxRelays = result.readRelays.map { UserRelay(url = it, read = true, write = false) }
            storageManager.saveOutboxRelays(hexPubkey, outboxRelays)
            storageManager.saveInboxRelays(hexPubkey, inboxRelays)

            // Populate Home relays category for feed subscription
            val allNip65Urls = (result.writeRelays + result.readRelays).distinct()
            val categoryRelays = allNip65Urls.map { UserRelay(url = it, read = true, write = true) }
            val existingCategories = storageManager.loadCategories(hexPubkey)
            val defaultCat = existingCategories.firstOrNull { it.id == "default_my_relays" }
            val updatedCategories = if (defaultCat != null) {
                val existingUrls = defaultCat.relays.map { it.url }.toSet()
                val newRelays = categoryRelays.filter { it.url !in existingUrls }
                existingCategories.map { cat ->
                    if (cat.id == "default_my_relays") cat.copy(relays = cat.relays + newRelays, isSubscribed = true)
                    else cat
                }
            } else {
                val newCat = RelayCategory(
                    id = "default_my_relays",
                    name = "Home relays",
                    relays = categoryRelays,
                    isDefault = true,
                    isSubscribed = true
                )
                existingCategories + newCat
            }
            storageManager.saveCategories(hexPubkey, updatedCategories)
            Log.d("OnboardingScreen", "Saved ${categoryRelays.size} relays to Home category")

            withContext(Dispatchers.Main) {
                phase = OnboardingPhase.READY
                statusText = "You're all set"
            }
        }
    }

    // ── Navigate after READY ──
    LaunchedEffect(phase) {
        if (phase == OnboardingPhase.READY) {
            // Clear persisted onboarding state on completion
            if (hexPubkey.isNotBlank()) {
                storageManager.clearOnboardingState(hexPubkey)
            }
            delay(400)
            onComplete()
        }
    }

    // ── Phase-derived visual properties ──
    val isLoading = phase in listOf(
        OnboardingPhase.LOADING_INDEXERS,
        OnboardingPhase.SEARCHING_NIP65,
        OnboardingPhase.SAVING
    )
    val phaseColor = when (phase) {
        OnboardingPhase.SELECT_INDEXERS -> MaterialTheme.colorScheme.primary
        OnboardingPhase.REVIEW_RELAYS -> MaterialTheme.colorScheme.primary
        OnboardingPhase.READY -> Color(0xFF4CAF50)
        OnboardingPhase.ERROR -> MaterialTheme.colorScheme.error
        OnboardingPhase.NIP65_NOT_FOUND -> MaterialTheme.colorScheme.tertiary
        OnboardingPhase.SAVING -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }
    val scrollablePhases = phase == OnboardingPhase.REVIEW_RELAYS || phase == OnboardingPhase.SELECT_INDEXERS || phase == OnboardingPhase.SEARCHING_NIP65

    // ── UI ──
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = if (scrollablePhases) Alignment.TopCenter else Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollablePhases)
                            Modifier.verticalScroll(rememberScrollState()).padding(top = 48.dp)
                        else Modifier
                    )
                    .padding(horizontal = 32.dp)
            ) {
                // ═══ ICON — animated phase indicator ═══
                val targetIcon = when (phase) {
                    OnboardingPhase.LOADING_INDEXERS -> Icons.Outlined.Radar
                    OnboardingPhase.SELECT_INDEXERS -> Icons.Outlined.Checklist
                    OnboardingPhase.SEARCHING_NIP65 -> Icons.Outlined.Search
                    OnboardingPhase.REVIEW_RELAYS -> Icons.Outlined.Hub
                    OnboardingPhase.NIP65_NOT_FOUND -> Icons.Outlined.Explore
                    OnboardingPhase.SAVING -> Icons.Outlined.Save
                    OnboardingPhase.READY -> Icons.Filled.CheckCircle
                    OnboardingPhase.ERROR -> Icons.Outlined.Warning
                }
                Surface(
                    shape = CircleShape,
                    color = phaseColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(80.dp),
                                strokeWidth = 2.dp,
                                color = phaseColor.copy(alpha = 0.3f)
                            )
                        }
                        Crossfade(targetState = targetIcon, animationSpec = tween(300), label = "icon") { icon ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, Modifier.size(36.dp), tint = phaseColor)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ═══ TITLE + STATUS ═══
                val title = when (phase) {
                    OnboardingPhase.LOADING_INDEXERS -> "Preparing"
                    OnboardingPhase.SELECT_INDEXERS -> "Select Indexers"
                    OnboardingPhase.SEARCHING_NIP65 -> "Searching Relays"
                    OnboardingPhase.REVIEW_RELAYS -> "Review Your Relays"
                    OnboardingPhase.NIP65_NOT_FOUND -> "Configure Relays"
                    OnboardingPhase.SAVING -> "Saving"
                    OnboardingPhase.READY -> "Ready"
                    OnboardingPhase.ERROR -> "Connection Issue"
                }
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn(tween(250, delayMillis = 100)) togetherWith fadeOut(tween(200)) },
                    label = "title"
                ) { t ->
                    Text(
                        text = t,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(4.dp))
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = { fadeIn(tween(250, delayMillis = 100)) togetherWith fadeOut(tween(200)) },
                    label = "status"
                ) { s ->
                    Text(
                        text = s,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ═══ PHASE CONTENT ═══
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        fadeIn(tween(300, delayMillis = 150)) togetherWith fadeOut(tween(250)) using
                                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(300) })
                    },
                    label = "phase_content"
                ) { currentPhase ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (currentPhase) {
                            OnboardingPhase.LOADING_INDEXERS -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                                // Allow skipping to manual setup after 5 seconds
                                var showSkip by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) {
                                    delay(5000)
                                    showSkip = true
                                }
                                AnimatedVisibility(visible = showSkip) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Spacer(Modifier.height(16.dp))
                                        TextButton(onClick = {
                                            phase = OnboardingPhase.NIP65_NOT_FOUND
                                            statusText = "Add indexers manually"
                                        }) {
                                            Text("Skip to manual setup", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }

                            OnboardingPhase.SELECT_INDEXERS -> {
                                IndexerSelectionCard(
                                    allIndexers = allIndexers,
                                    initialSelectedUrls = selectedIndexerUrls,
                                    onSelectionChanged = { latestSelectionRef.value = it },
                                    onConfirm = { urls ->
                                        selectedIndexerUrls = urls
                                        startNip65Search(urls.toList())
                                    },
                                    onChooseOwn = { urls ->
                                        selectedIndexerUrls = urls
                                        onOpenRelayDiscoverySelection(urls.toList())
                                    },
                                    onAddMore = { urls ->
                                        selectedIndexerUrls = urls
                                        onOpenRelayDiscoverySelection(urls.toList())
                                    }
                                )
                            }

                            OnboardingPhase.SEARCHING_NIP65 -> {
                                // Collect flows only when this phase is active
                                val multiSourceStatuses by Nip65RelayListRepository.multiSourceStatuses.collectAsState()
                                val multiSourceResults by Nip65RelayListRepository.multiSourceResults.collectAsState()
                                val multiSourceTotal by Nip65RelayListRepository.multiSourceTotal.collectAsState()
                                val multiSourceDone by Nip65RelayListRepository.multiSourceDone.collectAsState()
                                MultiSourceSearchCard(
                                    statuses = multiSourceStatuses,
                                    results = multiSourceResults,
                                    total = multiSourceTotal,
                                    done = multiSourceDone,
                                    onProceed = { proceedFromSearch() },
                                    onManualSetup = {
                                        phase = OnboardingPhase.NIP65_NOT_FOUND
                                        statusText = "Configure relays manually"
                                    },
                                    onRelayLogClick = onRelayLogClick
                                )
                            }

                            OnboardingPhase.REVIEW_RELAYS -> {
                                val multiSourceResults by Nip65RelayListRepository.multiSourceResults.collectAsState()
                                val nip66Relays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()
                                val result = chosenResult
                                if (result != null) {
                                    RelayReviewCard(
                                        result = result,
                                        allResults = multiSourceResults,
                                        nip66Relays = nip66Relays,
                                        onConfirm = { selectedResult ->
                                            saveRelayConfig(selectedResult)
                                        },
                                        onEdit = { selectedResult ->
                                            onOpenRelayManager(selectedResult.writeRelays, selectedResult.readRelays)
                                        },
                                        onUpdateOutdated = { correctResult, outdatedUrls ->
                                            Nip65RelayListRepository.publishToOutdatedRelays(correctResult, outdatedUrls)
                                        }
                                    )
                                }
                            }

                            OnboardingPhase.NIP65_NOT_FOUND -> {
                                // During onboarding: navigate to relay manager to add indexers
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No indexer relays found.\nAdd indexers to search for your relay configuration.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )

                                    Spacer(Modifier.height(20.dp))

                                    Button(
                                        onClick = { onOpenRelayDiscoverySelection(emptyList()) },
                                        modifier = Modifier.fillMaxWidth(0.8f)
                                    ) {
                                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Add Indexer Relays")
                                    }

                                    Spacer(Modifier.height(12.dp))

                                    TextButton(onClick = onComplete) {
                                        Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            OnboardingPhase.SAVING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF4CAF50)
                                )
                            }

                            OnboardingPhase.READY -> {
                                // Brief success — auto-navigates via LaunchedEffect
                            }

                            OnboardingPhase.ERROR -> {
                                Text(
                                    text = errorMessage ?: "Something went wrong",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                FilledTonalButton(onClick = onComplete) {
                                    Text("Continue Anyway")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Indexer Selection Card — two-group layout ──
// Group 1: "Fastest" — top 5 by RTT (always visible, toggleable)
// Group 2: "Your picks" — user-selected relays not in the RTT group
// Fixed 7 visible rows total (5 RTT + 2 user picks). Scrollable if more user picks.

@Composable
private fun IndexerSelectionCard(
    allIndexers: List<DiscoveredRelay>,
    initialSelectedUrls: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onChooseOwn: (Set<String>) -> Unit,
    onAddMore: (Set<String>) -> Unit
) {
    // Own selection state — isolated from parent to prevent AnimatedContent recomposition
    var selectedUrls by remember(initialSelectedUrls) { mutableStateOf(initialSelectedUrls) }

    // Top 5 by RTT (always shown regardless of selection state)
    val rttGroup = remember(allIndexers) { allIndexers.take(5) }
    val rttUrls = remember(rttGroup) { rttGroup.map { it.url }.toSet() }

    // Your Picks: selected URLs that aren't in the RTT group
    val userPickUrls = remember(selectedUrls, rttUrls) {
        selectedUrls.filter { it !in rttUrls }
    }
    // Resolve user pick URLs to DiscoveredRelay objects (if available)
    val userPickRelays = remember(userPickUrls, allIndexers) {
        val indexerMap = allIndexers.associateBy { it.url }
        userPickUrls.map { url -> indexerMap[url] to url }
    }

    // Toggle helper
    fun toggle(url: String) {
        val updated = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
        selectedUrls = updated
        onSelectionChanged(updated)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Radar, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("${selectedUrls.size} indexer${if (selectedUrls.size != 1) "s" else ""} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Text("${allIndexers.size} available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(10.dp))

                // ── Group 1: Fastest by RTT ──
                Text("fastest by latency",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))

                rttGroup.forEach { relay ->
                    key(relay.url) {
                        IndexerRelayRow(
                            relay = relay,
                            isSelected = relay.url in selectedUrls,
                            onClick = { toggle(relay.url) }
                        )
                    }
                }

                // ── Group 2: Your picks (scrollable) ──
                if (userPickRelays.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))

                    Text("your picks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        userPickRelays.forEach { (relay, url) ->
                            key(url) {
                                if (relay != null) {
                                    IndexerRelayRow(
                                        relay = relay,
                                        isSelected = true,
                                        onClick = { toggle(url) }
                                    )
                                } else {
                                    // URL not in NIP-66 data — show raw URL with lightweight indicator
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { toggle(url) }
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Action buttons
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onConfirm(selectedUrls) },
            enabled = selectedUrls.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search These Indexers")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onAddMore(selectedUrls) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add More Indexers")
        }

        Spacer(Modifier.height(4.dp))

        TextButton(
            onClick = { onChooseOwn(selectedUrls) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I'll choose my own indexers",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun IndexerRelayRow(
    relay: DiscoveredRelay,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        // Lightweight check indicator — replaces Material3 Checkbox which is
        // expensive (ripple, animation, accessibility) and causes scroll lag at 50+ rows
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp)
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            CircleShape
                        )
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                relay.name?.let { add(it) }
                relay.countryCode?.let { add(countryCodeToFlag(it)) }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        relay.avgRttRead?.let { rtt ->
            Spacer(Modifier.width(6.dp))
            val rttColor = when {
                rtt < 200 -> Color(0xFF4CAF50)
                rtt < 500 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            Text("${rtt}ms", style = MaterialTheme.typography.labelSmall,
                color = rttColor, fontSize = 10.sp)
        }
    }
}

// ── Multi-source NIP-65 search progress card ──
// Scalable design: groups relays by status (success → pending → no-data → failed/timeout),
// uses lazy scrolling inside a fixed-height container, supports re-ping per relay and batch retry.
// Handles thousands of indexers without layout lag.

@Composable
private fun MultiSourceSearchCard(
    statuses: List<Nip65RelayListRepository.IndexerQueryState>,
    results: List<Nip65RelayListRepository.Nip65SourceResult>,
    total: Int,
    done: Boolean,
    onProceed: () -> Unit,
    onManualSetup: () -> Unit,
    onRelayLogClick: (String) -> Unit
) {
    val successCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
    val failCount = statuses.count {
        it.status == Nip65RelayListRepository.IndexerQueryStatus.FAILED ||
        it.status == Nip65RelayListRepository.IndexerQueryStatus.TIMEOUT
    }
    val noDataCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.NO_DATA }
    val pendingCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.PENDING }
    val hasResults = results.isNotEmpty()
    val retryableCount = failCount + noDataCount

    // Group statuses by category for collapsible sections
    val successList = remember(statuses) {
        statuses.filter { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
    }
    val pendingList = remember(statuses) {
        statuses.filter { it.status == Nip65RelayListRepository.IndexerQueryStatus.PENDING }
    }
    val noDataList = remember(statuses) {
        statuses.filter { it.status == Nip65RelayListRepository.IndexerQueryStatus.NO_DATA }
    }
    val failedList = remember(statuses) {
        statuses.filter {
            it.status == Nip65RelayListRepository.IndexerQueryStatus.FAILED ||
            it.status == Nip65RelayListRepository.IndexerQueryStatus.TIMEOUT
        }
    }

    // Collapsible section state — success expanded by default, others collapsed when >5
    var successExpanded by remember { mutableStateOf(true) }
    var pendingExpanded by remember { mutableStateOf(true) }
    var noDataExpanded by remember { mutableStateOf(false) }
    var failedExpanded by remember { mutableStateOf(false) }

    // Date discrepancy
    val uniqueTimestamps = remember(results) { results.map { it.createdAt }.distinct().sorted() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header with progress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!done) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = if (!done) "Querying $total relays\u2026 ($pendingCount pending)"
                               else buildString {
                                   append("$successCount of $total responded")
                                   if (failCount > 0) append(" \u00b7 $failCount failed")
                                   if (noDataCount > 0) append(" \u00b7 $noDataCount empty")
                               },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Progress bar while searching
                if (total > 0 && !done) {
                    Spacer(Modifier.height(8.dp))
                    val completed = statuses.count { it.status != Nip65RelayListRepository.IndexerQueryStatus.PENDING }
                    LinearProgressIndicator(
                        progress = { completed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))

                // ── Grouped relay status sections (scrollable) ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // SUCCESS group
                    if (successList.isNotEmpty()) {
                        StatusGroupHeader(
                            icon = Icons.Filled.CheckCircle,
                            color = Color(0xFF4CAF50),
                            label = "Responded",
                            count = successCount,
                            expanded = successExpanded,
                            onToggle = { successExpanded = !successExpanded }
                        )
                        if (successExpanded) {
                            successList.forEach { state ->
                                IndexerStatusRow(
                                    state = state,
                                    onRelayLogClick = onRelayLogClick,
                                    onRePing = null
                                )
                            }
                        }
                    }

                    // PENDING group
                    if (pendingList.isNotEmpty()) {
                        StatusGroupHeader(
                            icon = Icons.Outlined.HourglassEmpty,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            label = "Pending",
                            count = pendingCount,
                            expanded = pendingExpanded,
                            onToggle = { pendingExpanded = !pendingExpanded }
                        )
                        if (pendingExpanded) {
                            pendingList.forEach { state ->
                                IndexerStatusRow(
                                    state = state,
                                    onRelayLogClick = onRelayLogClick,
                                    onRePing = null
                                )
                            }
                        }
                    }

                    // NO DATA group
                    if (noDataList.isNotEmpty()) {
                        StatusGroupHeader(
                            icon = Icons.Outlined.RemoveCircleOutline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            label = "No Data",
                            count = noDataCount,
                            expanded = noDataExpanded,
                            onToggle = { noDataExpanded = !noDataExpanded }
                        )
                        if (noDataExpanded) {
                            noDataList.forEach { state ->
                                IndexerStatusRow(
                                    state = state,
                                    onRelayLogClick = onRelayLogClick,
                                    onRePing = if (done) {{ Nip65RelayListRepository.rePingIndexer(state.url) }} else null
                                )
                            }
                        }
                    }

                    // FAILED / TIMEOUT group
                    if (failedList.isNotEmpty()) {
                        StatusGroupHeader(
                            icon = Icons.Filled.Error,
                            color = MaterialTheme.colorScheme.error,
                            label = "Failed",
                            count = failCount,
                            expanded = failedExpanded,
                            onToggle = { failedExpanded = !failedExpanded }
                        )
                        if (failedExpanded) {
                            failedList.forEach { state ->
                                IndexerStatusRow(
                                    state = state,
                                    onRelayLogClick = onRelayLogClick,
                                    onRePing = if (done) {{ Nip65RelayListRepository.rePingIndexer(state.url) }} else null
                                )
                            }
                        }
                    }
                }

                // Date discrepancy warning
                if (done && uniqueTimestamps.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.Warning, null, Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${uniqueTimestamps.size} different event dates found across indexers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // Action buttons (only after search completes)
        if (done) {
            Spacer(Modifier.height(16.dp))

            if (hasResults) {
                Button(
                    onClick = onProceed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Review Received Events")
                }
            } else {
                OutlinedButton(
                    onClick = onManualSetup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Configure Manually")
                }
            }

            // Retry all failed/no-data button
            if (retryableCount > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { Nip65RelayListRepository.rePingAllFailed() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retry $retryableCount Failed")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** Collapsible group header for status sections. */
@Composable
private fun StatusGroupHeader(
    icon: ImageVector,
    color: Color,
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/** Single indexer status row with optional re-ping button. */
@Composable
private fun IndexerStatusRow(
    state: Nip65RelayListRepository.IndexerQueryState,
    onRelayLogClick: (String) -> Unit,
    onRePing: (() -> Unit)?
) {
    val statusColor: Color
    val statusLabel: String

    when (state.status) {
        Nip65RelayListRepository.IndexerQueryStatus.PENDING -> {
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            statusLabel = "pending"
        }
        Nip65RelayListRepository.IndexerQueryStatus.SUCCESS -> {
            statusColor = Color(0xFF4CAF50)
            val r = state.result
            statusLabel = if (r != null) "${r.rTagCount} relays" else "ok"
        }
        Nip65RelayListRepository.IndexerQueryStatus.NO_DATA -> {
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            statusLabel = "no data"
        }
        Nip65RelayListRepository.IndexerQueryStatus.FAILED -> {
            statusColor = MaterialTheme.colorScheme.error
            statusLabel = "failed"
        }
        Nip65RelayListRepository.IndexerQueryStatus.TIMEOUT -> {
            statusColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            statusLabel = "timeout"
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRelayLogClick(state.url) }
            .padding(vertical = 3.dp, horizontal = 4.dp)
    ) {
        if (state.status == Nip65RelayListRepository.IndexerQueryStatus.PENDING) {
            CircularProgressIndicator(
                Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = statusColor
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, shape = CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))

        Text(
            text = state.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS)
                MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp
        )

        Spacer(Modifier.width(4.dp))

        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontSize = 9.sp
        )

        // Re-ping button for failed/no-data relays
        if (onRePing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "Retry",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRePing),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

// ── Relay Review Card — shows ALL returned events grouped by created_at ──
// Groups results by event timestamp. Each group shows which indexers returned
// that version, the relay list, and diffs vs the latest version. Outlier
// groups (old events from few indexers) are flagged.
// Buttons are placed ABOVE the relay list so the user doesn't have to scroll.

@Composable
private fun RelayReviewCard(
    result: Nip65RelayListRepository.Nip65SourceResult,
    allResults: List<Nip65RelayListRepository.Nip65SourceResult>,
    nip66Relays: Map<String, DiscoveredRelay>,
    onConfirm: (Nip65RelayListRepository.Nip65SourceResult) -> Unit,
    onEdit: (Nip65RelayListRepository.Nip65SourceResult) -> Unit,
    onUpdateOutdated: (Nip65RelayListRepository.Nip65SourceResult, List<String>) -> Map<String, Boolean> = { _, _ -> emptyMap() }
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    // Group results by created_at timestamp, sorted newest first
    val groupedByTimestamp = remember(allResults) {
        allResults.groupBy { it.createdAt }
            .entries
            .sortedByDescending { it.key }
    }
    val latestGroup = groupedByTimestamp.firstOrNull()
    val latestRelaySet = remember(latestGroup) {
        latestGroup?.value?.firstOrNull()?.allRelays?.toSet() ?: emptySet()
    }

    // Track which group the user wants to use (default: latest)
    var selectedTimestamp by remember(result) { mutableStateOf(result.createdAt) }

    // The result the user has selected
    val selectedResult = remember(selectedTimestamp, allResults) {
        allResults.firstOrNull { it.createdAt == selectedTimestamp } ?: result
    }
    val selectedRelays = remember(selectedResult) {
        val writeSet = selectedResult.writeRelays.toSet()
        val readSet = selectedResult.readRelays.toSet()
        selectedResult.allRelays.distinct().map { url ->
            Triple(url, url in writeSet, url in readSet)
        }
    }

    // Per-relay update status tracking: url → true (success) / false (fail)
    var updateResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isUpdating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Only allow updating when the selected version is the LATEST (newest created_at).
    // This prevents accidentally pushing older NIP-65 events to the network.
    val isLatestSelected = selectedTimestamp == groupedByTimestamp.firstOrNull()?.key
    val outdatedForSelected = remember(groupedByTimestamp, selectedTimestamp, isLatestSelected) {
        if (!isLatestSelected) emptyList()
        else groupedByTimestamp
            .filter { it.key != selectedTimestamp }
            .flatMap { (_, results) -> results.map { it.indexerUrl } }
    }
    // Only allow update if we have the raw signed event AND selected version is latest
    val canUpdate = isLatestSelected && outdatedForSelected.isNotEmpty() && selectedResult.rawEvent != null
    // Filter out already-successfully-updated relays
    val pendingOutdated = remember(outdatedForSelected, updateResults) {
        outdatedForSelected.filter { url -> updateResults[url] != true }
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ═══ ACTION BUTTONS — always visible at top ═══
        Button(
            onClick = { onConfirm(selectedResult) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Confirm Relay Choices")
        }

        if (canUpdate && pendingOutdated.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    if (!isUpdating) {
                        isUpdating = true
                        scope.launch(Dispatchers.IO) {
                            val results = onUpdateOutdated(selectedResult, pendingOutdated)
                            withContext(Dispatchers.Main) {
                                updateResults = updateResults + results
                                isUpdating = false
                            }
                        }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Update ${pendingOutdated.size} outdated relays")
            }
        }

        // Show update summary if any updates have been attempted
        val updatedOk = updateResults.count { it.value }
        val updatedFail = updateResults.count { !it.value }
        if (updatedOk > 0 || updatedFail > 0) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                if (updatedOk > 0) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(12.dp), tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(4.dp))
                    Text("$updatedOk updated", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50), fontSize = 10.sp)
                }
                if (updatedOk > 0 && updatedFail > 0) Spacer(Modifier.width(12.dp))
                if (updatedFail > 0) {
                    Icon(Icons.Filled.Error, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("$updatedFail failed", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onEdit(selectedResult) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Edit in Relay Manager")
        }

        Spacer(Modifier.height(16.dp))

        // ═══ EVENT VERSION GROUPS (only if multiple versions) ═══
        if (groupedByTimestamp.size > 1) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("${groupedByTimestamp.size} event versions found",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("Indexers returned different versions of your relay list. " +
                         "Tap a version to review it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp)

                    Spacer(Modifier.height(10.dp))

                    groupedByTimestamp.forEachIndexed { idx, (timestamp, results) ->
                        val isLatest = idx == 0
                        val isSelected = timestamp == selectedTimestamp
                        val indexerCount = results.size
                        val relayCount = results.first().rTagCount
                        val relaySet = results.first().allRelays.toSet()

                        // Compute diff vs latest
                        val added = if (!isLatest) relaySet - latestRelaySet else emptySet()
                        val removed = if (!isLatest) latestRelaySet - relaySet else emptySet()
                        val isOutlier = !isLatest && indexerCount == 1 && groupedByTimestamp.first().value.size > 1

                        // Per-relay update status for this group's indexers
                        val groupIndexerUrls = results.map { it.indexerUrl }
                        val groupUpdatedOk = groupIndexerUrls.count { updateResults[it] == true }
                        val groupUpdatedFail = groupIndexerUrls.count { updateResults[it] == false }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            ) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { selectedTimestamp = timestamp }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isSelected) {
                                        Icon(Icons.Filled.RadioButtonChecked, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Icon(Icons.Filled.RadioButtonUnchecked, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dateFormat.format(Date(timestamp * 1000)),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "$indexerCount indexer${if (indexerCount != 1) "s" else ""} · $relayCount relays",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (isLatest) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF4CAF50).copy(alpha = 0.15f)
                                        ) {
                                            Text("latest", style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4CAF50), fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                    if (isOutlier) {
                                        Spacer(Modifier.width(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        ) {
                                            Text("outlier", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error, fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }

                                // Diff summary for non-latest groups
                                if (!isLatest && (added.isNotEmpty() || removed.isNotEmpty())) {
                                    Spacer(Modifier.height(4.dp))
                                    Row {
                                        if (removed.isNotEmpty()) {
                                            Text("−${removed.size} missing",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 9.sp)
                                        }
                                        if (removed.isNotEmpty() && added.isNotEmpty()) {
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        if (added.isNotEmpty()) {
                                            Text("+${added.size} extra",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4CAF50),
                                                fontSize = 9.sp)
                                        }
                                    }
                                }

                                // Per-indexer rows with update status indicators
                                results.forEach { r ->
                                    val urlShort = r.indexerUrl.removePrefix("wss://").removeSuffix("/")
                                    val status = updateResults[r.indexerUrl]
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (status) {
                                            true -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(10.dp),
                                                tint = Color(0xFF4CAF50))
                                            false -> Icon(Icons.Filled.Error, null, Modifier.size(10.dp),
                                                tint = MaterialTheme.colorScheme.error)
                                            null -> Spacer(Modifier.width(10.dp))
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "↳ $urlShort",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (status) {
                                                true -> Color(0xFF4CAF50).copy(alpha = 0.7f)
                                                false -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                                null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            },
                                            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Per-group update status summary
                                if (groupUpdatedOk > 0 || groupUpdatedFail > 0) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = buildString {
                                            if (groupUpdatedOk > 0) append("$groupUpdatedOk updated")
                                            if (groupUpdatedOk > 0 && groupUpdatedFail > 0) append(" · ")
                                            if (groupUpdatedFail > 0) append("$groupUpdatedFail failed")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (groupUpdatedFail > 0) MaterialTheme.colorScheme.error
                                                else Color(0xFF4CAF50),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // ═══ SELECTED VERSION RELAY LIST (capped for performance) ═══
        val maxVisibleRelays = 8
        var showAllRelays by remember { mutableStateOf(false) }
        val visibleRelays = if (showAllRelays) selectedRelays else selectedRelays.take(maxVisibleRelays)
        val hasMore = selectedRelays.size > maxVisibleRelays && !showAllRelays

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // Summary row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Hub, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("${selectedRelays.size} relay${if (selectedRelays.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    if (selectedResult.writeRelays.isNotEmpty()) {
                        RelayBadge("${selectedResult.writeRelays.size}", Icons.Outlined.Upload, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (selectedResult.readRelays.isNotEmpty()) {
                        RelayBadge("${selectedResult.readRelays.size}", Icons.Outlined.Download, Color(0xFF4CAF50))
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                // Relay URL list with NIP-66 enrichment (capped)
                visibleRelays.forEach { (url, isWrite, isRead) ->
                    val nip66 = nip66Relays[url.trim().removeSuffix("/").lowercase()]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (nip66 != null) {
                                val meta = buildList {
                                    nip66.name?.let { add(it) }
                                    nip66.countryCode?.let { add(countryCodeToFlag(it)) }
                                    nip66.avgRttRead?.let { add("${it}ms") }
                                }
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        val label = if (isWrite && isRead) "r/w" else if (isWrite) "write" else "read"
                        val labelColor = if (isWrite && isRead) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else if (isWrite) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else Color(0xFF4CAF50).copy(alpha = 0.7f)
                        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = labelColor)
                    }
                }

                // "Show all" button if list is capped
                if (hasMore) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { showAllRelays = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Show all ${selectedRelays.size} relays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                // Source info
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))

                val dateStr = if (selectedResult.createdAt > 0)
                    dateFormat.format(Date(selectedResult.createdAt * 1000)) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        if (dateStr != null) {
                            Text("Published $dateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        val matchingIndexers = allResults.filter { it.createdAt == selectedTimestamp }
                        Text("${matchingIndexers.size} indexer${if (matchingIndexers.size != 1) "s" else ""} returned this version",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Small badge for outbox/inbox counts ──

@Composable
private fun RelayBadge(
    text: String,
    icon: ImageVector,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Icon(icon, null, Modifier.size(10.dp), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = color, fontSize = 10.sp)
        }
    }
}

// ── Country code to flag emoji ──

private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return code
    val first = Character.toChars(0x1F1E6 + (code[0].uppercaseChar() - 'A'))
    val second = Character.toChars(0x1F1E6 + (code[1].uppercaseChar() - 'A'))
    return String(first) + String(second)
}
