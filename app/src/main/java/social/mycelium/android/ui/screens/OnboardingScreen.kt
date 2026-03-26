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
import androidx.compose.ui.focus.focusRequester
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
import social.mycelium.android.ui.settings.ConnectionMode
import social.mycelium.android.ui.settings.NotificationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-login onboarding screen with intelligent relay discovery.
 *
 * ## Phase Order
 *
 * 1. CHOOSE_MODE — user picks "Find my relays" (auto) or "Set up manually"
 * 2. LOADING_INDEXERS — wait for NIP-66 relay monitor data (fetched at app init)
 * 3. SEARCHING_NIP65 — query anchor + NIP-66 indexers for kind-10002, stream results
 * 4. REVIEW_OUTBOXES — user reviews and confirms the discovered outbox/inbox config
 * 5. SAVING — apply confirmed NIP-65 config, clean indexers, fetch kind-10086/10050
 * 6. SELECT_INDEXERS — user confirms indexer relay set (AFTER outbox config is saved)
 * 7. PREFETCHING_LISTS — fetch follows, mutes, relay sets, bookmarks
 * 8. NOTIFICATION_SETUP — interactive notification permission & power walkthrough
 * 9. READY — navigate to dashboard
 *
 * The user always has agency: they can confirm, edit, skip, or go fully manual.
 * If no NIP-65 is found, NIP65_NOT_FOUND offers manual relay setup or retry.
 */
