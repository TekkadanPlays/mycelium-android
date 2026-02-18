package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import social.mycelium.android.data.UserRelay
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.relay.RelayEndpointStatus
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import social.mycelium.android.viewmodel.RelayManagementViewModel
import social.mycelium.android.viewmodel.AccountStateViewModel
import kotlinx.coroutines.launch

// ── Helpers ──

private fun normalizeRelayUrl(url: String): String {
    val trimmed = url.trim().removeSuffix("/")
    return when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> trimmed.replace("https://", "wss://")
        trimmed.startsWith("http://") -> trimmed.replace("http://", "ws://")
        else -> "wss://$trimmed"
    }
}

private fun isDuplicateRelay(url: String, existingRelays: List<UserRelay>): Boolean {
    val normalizedUrl = normalizeRelayUrl(url)
    return existingRelays.any { normalizeRelayUrl(it.url) == normalizedUrl }
}

private fun createRelayWithNip11Info(
    url: String,
    read: Boolean = true,
    write: Boolean = true,
    nip11Cache: social.mycelium.android.cache.Nip11CacheManager
): UserRelay {
    val normalizedUrl = normalizeRelayUrl(url)
    val cachedInfo = nip11Cache.getCachedRelayInfo(normalizedUrl)
    return UserRelay(
        url = normalizedUrl, read = read, write = write,
        addedAt = System.currentTimeMillis(), info = cachedInfo,
        isOnline = false, lastChecked = System.currentTimeMillis()
    )
}

private enum class RelayTab(val label: String) {
    OUTBOX("Outbox"),
    INBOX("Inbox"),
    INDEXER("Indexer")
}