private enum class OnboardingPhase {
    CHOOSE_MODE,           // Instant: user picks "Find my relays" or "Set up manually"
    LOADING_INDEXERS,      // Waiting for NIP-66 relay monitor data
    SEARCHING_NIP65,       // Querying indexers for kind-10002
    REVIEW_OUTBOXES,       // User reviews and merges discovered relay config (Diff UI)
    SELECT_INDEXERS,       // User picks indexers AFTER confirming outboxes
    PREFETCHING_LISTS,     // Background fetch of Mutes/Follows/People
    NOTIFICATION_SETUP,    // Interactive notification permission & power walkthrough
    NIP65_NOT_FOUND,       // No relay list found — manual setup
    SAVING,                // Applying confirmed NIP-65 config
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
        val restorable = setOf("REVIEW_OUTBOXES", "SELECT_INDEXERS", "NIP65_NOT_FOUND", "PREFETCHING_LISTS", "NOTIFICATION_SETUP")
        val initial = savedPhase?.takeIf { it in restorable }?.let {
            try {
                OnboardingPhase.valueOf(it)
            } catch (_: Exception) {
                null
            }
        } ?: OnboardingPhase.CHOOSE_MODE
        mutableStateOf(initial)
    }
    var statusText by remember {
        mutableStateOf(
            when (phase) {
                OnboardingPhase.SELECT_INDEXERS -> "Select your indexer relays"
                OnboardingPhase.REVIEW_OUTBOXES -> "Review your relay configuration"
                OnboardingPhase.PREFETCHING_LISTS -> "Downloading your lists…"
                OnboardingPhase.NOTIFICATION_SETUP -> "Configure notifications for the best experience"
                OnboardingPhase.CHOOSE_MODE -> "Welcome to Mycelium"
                else -> "Preparing indexer relays\u2026"
            }
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // NIP-66 indexer discovery state — ranked by trust + geo affinity
    var allIndexers by remember { mutableStateOf<List<DiscoveredRelay>>(emptyList()) }

    /** Tracks whether the indexer selection was populated from a published kind-10086/10050
     *  event (true) vs. NIP-66 suggestions / manual entry (false). Drives UI labeling. */
    var indexerSourceIsPublished by remember { mutableStateOf(false) }
    // Which indexer URLs the user has selected (pre-checked: top 5 by trust score).
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

    // ── Main initialization effect — restore state or show CHOOSE_MODE ──
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
            Log.d(
                "OnboardingScreen",
                "Restoring cached multi-source results for ${hexPubkey.take(8)} (${cachedResults.size} results)"
            )
            val best = cachedResults.maxByOrNull { it.createdAt }!!
            chosenResult = best
            val statuses = Nip65RelayListRepository.multiSourceStatuses.value
            val successCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
            // Restore to REVIEW_OUTBOXES if that's where the user was (e.g. back from relay manager)
            if (savedPhase == "REVIEW_OUTBOXES") {
                val uniqueTimestamps = cachedResults.map { it.createdAt }.distinct()
                statusText = if (uniqueTimestamps.size > 1)
                    "Found ${cachedResults.size} sources — review your relay configuration"
                else "Found your relay configuration"
                phase = OnboardingPhase.REVIEW_OUTBOXES
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
        val isReturning = hasReturnedIndexers ||
                (savedPhase == "SELECT_INDEXERS" && savedIndexers.isNotEmpty())
        if (isReturning) {
            Log.d(
                "OnboardingScreen",
                "Returning to SELECT_INDEXERS (fromDiscovery=$hasReturnedIndexers, selections=${selectedIndexerUrls.size})"
            )
            val userCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
            val indexers = Nip66RelayDiscoveryRepository.getRankedIndexers(userCountry)
            allIndexers = indexers
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
            statusText = "Select your indexer relays"
            Log.d(
                "OnboardingScreen",
                "Refreshed indexer list: ${indexers.size} relays, ${selectedIndexerUrls.size} selected"
            )
            return@LaunchedEffect
        }

        // Fresh start — go to CHOOSE_MODE immediately (no spinner).
        // NIP-66 is already fetching in the background (started in MainActivity).
        Log.d("OnboardingScreen", "Fresh onboarding — showing CHOOSE_MODE (savedPhase=$savedPhase)")
        phase = OnboardingPhase.CHOOSE_MODE
        statusText = "Welcome to Mycelium"
        errorMessage = null
        chosenResult = null
        showCustomInput = false
        addedCustomRelays = emptyList()
    }

    // ── Start NIP-65 search with selected indexers ──
    val searchScope = rememberCoroutineScope()
    fun startNip65Search(indexerUrls: List<String>, timeoutMs: Long = 3_000L) {
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
                Nip65RelayListRepository.fetchRelayListMultiSource(hexPubkey, indexerUrls, timeoutMs)

                // Wait for multi-source search to complete. With early completion
                // (3+ results → done) and 3s per-indexer timeout, this typically
                // finishes in under 5s. Cap at 30s as a safety net.
                var waited = 0L
                while (!Nip65RelayListRepository.multiSourceDone.value && waited < 30_000L) {
                    delay(500)
                    waited += 500
                }

                val results = Nip65RelayListRepository.multiSourceResults.value
                val statuses = Nip65RelayListRepository.multiSourceStatuses.value
                val successCount = statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.SUCCESS }
                val failCount =
                    statuses.count { it.status == Nip65RelayListRepository.IndexerQueryStatus.FAILED || it.status == Nip65RelayListRepository.IndexerQueryStatus.TIMEOUT }
                Log.d(
                    "OnboardingScreen",
                    "Multi-source: $successCount success, $failCount failed/timeout, ${results.size} with data"
                )

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

    // Known-reliable indexer relays with wide event coverage.
    // These are included as anchors alongside NIP-66 ranked results to ensure
    // at least some proven relays are always queried during auto-search.
    val anchorIndexers = listOf(
        "wss://relay.nostr.band",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://nostr.wine",
        "wss://relay.ditto.pub",
        "wss://purplepag.es",
    )

    // ── Auto-search: triggered when user picks "Find my relays" from CHOOSE_MODE ──
    fun startAutoSearch() {
        phase = OnboardingPhase.LOADING_INDEXERS
        statusText = "Finding indexer relays\u2026"

        searchScope.launch(Dispatchers.IO) {
            // Wait for NIP-66 data — but only briefly. It's already fetching from
            // MainActivity.onCreate(). Most fetches complete in <2s from disk cache.
            var waited = 0L
            val maxWait = 6_000L
            while (waited < maxWait) {
                val discovered = Nip66RelayDiscoveryRepository.discoveredRelays.value
                val hasIndexers = discovered.values.any { it.isSearch }
                if (hasIndexers) {
                    Log.d("OnboardingScreen", "Auto-search: NIP-66 indexers ready after ${waited}ms")
                    break
                }
                if (Nip66RelayDiscoveryRepository.hasFetched.value && !Nip66RelayDiscoveryRepository.isLoading.value) {
                    Log.d("OnboardingScreen", "Auto-search: NIP-66 fetch done after ${waited}ms")
                    break
                }
                delay(200)
                waited += 200
            }

            // Get ranked indexers (already filters auth-required, payment-required, stale)
            val userCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
            val indexers = Nip66RelayDiscoveryRepository.getRankedIndexers(userCountry)
            allIndexers = indexers

            // Merge anchor indexers with NIP-66 ranked results.
            // Anchors go first (proven reliable), then NIP-66 ranked fills the rest.
            // Deduplicate by normalized URL so anchors aren't doubled if they appear in NIP-66.
            val anchorNormalized = anchorIndexers.map { it.trim().removeSuffix("/").lowercase() }.toSet()
            val nip66Extras = indexers
                .map { it.url }
                .filter { it.trim().removeSuffix("/").lowercase() !in anchorNormalized }
                .take(6) // Up to 6 NIP-66 relays on top of the anchors
            val autoSelected = (anchorIndexers + nip66Extras).distinct()

            if (autoSelected.isEmpty()) {
                Log.w("OnboardingScreen", "Auto-search: no indexers found, falling back to manual")
                withContext(Dispatchers.Main) {
                    phase = OnboardingPhase.NIP65_NOT_FOUND
                    statusText = "No monitored indexers found — configure manually"
                }
                return@launch
            }

            selectedIndexerUrls = autoSelected.toSet()
            Log.d(
                "OnboardingScreen",
                "Auto-search: selected ${autoSelected.size} indexers (${anchorIndexers.size} anchors + ${nip66Extras.size} NIP-66)"
            )

            // Immediately start the NIP-65 multi-source search with 5s timeout
            // (cold WebSocket connections to new relays need more than 3s for
            // DNS + TCP + TLS + WS upgrade + REQ + response)
            withContext(Dispatchers.Main) {
                startNip65Search(autoSelected, timeoutMs = 5_000L)
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
            phase = OnboardingPhase.REVIEW_OUTBOXES
        } else {
            phase = OnboardingPhase.NIP65_NOT_FOUND
            statusText = "No relay configuration found"
        }
    }

    // ── Auto-complete when user returns from relay discovery/manager with relays configured ──
    // ── Auto-complete when user returns from relay discovery/manager ──
    // IMPORTANT: Only watch NIP65_NOT_FOUND phase here — SELECT_INDEXERS is now an
    // explicit user-confirmed step and must NOT be auto-advanced based on category state.
    LaunchedEffect(phase) {
        if (phase != OnboardingPhase.NIP65_NOT_FOUND) return@LaunchedEffect
        while (phase == OnboardingPhase.NIP65_NOT_FOUND) {
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

            // Apply to singleton state — REPLACE entirely so no stale outbox URLs survive
            // from a previously cached NIP-65 result. The old applyMultiSourceResult may
            // merge rather than replace.
            Nip65RelayListRepository.applyMultiSourceResult(result)

            // REPLACE outbox/inbox storage entirely — do NOT merge. This ensures removed relays
            // are actually removed and no ghost connections are established.
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

            // Clean up: remove outbox/inbox relay URLs from the Indexer list.
            // These were seeded during auto-search as anchor indexers but if they
            // overlap with the user's personal NIP-65 relays, they will create
            // duplicate subscription channels and choke the relay pool.
            val personalUrls = allNip65Urls.map { it.trim().removeSuffix("/").lowercase() }.toSet()
            val existingIndexers = storageManager.loadIndexerRelays(hexPubkey)
            val cleanedIndexers = existingIndexers.filter { indexer ->
                indexer.url.trim().removeSuffix("/").lowercase() !in personalUrls
            }
            if (cleanedIndexers.size < existingIndexers.size) {
                storageManager.saveIndexerRelays(hexPubkey, cleanedIndexers)
                Log.d("OnboardingScreen", "Cleaned ${existingIndexers.size - cleanedIndexers.size} personal relays from indexer list (${cleanedIndexers.size} remaining)")
            }

            // Before populating the indexer selection screen with NIP-66 suggestions,
            // check if the user has an existing kind-10050 (DM relays) or kind-10086
            // (Indexer list). The user explicitly requested we honor these lists before suggesting.
            var fetchedIndexerUrls: Set<String>? = null
            try {
                val stateMachine = social.mycelium.android.relay.RelayConnectionStateMachine.getInstance()
                val fetchUrls = (result.writeRelays + result.readRelays).distinct().take(5)
                if (fetchUrls.isNotEmpty()) {
                    var collected10086: com.example.cybin.core.Event? = null
                    var collected10050: com.example.cybin.core.Event? = null
                    
                    stateMachine.awaitOneShotSubscription(
                        fetchUrls,
                        com.example.cybin.core.Filter(
                            kinds = listOf(10050, 10086),
                            authors = listOf(hexPubkey),
                            limit = 5
                        ),
                        priority = com.example.cybin.relay.SubscriptionPriority.LOW,
                        settleMs = 800L,
                        maxWaitMs = 3000L
                    ) { event ->
                        if (event.kind == 10086) {
                            if (collected10086 == null || event.createdAt > collected10086!!.createdAt) {
                                collected10086 = event
                            }
                        } else if (event.kind == 10050) {
                            if (collected10050 == null || event.createdAt > collected10050!!.createdAt) {
                                collected10050 = event
                            }
                        }
                    }

                    // Prefer 10086 (explicit indexers), fallback to 10050 (DM relays)
                    val bestEvent = collected10086 ?: collected10050
                    if (bestEvent != null) {
                        fetchedIndexerUrls = bestEvent.tags
                            .filter { it.size >= 2 && it[0] == "relay" }
                            .map { social.mycelium.android.utils.normalizeRelayUrl(it[1]) }
                            .toSet()
                        Log.d("OnboardingScreen", "Found published list (kind=${bestEvent.kind}) with ${fetchedIndexerUrls.size} relays")
                    }
                }
            } catch (e: Exception) {
                Log.e("OnboardingScreen", "Failed to fetch existing lists: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                val userCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
                val indexers = Nip66RelayDiscoveryRepository.getRankedIndexers(userCountry)
                allIndexers = indexers

                val outboxes = storageManager.loadOutboxRelays(hexPubkey)
                    .map { it.url.trim().removeSuffix("/").lowercase() }.toSet()

                if (fetchedIndexerUrls != null && fetchedIndexerUrls!!.isNotEmpty()) {
                    selectedIndexerUrls = fetchedIndexerUrls!!
                        .filter { it.trim().removeSuffix("/").lowercase() !in outboxes }
                        .toSet()
                    indexerSourceIsPublished = true
                    statusText = "Your published indexer list"
                } else if (selectedIndexerUrls.isEmpty()) {
                    selectedIndexerUrls = indexers.map { it.url }
                        .filter { it.trim().removeSuffix("/").lowercase() !in outboxes }
                        .take(5).toSet()
                    indexerSourceIsPublished = false
                    statusText = "Select your indexer relays"
                } else {
                    selectedIndexerUrls = selectedIndexerUrls
                        .filter { it.trim().removeSuffix("/").lowercase() !in outboxes }
                        .toSet()
                    indexerSourceIsPublished = false
                    statusText = "Select your indexer relays"
                }

                phase = OnboardingPhase.SELECT_INDEXERS
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
        OnboardingPhase.SAVING,
        OnboardingPhase.PREFETCHING_LISTS
    )
    val phaseColor = when (phase) {
        OnboardingPhase.CHOOSE_MODE -> MaterialTheme.colorScheme.primary
        OnboardingPhase.REVIEW_OUTBOXES -> MaterialTheme.colorScheme.primary
        OnboardingPhase.SELECT_INDEXERS -> MaterialTheme.colorScheme.primary
        OnboardingPhase.NOTIFICATION_SETUP -> MaterialTheme.colorScheme.primary
        OnboardingPhase.READY -> Color(0xFF4CAF50)
        OnboardingPhase.ERROR -> MaterialTheme.colorScheme.error
        OnboardingPhase.NIP65_NOT_FOUND -> MaterialTheme.colorScheme.tertiary
        OnboardingPhase.SAVING -> Color(0xFF4CAF50)
        OnboardingPhase.PREFETCHING_LISTS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    val scrollablePhases =
        phase == OnboardingPhase.REVIEW_OUTBOXES || phase == OnboardingPhase.SELECT_INDEXERS || phase == OnboardingPhase.SEARCHING_NIP65 || phase == OnboardingPhase.PREFETCHING_LISTS || phase == OnboardingPhase.NOTIFICATION_SETUP

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
                    OnboardingPhase.CHOOSE_MODE -> Icons.Outlined.Hub
                    OnboardingPhase.LOADING_INDEXERS -> Icons.Outlined.Radar
                    OnboardingPhase.SEARCHING_NIP65 -> Icons.Outlined.Search
                    OnboardingPhase.REVIEW_OUTBOXES -> Icons.Outlined.Hub
                    OnboardingPhase.SELECT_INDEXERS -> Icons.Outlined.Checklist
                    OnboardingPhase.PREFETCHING_LISTS -> Icons.Outlined.CloudDownload
                    OnboardingPhase.NOTIFICATION_SETUP -> Icons.Outlined.Notifications
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
                    OnboardingPhase.CHOOSE_MODE -> "Set Up Relays"
                    OnboardingPhase.LOADING_INDEXERS -> "Preparing"
                    OnboardingPhase.SEARCHING_NIP65 -> "Searching Relays"
                    OnboardingPhase.REVIEW_OUTBOXES -> "Review Your Relays"
                    OnboardingPhase.SELECT_INDEXERS -> "Select Indexers"
                    OnboardingPhase.PREFETCHING_LISTS -> "Downloading Lists"
                    OnboardingPhase.NOTIFICATION_SETUP -> "Notifications"
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
                            OnboardingPhase.CHOOSE_MODE -> {
                                // Two clean options — NIP-66 preloads silently in background
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Mycelium can find your relay configuration automatically using monitored indexer relays, or you can set things up yourself.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )

                                    // Option 1: Automatic
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { startAutoSearch() }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Radar,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(22.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Find my relays",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = "Auto-discover from monitored indexers",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                            )
                                        }
                                    }

                                    // Option 2: Manual
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                phase = OnboardingPhase.NIP65_NOT_FOUND
                                                statusText = "Configure relays manually"
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Tune,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(22.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Set up manually",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = "Add relays yourself or pick indexers",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }

                                    // NIP-66 loading indicator (subtle, below options)
                                    val nip66Loading by Nip66RelayDiscoveryRepository.isLoading.collectAsState()
                                    val nip66HasData by Nip66RelayDiscoveryRepository.hasFetched.collectAsState()
                                    if (nip66Loading && !nip66HasData) {
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                strokeWidth = 1.dp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Preloading relay index\u2026",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }

                            OnboardingPhase.LOADING_INDEXERS -> {
                                // Brief loading state while auto-search resolves NIP-66
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "Checking monitored relays\u2026",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    // Allow bailing out after 3 seconds
                                    var showSkip by remember { mutableStateOf(false) }
                                    LaunchedEffect(Unit) {
                                        delay(3000)
                                        showSkip = true
                                    }
                                    AnimatedVisibility(visible = showSkip) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Spacer(Modifier.height(16.dp))
                                            TextButton(onClick = {
                                                phase = OnboardingPhase.NIP65_NOT_FOUND
                                                statusText = "Configure relays manually"
                                            }) {
                                                Text(
                                                    "Skip to manual setup",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
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

                            OnboardingPhase.REVIEW_OUTBOXES -> {
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
                                            Nip65RelayListRepository.publishToOutdatedRelays(
                                                correctResult,
                                                outdatedUrls
                                            )
                                        }
                                    )
                                }
                            }

                            OnboardingPhase.SELECT_INDEXERS -> {
                                // Load confirmed outboxes to show overlap warnings
                                val outboxRelays by remember(hexPubkey) {
                                    mutableStateOf(storageManager.loadOutboxRelays(hexPubkey))
                                }
                                val outboxUrls = remember(outboxRelays) {
                                    outboxRelays.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
                                }

                                IndexerSelectionCard(
                                    allIndexers = allIndexers,
                                    initialSelectedUrls = selectedIndexerUrls,
                                    outboxUrls = outboxUrls,
                                    isFromPublishedList = indexerSourceIsPublished,
                                    onSelectionChanged = { latestSelectionRef.value = it },
                                    onConfirm = { urls ->
                                        selectedIndexerUrls = urls
                                        val newIndexerRelays = urls.map { UserRelay(url = it, read = true, write = false) }
                                        val existingIndexers = storageManager.loadIndexerRelays(hexPubkey)
                                        val existingUrls = existingIndexers.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
                                        val toAdd = newIndexerRelays.filter { it.url.trim().removeSuffix("/").lowercase() !in existingUrls }
                                        if (toAdd.isNotEmpty() || urls.isEmpty()) {
                                            storageManager.saveIndexerRelays(hexPubkey, newIndexerRelays)
                                        }
                                        storageManager.setIndexersConfirmed(hexPubkey, true)

                                        phase = OnboardingPhase.PREFETCHING_LISTS
                                        statusText = "Downloading your lists…"
                                    },
                                    onBrowseAll = { urls ->
                                        selectedIndexerUrls = urls
                                        onOpenRelayDiscoverySelection(urls.toList())
                                    }
                                )
                            }

                            OnboardingPhase.PREFETCHING_LISTS -> {
                                PrefetchingListsUI(
                                    hexPubkey = hexPubkey,
                                    onComplete = {
                                        phase = OnboardingPhase.NOTIFICATION_SETUP
                                        statusText = "Configure notifications for the best experience"
                                    }
                                )
                            }

                            OnboardingPhase.NOTIFICATION_SETUP -> {
                                NotificationSetupUI(
                                    onComplete = {
                                        phase = OnboardingPhase.READY
                                    }
                                )
                            }

                            OnboardingPhase.NIP65_NOT_FOUND -> {
                                // Manual setup: multiple paths forward
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Add relays manually, or pick indexer relays to search for your existing configuration.",
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

                                    Spacer(Modifier.height(8.dp))

                                    // Let user try auto-search even from manual mode
                                    OutlinedButton(
                                        onClick = { startAutoSearch() },
                                        modifier = Modifier.fillMaxWidth(0.8f)
                                    ) {
                                        Icon(Icons.Outlined.Radar, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Try Automatic Search")
                                    }

                                    Spacer(Modifier.height(12.dp))

                                    TextButton(onClick = { 
                                         val userCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
                                         allIndexers = Nip66RelayDiscoveryRepository.getRankedIndexers(userCountry)
                                         indexerSourceIsPublished = false
                                         statusText = "Select your indexer relays"
                                         phase = OnboardingPhase.SELECT_INDEXERS 
                                    }) {
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

// ── Indexer Selection Card — streamlined layout ──
// Group 1: "Recommended" — top 5 by trust score (toggleable)
// Group 2: "Your picks" — user-selected relays not in the recommended group
// Inline manual URL input for quick adds without navigating away
// Fixed 7 visible rows total (5 RTT + 2 user picks). Scrollable if more user picks.

@Composable
private fun IndexerSelectionCard(
    allIndexers: List<DiscoveredRelay>,
    initialSelectedUrls: Set<String>,
    outboxUrls: Set<String> = emptySet(),
    /** True when the initial selection came from a published kind-10086/10050 event. */
    isFromPublishedList: Boolean = false,
    onSelectionChanged: (Set<String>) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onBrowseAll: (Set<String>) -> Unit
) {
    // Own selection state — isolated from parent to prevent AnimatedContent recomposition
    var selectedUrls by remember(initialSelectedUrls) { mutableStateOf(initialSelectedUrls) }

    // Manual URL input state
    var manualUrl by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var manualUrlError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Top 5 by trust score (shown when defaults haven't been cleared)
    val topGroup = remember(allIndexers) { allIndexers.take(5) }
    val topUrls = remember(topGroup) { topGroup.map { it.url }.toSet() }

    // Track if user explicitly cleared defaults via "I'll add my own".
    // Initialize from persisted selection: if none of the top URLs are selected,
    // the user previously cleared defaults — don't re-show them.
    var defaultsCleared by remember(topUrls, initialSelectedUrls) {
        mutableStateOf(initialSelectedUrls.isNotEmpty() && topUrls.isNotEmpty() && initialSelectedUrls.none { it in topUrls })
    }

    // Visible recommended group: hide if user cleared defaults
    val visibleTopGroup = if (defaultsCleared) emptyList() else topGroup

    // Your Picks: selected URLs that aren't in the visible top group
    val visibleTopUrls = remember(visibleTopGroup) { visibleTopGroup.map { it.url }.toSet() }
    val userPickUrls = remember(selectedUrls, visibleTopUrls) {
        selectedUrls.filter { it !in visibleTopUrls }
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

    // Add manual URL helper
    fun addManualUrl() {
        val raw = manualUrl.trim()
        if (raw.isBlank()) return
        // Normalize: add wss:// if missing, strip trailing slash
        val normalized = when {
            raw.startsWith("wss://") || raw.startsWith("ws://") -> raw
            raw.startsWith("https://") -> raw.replaceFirst("https://", "wss://")
            raw.startsWith("http://") -> raw.replaceFirst("http://", "ws://")
            else -> "wss://$raw"
        }.removeSuffix("/")

        // Basic validation
        if (!normalized.startsWith("wss://") && !normalized.startsWith("ws://")) {
            manualUrlError = "Invalid relay URL"
            return
        }
        if (normalized in selectedUrls) {
            manualUrlError = "Already added"
            return
        }
        if (normalized in outboxUrls) {
            manualUrlError = "Already an outbox (redundant)"
            // we don't return here, if they really want to add it they can,
            // but we show the error. Actually let's just allow it for now but show the error.
        }

        val updated = selectedUrls + normalized
        selectedUrls = updated
        onSelectionChanged(updated)
        manualUrl = ""
        manualUrlError = null
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
                    Icon(
                        Icons.Outlined.Radar, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${selectedUrls.size} indexer${if (selectedUrls.size != 1) "s" else ""} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!defaultsCleared) {
                        Text(
                            "${allIndexers.size} available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Source context + explainer
                if (isFromPublishedList) {
                    Text(
                        "Found from your published relay list. These relays are used to look up profiles, follow lists, and relay configurations for other users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                } else {
                    Text(
                        "Indexer relays help discover profiles and relay lists for other users. These are suggested based on reliability and response time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── Group 1: Recommended (trust + geo affinity) — hidden when defaults cleared ──
                if (visibleTopGroup.isNotEmpty()) {
                    Text(
                        if (isFromPublishedList) "suggested" else "recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(4.dp))

                    visibleTopGroup.forEach { relay ->
                        key(relay.url) {
                            IndexerRelayRow(
                                relay = relay,
                                isSelected = relay.url in selectedUrls,
                                isOverlapWarning = relay.url.trim().removeSuffix("/").lowercase() in outboxUrls,
                                onClick = { toggle(relay.url) }
                            )
                        }
                    }
                }

                // ── Group 2: Your picks (scrollable) ──
                if (userPickRelays.isNotEmpty()) {
                    if (visibleTopGroup.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    Spacer(Modifier.height(8.dp))

                    Text(
                        if (isFromPublishedList) "from your published list" else "your picks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
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
                                        isOverlapWarning = url.trim().removeSuffix("/").lowercase() in outboxUrls,
                                        onClick = { toggle(url) }
                                    )
                                } else {
                                    // URL not in NIP-66 data — show raw URL with lightweight indicator
                                    ManualRelayRow(
                                        url = url,
                                        isSelected = true,
                                        isOverlapWarning = url.trim().removeSuffix("/").lowercase() in outboxUrls,
                                        onClick = { toggle(url) }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Inline manual URL input ──
                if (showManualInput) {
                    if (visibleTopGroup.isNotEmpty() || userPickRelays.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "add relay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = manualUrl,
                            onValueChange = {
                                manualUrl = it
                                manualUrlError = null
                            },
                            placeholder = {
                                Text(
                                    "wss://relay.example.com",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            },
                            isError = manualUrlError != null,
                            supportingText = manualUrlError?.let { err -> { Text(err, fontSize = 10.sp) } },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addManualUrl() })
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { addManualUrl() },
                            enabled = manualUrl.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                        }
                    }

                    // Auto-focus the text field when it appears
                    LaunchedEffect(Unit) {
                        delay(200)
                        try {
                            focusRequester.requestFocus()
                        } catch (_: Exception) {
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
            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isFromPublishedList) "Confirm Indexers" else "Use Selected Indexers")
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    showManualInput = !showManualInput
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Relay", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { onBrowseAll(selectedUrls) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Explore, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Browse All", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(4.dp))

        TextButton(
            onClick = {
                // Clear all default selections and show manual input
                defaultsCleared = true
                val cleared = selectedUrls - topUrls
                selectedUrls = cleared
                onSelectionChanged(cleared)
                showManualInput = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "I'll add my own",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ManualRelayRow(
    url: String,
    isSelected: Boolean,
    isOverlapWarning: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
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
                text = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (isOverlapWarning) {
                Text(
                    text = "Outbox duplicate (redundant)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun IndexerRelayRow(
    relay: DiscoveredRelay,
    isSelected: Boolean,
    isOverlapWarning: Boolean = false,
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
            if (isOverlapWarning) {
                Text(
                    text = "Outbox duplicate (redundant)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 9.sp
                )
            } else if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Trust badge: observation count from monitors
        if (relay.monitorCount > 0) {
            Spacer(Modifier.width(6.dp))
            val trustColor = when {
                relay.monitorCount >= 10 -> Color(0xFF4CAF50)
                relay.monitorCount >= 3 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
            Text(
                "${relay.monitorCount}✕", style = MaterialTheme.typography.labelSmall,
                color = trustColor, fontSize = 10.sp
            )
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
                                    onRePing = if (done) {
                                        { Nip65RelayListRepository.rePingIndexer(state.url) }
                                    } else null
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
                                    onRePing = if (done) {
                                        { Nip65RelayListRepository.rePingIndexer(state.url) }
                                    } else null
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
                            Icon(
                                Icons.Outlined.Warning, null, Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
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
            val err = state.errorMessage
            statusLabel = when {
                err == null -> "failed"
                "502" in err -> "502"
                "401" in err || "403" in err -> "auth req"
                "402" in err -> "paid"
                "resolve host" in err.lowercase() || "DNS" in err.uppercase() -> "DNS fail"
                "refused" in err.lowercase() -> "refused"
                "CLEARTEXT" in err -> "no TLS"
                "closed" in err.lowercase() -> "closed"
                else -> err.take(20)
            }
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

        // ═══ EVENT VERSION GROUPS (only if multiple versions) ═══
        if (groupedByTimestamp.size > 1) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "${groupedByTimestamp.size} event versions found",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Indexers returned different versions of your relay list. " +
                                "Tap a version to review it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )

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
                                        Icon(
                                            Icons.Filled.RadioButtonChecked, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.RadioButtonUnchecked, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
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
                                            Text(
                                                "latest", style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4CAF50), fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (isOutlier) {
                                        Spacer(Modifier.width(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        ) {
                                            Text(
                                                "outlier", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error, fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                // Diff summary for non-latest groups
                                if (!isLatest && (added.isNotEmpty() || removed.isNotEmpty())) {
                                    Spacer(Modifier.height(4.dp))
                                    Row {
                                        if (removed.isNotEmpty()) {
                                            Text(
                                                "−${removed.size} missing",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 9.sp
                                            )
                                        }
                                        if (removed.isNotEmpty() && added.isNotEmpty()) {
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        if (added.isNotEmpty()) {
                                            Text(
                                                "+${added.size} extra",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF4CAF50),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }

                                // Per-indexer rows with update status indicators
                                results.forEach { r ->
                                    val urlShort = r.indexerUrl.removePrefix("wss://").removeSuffix("/")
                                    val status = updateResults[r.indexerUrl]
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (status) {
                                            true -> Icon(
                                                Icons.Filled.CheckCircle, null, Modifier.size(10.dp),
                                                tint = Color(0xFF4CAF50)
                                            )

                                            false -> Icon(
                                                Icons.Filled.Error, null, Modifier.size(10.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )

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
                    Icon(
                        Icons.Outlined.Hub, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${selectedRelays.size} relay${if (selectedRelays.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedResult.writeRelays.isNotEmpty()) {
                        RelayBadge(
                            "${selectedResult.writeRelays.size}",
                            Icons.Outlined.Upload,
                            MaterialTheme.colorScheme.primary
                        )
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
                                    if (nip66.paymentRequired) add("💳 Paid")
                                    if (nip66.authRequired) add("🔒 Auth")
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
                        val labelColor =
                            if (isWrite && isRead) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                        Text(
                            "Show all ${selectedRelays.size} relays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Source info
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))

                val dateStr = if (selectedResult.createdAt > 0)
                    dateFormat.format(Date(selectedResult.createdAt * 1000)) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        if (dateStr != null) {
                            Text(
                                "Published $dateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        val matchingIndexers = allResults.filter { it.createdAt == selectedTimestamp }
                        Text(
                            "${matchingIndexers.size} indexer${if (matchingIndexers.size != 1) "s" else ""} returned this version",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }

        // ═══ ACTION BUTTONS — at bottom after reviewing relay list ═══
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onConfirm(selectedResult) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Confirm Relay Choices")
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
                        Text(
                            "$updatedOk updated", style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50), fontSize = 10.sp
                        )
                    }
                    if (updatedOk > 0 && updatedFail > 0) Spacer(Modifier.width(12.dp))
                    if (updatedFail > 0) {
                        Icon(Icons.Filled.Error, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$updatedFail failed", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error, fontSize = 10.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
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
            Text(
                text, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = color, fontSize = 10.sp
            )
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

// ── Prefetching UI for Core Lists ──

@Composable
private fun PrefetchingListsUI(
    hexPubkey: String,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var isFetching by remember { mutableStateOf(true) }
    var currentTask by remember { mutableStateOf("Starting...") }

    LaunchedEffect(Unit) {
        val storageManager = RelayStorageManager(context)
        val allOutboxUrls = storageManager.loadOutboxRelays(hexPubkey).map { it.url }
        val allInboxUrls = storageManager.loadInboxRelays(hexPubkey).map { it.url }
        val allIndexerUrls = storageManager.loadIndexerRelays(hexPubkey).map { it.url }
        val categoryUrls = storageManager.loadCategories(hexPubkey).flatMap { it.relays }.map { it.url }
        val allUserRelayUrls = (allOutboxUrls + allInboxUrls + allIndexerUrls + categoryUrls).distinct()

        val signer = accountStateViewModel.getCurrentSigner()

        // ── Step 1: Reconnect to relays ──
        // saveRelayConfig called requestDisconnect() above. We now have the correct relay
        // URLs from storage — reconnect before any subscriptions will work.
        currentTask = "Connecting to relays\u2026"
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val stateMachine = social.mycelium.android.relay.RelayConnectionStateMachine.getInstance()
                stateMachine.requestConnect(allUserRelayUrls)
                // Poll for at least 1 connection, up to 4s
                var waitedMs = 0L
                while (waitedMs < 4_000L) {
                    delay(250L)
                    waitedMs += 250L
                    if (stateMachine.relayPool.getConnectedCount() >= 1) break
                }
                android.util.Log.d("PrefetchingListsUI", "Step 1 done: ${stateMachine.relayPool.getConnectedCount()} relays connected")
            } catch (e: Exception) {
                android.util.Log.e("PrefetchingListsUI", "Connection error: ${e.message}")
            }
        }

        // ── Step 2: Follows & Mutes ──
        // Fetch directly without StartupOrchestrator — the orchestrator will run
        // its own Phase 1 when the dashboard loads. Running it here too caused a
        // double Phase 1 (reset → run → reset → run) that re-triggered
        // fetchIndexerList and silently replaced the user's confirmed indexers.
        currentTask = "Fetching contacts & mutes\u2026"
        val followRelayUrls = (allIndexerUrls + allOutboxUrls).distinct()
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val followJob = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                    social.mycelium.android.repository.ContactListRepository.fetchFollowList(hexPubkey, followRelayUrls, forceRefresh = false)
                }
                val muteJob = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                    social.mycelium.android.repository.MuteListRepository.fetchMuteList(hexPubkey, allUserRelayUrls)
                }
                val deadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < deadline) {
                    if (followJob.isCompleted && muteJob.isCompleted) break
                    delay(100)
                }
            } catch (e: Exception) {
                android.util.Log.e("PrefetchingListsUI", "Follow/mute fetch error: ${e.message}")
            }
        }
        android.util.Log.d("PrefetchingListsUI", "Step 2 done: contacts & mutes")

        // ── Step 3: Relay Collections (kind-30002) ──
        currentTask = "Restoring relay collections\u2026"
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                social.mycelium.android.repository.RelayCategorySyncRepository.fetchRelaySets(
                    userPubkey = hexPubkey,
                    relayUrls = (allOutboxUrls + allInboxUrls + allIndexerUrls).distinct(),
                    context = context.applicationContext,
                )
                delay(5_000L)
                android.util.Log.d("PrefetchingListsUI", "Step 3 done: relay collections fetched")
            } catch (e: Exception) {
                android.util.Log.e("PrefetchingListsUI", "Relay sets fetch error: ${e.message}")
            }
        }

        // ── Step 4: Published Indexer List (kind-10086) ──
        // Use forceReplace=true here because the user will have JUST confirmed
        // their indexers in SELECT_INDEXERS. If a published 10086 exists, it should
        // be applied (the user can re-confirm on next startup via the diff banner).
        currentTask = "Restoring indexer list\u2026"
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                social.mycelium.android.repository.RelayCategorySyncRepository.fetchIndexerList(
                    userPubkey = hexPubkey,
                    relayUrls = (allOutboxUrls + allIndexerUrls).distinct(),
                    context = context.applicationContext,
                    forceReplace = true,
                )
                delay(3_000L)
                android.util.Log.d("PrefetchingListsUI", "Step 4 done: indexer list fetched")
            } catch (e: Exception) {
                android.util.Log.e("PrefetchingListsUI", "Indexer list fetch error: ${e.message}")
            }
        }

        // ── Step 5: Bookmarks ──
        currentTask = "Fetching bookmarks\u2026"
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                social.mycelium.android.repository.BookmarkRepository.fetchBookmarks(hexPubkey, allUserRelayUrls)
                delay(2_000L)
                android.util.Log.d("PrefetchingListsUI", "Step 5 done: bookmarks fetched")
            } catch (e: Exception) {
                android.util.Log.e("PrefetchingListsUI", "Bookmarks fetch error: ${e.message}")
            }
        }

        isFetching = false
        onComplete()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = currentTask,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This only happens once during setup.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NOTIFICATION_SETUP Phase
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Interactive walkthrough for notification permissions and power settings.
 * Guides the user through:
 * 1. POST_NOTIFICATIONS permission (API 33+)
 * 2. Battery optimization exemption
 * 3. Connection mode selection
 *
 * Each step shows its current status and provides a one-tap action to fix it.
 */
@Composable
private fun NotificationSetupUI(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val connectionMode by NotificationPreferences.connectionMode.collectAsState()

    var hasNotifPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var isBatteryUnrestricted by remember {
        mutableStateOf(
            try {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            } catch (_: Exception) { false }
        )
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasNotifPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true
                isBatteryUnrestricted = try {
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                    pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                } catch (_: Exception) { false }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Mycelium needs a few permissions to deliver notifications reliably. " +
                    "You can always change these later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        // ── Step 1: Notification Permission ──
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            NotificationSetupStep(
                icon = Icons.Outlined.Notifications,
                title = "Notification permission",
                description = if (hasNotifPermission) "Granted" else "Required for push notifications",
                isComplete = hasNotifPermission,
                actionLabel = if (!hasNotifPermission) "Enable" else null,
                onAction = {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Step 2: Battery Optimization ──
        NotificationSetupStep(
            icon = Icons.Outlined.BatteryChargingFull,
            title = "Battery optimization",
            description = if (isBatteryUnrestricted) "Unrestricted"
            else "Disable to prevent Android from killing background connections",
            isComplete = isBatteryUnrestricted,
            actionLabel = if (!isBatteryUnrestricted) "Unrestrict" else null,
            onAction = {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = android.net.Uri.parse("package:" + context.packageName)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        val fallback = android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        )
                        if (fallback.resolveActivity(context.packageManager) != null) {
                            context.startActivity(fallback)
                        }
                    }
                } catch (_: Exception) { }
            }
        )

        Spacer(Modifier.height(16.dp))

        // ── Step 3: Connection Mode ──
        Text(
            text = "Background connectivity",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Text(
            text = "Choose how Mycelium stays connected when the app is in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(8.dp))

        OnboardingConnectionModeOption(
            title = "Adaptive",
            description = "Periodic inbox checks. Good battery life.",
            selected = connectionMode == ConnectionMode.ADAPTIVE,
            onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.ADAPTIVE) },
            recommended = true
        )
        OnboardingConnectionModeOption(
            title = "Always On",
            description = "Real-time notifications. Higher battery usage.",
            selected = connectionMode == ConnectionMode.ALWAYS_ON,
            onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.ALWAYS_ON) }
        )
        OnboardingConnectionModeOption(
            title = "When Active",
            description = "No background notifications. Best battery.",
            selected = connectionMode == ConnectionMode.WHEN_ACTIVE,
            onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.WHEN_ACTIVE) }
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Continue")
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onComplete) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun NotificationSetupStep(
    icon: ImageVector,
    title: String,
    description: String,
    isComplete: Boolean,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isComplete) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isComplete) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (isComplete) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actionLabel != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onAction)
                ) {
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingConnectionModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