// ── Main Screen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManagementScreen(
    onBackClick: () -> Unit,
    relayRepository: RelayRepository,
    accountStateViewModel: AccountStateViewModel,
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    initialTab: String = "",
    prefillIndexerUrls: List<String> = emptyList(),
    prefillOutboxUrls: List<String> = emptyList(),
    prefillInboxUrls: List<String> = emptyList(),
    onOpenRelayLog: (String) -> Unit = {},
    onOpenRelayHealth: () -> Unit = {},
    onOpenRelayDiscovery: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nip11Cache = relayRepository.getNip11Cache()
    val storageManager = remember { RelayStorageManager(context) }
    val scope = rememberCoroutineScope()

    val viewModel: RelayManagementViewModel = viewModel {
        RelayManagementViewModel(relayRepository, storageManager)
    }

    val uiState by viewModel.uiState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { viewModel.loadUserRelays(it) }
    }

    // Prefill indexer relays when navigating with pre-selected URLs
    LaunchedEffect(prefillIndexerUrls, currentAccount) {
        if (prefillIndexerUrls.isNotEmpty() && currentAccount != null) {
            kotlinx.coroutines.delay(300) // Wait for relays to load
            val existing = viewModel.uiState.value.indexerRelays.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
            prefillIndexerUrls.forEach { url ->
                val normalized = normalizeRelayUrl(url)
                val key = normalized.trim().removeSuffix("/").lowercase()
                if (key !in existing) {
                    viewModel.addIndexerRelay(createRelayWithNip11Info(normalized, nip11Cache = nip11Cache))
                }
            }
        }
    }

    // Prefill outbox/inbox relays from NIP-65 event (onboarding flow)
    LaunchedEffect(prefillOutboxUrls, prefillInboxUrls, currentAccount) {
        if ((prefillOutboxUrls.isNotEmpty() || prefillInboxUrls.isNotEmpty()) && currentAccount != null) {
            kotlinx.coroutines.delay(300) // Wait for relays to load
            if (prefillOutboxUrls.isNotEmpty()) {
                val existingOutbox = viewModel.uiState.value.outboxRelays.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
                prefillOutboxUrls.forEach { url ->
                    val normalized = normalizeRelayUrl(url)
                    val key = normalized.trim().removeSuffix("/").lowercase()
                    if (key !in existingOutbox) {
                        viewModel.addOutboxRelay(createRelayWithNip11Info(normalized, nip11Cache = nip11Cache))
                    }
                }
            }
            if (prefillInboxUrls.isNotEmpty()) {
                val existingInbox = viewModel.uiState.value.inboxRelays.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
                prefillInboxUrls.forEach { url ->
                    val normalized = normalizeRelayUrl(url)
                    val key = normalized.trim().removeSuffix("/").lowercase()
                    if (key !in existingInbox) {
                        viewModel.addInboxRelay(createRelayWithNip11Info(normalized, nip11Cache = nip11Cache))
                    }
                }
            }
        }
    }

    // Relay state
    val relayCategories by viewModel.relayCategories.collectAsState()
    val relayProfiles by viewModel.relayProfiles.collectAsState()
    val outboxRelays by remember { derivedStateOf { uiState.outboxRelays } }
    val inboxRelays by remember { derivedStateOf { uiState.inboxRelays } }
    val indexerRelays by remember { derivedStateOf { uiState.indexerRelays } }

    // Live connection state
    val perRelayState by social.mycelium.android.relay.RelayConnectionStateMachine.getInstance()
        .perRelayState.collectAsState()

    // Trouble relay count for health icon badge
    val flaggedRelays by social.mycelium.android.relay.RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by social.mycelium.android.relay.RelayHealthTracker.blockedRelays.collectAsState()
    val troubleCount = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays + blockedRelays).distinct().size
    }

    // Input state per tab
    var relayUrlInput by remember { mutableStateOf("") }
    var showInputByTab by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var fabExpanded by remember { mutableStateOf(false) }

    // Category state
    var editingCategoryId by remember { mutableStateOf<String?>(null) }
    var editingCategoryName by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Profile dialog state
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editProfileTarget by remember { mutableStateOf<RelayProfile?>(null) }
    var editProfileName by remember { mutableStateOf("") }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var createProfileName by remember { mutableStateOf("") }
    var createProfileId by remember { mutableStateOf<String?>(null) }
    var createProfilePreviousPage by remember { mutableIntStateOf(0) }
    var showDeleteProfileConfirmation by remember { mutableStateOf(false) }

    // Edit Category dialog state
    var showEditCategoryDialog by remember { mutableStateOf(false) }
    var editCategoryTarget by remember { mutableStateOf<RelayCategory?>(null) }
    var editCategoryProfileId by remember { mutableStateOf<String?>(null) }
    var editCategoryName by remember { mutableStateOf("") }
    var showDeleteCategoryConfirmation by remember { mutableStateOf(false) }

    // Edit Relay dialog state
    var showEditRelayDialog by remember { mutableStateOf(false) }
    var editRelayTarget by remember { mutableStateOf<UserRelay?>(null) }
    var editRelayCategoryId by remember { mutableStateOf<String?>(null) }
    var editRelayProfileId by remember { mutableStateOf<String?>(null) }
    var editRelayUrl by remember { mutableStateOf("") }
    var showDeleteRelayConfirmation by remember { mutableStateOf(false) }

    // General dialog state
    val snackbarHostState = remember { SnackbarHostState() }
    var showToast by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf("") }
    var showPublishConfirmation by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // NIP-65 source info
    val nip65Source by social.mycelium.android.repository.Nip65RelayListRepository.sourceRelayUrl.collectAsState()
    val nip65CreatedAt by social.mycelium.android.repository.Nip65RelayListRepository.eventCreatedAt.collectAsState()

    // Tab + pager state — fixed tabs + dynamic profile tabs
    val fixedTabs = RelayTab.entries
    // During onboarding, only show Indexer tab, so count is 1
    val totalPageCount by remember(relayProfiles) {
        derivedStateOf {
            fixedTabs.size + relayProfiles.size
        }
    }
    val initialPage = remember(initialTab) {
        when (initialTab.lowercase()) {
            "outbox" -> RelayTab.OUTBOX.ordinal
            "inbox" -> RelayTab.INBOX.ordinal
            "indexer" -> RelayTab.INDEXER.ordinal
            else -> 0
        }
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { totalPageCount }
    val isProfileTab by remember { derivedStateOf {
        val idx = pagerState.currentPage - fixedTabs.size
        idx >= 0 && idx < relayProfiles.size
    } }
    val currentFixedTab by remember { derivedStateOf {
        if (pagerState.currentPage < fixedTabs.size) fixedTabs[pagerState.currentPage] else null
    } }
    val currentProfile by remember { derivedStateOf {
        val idx = pagerState.currentPage - fixedTabs.size
        if (idx >= 0 && idx < relayProfiles.size) relayProfiles[idx] else null
    } }
    val pagerScope = rememberCoroutineScope()

    // Reset input when switching pages
    LaunchedEffect(pagerState.currentPage) { 
        showInputByTab = showInputByTab - pagerState.currentPage
        relayUrlInput = ""; 
        fabExpanded = false 
    }

    fun addRelayTo(
        url: String, existing: List<UserRelay>,
        onAdd: (UserRelay) -> Unit
    ) {
        if (url.isBlank()) return
        val normalized = normalizeRelayUrl(url)
        if (isDuplicateRelay(normalized, existing)) {
            toastMessage = "$normalized already exists"; showToast = true
        } else {
            onAdd(createRelayWithNip11Info(normalized, nip11Cache = nip11Cache))
            relayUrlInput = ""; 
            showInputByTab = showInputByTab - pagerState.currentPage
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val fabVisible by remember(topAppBarState) {
                derivedStateOf { topAppBarState.collapsedFraction < 0.5f }
            }
            AnimatedVisibility(
                visible = fabVisible,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 80.dp)
                ) {
                    // Secondary: Update Indexers (Outbox/Inbox tabs only)
                    AnimatedVisibility(
                        visible = fabExpanded && (currentFixedTab == RelayTab.OUTBOX || currentFixedTab == RelayTab.INBOX),
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 }
                    ) {
                        FabMenuItem(label = "Publish Relay List", icon = Icons.Outlined.Publish) {
                            fabExpanded = false; showPublishConfirmation = true
                        }
                    }

                    // Secondary: Create New Profile (always available)
                    AnimatedVisibility(
                        visible = fabExpanded,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 }
                    ) {
                        FabMenuItem(label = "Create New Profile", icon = Icons.Outlined.AddCircleOutline) {
                            fabExpanded = false
                            val defaultName = "Profile ${relayProfiles.size + 1}"
                            val newProfile = RelayProfile(name = defaultName)
                            createProfilePreviousPage = pagerState.currentPage
                            createProfileId = newProfile.id
                            createProfileName = defaultName
                            viewModel.addProfile(newProfile)
                            val newIdx = fixedTabs.size + relayProfiles.size // points to newly added
                            pagerScope.launch { pagerState.animateScrollToPage(newIdx) }
                            showCreateProfileDialog = true
                        }
                    }

                    // Secondary: Edit Profile (profile tabs only)
                    AnimatedVisibility(
                        visible = fabExpanded && isProfileTab && currentProfile != null,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 }
                    ) {
                        FabMenuItem(label = "Edit Profile", icon = Icons.Outlined.Edit) {
                            fabExpanded = false
                            editProfileTarget = currentProfile
                            editProfileName = currentProfile?.name ?: ""
                            showEditProfileDialog = true
                        }
                    }

                    // Primary FAB
                    FloatingActionButton(
                        onClick = {
                            if (isProfileTab) {
                                fabExpanded = !fabExpanded
                            } else {
                                val currentTabInput = showInputByTab[pagerState.currentPage] ?: false
                                if (currentTabInput) {
                                    fabExpanded = !fabExpanded
                                } else {
                                    showInputByTab = showInputByTab + (pagerState.currentPage to true)
                                    fabExpanded = false
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            if (fabExpanded) Icons.Default.Close
                            else if (showInputByTab[pagerState.currentPage] == true) Icons.Default.MoreVert
                            else Icons.Default.Add,
                            contentDescription = if (fabExpanded) "Close menu"
                                else if (showInputByTab[pagerState.currentPage] == true) "More options"
                                else "Add"
                        )
                    }
                }
            }
        },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            "relays",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenRelayDiscovery) {
                            Icon(Icons.Outlined.TravelExplore, "Discover Relays")
                        }
                        // Health icon with trouble badge
                        IconButton(onClick = onOpenRelayHealth) {
                            BadgedBox(
                                badge = {
                                    if (troubleCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ) { Text("$troubleCount") }
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.HealthAndSafety, "Relay Health")
                            }
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Tab row — fixed tabs + dynamic profile tabs
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                ) {
                    // Fixed tabs
                    fixedTabs.forEachIndexed { index, tab ->
                        val count = when (tab) {
                            RelayTab.OUTBOX -> outboxRelays.size
                            RelayTab.INBOX -> inboxRelays.size
                            RelayTab.INDEXER -> indexerRelays.size
                        }
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(tab.label, style = MaterialTheme.typography.labelMedium)
                                    if (count > 0) {
                                        Text(
                                            "$count",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                    }
                    // Dynamic profile tabs
                    relayProfiles.forEachIndexed { profileIdx, profile ->
                        val pageIndex = fixedTabs.size + profileIdx
                        val isSelected = pagerState.currentPage == pageIndex
                        Tab(
                            selected = isSelected,
                            onClick = { pagerScope.launch { pagerState.animateScrollToPage(pageIndex) } },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(profile.name, style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (profile.isActive) {
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            Modifier
                                                .width(24.dp)
                                                .height(2.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier.fillMaxSize().padding(paddingValues)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                if (page < fixedTabs.size) {
                    when (fixedTabs[page]) {
                        RelayTab.OUTBOX -> RelayListTab(
                            relays = outboxRelays, perRelayState = perRelayState,
                            showInput = showInputByTab[page] == true,
                            relayUrlInput = relayUrlInput,
                            onRelayUrlChange = { relayUrlInput = it },
                            onAddRelay = { addRelayTo(relayUrlInput, outboxRelays) { viewModel.addOutboxRelay(it) } },
                            onEditRelay = { url ->
                                val relay = outboxRelays.find { it.url == url }
                                if (relay != null) {
                                    editRelayTarget = relay; editRelayCategoryId = "outbox"; editRelayProfileId = null
                                    editRelayUrl = relay.url; showEditRelayDialog = true
                                }
                            },
                            onOpenRelayLog = onOpenRelayLog, nip65Source = nip65Source, nip65CreatedAt = nip65CreatedAt,
                            emptyMessage = "Where you write to.\nThis should be found via indexer relays — but you can add some now if you'd like.",
                            modifier = Modifier.fillMaxSize())
                        RelayTab.INBOX -> RelayListTab(
                            relays = inboxRelays, perRelayState = perRelayState,
                            showInput = showInputByTab[page] == true,
                            relayUrlInput = relayUrlInput,
                            onRelayUrlChange = { relayUrlInput = it },
                            onAddRelay = { addRelayTo(relayUrlInput, inboxRelays) { viewModel.addInboxRelay(it) } },
                            onEditRelay = { url ->
                                val relay = inboxRelays.find { it.url == url }
                                if (relay != null) {
                                    editRelayTarget = relay; editRelayCategoryId = "inbox"; editRelayProfileId = null
                                    editRelayUrl = relay.url; showEditRelayDialog = true
                                }
                            },
                            onOpenRelayLog = onOpenRelayLog, nip65Source = nip65Source, nip65CreatedAt = nip65CreatedAt,
                            emptyMessage = "Where you receive replies.\nThis should be found via indexer relays — but you can add some now if you'd like.",
                            modifier = Modifier.fillMaxSize())
                        RelayTab.INDEXER -> RelayListTab(
                            relays = indexerRelays, perRelayState = perRelayState,
                            showInput = showInputByTab[page] == true,
                            relayUrlInput = relayUrlInput,
                            onRelayUrlChange = { relayUrlInput = it },
                            onAddRelay = { addRelayTo(relayUrlInput, indexerRelays) { viewModel.addIndexerRelay(it) } },
                            onEditRelay = { url ->
                                val relay = indexerRelays.find { it.url == url }
                                if (relay != null) {
                                    editRelayTarget = relay; editRelayCategoryId = "indexer"; editRelayProfileId = null
                                    editRelayUrl = relay.url; showEditRelayDialog = true
                                }
                            },
                            onOpenRelayLog = onOpenRelayLog, nip65Source = null, nip65CreatedAt = null,
                            emptyMessage = "Indexer relays for profile lookups and search.\nThese are discovered via NIP-66 during sign-in.",
                            modifier = Modifier.fillMaxSize())
                    }
                } else {
                    val profileIdx = page - fixedTabs.size
                    val profile = relayProfiles.getOrNull(profileIdx)
                    if (profile != null) {
                        CategoriesTab(
                            profileName = profile.name,
                            profileId = profile.id,
                            categories = profile.categories,
                            perRelayState = perRelayState,
                            categoryExpanded = categoryExpanded,
                            onCategoryExpandedChange = { categoryExpanded = it },
                            onAddCategory = { viewModel.addCategoryToProfile(profile.id, RelayCategory(name = "New Category")) },
                            onAddRelay = { catId, url ->
                                val normalized = normalizeRelayUrl(url)
                                val cat = profile.categories.find { it.id == catId }
                                if (cat != null && isDuplicateRelay(normalized, cat.relays)) {
                                    toastMessage = "$normalized already in ${cat.name}"; showToast = true
                                } else {
                                    viewModel.addRelayToProfileCategory(profile.id, catId, createRelayWithNip11Info(normalized, nip11Cache = nip11Cache))
                                }
                            },
                            onEditCategory = { cat ->
                                editCategoryTarget = cat
                                editCategoryProfileId = profile.id
                                editCategoryName = cat.name
                                showEditCategoryDialog = true
                            },
                            onEditRelay = { catId, relay ->
                                editRelayTarget = relay
                                editRelayCategoryId = catId
                                editRelayProfileId = profile.id
                                editRelayUrl = relay.url
                                showEditRelayDialog = true
                            },
                            onOpenRelayLog = onOpenRelayLog,
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            // Dismiss scrim — overlays pager content when FAB menu is expanded
            if (fabExpanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { fabExpanded = false }
                )
            }
        }
    }

    // ── Dialogs ──

    // Edit Category dialog — name, active toggle, delete
    if (showEditCategoryDialog && editCategoryTarget != null) {
        val category = editCategoryTarget!!
        AlertDialog(
            onDismissRequest = { showEditCategoryDialog = false; editCategoryTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Outlined.Folder, null, Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Category", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editCategoryName,
                        onValueChange = { editCategoryName = it },
                        singleLine = true,
                        label = { Text("Category name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Active", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    "Subscribe to relays in this category",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = category.isSubscribed,
                                onCheckedChange = { checked ->
                                    editCategoryTarget = category.copy(isSubscribed = checked)
                                    val profId = editCategoryProfileId
                                    if (profId != null) {
                                        val profile = relayProfiles.find { it.id == profId }
                                        if (profile != null) {
                                            viewModel.updateProfile(profId, profile.copy(
                                                categories = profile.categories.map {
                                                    if (it.id == category.id) it.copy(isSubscribed = checked) else it
                                                }
                                            ))
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().clickable {
                            showEditCategoryDialog = false
                            showDeleteCategoryConfirmation = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.Delete, "Delete category",
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Category",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editCategoryName.isNotBlank()) {
                            val profId = editCategoryProfileId
                            if (profId != null) {
                                val profile = relayProfiles.find { it.id == profId }
                                if (profile != null) {
                                    viewModel.updateProfile(profId, profile.copy(
                                        categories = profile.categories.map {
                                            if (it.id == category.id) it.copy(name = editCategoryName) else it
                                        }
                                    ))
                                }
                            }
                            showEditCategoryDialog = false; editCategoryTarget = null
                        }
                    },
                    enabled = editCategoryName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditCategoryDialog = false; editCategoryTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Category confirmation
    if (showDeleteCategoryConfirmation && editCategoryTarget != null) {
        ThemedConfirmDialog(
            icon = Icons.Outlined.Delete,
            title = "Delete Category?",
            message = "Delete \"${editCategoryTarget?.name}\"?" +
                if (editCategoryTarget?.relays?.isNotEmpty() == true)
                    "\nThis category contains ${editCategoryTarget?.relays?.size} relay(s)." else "",
            confirmText = "Delete",
            isDestructive = true,
            onConfirm = {
                val profId = editCategoryProfileId
                if (profId != null) {
                    editCategoryTarget?.let { viewModel.removeCategoryFromProfile(profId, it.id) }
                } else {
                    editCategoryTarget?.let { viewModel.deleteCategory(it.id) }
                }
                showDeleteCategoryConfirmation = false; editCategoryTarget = null; editCategoryProfileId = null
            },
            onDismiss = { showDeleteCategoryConfirmation = false }
        )
    }

    // Edit Profile dialog — name, active toggle, delete
    if (showEditProfileDialog && editProfileTarget != null) {
        val profile = editProfileTarget!!
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false; editProfileTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Outlined.Edit, null, Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Profile", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editProfileName,
                        onValueChange = { editProfileName = it },
                        singleLine = true,
                        label = { Text("Profile name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Active", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    "Use this profile for your feed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = profile.isActive,
                                onCheckedChange = { checked ->
                                    editProfileTarget = profile.copy(isActive = checked)
                                    if (checked) viewModel.activateProfile(profile.id)
                                    else viewModel.updateProfile(profile.id, profile.copy(isActive = false))
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().clickable {
                            showEditProfileDialog = false
                            showDeleteProfileConfirmation = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.Delete, "Delete profile",
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Profile",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editProfileName.isNotBlank()) {
                            viewModel.updateProfile(profile.id, profile.copy(name = editProfileName))
                            showEditProfileDialog = false; editProfileTarget = null
                        }
                    },
                    enabled = editProfileName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditProfileDialog = false; editProfileTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Profile confirmation
    if (showDeleteProfileConfirmation && editProfileTarget != null) {
        ThemedConfirmDialog(
            icon = Icons.Outlined.Delete,
            title = "Delete Profile?",
            message = run {
                val profile = editProfileTarget
                val catCount = profile?.categories?.size ?: 0
                val relayCount = profile?.categories?.sumOf { it.relays.size } ?: 0
                buildString {
                    append("Delete \"${profile?.name}\"?")
                    if (catCount > 0 || relayCount > 0) {
                        append("\n\nThis will permanently remove ")
                        val parts = mutableListOf<String>()
                        if (catCount > 0) parts += "$catCount category(ies)"
                        if (relayCount > 0) parts += "$relayCount relay(s)"
                        append(parts.joinToString(" and "))
                        append(" stored in this profile.")
                    }
                }
            },
            confirmText = "Delete",
            isDestructive = true,
            onConfirm = {
                editProfileTarget?.let {
                    viewModel.deleteProfile(it.id)
                    pagerScope.launch { pagerState.animateScrollToPage(0) }
                }
                showDeleteProfileConfirmation = false; editProfileTarget = null
            },
            onDismiss = { showDeleteProfileConfirmation = false }
        )
    }

    // Create New Profile dialog — profile already created, user is on its tab
    if (showCreateProfileDialog && createProfileId != null) {
        val cancelCreate = {
            createProfileId?.let { viewModel.deleteProfile(it) }
            showCreateProfileDialog = false
            createProfileId = null
            pagerScope.launch { pagerState.animateScrollToPage(createProfilePreviousPage) }
        }
        AlertDialog(
            onDismissRequest = { cancelCreate() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Name Your Profile", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = createProfileName,
                    onValueChange = { createProfileName = it },
                    singleLine = true,
                    label = { Text("Profile name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (createProfileName.isNotBlank()) {
                            createProfileId?.let { viewModel.updateProfile(it, relayProfiles.first { p -> p.id == it }.copy(name = createProfileName)) }
                            showCreateProfileDialog = false; createProfileId = null
                        }
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (createProfileName.isNotBlank()) {
                            createProfileId?.let { viewModel.updateProfile(it, relayProfiles.first { p -> p.id == it }.copy(name = createProfileName)) }
                            showCreateProfileDialog = false; createProfileId = null
                        }
                    },
                    enabled = createProfileName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { cancelCreate() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit Relay dialog — URL, read/write toggle, delete
    if (showEditRelayDialog && editRelayTarget != null) {
        val relay = editRelayTarget!!
        AlertDialog(
            onDismissRequest = { showEditRelayDialog = false; editRelayTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Outlined.Router, null, Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Relay", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editRelayUrl,
                        onValueChange = { editRelayUrl = it },
                        singleLine = true,
                        label = { Text("Relay URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Outlined.Link, null) }
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Active", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    "Include this relay for reading",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = relay.read,
                                onCheckedChange = { checked ->
                                    editRelayTarget = relay.copy(read = checked)
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().clickable {
                            showEditRelayDialog = false
                            showDeleteRelayConfirmation = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.Delete, "Delete relay",
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove Relay",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editRelayUrl.isNotBlank()) {
                            val newNorm = normalizeRelayUrl(editRelayUrl)
                            val oldNorm = normalizeRelayUrl(relay.url)
                            val profId = editRelayProfileId
                            val catId = editRelayCategoryId
                            if (profId != null && catId != null) {
                                // Profile category relay
                                viewModel.removeRelayFromProfileCategory(profId, catId, oldNorm)
                                viewModel.addRelayToProfileCategory(profId, catId,
                                    createRelayWithNip11Info(newNorm, read = editRelayTarget!!.read, write = editRelayTarget!!.write, nip11Cache = nip11Cache))
                            } else {
                                // Fixed tab relay (outbox/inbox/indexer)
                                val newRelay = createRelayWithNip11Info(newNorm, read = editRelayTarget!!.read, write = editRelayTarget!!.write, nip11Cache = nip11Cache)
                                val section = catId ?: ""
                                when (section) {
                                    "outbox" -> { viewModel.removeOutboxRelay(oldNorm); viewModel.addOutboxRelay(newRelay) }
                                    "inbox" -> { viewModel.removeInboxRelay(oldNorm); viewModel.addInboxRelay(newRelay) }
                                    "indexer" -> { viewModel.removeIndexerRelay(oldNorm); viewModel.addIndexerRelay(newRelay) }
                                }
                            }
                            showEditRelayDialog = false; editRelayTarget = null
                        }
                    },
                    enabled = editRelayUrl.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEditRelayDialog = false; editRelayTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Relay confirmation
    if (showDeleteRelayConfirmation && editRelayTarget != null) {
        ThemedConfirmDialog(
            icon = Icons.Outlined.Delete,
            title = "Remove Relay?",
            message = "Remove ${editRelayTarget?.displayName}?\n${editRelayTarget?.url}",
            confirmText = "Remove",
            isDestructive = true,
            onConfirm = {
                val oldUrl = editRelayTarget?.url ?: ""
                val profId = editRelayProfileId
                val catId = editRelayCategoryId
                if (profId != null && catId != null) {
                    viewModel.removeRelayFromProfileCategory(profId, catId, oldUrl)
                } else {
                    when (catId) {
                        "outbox" -> viewModel.removeOutboxRelay(oldUrl)
                        "inbox" -> viewModel.removeInboxRelay(oldUrl)
                        "indexer" -> viewModel.removeIndexerRelay(oldUrl)
                    }
                }
                showDeleteRelayConfirmation = false; editRelayTarget = null
            },
            onDismiss = { showDeleteRelayConfirmation = false }
        )
    }

    if (showPublishConfirmation) {
        PublishNip65Dialog(
            outboxRelays = outboxRelays,
            inboxRelays = inboxRelays,
            onConfirm = {
                scope.launch {
                    val signer = accountStateViewModel.getCurrentSigner()
                    if (signer != null) {
                        social.mycelium.android.repository.Nip65RelayListRepository.publishNip65(
                            context = context,
                            outboxUrls = outboxRelays.map { it.url },
                            inboxUrls = inboxRelays.map { it.url },
                            signer = signer
                        )
                        snackbarHostState.showSnackbar("Relay list published")
                    } else {
                        snackbarHostState.showSnackbar("No signer available")
                    }
                }
                showPublishConfirmation = false
            },
            onDismiss = { showPublishConfirmation = false }
        )
    }

    LaunchedEffect(showToast) {
        if (showToast) { snackbarHostState.showSnackbar(toastMessage); showToast = false }
    }
}

//  FAB Menu Item 

@Composable
private fun FabMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(icon, label)
        }
    }
}

//  Relay List Tab (Outbox / Inbox / Indexer) 

@Composable
private fun RelayListTab(
    relays: List<UserRelay>,
    perRelayState: Map<String, RelayEndpointStatus>,
    showInput: Boolean,
    relayUrlInput: String,
    onRelayUrlChange: (String) -> Unit,
    onAddRelay: () -> Unit,
    onEditRelay: (String) -> Unit,
    onOpenRelayLog: (String) -> Unit,
    nip65Source: String?,
    nip65CreatedAt: Long?,
    emptyMessage: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (nip65Source != null && relays.isNotEmpty()) {
            item(key = "source_banner") { Nip65SourceBanner(nip65Source, nip65CreatedAt) }
        }
        if (showInput) {
            item(key = "input") { RelayUrlInput(value = relayUrlInput, onValueChange = onRelayUrlChange, onAdd = onAddRelay) }
        }
        if (relays.isEmpty() && !showInput) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        relays.forEachIndexed { idx, relay ->
            item(key = "relay_${relay.url}") {
                RelayItem(relay = relay, connectionStatus = perRelayState[relay.url],
                    onOpenRelayLog = onOpenRelayLog,
                    onEdit = { onEditRelay(relay.url) },
                    showDivider = idx < relays.size - 1)
            }
        }
    }
}
//  Categories Tab 

@Composable
private fun CategoriesTab(
    profileName: String,
    profileId: String,
    categories: List<RelayCategory>,
    perRelayState: Map<String, RelayEndpointStatus>,
    categoryExpanded: Map<String, Boolean>,
    onCategoryExpandedChange: (Map<String, Boolean>) -> Unit,
    onAddCategory: () -> Unit,
    onAddRelay: (String, String) -> Unit,
    onEditCategory: (RelayCategory) -> Unit,
    onEditRelay: (String, UserRelay) -> Unit,
    onOpenRelayLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalRelays = categories.sumOf { it.relays.size }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        // Summary row
        item(key = "cat_summary") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("${categories.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("categories", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("$totalRelays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("relays", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    }
                }
                FilledTonalIconButton(
                    onClick = onAddCategory,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, "Add category", Modifier.size(20.dp))
                }
            }
        }
        if (categories.isEmpty()) {
            item(key = "cat_empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        Text("No categories in $profileName",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap the folder icon to create one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        categories.forEach { category ->
            item(key = "cat_${category.id}") {
                FeedCategorySection(
                    category = category,
                    isExpanded = categoryExpanded[category.id] ?: false,
                    onExpandToggle = {
                        onCategoryExpandedChange(categoryExpanded + (category.id to !(categoryExpanded[category.id] ?: false)))
                    },
                    onEditCategory = { onEditCategory(category) },
                    onAddRelay = { url -> onAddRelay(category.id, url) },
                    onEditRelay = { relay -> onEditRelay(category.id, relay) },
                    onOpenRelayLog = onOpenRelayLog,
                    perRelayState = perRelayState
                )
            }
        }
    }
}

//  Relay Item 

@Composable
private fun RelayItem(
    relay: UserRelay,
    connectionStatus: RelayEndpointStatus?,
    onOpenRelayLog: (String) -> Unit,
    onEdit: () -> Unit,
    showDivider: Boolean = true
) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onOpenRelayLog(relay.url) }
        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val dotColor = when (connectionStatus) {
            RelayEndpointStatus.Connected -> MaterialTheme.colorScheme.primary
            RelayEndpointStatus.Connecting -> MaterialTheme.colorScheme.tertiary
            RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(12.dp))
        if (relay.profileImage != null) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(relay.profileImage).crossfade(true).build(),
                contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape),
                contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.Router, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(relay.displayName, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relay.url, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Edit, "Edit relay", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            modifier = Modifier.padding(start = 56.dp))
    }
}

//  Add Relay Row (styled like a relay item) 

@Composable
private fun AddRelayRow(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onAdd: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    if (isEditing) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.Router, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f)
            ) {
                BasicTextField(
                    value = relayUrl,
                    onValueChange = onRelayUrlChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (relayUrl.isNotBlank()) {
                            onAdd(relayUrl)
                            onRelayUrlChange("")
                            keyboardController?.hide(); focusManager.clearFocus()
                            isEditing = false
                        }
                    }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box {
                            if (relayUrl.isEmpty()) {
                                Text("wss://relay.example.com",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                            innerTextField()
                        }
                    }
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { isEditing = false; onRelayUrlChange("") },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Close, "Cancel", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { isEditing = true }
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Text("Add relay",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        }
    }
}

//  Feed Category Section 

@Composable
private fun FeedCategorySection(
    category: RelayCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onEditCategory: () -> Unit,
    onAddRelay: (String) -> Unit,
    onEditRelay: (UserRelay) -> Unit,
    onOpenRelayLog: (String) -> Unit,
    perRelayState: Map<String, RelayEndpointStatus>
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = tween(200), label = "chevron"
    )
    var addRelayUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.animateContentSize()) {
        // ── Section header ──
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onExpandToggle
            ),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp),
                    tint = if (category.isSubscribed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    category.name, style = MaterialTheme.typography.labelMedium,
                    color = if (category.isSubscribed) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onEditCategory, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Edit, "Edit category", Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                if (category.relays.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${category.relays.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // ── Expanded content ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                // Relay list
                category.relays.forEachIndexed { idx, relay ->
                    RelayItem(relay = relay, connectionStatus = perRelayState[relay.url],
                        onOpenRelayLog = onOpenRelayLog,
                        onEdit = { onEditRelay(relay) },
                        showDivider = true)
                }
                // Add relay row — always at the bottom, styled like a relay
                AddRelayRow(
                    relayUrl = addRelayUrl,
                    onRelayUrlChange = { addRelayUrl = it },
                    onAdd = { url -> onAddRelay(url); addRelayUrl = "" }
                )
            }
        }
    }
}

//  Relay URL Input (used by fixed tabs) 

@Composable
private fun RelayUrlInput(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(value = value, onValueChange = onValueChange,
        placeholder = { Text("wss://relay.example.com") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (value.isNotBlank()) { onAdd(); keyboardController?.hide(); focusManager.clearFocus() }
        }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        leadingIcon = { Icon(Icons.Outlined.Router, null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add") }
            }
        })
}

//  NIP-65 Source Banner 

@Composable
private fun Nip65SourceBanner(source: String, createdAt: Long?) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Relay list from ${source.removePrefix("wss://").removeSuffix("/")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                if (createdAt != null && createdAt > 0) {
                    val dateStr = remember(createdAt) {
                        val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                        sdf.format(java.util.Date(createdAt * 1000))
                    }
                    Text("Published $dateStr", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

//  Themed Confirm Dialog 

@Composable
private fun ThemedConfirmDialog(
    icon: ImageVector, title: String, message: String, confirmText: String,
    isDestructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        icon = { Icon(icon, null, Modifier.size(28.dp),
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm,
                colors = if (isDestructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()) { Text(confirmText) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}

//  Publish Relay List Dialog 

@Composable
private fun PublishNip65Dialog(
    outboxRelays: List<UserRelay>, inboxRelays: List<UserRelay>,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        icon = { Icon(Icons.Outlined.Publish, null, Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Publish Relay List", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Broadcast your relay configuration so others can find you.")
                Spacer(Modifier.height(12.dp))
                Text("${outboxRelays.size} outbox (write) relay${if (outboxRelays.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                outboxRelays.forEach { relay ->
                    Text("  ${relay.url}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text("${inboxRelays.size} inbox (read) relay${if (inboxRelays.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                inboxRelays.forEach { relay ->
                    Text("  ${relay.url}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                val bothRelays = outboxRelays.filter { o -> inboxRelays.any { it.url == o.url } }
                if (bothRelays.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("${bothRelays.size} relay${if (bothRelays.size != 1) "s" else ""} marked as both read+write",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Publish") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}
