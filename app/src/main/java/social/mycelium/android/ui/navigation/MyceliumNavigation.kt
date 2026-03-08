package social.mycelium.android.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import social.mycelium.android.data.Author
import social.mycelium.android.data.DefaultRelayCategories
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.repository.NotificationsRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import social.mycelium.android.ui.components.NoteCard
import social.mycelium.android.utils.normalizeAuthorIdForCache
import social.mycelium.android.ui.components.ScrollAwareBottomNavigationBar
import social.mycelium.android.ui.components.ThreadSlideBackBox
import social.mycelium.android.ui.screens.AboutScreen
import social.mycelium.android.ui.screens.DebugSettingsScreen
import social.mycelium.android.ui.screens.AnnouncementsFeedScreen
import social.mycelium.android.ui.screens.DraftsScreen
import social.mycelium.android.ui.screens.GeneralSettingsScreen
import social.mycelium.android.ui.screens.AccountPreferencesScreen
import social.mycelium.android.ui.screens.AppearanceSettingsScreen
import social.mycelium.android.ui.screens.MediaSettingsScreen
import social.mycelium.android.ui.screens.NotificationSettingsScreen
import social.mycelium.android.ui.screens.FiltersBlocksSettingsScreen
import social.mycelium.android.ui.screens.DataStorageSettingsScreen
import social.mycelium.android.ui.screens.ComposeNoteScreen
import social.mycelium.android.ui.screens.ComposeTopicScreen
import social.mycelium.android.ui.screens.ComposeTopicReplyScreen
import social.mycelium.android.ui.screens.DashboardScreen
import social.mycelium.android.ui.screens.EffectsLabScreen
import social.mycelium.android.ui.screens.DebugFollowListScreen
import social.mycelium.android.ui.screens.ImageContentViewerScreen
import social.mycelium.android.ui.screens.VideoContentViewerScreen
import social.mycelium.android.ui.screens.ModernThreadViewScreen
import social.mycelium.android.ui.screens.NotificationsScreen
import social.mycelium.android.ui.screens.TopicsScreen
import social.mycelium.android.ui.screens.TopicThreadScreen
import social.mycelium.android.ui.screens.ProfileScreen
import social.mycelium.android.ui.screens.RelayLogScreen
import social.mycelium.android.ui.screens.RelayDiscoveryScreen
import social.mycelium.android.ui.screens.RelayConnectionStatusScreen
import social.mycelium.android.ui.screens.RelayHealthScreen
import social.mycelium.android.ui.screens.RelayManagementScreen
import social.mycelium.android.ui.screens.SettingsScreen
import social.mycelium.android.ui.screens.LiveExplorerScreen
import social.mycelium.android.ui.screens.ReactionsScreen
import social.mycelium.android.ui.screens.LiveStreamScreen
import social.mycelium.android.ui.screens.OnboardingScreen
import social.mycelium.android.ui.components.PipStreamManager
import social.mycelium.android.ui.components.PipStreamOverlay
import social.mycelium.android.ui.components.SharedPlayerPool
import social.mycelium.android.ui.screens.QrCodeScreen
import social.mycelium.android.ui.screens.ReplyComposeScreen
import social.mycelium.android.viewmodel.AnnouncementsViewModel
import social.mycelium.android.viewmodel.AppViewModel
import social.mycelium.android.viewmodel.rememberThreadStateHolder
import social.mycelium.android.viewmodel.DashboardViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import social.mycelium.android.auth.AmberState
import com.example.cybin.nip55.IActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main navigation composable for Mycelium app using Jetpack Navigation. This provides proper
 * backstack management like Primal, allowing infinite exploration through feeds, threads, and
 * profiles without losing history.
 *
 * The bottom navigation bar is persistent across main screens and hidden on detail screens. Uses
 * MaterialFadeThrough transitions for navigation bar page changes.
 */
/**
 * Build a stub Note for root walk-up when the root note isn't in cache.
 * Extracts the root author pubkey from the reply's p-tags and resolves it
 * from ProfileMetadataCache so the thread header isn't blank while fetching.
 */
private fun buildRootStubNote(
    rootId: String,
    replyNote: social.mycelium.android.data.Note,
    fallbackRelayUrls: List<String> = emptyList()
): social.mycelium.android.data.Note {
    val profileCache = ProfileMetadataCache.getInstance()
    // Try to find the root author pubkey from the reply's p-tags (first p-tag is typically the root author)
    val rootAuthorPubkey = replyNote.tags
        .firstOrNull { it.size >= 2 && it[0] == "p" }
        ?.getOrNull(1)
    val author = if (rootAuthorPubkey != null) {
        profileCache.resolveAuthor(rootAuthorPubkey)
    } else {
        Author(id = "", username = "", displayName = "", avatarUrl = null, isVerified = false)
    }
    val noteRelayUrls = replyNote.relayUrls.ifEmpty { listOfNotNull(replyNote.relayUrl) }.ifEmpty { fallbackRelayUrls }
    return social.mycelium.android.data.Note(
        id = rootId,
        author = author,
        content = "",
        timestamp = 0L,
        relayUrls = noteRelayUrls
    )
}

/** Build ReactionsData merging boost authors from note + NoteCountsRepository. */
private fun buildReactionsData(
    noteId: String,
    counts: social.mycelium.android.repository.NoteCounts?,
    noteBoostAuthors: List<social.mycelium.android.data.Author> = emptyList(),
    noteReactions: List<String> = emptyList(),
): social.mycelium.android.viewmodel.ReactionsData {
    val profileCache = social.mycelium.android.repository.ProfileMetadataCache.getInstance()
    val repostPubkeys = counts?.repostAuthors ?: emptyList()
    val existingIds = noteBoostAuthors.map { it.id }.toSet()
    val extra = repostPubkeys.filter { it !in existingIds }.map { profileCache.resolveAuthor(it) }
    val mergedBoosts = (noteBoostAuthors + extra).distinctBy { it.id }
    return social.mycelium.android.viewmodel.ReactionsData(
        noteId = noteId,
        reactions = counts?.reactions ?: noteReactions,
        reactionAuthors = counts?.reactionAuthors ?: emptyMap(),
        customEmojiUrls = counts?.customEmojiUrls ?: emptyMap(),
        zapAuthors = counts?.zapAuthors ?: emptyList(),
        zapAmountByAuthor = counts?.zapAmountByAuthor ?: emptyMap(),
        zapTotalSats = counts?.zapTotalSats ?: 0L,
        boostAuthors = mergedBoosts,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyceliumNavigation(
        appViewModel: AppViewModel,
        accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel,
        onAmberLogin: (android.content.Intent) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val relayRepository = remember { RelayRepository(context) }

    // Report TTFD after first frame so system and tools can measure startup accurately
    LaunchedEffect(Unit) {
        withFrameMillis { }
        (context as? ComponentActivity)?.reportFullyDrawn()
    }
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

    // Feed state - separate states for Home and Topics feeds
    val feedStateViewModel: social.mycelium.android.viewmodel.FeedStateViewModel = viewModel()

    // Thread state holder - persists scroll positions and comment states per thread
    val threadStateHolder = rememberThreadStateHolder()

    // Track scroll states for different screens
    val feedListState = rememberLazyListState()

    // Global top app bar state for collapsible navigation
    // This state is shared across main screens so collapse state persists during navigation
    val topAppBarState = rememberTopAppBarState()

    // Dashboard, Topics, and Notifications list states for scroll-to-top and position persistence
    val dashboardListState = rememberLazyListState()
    val topicsListState = rememberLazyListState()
    val notificationsListState = rememberLazyListState()
    var notificationsSelectedTab by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Snackbar host for app-wide messages (positioned above bottom nav)
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Helper: show a message via the app-wide snackbar (above bottom nav)
    val showSnackbar: (String) -> Unit = remember(snackbarHostState) {
        { msg: String -> coroutineScope.launch { snackbarHostState.showSnackbar(msg, duration = androidx.compose.material3.SnackbarDuration.Short) } }
    }

    // Handle deep-link navigation from tapping an Android notification
    val pendingNav by appViewModel.pendingNotificationNav.collectAsState()
    LaunchedEffect(pendingNav) {
        val nav = appViewModel.consumePendingNotificationNav() ?: return@LaunchedEffect
        val targetNoteId = nav.rootNoteId ?: nav.noteId
        val highlightReplyId = if (nav.rootNoteId != null) nav.noteId else null

        // Look up real data from NotificationsRepository (already ingested the event)
        val notifData = NotificationsRepository.findNotificationByNoteId(nav.noteId)

        // Mark the notification as read so it doesn't show as unread in the notifications view
        if (notifData != null) {
            NotificationsRepository.markAsSeen(notifData.id)
        }

        // Try to get a real Note with author metadata + relay URLs:
        // 1) From the notification's embedded note
        // 2) From the notification's targetNote (for likes/zaps/reposts)
        // 3) From NotesRepository cache (the event was already ingested)
        // 4) Fall back to a minimal stub (thread composable will fetch from relays)
        val realNote = notifData?.note
            ?: notifData?.targetNote
            ?: NotesRepository.getInstance().getNoteFromCache(targetNoteId)
        val noteForThread = if (realNote != null) {
            // If we have a rootNoteId that differs from the note id, try to get the root too
            if (targetNoteId != realNote.id) {
                NotesRepository.getInstance().getNoteFromCache(targetNoteId) ?: run {
                    // Prefer the targetNote's author (the actual parent/root) over the
                    // notification's author (the replier). Fall back to resolveAuthor from
                    // the reply's first p-tag (typically the root author).
                    val rootAuthor = notifData?.targetNote?.author
                        ?: realNote.tags.firstOrNull { it.size >= 2 && it[0] == "p" }
                            ?.getOrNull(1)?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
                        ?: social.mycelium.android.data.Author(id = "", username = "", displayName = "", avatarUrl = null, isVerified = false)
                    social.mycelium.android.data.Note(
                        id = targetNoteId,
                        author = rootAuthor,
                        content = "",
                        timestamp = 0L,
                        isReply = false,
                        relayUrls = realNote.relayUrls
                    )
                }
            } else {
                realNote
            }
        } else {
            // Absolute fallback: stub note. Thread composable will fetch from relays.
            // Try to resolve root author from notification's targetNote or p-tags
            val fallbackAuthor = notifData?.targetNote?.author
                ?: notifData?.note?.tags?.firstOrNull { it.size >= 2 && it[0] == "p" }
                    ?.getOrNull(1)?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
                ?: social.mycelium.android.data.Author(id = "", username = "", displayName = "", avatarUrl = null, isVerified = false)
            social.mycelium.android.data.Note(
                id = targetNoteId,
                author = fallbackAuthor,
                content = "",
                timestamp = 0L
            )
        }
        appViewModel.storeNoteForThread(noteForThread)
        // Also store the reply note if different from the root (for highlight scroll)
        if (highlightReplyId != null && notifData?.note != null && notifData.note.id == highlightReplyId) {
            appViewModel.storeNoteForThread(notifData.note)
        }
        val route = if (highlightReplyId != null) {
            "thread/$targetNoteId?replyKind=1&highlightReplyId=$highlightReplyId"
        } else {
            "thread/$targetNoteId?replyKind=1"
        }
        navController.navigate(route) { launchSingleTop = true }
        Log.d("MyceliumNav", "Deep-link from notification: route=$route type=${nav.notifType} notifFound=${notifData != null} realNote=${realNote != null}")
    }

    // Observe async toast messages (e.g. reaction failures, publish results)
    val toastMsg by accountStateViewModel.toastMessage.collectAsState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            accountStateViewModel.clearToast()
            snackbarHostState.showSnackbar(it, duration = androidx.compose.material3.SnackbarDuration.Short)
        }
    }

    // Observe publish failures and show snackbar with failure details
    LaunchedEffect(Unit) {
        social.mycelium.android.relay.RelayHealthTracker.publishFailure.collect { report ->
            val kindLabel = when (report.kind) {
                1 -> "note"
                7 -> "reaction"
                11 -> "topic"
                1111 -> "reply"
                else -> "event"
            }
            val msg = "Publishing $kindLabel failed on ${report.failureCount} of ${report.targetRelayCount} relays"
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Details",
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                navController.navigate("settings/publish_results") { launchSingleTop = true }
            }
        }
    }

    // Register Amber foreground launcher so NIP-55 signing can prompt the user when needed
    val amberState by accountStateViewModel.amberState.collectAsState()
    val signer = (amberState as? AmberState.LoggedIn)?.signer
    if (signer is IActivityLauncher) {
        val activityLauncher = signer as IActivityLauncher
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { data ->
                    try {
                        activityLauncher.newResponse(data)
                    } catch (e: ClassCastException) {
                        Log.e("MyceliumNavigation", "Amber response cast failed: ${e.message}")
                    }
                }
            } else {
                // User rejected or Amber was cancelled — resume the waiting coroutine
                // with an empty intent so it doesn't hang until timeout
                Log.d("MyceliumNavigation", "Amber signing rejected/cancelled (resultCode=${result.resultCode})")
                activityLauncher.newResponse(Intent())
            }
        }
        DisposableEffect(activityLauncher, launcher) {
            val launcherFn: (Intent) -> Unit = { intent ->
                try {
                    launcher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    showSnackbar("Amber signer not found")
                }
            }
            activityLauncher.registerForegroundLauncher(launcherFn)
            onDispose {
                activityLauncher.unregisterForegroundLauncher(launcherFn)
            }
        }
    }

    // Overlay thread stacks – each context maintains a stack of notes for chained thread exploration.
    // The top of the stack is the currently displayed thread. Back pops. onNoteClick pushes.
    val overlayThreadStack = remember { mutableStateListOf<social.mycelium.android.data.Note>() }
    val overlayTopicThreadStack = remember { mutableStateListOf<social.mycelium.android.data.Note>() }
    val overlayProfileThreadStack = remember { mutableStateListOf<social.mycelium.android.data.Note>() }
    val overlayNotifThreadStack = remember { mutableStateListOf<social.mycelium.android.data.Note>() }
    // Track replyKind per overlay entry for notifications (kind-1 vs kind-1111)
    val overlayNotifReplyKinds = remember { mutableStateListOf<Int>() }
    // Track highlightReplyId per overlay entry — when a quoted note is a reply,
    // we open the root thread but scroll/expand to the specific quoted reply.
    val overlayThreadHighlightIds = remember { mutableStateListOf<String?>() }
    val overlayTopicThreadHighlightIds = remember { mutableStateListOf<String?>() }
    val overlayProfileThreadHighlightIds = remember { mutableStateListOf<String?>() }
    val overlayNotifThreadHighlightIds = remember { mutableStateListOf<String?>() }
    // Derived IDs for backward compatibility with visibility/preserve checks
    val overlayThreadNoteId: String? = overlayThreadStack.lastOrNull()?.id
    val overlayTopicThreadNoteId: String? = overlayTopicThreadStack.lastOrNull()?.id
    val overlayProfileThreadNoteId: String? = overlayProfileThreadStack.lastOrNull()?.id
    val overlayNotifThreadNoteId: String? = overlayNotifThreadStack.lastOrNull()?.id

    // Determine current route to show/hide bottom nav
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Clear overlays when navigating away from their host screen.
    // Preserve overlay when navigating to screens that should return to the thread on back
    // (profile, image/video viewer, thread, reply compose, QR).
    val overlayPreserveRoutes = setOf("dashboard", "image_viewer", "video_viewer", "user_qr")
    LaunchedEffect(currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        val preserveDashboardOverlay = route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
                || route.startsWith("note_relays/")
                || route.startsWith("relay_log/")
                || route.startsWith("reactions/")
                || route.startsWith("zap_settings")
                || route.startsWith("reply_compose")
        if (!preserveDashboardOverlay) {
            overlayThreadStack.clear()
            overlayThreadHighlightIds.clear()
        }
        val preserveTopicOverlay = route == "topics" || route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
                || route.startsWith("note_relays/")
                || route.startsWith("relay_log/")
                || route.startsWith("reactions/")
                || route.startsWith("zap_settings")
                || route.startsWith("reply_compose")
        if (!preserveTopicOverlay) {
            overlayTopicThreadStack.clear()
            overlayTopicThreadHighlightIds.clear()
        }
        val preserveProfileOverlay = route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
                || route.startsWith("note_relays/")
                || route.startsWith("relay_log/")
                || route.startsWith("reactions/")
                || route.startsWith("zap_settings")
                || route.startsWith("reply_compose")
        if (!preserveProfileOverlay) {
            overlayProfileThreadStack.clear()
            overlayProfileThreadHighlightIds.clear()
        }
        val preserveNotifOverlay = route == "notifications" || route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
                || route.startsWith("note_relays/")
                || route.startsWith("relay_log/")
                || route.startsWith("reactions/")
                || route.startsWith("zap_settings")
                || route.startsWith("reply_compose")
        if (!preserveNotifOverlay) {
            overlayNotifThreadStack.clear()
            overlayNotifThreadHighlightIds.clear()
            overlayNotifReplyKinds.clear()
        }
    }

    // Main screens that should show the bottom navigation
    val mainScreenRoutes = setOf("dashboard", "notifications", "relays?tab={tab}&prefill={prefill}&outbox={outbox}&inbox={inbox}", "announcements", "messages", "wallet", "profile/{authorId}", "user_profile", "topics")
    // Check if a route is a main screen (handles parameterized routes like relays?tab=...)
    fun isMainScreen(route: String?): Boolean {
        if (route == null) return false
        return route in mainScreenRoutes || route.startsWith("relays") || route.startsWith("announcements") || route.startsWith("profile") || route == "user_profile"
    }

    // Tab index for direction-aware transitions between bottom nav destinations.
    // Order matches the visual left-to-right bottom nav layout:
    // HOME(0) | MESSAGES(1) | WALLET(2) | ANNOUNCEMENTS(3) | NOTIFICATIONS(4)
    // Topics (5) and Profile (6) are accessed via menus, not bottom nav.
    fun routeToTabIndex(route: String?): Int = when {
        route == "dashboard" -> 0
        route == "messages" -> 1
        route == "wallet" -> 2
        route?.startsWith("announcements") == true -> 3
        route?.startsWith("relays") == true -> 3 // Relay Manager also maps to same tab index
        route == "notifications" -> 4
        route == "topics" -> 5
        route?.startsWith("profile") == true || route == "user_profile" -> 6
        else -> -1
    }
    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
    val showBottomNav = currentAccount != null
        && onboardingComplete
        && currentRoute != "onboarding"
        && isMainScreen(currentRoute)
        && currentRoute?.startsWith("thread") != true
        && overlayThreadNoteId == null
        && overlayTopicThreadNoteId == null
        && overlayProfileThreadNoteId == null
        && overlayNotifThreadNoteId == null

    // Sidebar drawer state — shared across Dashboard + Topics so it survives navigation
    val sidebarDrawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed
    )
    var isDrawerOpen by remember { mutableStateOf(false) }
    // Helper: perform an action then close the drawer. Action runs synchronously on the main thread,
    // then the drawer close fires in a nav-level coroutine that survives screen changes.
    fun closeDrawerThen(action: () -> Unit) {
        action()
        coroutineScope.launch {
            sidebarDrawerState.snapTo(androidx.compose.material3.DrawerValue.Closed)
        }
    }

    // Defer showing bottom bar when returning from thread so pop transition settles (avoids flash)
    var allowBottomNavVisible by remember { mutableStateOf(true) }
    val isOnMainScreen = isMainScreen(currentRoute) &&
            currentRoute?.startsWith("thread") != true && overlayThreadNoteId == null && overlayTopicThreadNoteId == null && overlayProfileThreadNoteId == null && overlayNotifThreadNoteId == null
    LaunchedEffect(currentRoute, overlayThreadNoteId, overlayTopicThreadNoteId, overlayProfileThreadNoteId, overlayNotifThreadNoteId) {
        if (!isOnMainScreen) {
            allowBottomNavVisible = false
        } else if (!allowBottomNavVisible && isOnMainScreen) {
            kotlinx.coroutines.delay(80)
            allowBottomNavVisible = true
        }
    }

    // Current destination for bottom nav highlighting
    val currentDestination =
            when {
                currentRoute == "dashboard" -> "home"
                currentRoute == "topics" -> "topics"
                currentRoute == "notifications" -> "notifications"
                currentRoute?.startsWith("announcements") == true -> "announcements"
                currentRoute?.startsWith("relays") == true -> "announcements"
                currentRoute?.startsWith("profile") == true -> "profile"
                isMainScreen(currentRoute) -> currentRoute ?: "home"
                else -> "home"
            }

    // Real notification count from NotificationsRepository (subscription started below when account + relays ready)
    val notificationUnseenCount by NotificationsRepository.unseenCount.collectAsState(initial = 0)

    // Cached reply counts (updated when user opens a thread); used by Dashboard and Profile cards
    val replyCountByNoteId by social.mycelium.android.repository.ReplyCountCache.replyCountByNoteId.collectAsState()
    // Zap/reaction counts from kind-7 and kind-9735; used by Dashboard and Profile cards
    val noteCountsByNoteId by social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.collectAsState()

    // Initialize notifications persistence (SharedPreferences for seen IDs)
    LaunchedEffect(Unit) {
        NotificationsRepository.init(context)
        social.mycelium.android.repository.DraftsRepository.init(context)
    }
    // Load drafts when account is available
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            social.mycelium.android.repository.DraftsRepository.loadDrafts(pubkey)
        }
    }

    // Start notifications subscription and load anchor subscriptions when we have account + relays.
    // Gated behind onboardingComplete to prevent premature relay connections during onboarding.
    val storageManager = remember { RelayStorageManager(context) }
    LaunchedEffect(currentAccount) {
        val pubkey = currentAccount?.toHexKey() ?: return@LaunchedEffect
        // Set current user pubkey so own events are displayed immediately in the feed
        NotesRepository.getInstance().setCurrentUserPubkey(pubkey)
    }
    LaunchedEffect(currentAccount, onboardingComplete) {
        val pubkey = currentAccount?.toHexKey() ?: return@LaunchedEffect
        if (!onboardingComplete) return@LaunchedEffect
        // Relay data is persisted during onboarding — load it directly.
        // Notifications need to query ALL user relays for p-tagged events — outbox relays
        // are where our notes live, so others' reactions/replies land there too.
        // NOTE: indexer relays are intentionally excluded from feed/notification
        // connections — they are only for NIP-65 lookups during onboarding.
        val categories = storageManager.loadCategories(pubkey)
        val outboxRelays = storageManager.loadOutboxRelays(pubkey)
        val inboxRelays = storageManager.loadInboxRelays(pubkey)

        val categoryUrls = categories.flatMap { it.relays }.map { it.url }
        val outboxUrls = outboxRelays.map { it.url }
        val inboxUrls = inboxRelays.map { it.url }

        val allUserRelayUrls = (categoryUrls + outboxUrls + inboxUrls)
            .map { it.trim().removeSuffix("/") }
            .distinct()

        Log.d(
            "MyceliumNav",
            "Notif sub: categories=${categoryUrls.size}, outbox=${outboxUrls.size}, " +
                "inbox=${inboxUrls.size}, total=${allUserRelayUrls.size}"
        )

        if (allUserRelayUrls.isNotEmpty()) {
            val indexerUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            NotificationsRepository.setCacheRelayUrls(indexerUrls)
            NotificationsRepository.startSubscription(pubkey, inboxUrls, outboxUrls, categoryUrls)
            Log.d("MyceliumNav", "Notif subscription started: inbox=${inboxUrls.size}, outbox=${outboxUrls.size}, categories=${categoryUrls.size}")
            // Enable Android push notifications after a brief delay for subscription setup.
            // seenEventIds dedup in handleEvent prevents replayed events from re-firing.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(2_000L)
                NotificationsRepository.enableAndroidNotifications()
                Log.d("MyceliumNav", "Android push notifications enabled (initial replay settled)")
            }
            // Fetch mute list (NIP-51 kind 10000) and bookmarks (kind 10003)
            social.mycelium.android.repository.MuteListRepository.fetchMuteList(pubkey, allUserRelayUrls)
            social.mycelium.android.repository.BookmarkRepository.fetchBookmarks(pubkey, allUserRelayUrls)
            // Load kind:30073 anchor subscriptions (favorites) for this user
            accountStateViewModel.requestMySubscriptions()
        } else {
            Log.w("MyceliumNav", "Notif subscription skipped: no relay URLs found for ${pubkey.take(8)}")
        }
    }

    // DM subscription: disabled until user-configured DM relays are implemented.
    // DM relays are a distinct category (not inbox/outbox) specifically for NIP-17 gift wraps.
    // Without explicit DM relays, we don't start a subscription — avoids unnecessary Amber
    // decrypt calls on general/fallback relays.
    // TODO: Re-enable when DM relay configuration UI is added to relay settings.

    // onboardingComplete is the single source of truth for whether the user can see the feed.
    // It's persisted per-account and set in AccountStateViewModel during login/switch.
    val authState by accountStateViewModel.authState.collectAsState()
    val accountsRestored by accountStateViewModel.accountsRestored.collectAsState()
    // CRITICAL: Only consider onboarding needed if we have account WITH valid pubkey
    val needsOnboarding = accountsRestored && authState.isAuthenticated &&
        currentAccount?.toHexKey()?.isNotBlank() == true && !onboardingComplete

    // Navigate to onboarding when account changes and user hasn't completed onboarding.
    // Track which npub we've already triggered for to avoid re-triggering on recomposition.
    var onboardingTriggeredForNpub by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentAccount, needsOnboarding) {
        val account = currentAccount ?: return@LaunchedEffect
        if (!needsOnboarding) return@LaunchedEffect
        if (account.npub == onboardingTriggeredForNpub) return@LaunchedEffect
        onboardingTriggeredForNpub = account.npub
        navController.navigate("onboarding") {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Double-tap back to exit
    var backPressedTime by remember { mutableLongStateOf(0L) }
    var shouldShowExitToast by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Show exit snackbar when needed
    LaunchedEffect(shouldShowExitToast) {
        if (shouldShowExitToast) {
            snackbarHostState.showSnackbar("Press back again to exit", duration = androidx.compose.material3.SnackbarDuration.Short)
            shouldShowExitToast = false
        }
    }

    // Handle back press on main screens
    BackHandler(enabled = isMainScreen(currentRoute)) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - backPressedTime < 2000) {
            // Double tap detected - exit app
            (context as? android.app.Activity)?.finish()
        } else {
            // First tap - show toast
            backPressedTime = currentTime
            shouldShowExitToast = true
        }
    }

    Scaffold(
            contentWindowInsets = WindowInsets(0),
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = scaffoldPadding.calculateTopPadding())) {
            NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    // Instant transitions everywhere — seamless like Topics.
                    // Only thread routes get a slide animation (unique thread UX).
                    enterTransition = {
                        val target = targetState.destination.route
                        val isThread = target?.startsWith("thread/") == true || target?.startsWith("topic_thread/") == true
                        if (isThread) {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(300, easing = MaterialMotion.EasingEmphasizedDecelerate)
                            )
                        } else EnterTransition.None
                    },
                    exitTransition = {
                        val target = targetState.destination.route
                        val isThread = target?.startsWith("thread/") == true || target?.startsWith("topic_thread/") == true
                        if (isThread) {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(300, easing = MaterialMotion.EasingEmphasizedAccelerate)
                            )
                        } else ExitTransition.None
                    },
                    popEnterTransition = {
                        val initial = initialState.destination.route
                        val isThread = initial?.startsWith("thread/") == true || initial?.startsWith("topic_thread/") == true
                        if (isThread) {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(300, easing = MaterialMotion.EasingEmphasizedDecelerate)
                            )
                        } else EnterTransition.None
                    },
                    popExitTransition = {
                        val initial = initialState.destination.route
                        val isThread = initial?.startsWith("thread/") == true || initial?.startsWith("topic_thread/") == true
                        if (isThread) {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(300, easing = MaterialMotion.EasingEmphasizedAccelerate)
                            )
                        } else ExitTransition.None
                    }
            ) {
                // Dashboard - Home feed (thread opens as overlay so feed stays visible for slide-back)
                composable("dashboard") {
                    // Wait for account restoration before rendering anything — prevents
                    // login/guest UI flashing on cold start for signed-in users.
                    val accountsRestoredLocal by accountStateViewModel.accountsRestored.collectAsState()
                    if (!accountsRestoredLocal) {
                        // Blank placeholder while account state loads — prevents guest/login flash.
                        // Scaffold background already provides the correct color.
                        Box(Modifier.fillMaxSize())
                        return@composable
                    }

                    // Guard: if we have account but onboarding not complete, redirect to onboarding
                    // If NO account, stay on dashboard to show "Login with Amber" button
                    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
                    val appState by appViewModel.appState.collectAsState()
                    val context = LocalContext.current
                    val storageManager = remember { social.mycelium.android.repository.RelayStorageManager(context) }
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

                    LaunchedEffect(currentAccount, onboardingComplete) {
                        val account = currentAccount ?: return@LaunchedEffect
                        val hasValidPubkey = account.toHexKey()?.isNotBlank() == true
                        if (hasValidPubkey && !onboardingComplete) {
                            navController.navigate("onboarding") {
                                popUpTo("dashboard") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    val fallbackRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            val subscribedRelays = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }.map { it.url }.distinct()
                            subscribedRelays.ifEmpty {
                                val defaultCat = categories.firstOrNull { it.isDefault }
                                defaultCat?.relays?.map { it.url } ?: emptyList()
                            }
                        } ?: emptyList()
                    }
                    val cacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    // Gate: don't render feed while redirecting to onboarding (prevents flash)
                    val hasAccount = currentAccount?.toHexKey()?.isNotBlank() == true
                    if (hasAccount && !onboardingComplete) {
                        // Blank — LaunchedEffect above will redirect to onboarding
                        Box(Modifier.fillMaxSize())
                    } else Box(modifier = Modifier.fillMaxSize()) {
                        DashboardScreen(
                            isSearchMode = appState.isSearchMode,
                            onSearchModeChange = { appViewModel.updateSearchMode(it) },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onNavigateTo = { screen -> closeDrawerThen {
                                when {
                                    screen == "settings" -> navController.navigate("settings") { launchSingleTop = true }
                                    screen.startsWith("relays") -> navController.navigate(screen) { launchSingleTop = true }
                                    screen == "notifications" -> navController.navigate("notifications") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    screen == "messages" -> navController.navigate("messages") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    screen == "user_profile" -> currentAccount?.toHexKey()?.let {
                                        navController.navigateToProfile(it)
                                    }
                                    screen == "compose" || screen.startsWith("compose?") -> navController.navigate(screen) { launchSingleTop = true }
                                    screen == "drafts" -> navController.navigate("drafts") { launchSingleTop = true }
                                    screen == "topics" -> navController.navigate("topics") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    screen.startsWith("live_stream/") -> navController.navigate(screen) { launchSingleTop = true }
                                    screen == "live_explorer" -> navController.navigate("live_explorer") { launchSingleTop = true }
                                    screen == "relay_discovery" -> navController.navigate("relay_discovery") { launchSingleTop = true }
                                    screen == "relay_connection_status" -> navController.navigate("relay_connection_status") { launchSingleTop = true }
                                    screen == "onboarding" -> navController.navigate("onboarding") { launchSingleTop = true }
                                    screen.startsWith("settings/") -> navController.navigate(screen) { launchSingleTop = true }
                                }
                            } },
                            onThreadClick = { note, _ ->
                                feedStateViewModel.saveHomeScrollPosition(
                                    dashboardListState.firstVisibleItemIndex,
                                    dashboardListState.firstVisibleItemScrollOffset
                                )
                                // Reposts use synthetic "repost:xxx" IDs; resolve to the real note ID for thread fetching
                                val threadNote = if (note.originalNoteId != null) {
                                    note.copy(id = note.originalNoteId)
                                } else note
                                // If this is a kind-1 reply (e.g. quoted reply), navigate to the thread root
                                if (threadNote.rootNoteId != null && threadNote.isReply) {
                                    val rootId = threadNote.rootNoteId!!
                                    val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                    val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote)
                                    appViewModel.updateSelectedNote(overlayNote)
                                    appViewModel.updateThreadRelayUrls(overlayNote.relayUrls.ifEmpty { listOfNotNull(overlayNote.relayUrl) })
                                    appViewModel.markThreadViewed(note.id)
                                    overlayThreadStack.clear()
                                    overlayThreadHighlightIds.clear()
                                    overlayThreadStack.add(overlayNote)
                                    overlayThreadHighlightIds.add(threadNote.id) // scroll to the quoted reply
                                    // Async-fetch the root note so thread view has full data
                                    if (rootFromCache == null) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                noteId = rootId,
                                                userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) } +
                                                    NotesRepository.getInstance().INDEXER_RELAYS,
                                                authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                            )
                                            if (fetched != null) {
                                                withContext(Dispatchers.Main.immediate) {
                                                    val idx = overlayThreadStack.indexOfFirst { it.id == rootId }
                                                    if (idx >= 0) overlayThreadStack[idx] = fetched
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    appViewModel.updateSelectedNote(threadNote)
                                    appViewModel.updateThreadRelayUrls(threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) })
                                    appViewModel.markThreadViewed(note.id)
                                    overlayThreadStack.clear()
                                    overlayThreadHighlightIds.clear()
                                    overlayThreadStack.add(threadNote)
                                    overlayThreadHighlightIds.add(null) // no highlight for non-reply
                                }
                            },
                            onImageTap = { note, _, _ ->
                                val threadNote = if (note.originalNoteId != null) {
                                    note.copy(id = note.originalNoteId)
                                } else note
                                appViewModel.updateSelectedNote(threadNote)
                                appViewModel.updateThreadRelayUrls(threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) })
                                appViewModel.markThreadViewed(note.id)
                                overlayThreadStack.clear()
                                overlayThreadHighlightIds.clear()
                                overlayThreadStack.add(threadNote)
                                overlayThreadHighlightIds.add(null)
                            },
                            onOpenImageViewer = { urls, index ->
                                appViewModel.openImageViewer(urls, index)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, index ->
                                appViewModel.openVideoViewer(urls, index)
                                navController.navigate("video_viewer")
                            },
                            mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                            onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                            onScrollToTop = {
                                coroutineScope.launch {
                                    dashboardListState.scrollToItem(0)
                                }
                            },
                            listState = dashboardListState,
                            feedStateViewModel = feedStateViewModel,
                            accountStateViewModel = accountStateViewModel,
                            relayRepository = relayRepository,
                            onLoginClick = {
                                val loginIntent = accountStateViewModel.loginWithAmber()
                                onAmberLogin(loginIntent)
                            },
                            initialTopAppBarState = topAppBarState,
                            isDashboardVisible = currentRoute in setOf("dashboard", "image_viewer", "video_viewer"),
                            onQrClick = { navController.navigate("user_qr") { launchSingleTop = true } },
                            onSidebarRelayHealthClick = { closeDrawerThen {
                                navController.navigate("settings/relay_health") {
                                    launchSingleTop = true
                                }
                            } },
                            onSidebarRelayDiscoveryClick = { closeDrawerThen {
                                navController.navigate("relay_discovery") {
                                    launchSingleTop = true
                                }
                            } },
                            onRelayClick = { relayUrl ->
                                val encoded = android.net.Uri.encode(relayUrl)
                                navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                            },
                            onSeeAllReactions = { note ->
                                val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[note.id]
                                appViewModel.storeReactionsData(buildReactionsData(note.id, counts, note.repostedByAuthors, note.reactions))
                                navController.navigate("reactions/${note.id}") { launchSingleTop = true }
                            },
                            onNavigateToRelayList = { urls ->
                                val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                            },
                            hiddenNoteIds = appViewModel.hiddenNoteIds.collectAsState().value,
                            onClearRead = { appViewModel.clearReadNotes() },
                            hasReadNotes = appViewModel.hasViewedNotes(),
                            onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                            onDrawerStateChanged = { open -> isDrawerOpen = open },
                            drawerState = sidebarDrawerState
                        )

                        // Intercept system back gesture when overlay thread is showing
                        BackHandler(enabled = overlayThreadStack.isNotEmpty()) {
                            overlayThreadStack.removeLastOrNull()
                            overlayThreadHighlightIds.removeLastOrNull()
                        }

                        // Thread overlay: feed stays underneath so slide-back reveals it; slide in from right like nav thread.
                        // Stack-based: top of stack is the current thread. Back pops. onNoteClick pushes.
                        val contentNote = overlayThreadStack.lastOrNull()
                        var lastOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                        if (contentNote != null) lastOverlayNote = contentNote
                        val displayNote = contentNote ?: lastOverlayNote
                        val showThreadOverlay = contentNote != null
                        AnimatedVisibility(
                            visible = showThreadOverlay,
                            enter = slideInHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            ) { fullWidth -> fullWidth },
                            exit = slideOutHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            ) { fullWidth -> fullWidth }
                        ) {
                            if (displayNote != null) {
                                val noteId = displayNote.id
                                val relayUrls = (displayNote.relayUrls.ifEmpty { listOfNotNull(displayNote.relayUrl) } + fallbackRelayUrls).distinct()
                                val savedScrollState = threadStateHolder.getScrollState(noteId)
                                val threadListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                                )
                                val commentStates = threadStateHolder.getCommentStates(noteId)
                                var expandedControlsCommentId by remember(noteId) {
                                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                                }
                                var expandedControlsReplyId by remember(noteId) {
                                    mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                                }
                                val authState by accountStateViewModel.authState.collectAsState()
                                DisposableEffect(noteId) {
                                    onDispose {
                                        threadStateHolder.saveScrollState(noteId, threadListState)
                                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                                        threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                                    }
                                }
                                ThreadSlideBackBox(onBack = { overlayThreadStack.removeLastOrNull(); overlayThreadHighlightIds.removeLastOrNull() }) {
                                    ModernThreadViewScreen(
                                        note = displayNote,
                                        comments = emptyList(),
                                        listState = threadListState,
                                        commentStates = commentStates,
                                        expandedControlsCommentId = expandedControlsCommentId,
                                        onExpandedControlsChange = { expandedControlsCommentId = if (expandedControlsCommentId == it) null else it },
                                        expandedControlsReplyId = expandedControlsReplyId,
                                        onExpandedControlsReplyChange = { replyId ->
                                            expandedControlsReplyId = if (expandedControlsReplyId == replyId) null else replyId
                                        },
                                        topAppBarState = topAppBarState,
                                        replyKind = 1,
                                        highlightReplyId = overlayThreadHighlightIds.lastOrNull(),
                                        relayUrls = relayUrls,
                                        cacheRelayUrls = cacheRelayUrls,
                                        onBackClick = { overlayThreadStack.removeLastOrNull(); overlayThreadHighlightIds.removeLastOrNull() },
                                        onProfileClick = { navController.navigateToProfile(it) },
                                        onNoteClick = { clickedNote ->
                                            // Resolve repost IDs
                                            val threadNote = if (clickedNote.originalNoteId != null) clickedNote.copy(id = clickedNote.originalNoteId) else clickedNote
                                            if (threadNote.isReply && threadNote.rootNoteId != null) {
                                                // Root walk-up: open the full thread, not just the reply
                                                val rootId = threadNote.rootNoteId!!
                                                val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                                val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote)
                                                overlayThreadStack.add(overlayNote)
                                                overlayThreadHighlightIds.add(threadNote.id)
                                                if (rootFromCache == null) {
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                            noteId = rootId,
                                                            userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) } +
                                                                NotesRepository.getInstance().INDEXER_RELAYS,
                                                            authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                                        )
                                                        if (fetched != null) {
                                                            withContext(Dispatchers.Main.immediate) {
                                                                val idx = overlayThreadStack.indexOfFirst { it.id == rootId }
                                                                if (idx >= 0) overlayThreadStack[idx] = fetched
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                overlayThreadStack.add(threadNote)
                                                overlayThreadHighlightIds.add(null)
                                            }
                                        },
                                        onImageTap = { _, urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer") { launchSingleTop = true }
                                        },
                                        onOpenImageViewer = { urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer") { launchSingleTop = true }
                                        },
                                        onVideoClick = { urls, idx ->
                                            appViewModel.openVideoViewer(urls, idx)
                                            navController.navigate("video_viewer") { launchSingleTop = true }
                                        },
                                        onReact = { note, emoji ->
                                            val error = accountStateViewModel.sendReaction(note, emoji)
                                            if (error != null) showSnackbar(error)
                                        },
                                        onBoost = { n ->
                                            val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                                            if (err != null) showSnackbar(err)
                                        },
                                        onQuote = { n ->
                                            val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                                            val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                                            navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                        },
                                        onFork = { n ->
                                            val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                                            navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                        },
                                        onCustomZapSend = { note, amount, zapType, msg ->
                                            val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                            if (err != null) showSnackbar(err)
                                        },
                                        onZap = { nId, amount ->
                                            if (contentNote != null && contentNote.id == nId) {
                                                val err = accountStateViewModel.sendZap(contentNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                                if (err != null) showSnackbar(err)
                                            }
                                        },
                                        zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                        zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                        myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                                        boostedNoteIds = accountStateViewModel.boostedNoteIds.collectAsState().value,
                                        onLoginClick = {
                                            val loginIntent = accountStateViewModel.loginWithAmber()
                                            onAmberLogin(loginIntent)
                                        },
                                        isGuest = authState.isGuest,
                                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                                        userAvatarUrl = authState.userProfile?.picture,
                                        accountNpub = currentAccount?.npub,
                                        onHeaderProfileClick = {
                                            authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                                        },
                                        onHeaderAccountsClick = { },
                                        onHeaderQrCodeClick = { navController.navigate("user_qr") { launchSingleTop = true } },
                                        onHeaderSettingsClick = { navController.navigate("settings") { launchSingleTop = true } },
                                        mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                                        onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                                        onRelayNavigate = { relayUrl ->
                                            val encoded = android.net.Uri.encode(relayUrl)
                                            navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                                        },
                                        onNavigateToRelayList = { urls ->
                                            val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                            navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                                        },
                                        onPublishThreadReply = { rootId, rootPubkey, parentId, parentPubkey, content ->
                                            accountStateViewModel.publishKind1Reply(rootId, rootPubkey, parentId, parentPubkey, content)
                                        },
                                        onOpenReplyCompose = { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                            appViewModel.setReplyToNote(replyToNote)
                                            val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                            navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}&replyKind=1")
                                        },
                                        currentUserAuthor = remember(currentAccount) {
                                            currentAccount?.toHexKey()?.let { hex ->
                                                ProfileMetadataCache.getInstance().resolveAuthor(hex)
                                            }
                                        },
                                        onSeeAllReactions = { noteId ->
                                            val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[noteId]
                                            appViewModel.storeReactionsData(buildReactionsData(noteId, counts, contentNote?.repostedByAuthors ?: emptyList()))
                                            navController.navigate("reactions/$noteId") { launchSingleTop = true }
                                        },
                                        onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                                        threadDrafts = run {
                                            val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                                            remember(draftsList, displayNote.id) {
                                                social.mycelium.android.repository.DraftsRepository.replyDraftsForThread(displayNote.id)
                                            }
                                        },
                                        onEditDraft = { draft ->
                                            val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                            when (draft.type) {
                                                social.mycelium.android.data.DraftType.REPLY_KIND1 ->
                                                    navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1&draftId=${enc(draft.id)}")
                                                social.mycelium.android.data.DraftType.TOPIC_REPLY ->
                                                    navController.navigate("compose_topic_reply/${enc(draft.rootId)}?draftId=${enc(draft.id)}")
                                                else ->
                                                    navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1111&draftId=${enc(draft.id)}")
                                            }
                                        },
                                        onDeleteDraft = { draftId -> social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Onboarding — post-login relay initialization flow
                composable("onboarding") {
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val hexPubkey = currentAccount?.toHexKey() ?: ""

                    // Consume-and-clear: read returned indexer URLs once, then remove
                    // from savedStateHandle so they don't persist across recompositions.
                    // This eliminates the race between getStateFlow default emission
                    // and the actual value that caused selections to revert.
                    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
                    val returnedIndexers = remember {
                        val urls = savedStateHandle?.get<ArrayList<String>>("selected_indexers")
                        if (urls != null) {
                            savedStateHandle.remove<ArrayList<String>>("selected_indexers")
                            android.util.Log.d("OnboardingNav", "Consumed ${urls.size} returned indexers: ${urls.take(3)}")
                        }
                        mutableStateOf(urls?.toList() ?: emptyList())
                    }

                    OnboardingScreen(
                        hexPubkey = hexPubkey,
                        onComplete = {
                            accountStateViewModel.setOnboardingComplete(true)
                            navController.navigate("dashboard") {
                                popUpTo("onboarding") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onOpenRelayDiscovery = {
                            navController.navigate("relay_discovery")
                        },
                        onOpenRelayManager = { outboxUrls, inboxUrls ->
                            val outboxEncoded = android.net.Uri.encode(outboxUrls.joinToString(","))
                            val inboxEncoded = android.net.Uri.encode(inboxUrls.joinToString(","))
                            navController.navigate("relays?tab=outbox&outbox=$outboxEncoded&inbox=$inboxEncoded")
                        },
                        onOpenRelayDiscoverySelection = { preSelectedUrls ->
                            val encoded = android.net.Uri.encode(preSelectedUrls.joinToString(","))
                            navController.navigate("relay_discovery?selection=true&prefill=$encoded")
                        },
                        returnedIndexerUrls = returnedIndexers.value,
                        onRelayLogClick = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded")
                        }
                    )
                }

                // Thread view - Can navigate to profiles and other threads (from notifications, topics, profile)
                composable(
                        route = "thread/{noteId}?replyKind={replyKind}&highlightReplyId={highlightReplyId}",
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("replyKind") {
                                type = NavType.IntType
                                defaultValue = 1 // Default to Kind 1 (home feed)
                            },
                            navArgument("highlightReplyId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        ),
                        enterTransition = {
                            slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            )
                        },
                        exitTransition = { null },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            )
                        }
                ) { backStackEntry ->
                    val noteId = backStackEntry.arguments?.getString("noteId") ?: return@composable
                    val replyKind = backStackEntry.arguments?.getInt("replyKind") ?: 1
                    val highlightReplyId = backStackEntry.arguments?.getString("highlightReplyId")
                    val context = LocalContext.current

                    // Get note from AppViewModel: try notesById map first (supports stacked threads),
                    // then fall back to selectedNote (legacy callers like notifications)
                    val appState by appViewModel.appState.collectAsState()
                    val rawNote = appState.notesById[noteId]
                        ?: appState.selectedNote?.takeIf { it.id == noteId }

                    // ── Recursive root walk-up ──────────────────────────────────
                    // For kind-1 threads, the note we received might be a reply deep
                    // in a chain. We walk up rootNoteId pointers until we find the
                    // TRUE root (a note that is not itself a reply). This mirrors
                    // kind-11/1111 where the uppercase "E" tag always points to the
                    // topic root unambiguously.
                    // Max hops to prevent infinite loops on circular e-tag references.
                    val MAX_ROOT_WALK_HOPS = 10

                    var resolvedRootNote by remember(noteId) { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                    var rootWalkFailed by remember(noteId) { mutableStateOf(false) }
                    // The reply the user originally tapped — we'll scroll to this
                    var resolvedHighlightReplyId by remember(noteId) { mutableStateOf(highlightReplyId) }
                    val currentAccountForRoot by accountStateViewModel.currentAccount.collectAsState()

                    LaunchedEffect(rawNote?.id, rawNote?.rootNoteId) {
                        if (rawNote == null) return@LaunchedEffect

                        // Build user's configured relay URLs once
                        val pubkey = currentAccountForRoot?.toHexKey()
                        val userRelayUrls = if (pubkey != null) {
                            val sm = social.mycelium.android.repository.RelayStorageManager(context)
                            sm.loadCategories(pubkey).flatMap { it.relays }.map { it.url }.distinct()
                        } else emptyList()

                        // Extract relay hints from e-tags: ["e", id, relay_hint, marker]
                        // These tell us where the referenced event was seen — most reliable source.
                        fun extractRelayHints(note: social.mycelium.android.data.Note, targetId: String): List<String> {
                            return note.tags.filter { tag ->
                                tag.size >= 3 && tag[0] == "e" && tag[1] == targetId && tag[2].isNotBlank()
                            }.mapNotNull { it.getOrNull(2) }
                        }

                        // Helper: fetch a note by id from cache or relays (expanded strategy)
                        suspend fun fetchNote(id: String, hints: List<String> = emptyList(), authorPubkey: String? = null): social.mycelium.android.data.Note? {
                            // Check ViewModel store first
                            appState.notesById[id]?.let { return it }
                            if (appState.selectedNote?.id == id) return appState.selectedNote
                            // Cache
                            NotesRepository.getInstance().getNoteFromCache(id)?.let { return it }
                            // Expanded relay fetch: hints → author outbox → user relays → indexers
                            return NotesRepository.getInstance().fetchNoteByIdExpanded(
                                noteId = id,
                                userRelayUrls = userRelayUrls,
                                relayHints = hints,
                                authorPubkey = authorPubkey
                            )
                        }

                        // If rawNote is not a reply, it IS the root — done
                        val firstRootId = rawNote.rootNoteId?.takeIf { it != rawNote.id && it.isNotBlank() }
                        if (firstRootId == null) {
                            resolvedRootNote = rawNote
                            return@LaunchedEffect
                        }

                        // rawNote is a reply — set it as the highlight target
                        if (resolvedHighlightReplyId == null) {
                            resolvedHighlightReplyId = rawNote.id
                        }

                        // Walk up the chain: fetch rootNoteId, check if THAT is also
                        // a reply, keep going until we find the true root.
                        var currentId: String = firstRootId
                        var currentNote: social.mycelium.android.data.Note = rawNote
                        val visited = mutableSetOf<String>(rawNote.id) // cycle detection
                        var hops = 0
                        var trueRoot: social.mycelium.android.data.Note? = null
                        // Track the deepest successfully fetched note so we can use it
                        // as fallback root when the chain leads to an unfetchable event
                        // (e.g. kind-1111 reply to an unknown event type).
                        var deepestFetched: social.mycelium.android.data.Note? = null

                        while (hops < MAX_ROOT_WALK_HOPS) {
                            hops++
                            if (currentId in visited) break // cycle
                            visited.add(currentId)

                            // Extract relay hints from the current note's e-tags for the target ID
                            val hints = extractRelayHints(currentNote, currentId) +
                                currentNote.relayUrls // also try relays where the current note was seen
                            val fetched = fetchNote(currentId, hints, currentNote.author.id)
                            if (fetched == null) {
                                android.util.Log.d("ThreadView", "Root walk-up: failed to fetch ${currentId.take(8)} (hop $hops, hints=${hints.size})")
                                break
                            }
                            deepestFetched = fetched

                            val nextRootId = fetched.rootNoteId?.takeIf { it != fetched.id && it.isNotBlank() }
                            if (nextRootId == null) {
                                // This note is NOT a reply — it's the true root!
                                trueRoot = fetched
                                break
                            }

                            // This note is also a reply — keep walking up
                            currentNote = fetched
                            currentId = nextRootId
                        }

                        // Use true root if found, otherwise the deepest note we could
                        // reach in the chain, otherwise fall back to rawNote.
                        val bestRoot = trueRoot ?: deepestFetched
                        if (bestRoot != null) {
                            resolvedRootNote = bestRoot
                            appViewModel.storeNoteForThread(bestRoot)
                            if (trueRoot == null) {
                                android.util.Log.d("ThreadView", "Root walk-up: true root not found, using deepest fetched ${bestRoot.id.take(8)} (kind=${bestRoot.kind})")
                            }
                        } else {
                            // Couldn't fetch anything — fall back to rawNote
                            rootWalkFailed = true
                        }
                    }

                    // Determine the note to display as thread root
                    val note: social.mycelium.android.data.Note? = resolvedRootNote

                    if (note == null) {
                        // Show loading while waiting for the root walk to complete
                        var waitElapsed by remember { mutableStateOf(0) }
                        LaunchedEffect(noteId, rootWalkFailed) {
                            if (rootWalkFailed) return@LaunchedEffect
                            while (waitElapsed < 10) {
                                kotlinx.coroutines.delay(1000)
                                waitElapsed++
                            }
                            rootWalkFailed = true
                        }
                        if (rootWalkFailed && rawNote != null) {
                            // Fallback: show the reply note as root — better than nothing
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Loading thread…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            return@composable
                        }
                    }
                    // Final resolved note: either the true root, or fallback to rawNote
                    val resolvedNote = note ?: rawNote!!

                    val authState by accountStateViewModel.authState.collectAsState()

                    // Get relay URLs for thread replies: use feed's relays when opened from topics/dashboard, else default category
                    val storageManager = remember { social.mycelium.android.repository.RelayStorageManager(context) }
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val fallbackRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            val subscribedRelays = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }.map { it.url }.distinct()
                            subscribedRelays.ifEmpty {
                                val defaultCat = categories.firstOrNull { it.isDefault }
                                defaultCat?.relays?.map { it.url } ?: emptyList()
                            }
                        } ?: emptyList()
                    }
                    val threadRelayUrls = appState.threadRelayUrls
                    val cacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    // Fetch outbox relays for the thread note's author so replies are discoverable
                    // even when the note came from a quoted embed with minimal relay info
                    var authorOutboxRelays by remember(resolvedNote.author.id) { mutableStateOf<List<String>>(emptyList()) }
                    LaunchedEffect(resolvedNote.author.id, cacheRelayUrls) {
                        val authorPubkey = resolvedNote.author.id
                        if (authorPubkey.isNotBlank()) {
                            val discoveryRelays = cacheRelayUrls
                            social.mycelium.android.repository.Nip65RelayListRepository.fetchOutboxRelaysForAuthor(authorPubkey, discoveryRelays)
                            // Poll for cached result (async fetch)
                            repeat(8) {
                                val cached = social.mycelium.android.repository.Nip65RelayListRepository.getCachedOutboxRelays(authorPubkey)
                                if (cached != null && cached.isNotEmpty()) {
                                    authorOutboxRelays = cached
                                    return@LaunchedEffect
                                }
                                kotlinx.coroutines.delay(500)
                            }
                        }
                    }

                    val relayUrls = remember(threadRelayUrls, fallbackRelayUrls, authorOutboxRelays) {
                        ((threadRelayUrls ?: emptyList()) + fallbackRelayUrls + authorOutboxRelays).distinct()
                    }

                    // Live reply list is driven by repliesState (Kind1RepliesViewModel / ThreadRepliesViewModel).
                    val sampleComments = emptyList<social.mycelium.android.ui.screens.CommentThread>()

                        // Restore scroll state for this specific thread
                        val savedScrollState = threadStateHolder.getScrollState(noteId)
                        val threadListState =
                                rememberLazyListState(
                                        initialFirstVisibleItemIndex =
                                                savedScrollState.firstVisibleItemIndex,
                                        initialFirstVisibleItemScrollOffset =
                                                savedScrollState.firstVisibleItemScrollOffset
                                )

                        // Get comment states for this specific thread
                        val commentStates = threadStateHolder.getCommentStates(noteId)
                        var expandedControlsCommentId by remember {
                            mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                        }
                        var expandedControlsReplyId by remember {
                            mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                        }

                        // Share global TopAppBarState so header collapsed/expanded state
                        // persists across navigation (edge-to-edge consistency)

                        // Save scroll state when leaving the screen
                        DisposableEffect(noteId) {
                            onDispose {
                                threadStateHolder.saveScrollState(noteId, threadListState)
                                threadStateHolder.setExpandedControls(
                                        noteId,
                                        expandedControlsCommentId
                                )
                                threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                            }
                        }

                    ThreadSlideBackBox(onBack = { navController.popBackStack() }) {
                    ModernThreadViewScreen(
                            note = resolvedNote,
                            comments = sampleComments, // preview-only; live content from repliesState
                            listState = threadListState,
                            commentStates = commentStates,
                            expandedControlsCommentId = expandedControlsCommentId,
                            onExpandedControlsChange = { commentId ->
                                expandedControlsCommentId =
                                        if (expandedControlsCommentId == commentId) null
                                        else commentId
                            },
                            expandedControlsReplyId = expandedControlsReplyId,
                            onExpandedControlsReplyChange = { replyId ->
                                expandedControlsReplyId =
                                        if (expandedControlsReplyId == replyId) null
                                        else replyId
                            },
                            topAppBarState = topAppBarState,
                            replyKind = replyKind,
                            highlightReplyId = resolvedHighlightReplyId,
                            relayUrls = relayUrls,
                            cacheRelayUrls = cacheRelayUrls,
                            onBackClick = { navController.popBackStack() },
                            onLike = { noteId ->
                                val targetNote = if (resolvedNote.id == noteId) resolvedNote else null
                                if (targetNote != null) {
                                    val err = accountStateViewModel.sendReaction(targetNote, "+")
                                    if (err != null) showSnackbar(err)
                                }
                            },
                            onShare = { /* TODO: Handle share */},
                            onComment = { noteId ->
                                // Open reply compose for the tapped note
                                appViewModel.setReplyToNote(null)
                                val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                navController.navigate("reply_compose?rootId=${enc(resolvedNote.id)}&rootPubkey=${enc(resolvedNote.author.id)}&replyKind=1")
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onNoteClick = { clickedNote ->
                                val threadNote = if (clickedNote.originalNoteId != null) clickedNote.copy(id = clickedNote.originalNoteId) else clickedNote
                                if (threadNote.isReply && threadNote.rootNoteId != null) {
                                    appViewModel.storeNoteForThread(threadNote)
                                    navController.navigate("thread/${threadNote.rootNoteId}?replyKind=1&highlightReplyId=${threadNote.id}")
                                } else {
                                    appViewModel.storeNoteForThread(threadNote)
                                    navController.navigate("thread/${threadNote.id}?replyKind=1")
                                }
                            },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) showSnackbar(error)
                            },
                            onBoost = { n ->
                                val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                                if (err != null) showSnackbar(err)
                            },
                            onQuote = { n ->
                                val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                                val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                                navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                            },
                            onFork = { n ->
                                val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                                navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) showSnackbar(err)
                            },
                            onZap = { nId, amount ->
                                if (resolvedNote.id == nId) {
                                    val err = accountStateViewModel.sendZap(resolvedNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) showSnackbar(err)
                                }
                            },
                            onSendZap = { targetNote, amount ->
                                val err = accountStateViewModel.sendZap(targetNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                if (err != null) showSnackbar(err)
                            },
                            zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                            zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                            myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                            boostedNoteIds = accountStateViewModel.boostedNoteIds.collectAsState().value,
                            onVote = { noteId, authorPubkey, direction ->
                                accountStateViewModel.sendVote(noteId, authorPubkey, direction, replyKind)
                            },
                            onCommentLike = { /* TODO: Handle comment like */},
                            onCommentReply = { /* Handled inside ModernThreadViewScreen via effectiveOnCommentReply */},
                            onPublishThreadReply = when (replyKind) {
                                1111 -> { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                    accountStateViewModel.publishThreadReply(rootId, rootPubkey, parentId, parentPubkey, content)
                                } }
                                1 -> { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                    accountStateViewModel.publishKind1Reply(rootId, rootPubkey, parentId, parentPubkey, content)
                                } }
                                else -> null
                            },
                            onOpenReplyCompose = { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                appViewModel.setReplyToNote(replyToNote)
                                val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}&replyKind=$replyKind")
                            },
                            onLoginClick = {
                                val loginIntent = accountStateViewModel.loginWithAmber()
                                onAmberLogin(loginIntent)
                            },
                            isGuest = authState.isGuest,
                            userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                            userAvatarUrl = authState.userProfile?.picture,
                            accountNpub = currentAccount?.npub,
                            currentUserAuthor = remember(currentAccount) {
                                currentAccount?.toHexKey()?.let { hex ->
                                    ProfileMetadataCache.getInstance().resolveAuthor(hex)
                                }
                            },
                            onHeaderProfileClick = {
                                authState.userProfile?.pubkey?.let { pubkey ->
                                    navController.navigateToProfile(pubkey)
                                }
                            },
                            onHeaderAccountsClick = { },
                            onHeaderQrCodeClick = { navController.navigate("user_qr") },
                            onHeaderSettingsClick = { navController.navigate("settings") },
                            mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                            onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                            onRelayNavigate = { relayUrl ->
                                val encoded = android.net.Uri.encode(relayUrl)
                                navController.navigate("relay_log/$encoded")
                            },
                            onNavigateToRelayList = { urls ->
                                val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                            },
                            onSeeAllReactions = { nId ->
                                val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[nId]
                                appViewModel.storeReactionsData(buildReactionsData(nId, counts, resolvedNote.repostedByAuthors))
                                navController.navigate("reactions/$nId") { launchSingleTop = true }
                            },
                            onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                            threadDrafts = run {
                                val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                                remember(draftsList, resolvedNote.id) {
                                    social.mycelium.android.repository.DraftsRepository.replyDraftsForThread(resolvedNote.id)
                                }
                            },
                            onEditDraft = { draft ->
                                val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                when (draft.type) {
                                    social.mycelium.android.data.DraftType.REPLY_KIND1 ->
                                        navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1&draftId=${enc(draft.id)}")
                                    social.mycelium.android.data.DraftType.TOPIC_REPLY ->
                                        navController.navigate("compose_topic_reply/${enc(draft.rootId)}?draftId=${enc(draft.id)}")
                                    else ->
                                        navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1111&draftId=${enc(draft.id)}")
                                }
                            },
                            onDeleteDraft = { draftId -> social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId) }
                    )
                    }
                }

                // User QR code (npub) — from header profile menu
                composable("user_qr") {
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    QrCodeScreen(
                        npub = currentAccount?.npub,
                        onBack = { navController.popBackStack() }
                    )
                }

                // Full-screen image viewer (Save, HD, back) — smooth fade back
                composable(
                    "image_viewer",
                    enterTransition = { fadeIn(animationSpec = tween(150)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { fadeOut(animationSpec = tween(200)) }
                ) {
                    val appState by appViewModel.appState.collectAsState()
                    // Remember URLs locally so they survive clearImageViewer() during exit animation
                    val urls = appState.imageViewerUrls
                    var rememberedUrls by remember { mutableStateOf(urls) }
                    var rememberedIndex by remember { mutableStateOf(appState.imageViewerInitialIndex) }
                    if (urls != null) {
                        rememberedUrls = urls
                        rememberedIndex = appState.imageViewerInitialIndex
                    }
                    val displayUrls = rememberedUrls
                    if (displayUrls != null) {
                        val selectedNoteId = appState.selectedNote?.id
                        ImageContentViewerScreen(
                            urls = displayUrls,
                            initialIndex = rememberedIndex,
                            onBackClick = {
                                navController.popBackStack()
                                appViewModel.clearImageViewer()
                            },
                            onPageChanged = { page ->
                                if (selectedNoteId != null) {
                                    appViewModel.updateMediaPage(selectedNoteId, page)
                                }
                            }
                        )
                    }
                }

                // Full-screen video viewer — smooth fade back
                composable(
                    "video_viewer",
                    enterTransition = { fadeIn(animationSpec = tween(150)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { fadeOut(animationSpec = tween(200)) }
                ) {
                    // Kill PiP when any media goes fullscreen
                    LaunchedEffect(Unit) { PipStreamManager.kill() }
                    val appState by appViewModel.appState.collectAsState()
                    // Remember URLs locally so they survive clearVideoViewer() during exit animation
                    val urls = appState.videoViewerUrls
                    var rememberedUrls by remember { mutableStateOf(urls) }
                    var rememberedIndex by remember { mutableStateOf(appState.videoViewerInitialIndex) }
                    var rememberedInstanceKey by remember { mutableStateOf(appState.videoViewerInstanceKey) }
                    if (urls != null) {
                        rememberedUrls = urls
                        rememberedIndex = appState.videoViewerInitialIndex
                        rememberedInstanceKey = appState.videoViewerInstanceKey
                    }
                    val displayUrls = rememberedUrls
                    if (displayUrls != null) {
                        VideoContentViewerScreen(
                            urls = displayUrls,
                            initialIndex = rememberedIndex,
                            onBackClick = {
                                navController.popBackStack()
                                appViewModel.clearVideoViewer()
                            },
                            instanceKey = rememberedInstanceKey
                        )
                    }
                }

                // Zap settings — singleton page for configuring zap amounts
                composable("zap_settings") {
                    social.mycelium.android.ui.screens.ZapSettingsScreen(
                        onBackClick = { navController.popBackStack() }
                    )
                }

                // NIP-53 Live broadcast explorer
                composable(route = "live_explorer") {
                    LiveExplorerScreen(
                        onBackClick = { navController.popBackStack() },
                        onActivityClick = { addressableId ->
                            navController.navigate("live_stream/$addressableId") {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                // NIP-53 Live Stream viewer
                composable(
                    route = "live_stream/{addressableId}",
                    arguments = listOf(navArgument("addressableId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val addressableId = backStackEntry.arguments?.getString("addressableId") ?: return@composable
                    LiveStreamScreen(
                        activityAddressableId = addressableId,
                        onBackClick = { navController.popBackStack() },
                        onProfileClick = { authorId -> navController.navigateToProfile(authorId) },
                        onRelayNavigate = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded")
                        }
                    )
                }

                // Profile view - Can navigate to threads and other profiles
                // No per-route transitions: NavHost direction-aware system handles tab animations.
                composable(
                        route = "profile/{authorId}",
                        arguments = listOf(navArgument("authorId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val authorId =
                            backStackEntry.arguments?.getString("authorId") ?: return@composable

                    val dashboardViewModel: DashboardViewModel = viewModel()
                    val dashboardState by dashboardViewModel.uiState.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val storageManager = remember(context) { RelayStorageManager(context) }
                    val cacheUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    // Resolve author from profile cache (kind-0); use normalized hex for lookup so npub/hex both match
                    val profileCache = ProfileMetadataCache.getInstance()
                    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
                    var author by remember(authorId) {
                        mutableStateOf(profileCache.resolveAuthor(cacheKey))
                    }
                    LaunchedEffect(authorId) {
                        if (profileCache.getAuthor(cacheKey) == null && cacheUrls.isNotEmpty()) {
                            profileCache.requestProfiles(listOf(cacheKey), cacheUrls)
                        }
                    }
                    LaunchedEffect(authorId) {
                        profileCache.profileUpdated
                            .filter { it == cacheKey }
                            .collect { profileCache.getAuthor(cacheKey)?.let { a -> author = a } }
                    }

                    val authorIdLower = remember(author.id) { author.id.lowercase() }
                    // Dashboard notes for this author (already in home feed cache)
                    val dashboardAuthorNotes = remember(dashboardState.notes, authorIdLower) {
                        dashboardState.notes.filter { it.author.id.lowercase() == authorIdLower }
                    }
                    // Dedicated profile feed subscription (independent of home feed)
                    // Start with current user's relays, then merge viewed profile's NIP-65 outbox relays
                    val userRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            val categoryUrls = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }
                                .map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
                            val indexerUrls = storageManager.loadIndexerRelays(pubkey).map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
                            (categoryUrls + indexerUrls).distinct()
                        } ?: emptyList()
                    }
                    // Fetch the viewed profile's NIP-65 outbox (write) relays
                    var authorOutboxRelays by remember(cacheKey) { mutableStateOf<List<String>>(emptyList()) }
                    LaunchedEffect(cacheKey, cacheUrls) {
                        if (cacheKey.isNotBlank()) {
                            val discoveryRelays = cacheUrls.ifEmpty { userRelayUrls }
                            if (discoveryRelays.isNotEmpty()) {
                                social.mycelium.android.repository.Nip65RelayListRepository.fetchOutboxRelaysForAuthor(cacheKey, discoveryRelays)
                                // Poll for cached result (async batch fetch)
                                repeat(10) {
                                    val cached = social.mycelium.android.repository.Nip65RelayListRepository.getCachedOutboxRelays(cacheKey)
                                    if (cached != null && cached.isNotEmpty()) {
                                        authorOutboxRelays = cached
                                        return@LaunchedEffect
                                    }
                                    kotlinx.coroutines.delay(400)
                                }
                            }
                        }
                    }
                    // Merge: user's relays + viewed profile's outbox relays for comprehensive coverage
                    val profileRelayUrls = remember(userRelayUrls, authorOutboxRelays) {
                        (userRelayUrls + authorOutboxRelays.map { social.mycelium.android.utils.normalizeRelayUrl(it) }).distinct()
                    }
                    // Use static cache so the repo (and its notes) survives composable lifecycle
                    // stops during Navigation Compose transitions (profile → image_viewer → back).
                    val profileFeedRepo = remember(cacheKey, profileRelayUrls) {
                        social.mycelium.android.repository.ProfileFeedRepository.getOrCreate(cacheKey, profileRelayUrls)
                    }
                    val profileFeedNotes by profileFeedRepo.notes.collectAsState()
                    val profileIsLoading by profileFeedRepo.isLoading.collectAsState()
                    val profileIsLoadingMore by profileFeedRepo.isLoadingMore.collectAsState()
                    val profileHasMore by profileFeedRepo.hasMore.collectAsState()
                    // Start or resume subscription based on current state.
                    // pause() on dispose keeps notes alive across lifecycle stops.
                    DisposableEffect(profileFeedRepo) {
                        if (profileFeedRepo.notes.value.isEmpty()) {
                            profileFeedRepo.start()
                        } else {
                            profileFeedRepo.resume()
                        }
                        onDispose { profileFeedRepo.pause() }
                    }
                    // Evict from static cache when the back-stack entry is truly destroyed
                    // (user navigated back from profile). We observe the NavBackStackEntry lifecycle
                    // directly — ON_DESTROY only fires when the entry is popped, not on STOPPED.
                    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    val evictKey = cacheKey // capture for lambda
                    DisposableEffect(lifecycleOwner, evictKey) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                                social.mycelium.android.repository.ProfileFeedRepository.evict(evictKey)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    // Merge: dashboard notes (richer: relay URLs, counts, repost info) take priority
                    val authorNotes = remember(profileFeedNotes, dashboardAuthorNotes) {
                        (dashboardAuthorNotes + profileFeedNotes)
                            .distinctBy { it.id }
                            .sortedByDescending { it.repostTimestamp ?: it.timestamp }
                    }
                    // Fetch profile counts (following/followers) from indexer relays
                    LaunchedEffect(cacheKey, profileRelayUrls) {
                        social.mycelium.android.repository.ProfileCountsRepository.fetchCounts(cacheKey, profileRelayUrls)
                    }
                    val allProfileCounts by social.mycelium.android.repository.ProfileCountsRepository.countsMap.collectAsState()
                    val profileCounts = allProfileCounts[cacheKey]

                    // ── Badge fetching (NIP-58) ──
                    val profileBadges by remember(cacheKey, profileRelayUrls) {
                        social.mycelium.android.repository.BadgeRepository.badgesFor(cacheKey, profileRelayUrls)
                    }.collectAsState()

                    // ── Parent note fetching for reply context (batch via indexer relays) ──
                    val parentNotesMap = remember { androidx.compose.runtime.mutableStateMapOf<String, social.mycelium.android.data.Note>() }
                    LaunchedEffect(authorNotes) {
                        val replies = authorNotes.filter { it.isReply && it.replyToId != null }
                        val missingIds = replies.mapNotNull { it.replyToId }
                            .distinct()
                            .filter { id -> id !in parentNotesMap }
                        if (missingIds.isEmpty()) return@LaunchedEffect
                        val notesRepo = NotesRepository.getInstance()
                        // Resolve from local cache first
                        val stillMissing = mutableListOf<String>()
                        for (parentId in missingIds) {
                            val cached = notesRepo.getNoteFromCache(parentId)
                            if (cached != null) {
                                parentNotesMap[parentId] = cached
                            } else {
                                stillMissing.add(parentId)
                            }
                        }
                        // Batch-fetch remaining from indexer relays in a single subscription
                        if (stillMissing.isNotEmpty()) {
                            val batchResults = notesRepo.fetchNotesByIdsBatch(
                                noteIds = stillMissing,
                                userRelayUrls = profileRelayUrls
                            )
                            parentNotesMap.putAll(batchResults)
                        }
                    }

                    val profileTimeGapIndex by profileFeedRepo.timeGapIndex.collectAsState()
                    val profilePerTabHasMore by profileFeedRepo.perTabHasMore.collectAsState()
                    val followList = dashboardState.followList
                    val followSetLower = remember(followList) { followList.map { it.lowercase() }.toSet() }
                    val isFollowing = followSetLower.isNotEmpty() && authorIdLower in followSetLower
                    // Relay orb tap navigates to relay_log page via onRelayClick callback
                    val zapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val zappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val zappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = authorNotes,
                            isProfileLoading = profileIsLoading,
                            isLoadingMore = profileIsLoadingMore,
                            hasMore = profileHasMore,
                            onLoadMore = { tab -> profileFeedRepo.loadMore(tab) },
                            followingCount = profileCounts?.followingCount,
                            followerCount = profileCounts?.followerCount,
                            isLoadingCounts = profileCounts?.isLoadingFollowing == true || profileCounts?.isLoadingFollowers == true,
                            timeGapIndex = profileTimeGapIndex,
                            perTabHasMore = profilePerTabHasMore,
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) })
                                if (note.isReply && note.rootNoteId != null) {
                                    // Reply: navigate to full thread with root walk-up, highlighting this reply
                                    navController.navigate("thread/${note.rootNoteId}?replyKind=1&highlightReplyId=${note.id}")
                                } else {
                                    // Root note: show overlay thread on profile
                                    overlayProfileThreadStack.clear()
                                    overlayProfileThreadHighlightIds.clear()
                                    overlayProfileThreadStack.add(note)
                                    overlayProfileThreadHighlightIds.add(null)
                                }
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) showSnackbar(error)
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) showSnackbar(err)
                            },
                            onZap = { noteId, amount ->
                                val n = authorNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) showSnackbar(err)
                                }
                            },
                            isZapInProgress = { id -> id in zapInProgressIds },
                            isZapped = { id -> id in zappedIds },
                            myZappedAmountForNote = { id -> zappedAmountByNoteId[id] },
                            overrideReplyCountForNote = { id -> replyCountByNoteId[id] },
                            countsForNote = { id -> noteCountsByNoteId[id] },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onProfileClick = { newAuthorId ->
                                navController.navigateToProfile(newAuthorId)
                            },
                            onRelayClick = { relayUrl ->
                                val encoded = android.net.Uri.encode(relayUrl)
                                navController.navigate("relay_log/$encoded")
                            },
                            onNavigateTo = { /* Not needed with NavController */ },
                            accountNpub = currentAccount?.npub,
                            isFollowing = isFollowing,
                            onFollowClick = {
                                val targetHex = normalizeAuthorIdForCache(author.id)
                                val error = if (isFollowing) {
                                    accountStateViewModel.unfollowUser(targetHex)
                                } else {
                                    accountStateViewModel.followUser(targetHex)
                                }
                                if (error != null) {
                                    showSnackbar(error)
                                } else {
                                    // follow/unfollow emits via followListUpdates flow — no forceRefresh needed.
                                    // Invalidate profile counts so following/follower numbers refresh.
                                    currentAccount?.toHexKey()?.let { pubkey ->
                                        social.mycelium.android.repository.ProfileCountsRepository.invalidate(pubkey)
                                        social.mycelium.android.repository.ProfileCountsRepository.invalidate(targetHex)
                                    }
                                }
                            },
                            parentNoteForReply = { parentId -> parentNotesMap[parentId] },
                            onSeeAllReactions = { note ->
                                val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[note.id]
                                appViewModel.storeReactionsData(buildReactionsData(note.id, counts, note.repostedByAuthors, note.reactions))
                                navController.navigate("reactions/${note.id}") { launchSingleTop = true }
                            },
                            badges = profileBadges
                    )

                    // ── Profile thread overlay (stack-based) ──────────────────────────
                    BackHandler(enabled = overlayProfileThreadStack.isNotEmpty()) {
                        overlayProfileThreadStack.removeLastOrNull()
                        overlayProfileThreadHighlightIds.removeLastOrNull()
                    }

                    val profileContentNote = overlayProfileThreadStack.lastOrNull()
                    var lastProfileOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                    if (profileContentNote != null) lastProfileOverlayNote = profileContentNote
                    val profileDisplayNote: social.mycelium.android.data.Note? = profileContentNote ?: lastProfileOverlayNote
                    val showProfileOverlay = profileContentNote != null
                    AnimatedVisibility(
                        visible = showProfileOverlay,
                        enter = slideInHorizontally(animationSpec = tween(300)) { it },
                        exit = slideOutHorizontally(animationSpec = tween(300)) { it }
                    ) {
                        if (profileDisplayNote != null) {
                            val profileThreadRelayUrls = profileDisplayNote.relayUrls.ifEmpty { listOfNotNull(profileDisplayNote.relayUrl) }.ifEmpty { profileRelayUrls }
                            val pNoteId = profileDisplayNote.id
                            val profileThreadListState = rememberLazyListState()
                            var profileExpandedControlsCommentId by remember(pNoteId) { mutableStateOf<String?>(null) }
                            var profileExpandedControlsReplyId by remember(pNoteId) { mutableStateOf<String?>(null) }
                            ThreadSlideBackBox(onBack = { overlayProfileThreadStack.removeLastOrNull(); overlayProfileThreadHighlightIds.removeLastOrNull() }) {
                                ModernThreadViewScreen(
                                    note = profileDisplayNote,
                                    comments = emptyList(),
                                    listState = profileThreadListState,
                                    expandedControlsCommentId = profileExpandedControlsCommentId,
                                    onExpandedControlsChange = { profileExpandedControlsCommentId = if (profileExpandedControlsCommentId == it) null else it },
                                    expandedControlsReplyId = profileExpandedControlsReplyId,
                                    onExpandedControlsReplyChange = { replyId ->
                                        profileExpandedControlsReplyId = if (profileExpandedControlsReplyId == replyId) null else replyId
                                    },
                                    topAppBarState = topAppBarState,
                                    replyKind = 1,
                                    highlightReplyId = overlayProfileThreadHighlightIds.lastOrNull(),
                                    relayUrls = profileThreadRelayUrls,
                                    cacheRelayUrls = cacheUrls,
                                    onBackClick = { overlayProfileThreadStack.removeLastOrNull(); overlayProfileThreadHighlightIds.removeLastOrNull() },
                                    onProfileClick = { navController.navigateToProfile(it) },
                                    onNoteClick = { clickedNote ->
                                        val threadNote = if (clickedNote.originalNoteId != null) clickedNote.copy(id = clickedNote.originalNoteId) else clickedNote
                                        if (threadNote.isReply && threadNote.rootNoteId != null) {
                                            val rootId = threadNote.rootNoteId!!
                                            val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                            val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote, profileThreadRelayUrls)
                                            overlayProfileThreadStack.add(overlayNote)
                                            overlayProfileThreadHighlightIds.add(threadNote.id)
                                            if (rootFromCache == null) {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                        noteId = rootId,
                                                        userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) }.ifEmpty { profileThreadRelayUrls } +
                                                            NotesRepository.getInstance().INDEXER_RELAYS,
                                                        authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                                    )
                                                    if (fetched != null) {
                                                        withContext(Dispatchers.Main.immediate) {
                                                            val idx = overlayProfileThreadStack.indexOfFirst { it.id == rootId }
                                                            if (idx >= 0) overlayProfileThreadStack[idx] = fetched
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            overlayProfileThreadStack.add(threadNote)
                                            overlayProfileThreadHighlightIds.add(null)
                                        }
                                    },
                                    onImageTap = { _, urls, idx ->
                                        appViewModel.openImageViewer(urls, idx)
                                        navController.navigate("image_viewer") { launchSingleTop = true }
                                    },
                                    onOpenImageViewer = { urls, idx ->
                                        appViewModel.openImageViewer(urls, idx)
                                        navController.navigate("image_viewer") { launchSingleTop = true }
                                    },
                                    onVideoClick = { urls, idx ->
                                        appViewModel.openVideoViewer(urls, idx)
                                        navController.navigate("video_viewer") { launchSingleTop = true }
                                    },
                                    onReact = { reactNote, emoji ->
                                        val error = accountStateViewModel.sendReaction(reactNote, emoji)
                                        if (error != null) showSnackbar(error)
                                    },
                                    onBoost = { n ->
                                        val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                                        if (err != null) showSnackbar(err)
                                    },
                                    onQuote = { n ->
                                        val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                                        val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                                        navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                    },
                                    onFork = { n ->
                                        val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                                        navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                    },
                                    onCustomZapSend = { zapNote, amount, zapType, msg ->
                                        val err = accountStateViewModel.sendZap(zapNote, amount, zapType, msg)
                                        if (err != null) showSnackbar(err)
                                    },
                                    onZap = { nId, amount ->
                                        if (profileDisplayNote.id == nId) {
                                            val err = accountStateViewModel.sendZap(profileDisplayNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                            if (err != null) showSnackbar(err)
                                        }
                                    },
                                    zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                    zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                    myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                                    boostedNoteIds = accountStateViewModel.boostedNoteIds.collectAsState().value,
                                    onPublishThreadReply = { rootId, rootPubkey, parentId, parentPubkey, content ->
                                        accountStateViewModel.publishKind1Reply(rootId, rootPubkey, parentId, parentPubkey, content)
                                    },
                                    onOpenReplyCompose = { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                        appViewModel.setReplyToNote(replyToNote)
                                        val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                        navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}&replyKind=1")
                                    },
                                    onLoginClick = {
                                        val loginIntent = accountStateViewModel.loginWithAmber()
                                        onAmberLogin(loginIntent)
                                    },
                                    isGuest = authState.isGuest,
                                    userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                                    userAvatarUrl = authState.userProfile?.picture,
                                    accountNpub = currentAccount?.npub,
                                    currentUserAuthor = remember(currentAccount) {
                                        currentAccount?.toHexKey()?.let { hex ->
                                            ProfileMetadataCache.getInstance().resolveAuthor(hex)
                                        }
                                    },
                                    onHeaderProfileClick = {
                                        authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                                    },
                                    onHeaderAccountsClick = { },
                                    onHeaderQrCodeClick = { navController.navigate("user_qr") { launchSingleTop = true } },
                                    onHeaderSettingsClick = { navController.navigate("settings") { launchSingleTop = true } },
                                    mediaPageForNote = { nId -> appViewModel.getMediaPage(nId) },
                                    onMediaPageChanged = { nId, page -> appViewModel.updateMediaPage(nId, page) },
                                    onRelayNavigate = { relayUrl ->
                                        val encoded = android.net.Uri.encode(relayUrl)
                                        navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                                    },
                                    onNavigateToRelayList = { urls ->
                                        val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                        navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                                    },
                                    onSeeAllReactions = { nId ->
                                        val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[nId]
                                        appViewModel.storeReactionsData(buildReactionsData(nId, counts, profileDisplayNote.repostedByAuthors))
                                        navController.navigate("reactions/$nId") { launchSingleTop = true }
                                    },
                                    onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                                    threadDrafts = run {
                                        val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                                        remember(draftsList, profileDisplayNote.id) {
                                            social.mycelium.android.repository.DraftsRepository.replyDraftsForThread(profileDisplayNote.id)
                                        }
                                    },
                                    onEditDraft = { draft ->
                                        val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                        when (draft.type) {
                                            social.mycelium.android.data.DraftType.REPLY_KIND1 ->
                                                navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1&draftId=${enc(draft.id)}")
                                            social.mycelium.android.data.DraftType.TOPIC_REPLY ->
                                                navController.navigate("compose_topic_reply/${enc(draft.rootId)}?draftId=${enc(draft.id)}")
                                            else ->
                                                navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1111&draftId=${enc(draft.id)}")
                                        }
                                    },
                                    onDeleteDraft = { draftId -> social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId) }
                                )
                            }
                        }
                    }
                }

                // User's own profile: resolve from ProfileMetadataCache so kind-0 shows when loaded
                composable("user_profile") {
                    val authState by accountStateViewModel.authState.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val navContext = LocalContext.current
                    val navStorageManager = remember(navContext) { RelayStorageManager(navContext) }
                    val currentUserPubkey = authState.userProfile?.pubkey ?: "guest"
                    val cacheUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            navStorageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    val fallbackAuthor = authState.userProfile?.let { userProfile ->
                        Author(
                            id = userProfile.pubkey,
                            username = userProfile.name ?: "user",
                            displayName = userProfile.displayName ?: userProfile.name ?: "User",
                            avatarUrl = userProfile.picture,
                            isVerified = false
                        )
                    } ?: Author(
                        id = "guest",
                        username = "guest",
                        displayName = "Guest User",
                        avatarUrl = null,
                        isVerified = false
                    )
                    val userCacheKey = remember(currentUserPubkey) {
                        if (currentUserPubkey == "guest") null else normalizeAuthorIdForCache(currentUserPubkey)
                    }
                    var author by remember(currentUserPubkey) {
                        mutableStateOf(
                            if (currentUserPubkey == "guest") fallbackAuthor
                            else ProfileMetadataCache.getInstance().resolveAuthor(userCacheKey!!)
                        )
                    }
                    LaunchedEffect(currentUserPubkey) {
                        if (userCacheKey != null && ProfileMetadataCache.getInstance().getAuthor(userCacheKey) == null && cacheUrls.isNotEmpty()) {
                            ProfileMetadataCache.getInstance().requestProfiles(listOf(userCacheKey), cacheUrls)
                        }
                    }
                    LaunchedEffect(currentUserPubkey) {
                        if (userCacheKey == null) return@LaunchedEffect
                        ProfileMetadataCache.getInstance().profileUpdated
                            .filter { it == userCacheKey }
                            .collect { ProfileMetadataCache.getInstance().getAuthor(userCacheKey)?.let { a -> author = a } }
                    }

                    // Dedicated profile feed subscription for user's own profile
                    val userProfileRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = navStorageManager.loadCategories(pubkey)
                            val categoryUrls = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }
                                .map { it.url }
                            val indexerUrls = navStorageManager.loadIndexerRelays(pubkey).map { it.url }
                            (categoryUrls + indexerUrls).distinct()
                        } ?: emptyList()
                    }
                    val userProfileFeedRepo = remember(userCacheKey, userProfileRelayUrls) {
                        if (userCacheKey != null) {
                            social.mycelium.android.repository.ProfileFeedRepository(
                                authorPubkey = userCacheKey,
                                relayUrls = userProfileRelayUrls
                            )
                        } else null
                    }
                    val userProfileFeedNotes by (userProfileFeedRepo?.notes ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
                    val userProfileIsLoading by (userProfileFeedRepo?.isLoading ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    val userProfileIsLoadingMore by (userProfileFeedRepo?.isLoadingMore ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    val userProfileHasMore by (userProfileFeedRepo?.hasMore ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
                    // Start subscription and dispose on exit
                    DisposableEffect(userProfileFeedRepo) {
                        userProfileFeedRepo?.start()
                        onDispose { userProfileFeedRepo?.dispose() }
                    }
                    // Merge: profile feed notes + dashboard notes for this author
                    val dashboardVm: DashboardViewModel = viewModel()
                    val dashState by dashboardVm.uiState.collectAsState()
                    val userIdLower = remember(userCacheKey) { userCacheKey?.lowercase() }
                    val dashboardUserNotes = remember(dashState.notes, userIdLower) {
                        if (userIdLower != null) dashState.notes.filter { it.author.id.lowercase() == userIdLower }
                        else emptyList()
                    }
                    val userNotes = remember(userProfileFeedNotes, dashboardUserNotes) {
                        (userProfileFeedNotes + dashboardUserNotes)
                            .distinctBy { it.id }
                            .sortedByDescending { it.timestamp }
                    }

                    // ── Own-profile badge fetching (NIP-58) ──
                    val ownProfileBadges by remember(userCacheKey, userProfileRelayUrls) {
                        if (userCacheKey != null) social.mycelium.android.repository.BadgeRepository.badgesFor(userCacheKey, userProfileRelayUrls)
                        else kotlinx.coroutines.flow.MutableStateFlow(emptyList<social.mycelium.android.repository.BadgeRepository.Badge>())
                    }.collectAsState()

                    val userZapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val userZappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val userZappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = userNotes,
                            isProfileLoading = userProfileIsLoading,
                            isLoadingMore = userProfileIsLoadingMore,
                            hasMore = userProfileHasMore,
                            onLoadMore = { tab -> userProfileFeedRepo?.loadMore(tab) },
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) showSnackbar(error)
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) showSnackbar(err)
                            },
                            onZap = { noteId, amount ->
                                val n = userNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) showSnackbar(err)
                                }
                            },
                            isZapInProgress = { id -> id in userZapInProgressIds },
                            isZapped = { id -> id in userZappedIds },
                            myZappedAmountForNote = { id -> userZappedAmountByNoteId[id] },
                            overrideReplyCountForNote = { id -> replyCountByNoteId[id] },
                            countsForNote = { id -> noteCountsByNoteId[id] },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onRelayClick = { relayUrl ->
                                val encoded = android.net.Uri.encode(relayUrl)
                                navController.navigate("relay_log/$encoded")
                            },
                            accountNpub = currentAccount?.npub,
                            onNavigateTo = { /* Not needed with NavController */ },
                            isFollowing = false,
                            onFollowClick = { },
                            onSeeAllReactions = { note ->
                                val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[note.id]
                                appViewModel.storeReactionsData(buildReactionsData(note.id, counts, note.repostedByAuthors, note.reactions))
                                navController.navigate("reactions/${note.id}") { launchSingleTop = true }
                            },
                            badges = ownProfileBadges
                    )
                }

                // Settings — feed and relay connections persist; no disconnect when visiting settings.
                composable("settings") {
                    SettingsScreen(
                            onBackClick = { navController.popBackStack() },
                            onNavigateTo = { screen ->
                                when (screen) {
                                    "appearance" -> navController.navigate("settings/appearance") { launchSingleTop = true }
                                    "media" -> navController.navigate("settings/media") { launchSingleTop = true }
                                    "account_preferences" ->
                                            navController.navigate("settings/account_preferences") { launchSingleTop = true }
                                    "notifications" -> navController.navigate("settings/notifications") { launchSingleTop = true }
                                    "filters_blocks" -> navController.navigate("settings/filters_blocks") { launchSingleTop = true }
                                    "data_storage" -> navController.navigate("settings/data_storage") { launchSingleTop = true }
                                    "power" -> navController.navigate("settings/power") { launchSingleTop = true }
                                    "about" -> navController.navigate("settings/about") { launchSingleTop = true }
                                    "direct_messages" -> navController.navigate("settings/direct_messages") { launchSingleTop = true }
                                    "debug" -> navController.navigate("settings/debug") { launchSingleTop = true }
                                }
                            },
                            onBugReportClick = {
                                val intent =
                                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                                intent.data =
                                        android.net.Uri.parse(
                                                "https://github.com/TekkadanPlays/mycelium-android/issues"
                                        )
                                context.startActivity(intent)
                            }
                    )
                }

                // Settings sub-screens
                composable("settings/power") {
                    social.mycelium.android.ui.screens.PowerSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/appearance") {
                    AppearanceSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/media") {
                    MediaSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/account_preferences") {
                    AccountPreferencesScreen(
                            onBackClick = { navController.popBackStack() },
                            accountStateViewModel = accountStateViewModel
                    )
                }

                composable("settings/notifications") {
                    NotificationSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/filters_blocks") {
                    FiltersBlocksSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/data_storage") {
                    DataStorageSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/direct_messages") {
                    social.mycelium.android.ui.screens.DmSettingsScreen(
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable("messages") {
                    social.mycelium.android.ui.screens.ConversationsScreen(
                        onConversationClick = { peerPubkey ->
                            navController.navigate("chat/$peerPubkey") {
                                launchSingleTop = true
                            }
                        },
                        onNewMessage = {
                            navController.navigate("new_dm") {
                                launchSingleTop = true
                            }
                        },
                        isGuest = authState.isGuest,
                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                        userAvatarUrl = authState.userProfile?.picture,
                        onUserProfileClick = {
                            authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                        },
                        onSettingsClick = {
                            navController.navigate("settings") { launchSingleTop = true }
                        },
                        onRelaysClick = {
                            navController.navigate("relays") { launchSingleTop = true }
                        },
                        onLoginClick = {
                            navController.navigate("login") { launchSingleTop = true }
                        },
                        onNavigateToTopics = {
                            navController.navigate("topics") {
                                popUpTo("messages") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToHome = {
                            navController.navigate("dashboard") {
                                popUpTo("messages") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToLive = {
                            navController.navigate("live_explorer") { launchSingleTop = true }
                        }
                    )
                }

                composable("new_dm") {
                    social.mycelium.android.ui.screens.NewDmScreen(
                        onBackClick = { navController.popBackStack() },
                        onPeerSelected = { peerPubkey ->
                            navController.navigate("chat/$peerPubkey") {
                                popUpTo("messages") { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = "chat/{peerPubkey}",
                    arguments = listOf(navArgument("peerPubkey") { type = NavType.StringType })
                ) { backStackEntry ->
                    val peerPubkey = backStackEntry.arguments?.getString("peerPubkey") ?: ""
                    val chatSigner = accountStateViewModel.getCurrentSigner()
                    val chatPubkey = accountStateViewModel.currentAccount.collectAsState().value?.toHexKey()
                    val chatRelayUrls = remember {
                        val ctx = navController.context
                        val sm = social.mycelium.android.repository.RelayStorageManager(ctx)
                        val pk = chatPubkey ?: ""
                        (sm.loadOutboxRelays(pk).map { it.url } + sm.loadInboxRelays(pk).map { it.url })
                            .map { it.trim().removeSuffix("/") }
                            .distinct()
                            .toSet()
                    }
                    social.mycelium.android.ui.screens.ChatScreen(
                        peerPubkey = peerPubkey,
                        signer = chatSigner,
                        userPubkey = chatPubkey,
                        relayUrls = chatRelayUrls,
                        onBackClick = { navController.popBackStack() },
                        onProfileClick = { pubkey -> navController.navigateToProfile(pubkey) }
                    )
                }

                composable("settings/about") {
                    AboutScreen(
                        onBackClick = { navController.popBackStack() },
                        onProfileClick = { pubkey -> navController.navigateToProfile(pubkey) }
                    )
                }

                composable("settings/debug") {
                    DebugSettingsScreen(
                        onBackClick = { navController.popBackStack() },
                        onEffectsLab = {
                            navController.navigate("effects_lab") { launchSingleTop = true }
                        }
                    )
                }

                composable("effects_lab") {
                    EffectsLabScreen(
                        onBackClick = { navController.popBackStack() },
                        onNoteClick = { note ->
                            appViewModel.updateSelectedNote(note)
                            navController.navigate("thread/${note.id}") { launchSingleTop = true }
                        }
                    )
                }

                composable("note_relays/{relayUrlsEncoded}") { backStackEntry ->
                    val encodedUrls = backStackEntry.arguments?.getString("relayUrlsEncoded") ?: ""
                    val relayUrls = encodedUrls.split(",").map { android.net.Uri.decode(it) }.filter { it.isNotBlank() }
                    social.mycelium.android.ui.screens.NoteRelaysScreen(
                        relayUrls = relayUrls,
                        onBackClick = { navController.popBackStack() },
                        onRelayClick = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                        }
                    )
                }

                composable("relay_connection_status") {
                    RelayConnectionStatusScreen(
                        onBackClick = { navController.popBackStack() },
                        onRelayClick = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable("settings/relay_health") {
                    RelayHealthScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenRelayManager = {
                            // Pop back to relays instead of pushing a new instance (avoids routing loop)
                            navController.navigate("relays") {
                                popUpTo("relays") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onOpenRelayDiscovery = {
                            navController.navigate("relay_discovery") {
                                launchSingleTop = true
                            }
                        },
                        onOpenRelayLog = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") {
                                launchSingleTop = true
                            }
                        },
                        onOpenRelayUsers = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_users/$encoded") {
                                launchSingleTop = true
                            }
                        },
                        onOpenNeedsAttention = {
                            navController.navigate("relay_needs_attention") {
                                launchSingleTop = true
                            }
                        },
                        onOpenPublishResults = {
                            navController.navigate("settings/publish_results") {
                                launchSingleTop = true
                            }
                        },
                        onProfileClick = { authorId ->
                            navController.navigateToProfile(authorId)
                        }
                    )
                }

                composable("settings/publish_results") {
                    social.mycelium.android.ui.screens.PublishResultsScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenRelayLog = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable("relay_needs_attention") {
                    social.mycelium.android.ui.screens.RelayNeedsAttentionScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRelayLog = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable("wallet") {
                    val walletSigner = accountStateViewModel.getCurrentSigner()
                    val walletPubkey = accountStateViewModel.currentAccount.collectAsState().value?.toHexKey()
                    social.mycelium.android.ui.screens.WalletScreen(
                        signer = walletSigner,
                        pubkey = walletPubkey
                    )
                }

                composable(
                    route = "reactions/{noteId}",
                    arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(300)
                        )
                    }
                ) {
                    val reactionsData by appViewModel.pendingReactionsData.collectAsState()
                    val data = reactionsData
                    if (data != null) {
                        ReactionsScreen(
                            data = data,
                            onBackClick = { navController.popBackStack() },
                            onProfileClick = { authorId -> navController.navigateToProfile(authorId) }
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No reaction data available")
                        }
                    }
                }

                composable(
                    "relay_discovery?selection={selection}&prefill={prefill}",
                    arguments = listOf(
                        navArgument("selection") { type = NavType.StringType; defaultValue = "" },
                        navArgument("prefill") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val isSelectionMode = backStackEntry.arguments?.getString("selection") == "true"
                    val prefillRaw = backStackEntry.arguments?.getString("prefill") ?: ""
                    val preSelectedUrls = if (prefillRaw.isNotBlank())
                        android.net.Uri.decode(prefillRaw).split(",").filter { it.isNotBlank() }
                    else emptyList()

                    RelayDiscoveryScreen(
                        onBackClick = { navController.popBackStack() },
                        onRelayClick = { relayUrl ->
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                        },
                        selectionMode = isSelectionMode,
                        preSelectedUrls = preSelectedUrls,
                        onConfirmSelection = { urls ->
                            android.util.Log.d("DiscoveryNav", "onConfirmSelection: ${urls.size} URLs, prevEntry=${navController.previousBackStackEntry?.destination?.route}")
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set("selected_indexers", ArrayList(urls))
                            navController.popBackStack()
                        }
                    )
                }

                composable("debug_follow_list") {
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val pubkey = currentAccount?.toHexKey()
                    DebugFollowListScreen(
                        currentAccountPubkey = pubkey,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                // Announcements Feed — bottom nav "News" tab
                composable("announcements") {
                    val announcementsViewModel: AnnouncementsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val announcementsUiState by announcementsViewModel.uiState.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

                    LaunchedEffect(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            announcementsViewModel.subscribe(pubkey)
                        }
                    }

                    val announcementsDraftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()

                    AnnouncementsFeedScreen(
                        uiState = announcementsUiState,
                        onRefresh = {
                            currentAccount?.toHexKey()?.let { announcementsViewModel.refresh(it) }
                        },
                        onNoteClick = { note ->
                            val threadNote = if (note.originalNoteId != null) note.copy(id = note.originalNoteId) else note
                            if (threadNote.isReply && threadNote.rootNoteId != null) {
                                appViewModel.storeNoteForThread(threadNote)
                                navController.navigate("thread/${threadNote.rootNoteId}?replyKind=1&highlightReplyId=${threadNote.id}") { launchSingleTop = true }
                            } else {
                                appViewModel.updateSelectedNote(threadNote)
                                navController.navigate("thread/${threadNote.id}") { launchSingleTop = true }
                            }
                        },
                        onProfileClick = { authorId ->
                            navController.navigateToProfile(authorId)
                        },
                        onConfigureRelays = {
                            navController.navigate("relays?tab=system") { launchSingleTop = true }
                        },
                        onEffectsLab = {
                            navController.navigate("effects_lab") { launchSingleTop = true }
                        },
                        topAppBarState = topAppBarState,
                        onMenuClick = { /* no drawer on announcements */ },
                        isGuest = authState.isGuest,
                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                        userAvatarUrl = authState.userProfile?.picture,
                        onUserProfileClick = {
                            authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                        },
                        onAccountsClick = { /* account switcher not wired on this tab */ },
                        onSettingsClick = {
                            navController.navigate("settings") { launchSingleTop = true }
                        },
                        onRelaysClick = {
                            navController.navigate("relays") { launchSingleTop = true }
                        },
                        onLoginClick = {
                            navController.navigate("login") { launchSingleTop = true }
                        },
                        onNavigateToTopics = {
                            navController.navigate("topics") {
                                popUpTo("announcements") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToHome = {
                            navController.navigate("dashboard") {
                                popUpTo("announcements") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToLive = {
                            navController.navigate("live_explorer") { launchSingleTop = true }
                        },
                        onCompose = {
                            navController.navigate("compose?origin=announcements") { launchSingleTop = true }
                        },
                        draftCount = announcementsDraftsList.size,
                        onDrafts = {
                            navController.navigate("drafts") { launchSingleTop = true }
                        }
                    )
                }

                // Relay Management - Can navigate back to dashboard
                composable(
                    "relays?tab={tab}&prefill={prefill}&outbox={outbox}&inbox={inbox}",
                    arguments = listOf(
                        navArgument("tab") { type = NavType.StringType; defaultValue = "" },
                        navArgument("prefill") { type = NavType.StringType; defaultValue = "" },
                        navArgument("outbox") { type = NavType.StringType; defaultValue = "" },
                        navArgument("inbox") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val initialTab = backStackEntry.arguments?.getString("tab") ?: ""
                    val prefillRaw = backStackEntry.arguments?.getString("prefill") ?: ""
                    val prefillIndexerUrls = if (prefillRaw.isNotBlank())
                        android.net.Uri.decode(prefillRaw).split(",").filter { it.isNotBlank() }
                    else emptyList()
                    val outboxRaw = backStackEntry.arguments?.getString("outbox") ?: ""
                    val prefillOutboxUrls = if (outboxRaw.isNotBlank())
                        android.net.Uri.decode(outboxRaw).split(",").filter { it.isNotBlank() }
                    else emptyList()
                    val inboxRaw = backStackEntry.arguments?.getString("inbox") ?: ""
                    val prefillInboxUrls = if (inboxRaw.isNotBlank())
                        android.net.Uri.decode(inboxRaw).split(",").filter { it.isNotBlank() }
                    else emptyList()
                    RelayManagementScreen(
                            onBackClick = { navController.popBackStack() },
                            relayRepository = relayRepository,
                            accountStateViewModel = accountStateViewModel,
                            topAppBarState = topAppBarState,
                            initialTab = initialTab,
                            prefillIndexerUrls = prefillIndexerUrls,
                            prefillOutboxUrls = prefillOutboxUrls,
                            prefillInboxUrls = prefillInboxUrls,
                            onOpenRelayLog = { relayUrl ->
                                val encoded = java.net.URLEncoder.encode(relayUrl, "UTF-8")
                                navController.navigate("relay_log/$encoded") {
                                    launchSingleTop = true
                                }
                            },
                            onOpenRelayHealth = {
                                navController.navigate("settings/relay_health") {
                                    launchSingleTop = true
                                }
                            },
                            onOpenRelayDiscovery = {
                                navController.navigate("relay_discovery") {
                                    launchSingleTop = true
                                }
                            }
                    )
                }

                // Relay activity log screen
                composable(
                    "relay_log/{relayUrl}",
                    arguments = listOf(navArgument("relayUrl") { type = NavType.StringType })
                ) { backStackEntry ->
                    val relayUrl = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("relayUrl") ?: "",
                        "UTF-8"
                    )
                    val logPubkey = accountStateViewModel.currentAccount.value?.toHexKey()
                    val logProfiles = remember(logPubkey) {
                        logPubkey?.let { RelayStorageManager(context).loadProfiles(it) } ?: emptyList()
                    }
                    // Count followed users who write/read to this relay per NIP-65
                    val logAuthorRelaySnapshot by social.mycelium.android.repository.Nip65RelayListRepository.authorRelaySnapshot.collectAsState()
                    val logNip65Pubkey = social.mycelium.android.repository.Nip65RelayListRepository.currentPubkey
                    val logFollowSet = remember(logNip65Pubkey ?: logPubkey) {
                        val pk = logNip65Pubkey ?: logPubkey
                        pk?.let { social.mycelium.android.repository.ContactListRepository.getCachedFollowList(it) } ?: emptySet()
                    }
                    val logNormalizedUrl = relayUrl.trimEnd('/')
                    val logFollowedUserCount = remember(logNormalizedUrl, logAuthorRelaySnapshot, logFollowSet) {
                        var count = 0
                        for (pk in logFollowSet) {
                            val ar = logAuthorRelaySnapshot[pk] ?: continue
                            if (ar.writeRelays.any { it.trimEnd('/').equals(logNormalizedUrl, ignoreCase = true) } ||
                                ar.readRelays.any { it.trimEnd('/').equals(logNormalizedUrl, ignoreCase = true) }) {
                                count++
                            }
                        }
                        count
                    }
                    RelayLogScreen(
                        relayUrl = relayUrl,
                        onBack = { navController.popBackStack() },
                        relayProfiles = logProfiles,
                        onOpenRelayUsers = {
                            val encoded = android.net.Uri.encode(relayUrl)
                            navController.navigate("relay_users/$encoded") { launchSingleTop = true }
                        },
                        followedUserCount = logFollowedUserCount,
                        currentUserPubkey = logPubkey,
                        currentSigner = accountStateViewModel.getCurrentSigner(),
                        onAddToRelayProfile = addToRelayProfile@{ url, profileType ->
                            val pubkey = logPubkey ?: return@addToRelayProfile
                            val sm = RelayStorageManager(context)
                            val normalized = RelayStorageManager.normalizeRelayUrl(url)
                            val newRelay = social.mycelium.android.data.UserRelay(url = normalized, read = true, write = true)
                            val label = when {
                                profileType == "outbox" -> {
                                    val existing = sm.loadOutboxRelays(pubkey)
                                    if (existing.none { it.url == normalized }) sm.saveOutboxRelays(pubkey, existing + newRelay)
                                    "Outbox"
                                }
                                profileType == "inbox" -> {
                                    val existing = sm.loadInboxRelays(pubkey)
                                    if (existing.none { it.url == normalized }) sm.saveInboxRelays(pubkey, existing + newRelay)
                                    "Inbox"
                                }
                                profileType == "indexer" -> {
                                    val existing = sm.loadIndexerRelays(pubkey)
                                    if (existing.none { it.url == normalized }) sm.saveIndexerRelays(pubkey, existing + newRelay)
                                    "Indexer"
                                }
                                profileType.startsWith("profile:") -> {
                                    val profileId = profileType.removePrefix("profile:")
                                    val profiles = sm.loadProfiles(pubkey)
                                    val profile = profiles.firstOrNull { it.id == profileId }
                                    if (profile != null) {
                                        val defaultCat = profile.categories.firstOrNull()
                                        if (defaultCat != null && defaultCat.relays.none { it.url == normalized }) {
                                            val updatedProfile = profile.copy(
                                                categories = profile.categories.map { cat ->
                                                    if (cat.id == defaultCat.id) cat.copy(relays = cat.relays + newRelay) else cat
                                                }
                                            )
                                            sm.saveProfiles(pubkey, profiles.map { if (it.id == profileId) updatedProfile else it })
                                        }
                                        profile.name
                                    } else null
                                }
                                else -> null
                            }
                            if (label != null) {
                                showSnackbar("Added to $label")
                            }
                        }
                    )
                }

                // Relay users screen — shows followed NIP-65 write/read users for a relay
                composable(
                    "relay_users/{relayUrl}",
                    arguments = listOf(navArgument("relayUrl") { type = NavType.StringType })
                ) { backStackEntry ->
                    val relayUrl = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("relayUrl") ?: "",
                        "UTF-8"
                    )
                    val authorRelaySnapshot by social.mycelium.android.repository.Nip65RelayListRepository.authorRelaySnapshot.collectAsState()
                    val normalizedUrl = relayUrl.trimEnd('/')
                    val nip65Pubkey = social.mycelium.android.repository.Nip65RelayListRepository.currentPubkey
                    val accountPubkey = accountStateViewModel.currentAccount.value?.toHexKey()
                    val followSet = remember(nip65Pubkey ?: accountPubkey) {
                        val pk = nip65Pubkey ?: accountPubkey
                        pk?.let { social.mycelium.android.repository.ContactListRepository.getCachedFollowList(it) } ?: emptySet()
                    }
                    val (outboxUsers, inboxUsers) = remember(normalizedUrl, authorRelaySnapshot, followSet) {
                        val outbox = mutableListOf<String>()
                        val inbox = mutableListOf<String>()
                        for (pk in followSet) {
                            val authorRelays = authorRelaySnapshot[pk] ?: continue
                            if (authorRelays.writeRelays.any { it.trimEnd('/').equals(normalizedUrl, ignoreCase = true) }) {
                                outbox.add(pk)
                            }
                            if (authorRelays.readRelays.any { it.trimEnd('/').equals(normalizedUrl, ignoreCase = true) }) {
                                inbox.add(pk)
                            }
                        }
                        outbox to inbox
                    }
                    social.mycelium.android.ui.screens.RelayUsersScreen(
                        relayUrl = relayUrl,
                        outboxUsers = outboxUsers,
                        inboxUsers = inboxUsers,
                        onBack = { navController.popBackStack() },
                        onProfileClick = { authorId -> navController.navigateToProfile(authorId) }
                    )
                }

                // Notifications - Can navigate to threads and profiles
                // No per-route transitions: NavHost direction-aware system handles tab animations.
                composable("notifications") {
                    // Guard: if we have account but onboarding not complete, redirect to onboarding
                    // If NO account, redirect to dashboard to show sign-in
                    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    LaunchedEffect(currentAccount, onboardingComplete) {
                        if (currentAccount == null) {
                            navController.navigate("dashboard") {
                                popUpTo("notifications") { inclusive = true }
                                launchSingleTop = true
                            }
                            return@LaunchedEffect
                        }
                        val hasValidPubkey = currentAccount?.toHexKey()?.isNotBlank() == true
                        if (hasValidPubkey && !onboardingComplete) {
                            navController.navigate("onboarding") {
                                popUpTo("notifications") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Relay URLs for notification thread overlays — computed BEFORE callbacks that use them
                    val notifFallbackRelayUrls = remember(currentAccount) {
                        val userRelays = currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            categories.filter { it.isSubscribed }.flatMap { it.relays }.map { it.url }.distinct()
                        } ?: emptyList()
                        (userRelays + NotesRepository.getInstance().INDEXER_RELAYS).distinct()
                    }
                    val notifCacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            categories.flatMap { it.relays }.map { it.url }.distinct()
                        } ?: emptyList()
                    }

                    val notifCoroutineScope = rememberCoroutineScope()
                    NotificationsScreen(
                            listState = notificationsListState,
                            selectedTabIndex = notificationsSelectedTab,
                            onTabSelected = { notificationsSelectedTab = it },
                            onBackClick = {
                                navController.popBackStack()
                            },
                            onNoteClick = { note ->
                                val threadNote = if (note.originalNoteId != null) note.copy(id = note.originalNoteId) else note
                                overlayNotifThreadStack.clear()
                                overlayNotifReplyKinds.clear()
                                overlayNotifThreadHighlightIds.clear()
                                if (threadNote.isReply && threadNote.rootNoteId != null) {
                                    val rootId = threadNote.rootNoteId!!
                                    val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                    val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote)
                                    overlayNotifThreadStack.add(overlayNote)
                                    overlayNotifReplyKinds.add(1)
                                    overlayNotifThreadHighlightIds.add(threadNote.id)
                                    if (rootFromCache == null) {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                noteId = rootId,
                                                userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) } +
                                                    NotesRepository.getInstance().INDEXER_RELAYS,
                                                authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                            )
                                            if (fetched != null) {
                                                withContext(Dispatchers.Main.immediate) {
                                                    val idx = overlayNotifThreadStack.indexOfFirst { it.id == rootId }
                                                    if (idx >= 0) overlayNotifThreadStack[idx] = fetched
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    overlayNotifThreadStack.add(threadNote)
                                    overlayNotifReplyKinds.add(1)
                                    overlayNotifThreadHighlightIds.add(null)
                                }
                            },
                            onOpenThreadForRootId = { rootNoteId, replyKind, replyNoteId, targetNote ->
                                // Only use targetNote if it IS the root note; otherwise create a stub
                                // and fetch the real root. Previously targetNote.copy(id=rootNoteId) created
                                // a frankenstein note showing the user's reply content as the root.
                                val overlayNote = if (targetNote != null && targetNote.id == rootNoteId) targetNote
                                    else NotesRepository.getInstance().getNoteFromCache(rootNoteId)
                                        ?: if (targetNote != null) buildRootStubNote(rootNoteId, targetNote, notifFallbackRelayUrls)
                                        else {
                                            // No targetNote and root not cached — try resolving own profile
                                            // (most notification threads originate from the user's own notes)
                                            val ownPubkey = currentAccount?.toHexKey()
                                            val fallbackAuthor = if (!ownPubkey.isNullOrBlank()) ProfileMetadataCache.getInstance().resolveAuthor(ownPubkey)
                                                else social.mycelium.android.data.Author(id = "", username = "", displayName = "", avatarUrl = null, isVerified = false)
                                            social.mycelium.android.data.Note(
                                                id = rootNoteId,
                                                author = fallbackAuthor,
                                                content = "",
                                                timestamp = 0L,
                                                relayUrls = notifFallbackRelayUrls
                                            )
                                        }
                                overlayNotifThreadStack.clear()
                                overlayNotifReplyKinds.clear()
                                overlayNotifThreadHighlightIds.clear()
                                overlayNotifThreadStack.add(overlayNote)
                                overlayNotifReplyKinds.add(replyKind)
                                overlayNotifThreadHighlightIds.add(replyNoteId)
                                // Async-fetch the root note so the thread composable has full data
                                notifCoroutineScope.launch(Dispatchers.IO) {
                                    var note = NotesRepository.getInstance().getNoteFromCache(rootNoteId)
                                    if (note == null) {
                                        val pubkey = currentAccount?.toHexKey() ?: return@launch
                                        val categories = storageManager.loadCategories(pubkey)
                                        val relayUrls = (categories.flatMap { it.relays }.map { it.url } + NotesRepository.getInstance().INDEXER_RELAYS).distinct()
                                        // Use expanded fetch (outbox + relay hints + indexers) for better coverage
                                        note = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                            noteId = rootNoteId,
                                            userRelayUrls = relayUrls,
                                            authorPubkey = targetNote?.author?.id?.takeIf { it.isNotBlank() }
                                        )
                                    }
                                    if (note != null) {
                                        withContext(Dispatchers.Main.immediate) {
                                            // Replace the stub with the real note in the stack
                                            val idx = overlayNotifThreadStack.indexOfFirst { it.id == rootNoteId }
                                            if (idx >= 0) overlayNotifThreadStack[idx] = note
                                        }
                                    }
                                }
                            },
                            onLike = { /* TODO: Handle like */},
                            onShare = { /* TODO: Handle share */},
                            onComment = { /* TODO: Handle comment */},
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings/notifications") {
                                    launchSingleTop = true
                                }
                            },
                            topAppBarState = topAppBarState
                    )

                    // ── Notifications thread overlay (stack-based) ──────────────────────
                    BackHandler(enabled = overlayNotifThreadStack.isNotEmpty()) {
                        overlayNotifThreadStack.removeLastOrNull()
                        overlayNotifReplyKinds.removeLastOrNull()
                        overlayNotifThreadHighlightIds.removeLastOrNull()
                    }

                    val notifContentNote = overlayNotifThreadStack.lastOrNull()
                    val notifReplyKind = overlayNotifReplyKinds.lastOrNull() ?: 1
                    var lastNotifOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                    if (notifContentNote != null) lastNotifOverlayNote = notifContentNote
                    val notifDisplayNote = notifContentNote ?: lastNotifOverlayNote
                    val showNotifOverlay = notifContentNote != null
                    AnimatedVisibility(
                        visible = showNotifOverlay,
                        enter = slideInHorizontally(
                            animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                        ) { fullWidth -> fullWidth },
                        exit = slideOutHorizontally(
                            animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                        ) { fullWidth -> fullWidth }
                    ) {
                        if (notifDisplayNote != null) {
                            val noteId = notifDisplayNote.id
                            val notifRelayUrls = (notifDisplayNote.relayUrls.ifEmpty { listOfNotNull(notifDisplayNote.relayUrl) } + notifFallbackRelayUrls).distinct()
                            val notifThreadListState = rememberLazyListState()
                            var notifExpandedControlsCommentId by remember(noteId) { mutableStateOf<String?>(null) }
                            var notifExpandedControlsReplyId by remember(noteId) { mutableStateOf<String?>(null) }
                            val authState by accountStateViewModel.authState.collectAsState()
                            ThreadSlideBackBox(onBack = {
                                overlayNotifThreadStack.removeLastOrNull()
                                overlayNotifReplyKinds.removeLastOrNull()
                                overlayNotifThreadHighlightIds.removeLastOrNull()
                            }) {
                                ModernThreadViewScreen(
                                    note = notifDisplayNote,
                                    comments = emptyList(),
                                    listState = notifThreadListState,
                                    expandedControlsCommentId = notifExpandedControlsCommentId,
                                    onExpandedControlsChange = { notifExpandedControlsCommentId = if (notifExpandedControlsCommentId == it) null else it },
                                    expandedControlsReplyId = notifExpandedControlsReplyId,
                                    onExpandedControlsReplyChange = { replyId ->
                                        notifExpandedControlsReplyId = if (notifExpandedControlsReplyId == replyId) null else replyId
                                    },
                                    topAppBarState = topAppBarState,
                                    replyKind = notifReplyKind,
                                    highlightReplyId = overlayNotifThreadHighlightIds.lastOrNull(),
                                    relayUrls = notifRelayUrls,
                                    cacheRelayUrls = notifCacheRelayUrls,
                                    onBackClick = {
                                        overlayNotifThreadStack.removeLastOrNull()
                                        overlayNotifReplyKinds.removeLastOrNull()
                                        overlayNotifThreadHighlightIds.removeLastOrNull()
                                    },
                                    onProfileClick = { navController.navigateToProfile(it) },
                                    onNoteClick = { clickedNote ->
                                        val threadNote = if (clickedNote.originalNoteId != null) clickedNote.copy(id = clickedNote.originalNoteId) else clickedNote
                                        if (threadNote.isReply && threadNote.rootNoteId != null) {
                                            val rootId = threadNote.rootNoteId!!
                                            val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                            val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote, notifRelayUrls)
                                            overlayNotifThreadStack.add(overlayNote)
                                            overlayNotifReplyKinds.add(1)
                                            overlayNotifThreadHighlightIds.add(threadNote.id)
                                            if (rootFromCache == null) {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                        noteId = rootId,
                                                        userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) }.ifEmpty { notifRelayUrls } +
                                                            NotesRepository.getInstance().INDEXER_RELAYS,
                                                        authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                                    )
                                                    if (fetched != null) {
                                                        withContext(Dispatchers.Main.immediate) {
                                                            val idx = overlayNotifThreadStack.indexOfFirst { it.id == rootId }
                                                            if (idx >= 0) overlayNotifThreadStack[idx] = fetched
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            overlayNotifThreadStack.add(threadNote)
                                            overlayNotifReplyKinds.add(1)
                                            overlayNotifThreadHighlightIds.add(null)
                                        }
                                    },
                                    onImageTap = { _, urls, idx ->
                                        appViewModel.openImageViewer(urls, idx)
                                        navController.navigate("image_viewer") { launchSingleTop = true }
                                    },
                                    onOpenImageViewer = { urls, idx ->
                                        appViewModel.openImageViewer(urls, idx)
                                        navController.navigate("image_viewer") { launchSingleTop = true }
                                    },
                                    onVideoClick = { urls, idx ->
                                        appViewModel.openVideoViewer(urls, idx)
                                        navController.navigate("video_viewer") { launchSingleTop = true }
                                    },
                                    onReact = { reactNote, emoji ->
                                        val error = accountStateViewModel.sendReaction(reactNote, emoji)
                                        if (error != null) showSnackbar(error)
                                    },
                                    onBoost = { n ->
                                        val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                                        if (err != null) showSnackbar(err)
                                    },
                                    onQuote = { n ->
                                        val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                                        val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                                        navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                    },
                                    onFork = { n ->
                                        val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                                        navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                    },
                                    onCustomZapSend = { zapNote, amount, zapType, msg ->
                                        val err = accountStateViewModel.sendZap(zapNote, amount, zapType, msg)
                                        if (err != null) showSnackbar(err)
                                    },
                                    onZap = { nId, amount ->
                                        if (notifDisplayNote.id == nId) {
                                            val err = accountStateViewModel.sendZap(notifDisplayNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                            if (err != null) showSnackbar(err)
                                        }
                                    },
                                    zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                    zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                    myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                                    boostedNoteIds = accountStateViewModel.boostedNoteIds.collectAsState().value,
                                    onVote = { noteId, authorPubkey, direction ->
                                        accountStateViewModel.sendVote(noteId, authorPubkey, direction, notifReplyKind)
                                    },
                                    onPublishThreadReply = when (notifReplyKind) {
                                        1111 -> { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                            accountStateViewModel.publishThreadReply(rootId, rootPubkey, parentId, parentPubkey, content)
                                        } }
                                        1 -> { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                            accountStateViewModel.publishKind1Reply(rootId, rootPubkey, parentId, parentPubkey, content)
                                        } }
                                        else -> null
                                    },
                                    onOpenReplyCompose = { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                        appViewModel.setReplyToNote(replyToNote)
                                        val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                        navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}&replyKind=$notifReplyKind") { launchSingleTop = true }
                                    },
                                    currentUserAuthor = remember(currentAccount) {
                                        currentAccount?.toHexKey()?.let { hex ->
                                            ProfileMetadataCache.getInstance().resolveAuthor(hex)
                                        }
                                    },
                                    onSeeAllReactions = { nId ->
                                        val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[nId]
                                        appViewModel.storeReactionsData(buildReactionsData(nId, counts, notifDisplayNote.repostedByAuthors))
                                        navController.navigate("reactions/$nId") { launchSingleTop = true }
                                    },
                                    onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                                    isGuest = authState.isGuest,
                                    userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                                    userAvatarUrl = authState.userProfile?.picture,
                                    accountNpub = currentAccount?.npub,
                                    mediaPageForNote = { nId -> appViewModel.getMediaPage(nId) },
                                    onMediaPageChanged = { nId, page -> appViewModel.updateMediaPage(nId, page) },
                                    onRelayNavigate = { relayUrl ->
                                        val encoded = android.net.Uri.encode(relayUrl)
                                        navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                                    },
                                    onNavigateToRelayList = { urls ->
                                        val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                        navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                                    },
                                    threadDrafts = run {
                                        val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                                        remember(draftsList, notifDisplayNote.id) {
                                            social.mycelium.android.repository.DraftsRepository.replyDraftsForThread(notifDisplayNote.id)
                                        }
                                    },
                                    onEditDraft = { draft ->
                                        val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                        when (draft.type) {
                                            social.mycelium.android.data.DraftType.REPLY_KIND1 ->
                                                navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1&draftId=${enc(draft.id)}")
                                            social.mycelium.android.data.DraftType.TOPIC_REPLY ->
                                                navController.navigate("compose_topic_reply/${enc(draft.rootId)}?draftId=${enc(draft.id)}")
                                            else ->
                                                navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1111&draftId=${enc(draft.id)}")
                                        }
                                    },
                                    onDeleteDraft = { draftId -> social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId) }
                                )
                            }
                        }
                    }
                }

                // Topics - Kind 11 topics with kind 1111 replies (thread opens as overlay so feed stays visible for slide-back)
                composable("topics") {
                    // Guard: if we have account but onboarding not complete, redirect to onboarding
                    // If NO account, redirect to dashboard to show sign-in
                    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
                    val appState by appViewModel.appState.collectAsState()
                    val context = LocalContext.current
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

                    LaunchedEffect(currentAccount, onboardingComplete) {
                        if (currentAccount == null) {
                            navController.navigate("dashboard") {
                                popUpTo("topics") { inclusive = true }
                                launchSingleTop = true
                            }
                            return@LaunchedEffect
                        }
                        val hasValidPubkey = currentAccount?.toHexKey()?.isNotBlank() == true
                        if (hasValidPubkey && !onboardingComplete) {
                            navController.navigate("onboarding") {
                                popUpTo("topics") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    val topicStorageManager = remember { social.mycelium.android.repository.RelayStorageManager(context) }
                    val topicFallbackRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = topicStorageManager.loadCategories(pubkey)
                            val subscribedRelays = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }.map { it.url }.distinct()
                            subscribedRelays.ifEmpty {
                                categories.flatMap { it.relays }.map { it.url }.distinct()
                            }
                        } ?: emptyList()
                    }
                    val topicCacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            topicStorageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        TopicsScreen(
                                onNavigateTo = { destination -> closeDrawerThen {
                                    if (destination == "dashboard") {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        navController.navigate(destination) { launchSingleTop = true }
                                    }
                                } },
                                onThreadClick = { note, relayUrls ->
                                    appViewModel.updateSelectedNote(note)
                                    appViewModel.updateThreadRelayUrls(relayUrls)
                                    overlayTopicThreadStack.clear()
                                    overlayTopicThreadHighlightIds.clear()
                                    overlayTopicThreadStack.add(note)
                                    overlayTopicThreadHighlightIds.add(null)
                                },
                                onProfileClick = { authorId ->
                                    navController.navigateToProfile(authorId)
                                },
                                listState = topicsListState,
                                feedStateViewModel = feedStateViewModel,
                                appViewModel = appViewModel,
                                relayRepository = relayRepository,
                                accountStateViewModel = accountStateViewModel,
                                onLoginClick = {
                                    val loginIntent = accountStateViewModel.loginWithAmber()
                                    onAmberLogin(loginIntent)
                                },
                                initialTopAppBarState = topAppBarState,
                                onQrClick = { navController.navigate("user_qr") { launchSingleTop = true } },
                                onSidebarRelayHealthClick = { closeDrawerThen {
                                    navController.navigate("settings/relay_health") {
                                        launchSingleTop = true
                                    }
                                } },
                                onSidebarRelayDiscoveryClick = { closeDrawerThen {
                                    navController.navigate("relay_discovery") {
                                        launchSingleTop = true
                                    }
                                } },
                                onNavigateToCreateTopic = { hashtag ->
                                    val encoded = android.net.Uri.encode(hashtag ?: "")
                                    navController.navigate("compose_topic?hashtag=$encoded") { launchSingleTop = true }
                                },
                                onNavigateToTopicDrafts = {
                                    navController.navigate("drafts") { launchSingleTop = true }
                                },
                                onRelayClick = { relayUrl ->
                                    val encoded = android.net.Uri.encode(relayUrl)
                                    navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                                },
                                onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                                onDrawerStateChanged = { open -> isDrawerOpen = open },
                                drawerState = sidebarDrawerState
                        )

                        // Intercept system back gesture when overlay thread is showing
                        BackHandler(enabled = overlayTopicThreadStack.isNotEmpty()) {
                            overlayTopicThreadStack.removeLastOrNull()
                            overlayTopicThreadHighlightIds.removeLastOrNull()
                        }

                        // Thread overlay: stack-based for chained exploration
                        val topicContentNote = overlayTopicThreadStack.lastOrNull()
                        var lastTopicOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                        if (topicContentNote != null) lastTopicOverlayNote = topicContentNote
                        val topicDisplayNote = topicContentNote ?: lastTopicOverlayNote
                        val showTopicOverlay = topicContentNote != null
                        AnimatedVisibility(
                            visible = showTopicOverlay,
                            enter = slideInHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            ) { fullWidth -> fullWidth },
                            exit = slideOutHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            ) { fullWidth -> fullWidth }
                        ) {
                            if (topicDisplayNote != null) {
                                val noteId = topicDisplayNote.id
                                val relayUrls = (topicDisplayNote.relayUrls.ifEmpty { listOfNotNull(topicDisplayNote.relayUrl) } + topicFallbackRelayUrls).distinct()
                                val savedScrollState = threadStateHolder.getScrollState(noteId)
                                val threadListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                                )
                                val commentStates = threadStateHolder.getCommentStates(noteId)
                                var expandedControlsCommentId by remember(noteId) {
                                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                                }
                                var expandedControlsReplyId by remember(noteId) {
                                    mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                                }
                                val authState by accountStateViewModel.authState.collectAsState()
                                DisposableEffect(noteId) {
                                    onDispose {
                                        threadStateHolder.saveScrollState(noteId, threadListState)
                                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                                        threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                                    }
                                }
                                ThreadSlideBackBox(onBack = { overlayTopicThreadStack.removeLastOrNull(); overlayTopicThreadHighlightIds.removeLastOrNull() }) {
                                    ModernThreadViewScreen(
                                        note = topicDisplayNote,
                                        comments = emptyList(),
                                        listState = threadListState,
                                        commentStates = commentStates,
                                        expandedControlsCommentId = expandedControlsCommentId,
                                        onExpandedControlsChange = { expandedControlsCommentId = if (expandedControlsCommentId == it) null else it },
                                        expandedControlsReplyId = expandedControlsReplyId,
                                        onExpandedControlsReplyChange = { replyId ->
                                            expandedControlsReplyId = if (expandedControlsReplyId == replyId) null else replyId
                                        },
                                        topAppBarState = topAppBarState,
                                        replyKind = 1111,
                                        highlightReplyId = overlayTopicThreadHighlightIds.lastOrNull(),
                                        relayUrls = relayUrls,
                                        cacheRelayUrls = topicCacheRelayUrls,
                                        onBackClick = { overlayTopicThreadStack.removeLastOrNull(); overlayTopicThreadHighlightIds.removeLastOrNull() },
                                        onProfileClick = { navController.navigateToProfile(it) },
                                        onNoteClick = { clickedNote ->
                                            val threadNote = if (clickedNote.originalNoteId != null) clickedNote.copy(id = clickedNote.originalNoteId) else clickedNote
                                            if (threadNote.isReply && threadNote.rootNoteId != null) {
                                                val rootId = threadNote.rootNoteId!!
                                                val rootFromCache = NotesRepository.getInstance().getNoteFromCache(rootId)
                                                val overlayNote = rootFromCache ?: buildRootStubNote(rootId, threadNote, relayUrls)
                                                overlayTopicThreadStack.add(overlayNote)
                                                overlayTopicThreadHighlightIds.add(threadNote.id)
                                                if (rootFromCache == null) {
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        val fetched = NotesRepository.getInstance().fetchNoteByIdExpanded(
                                                            noteId = rootId,
                                                            userRelayUrls = threadNote.relayUrls.ifEmpty { listOfNotNull(threadNote.relayUrl) }.ifEmpty { relayUrls } +
                                                                NotesRepository.getInstance().INDEXER_RELAYS,
                                                            authorPubkey = threadNote.author.id.takeIf { it.isNotBlank() }
                                                        )
                                                        if (fetched != null) {
                                                            withContext(Dispatchers.Main.immediate) {
                                                                val idx = overlayTopicThreadStack.indexOfFirst { it.id == rootId }
                                                                if (idx >= 0) overlayTopicThreadStack[idx] = fetched
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                overlayTopicThreadStack.add(threadNote)
                                                overlayTopicThreadHighlightIds.add(null)
                                            }
                                        },
                                        onImageTap = { _, urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer") { launchSingleTop = true }
                                        },
                                        onOpenImageViewer = { urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer") { launchSingleTop = true }
                                        },
                                        onVideoClick = { urls, idx ->
                                            appViewModel.openVideoViewer(urls, idx)
                                            navController.navigate("video_viewer") { launchSingleTop = true }
                                        },
                                        onReact = { note, emoji ->
                                            val error = accountStateViewModel.sendReaction(note, emoji)
                                            if (error != null) showSnackbar(error)
                                        },
                                        onBoost = { n ->
                                            val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                                            if (err != null) showSnackbar(err)
                                        },
                                        onQuote = { n ->
                                            val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                                            val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                                            navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                        },
                                        onFork = { n ->
                                            val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                                            navController.navigate("compose?initialContent=$encoded") { launchSingleTop = true }
                                        },
                                        onCustomZapSend = { note, amount, zapType, msg ->
                                            val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                            if (err != null) showSnackbar(err)
                                        },
                                        onZap = { nId, amount ->
                                            if (topicContentNote != null && topicContentNote.id == nId) {
                                                val err = accountStateViewModel.sendZap(topicContentNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                                if (err != null) showSnackbar(err)
                                            }
                                        },
                                        zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                        zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                        myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                                        boostedNoteIds = accountStateViewModel.boostedNoteIds.collectAsState().value,
                                        onVote = { noteId, authorPubkey, direction ->
                                            accountStateViewModel.sendVote(noteId, authorPubkey, direction, 1111)
                                        },
                                        onLoginClick = {
                                            val loginIntent = accountStateViewModel.loginWithAmber()
                                            onAmberLogin(loginIntent)
                                        },
                                        isGuest = authState.isGuest,
                                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                                        userAvatarUrl = authState.userProfile?.picture,
                                        accountNpub = currentAccount?.npub,
                                        onHeaderProfileClick = {
                                            authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                                        },
                                        onHeaderAccountsClick = { },
                                        onHeaderQrCodeClick = { navController.navigate("user_qr") { launchSingleTop = true } },
                                        onHeaderSettingsClick = { navController.navigate("settings") { launchSingleTop = true } },
                                        mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                                        onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                                        onRelayNavigate = { relayUrl ->
                                            val encoded = android.net.Uri.encode(relayUrl)
                                            navController.navigate("relay_log/$encoded") { launchSingleTop = true }
                                        },
                                        onNavigateToRelayList = { urls ->
                                            val encoded = urls.joinToString(",") { android.net.Uri.encode(it) }
                                            navController.navigate("note_relays/$encoded") { launchSingleTop = true }
                                        },
                                        onPublishThreadReply = { rootId, rootPubkey, parentId, parentPubkey, content ->
                                            accountStateViewModel.publishThreadReply(rootId, rootPubkey, parentId, parentPubkey, content)
                                        },
                                        onOpenReplyCompose = { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                            appViewModel.setReplyToNote(replyToNote)
                                            val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                            navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}&replyKind=1111")
                                        },
                                        currentUserAuthor = remember(currentAccount) {
                                            currentAccount?.toHexKey()?.let { hex ->
                                                ProfileMetadataCache.getInstance().resolveAuthor(hex)
                                            }
                                        },
                                        onSeeAllReactions = { nId ->
                                            val counts = social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.value[nId]
                                            appViewModel.storeReactionsData(buildReactionsData(nId, counts, topicContentNote?.repostedByAuthors ?: emptyList()))
                                            navController.navigate("reactions/$nId") { launchSingleTop = true }
                                        },
                                        onNavigateToZapSettings = { navController.navigate("zap_settings") { launchSingleTop = true } },
                                        threadDrafts = run {
                                            val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                                            remember(draftsList, topicDisplayNote.id) {
                                                social.mycelium.android.repository.DraftsRepository.replyDraftsForThread(topicDisplayNote.id)
                                            }
                                        },
                                        onEditDraft = { draft ->
                                            val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                            when (draft.type) {
                                                social.mycelium.android.data.DraftType.REPLY_KIND1 ->
                                                    navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1&draftId=${enc(draft.id)}")
                                                social.mycelium.android.data.DraftType.TOPIC_REPLY ->
                                                    navController.navigate("compose_topic_reply/${enc(draft.rootId)}?draftId=${enc(draft.id)}")
                                                else ->
                                                    navController.navigate("reply_compose?rootId=${enc(draft.rootId)}&rootPubkey=${enc(draft.rootPubkey)}&parentId=${enc(draft.parentId)}&parentPubkey=${enc(draft.parentPubkey)}&replyKind=1111&draftId=${enc(draft.id)}")
                                            }
                                        },
                                        onDeleteDraft = { draftId -> social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Topic Thread - View kind:11 topic with kind:1 replies
                composable(
                    route = "topic_thread/{topicId}",
                    arguments = listOf(
                        navArgument("topicId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val topicId = backStackEntry.arguments?.getString("topicId") ?: return@composable
                    val topicsRepository = remember { social.mycelium.android.repository.TopicsRepository.getInstance(context) }
                    val allTopics by topicsRepository.topics.collectAsState()
                    val topic = allTopics[topicId]
                    
                    // Get relay URLs for fetching kind:1111 replies
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val topicThreadStorageManager = remember(context) { RelayStorageManager(context) }
                    val topicRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = topicThreadStorageManager.loadCategories(pubkey)
                            val subscribedRelays = categories.filter { it.isSubscribed }
                                .flatMap { it.relays }.map { it.url }.distinct()
                            subscribedRelays.ifEmpty {
                                categories.flatMap { it.relays }.map { it.url }.distinct()
                            }
                        } ?: emptyList()
                    }
                    val topicCacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            topicThreadStorageManager.loadIndexerRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }
                    
                    if (topic != null) {
                        TopicThreadScreen(
                            topic = topic,
                            onBackClick = { navController.popBackStack() },
                            onReplyKind1111Click = {
                                // Navigate to kind:1111 reply (existing reply compose)
                                val rootId = topicId
                                val rootPubkey = topic.author.id
                                navController.navigate("reply_compose?rootId=$rootId&rootPubkey=$rootPubkey&replyKind=1111") { launchSingleTop = true }
                            },
                            onReplyKind1Click = {
                                // Navigate to kind:1 reply with I tags
                                val encoded = android.net.Uri.encode(topicId)
                                navController.navigate("compose_topic_reply/$encoded") { launchSingleTop = true }
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onImageTap = { note, urls, index ->
                                appViewModel.updateSelectedNote(note)
                                navController.navigate("image_viewer") { launchSingleTop = true }
                            },
                            onOpenImageViewer = { urls, index ->
                                navController.navigate("image_viewer") { launchSingleTop = true }
                            },
                            onVideoClick = { urls, index ->
                                navController.navigate("video_viewer") { launchSingleTop = true }
                            },
                            accountStateViewModel = accountStateViewModel,
                            relayUrls = topicRelayUrls,
                            cacheRelayUrls = topicCacheRelayUrls
                        )
                    } else {
                        // Topic not found - show loading or error
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                composable(
                    route = "compose?initialContent={initialContent}&draftId={draftId}&origin={origin}",
                    arguments = listOf(
                        navArgument("initialContent") { type = NavType.StringType; defaultValue = "" },
                        navArgument("draftId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("origin") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val initialContent = backStackEntry.arguments?.getString("initialContent").orEmpty()
                        .let { android.net.Uri.decode(it) }
                    val draftId = backStackEntry.arguments?.getString("draftId")?.takeIf { it.isNotBlank() }
                    val origin = backStackEntry.arguments?.getString("origin").orEmpty()
                    val composeContext = LocalContext.current
                    val composeStorageManager = remember(composeContext) { RelayStorageManager(composeContext) }
                    val currentAccountForCompose by accountStateViewModel.currentAccount.collectAsState()
                    val relayCategoriesForCompose = remember(currentAccountForCompose) {
                        currentAccountForCompose?.toHexKey()?.let { pubkey ->
                            composeStorageManager.loadCategories(pubkey)
                        } ?: DefaultRelayCategories.getAllDefaultCategories()
                    }
                    val relayProfilesForCompose = remember(currentAccountForCompose) {
                        currentAccountForCompose?.toHexKey()?.let { pubkey ->
                            composeStorageManager.loadProfiles(pubkey)
                        } ?: emptyList()
                    }
                    val announcementRelaysForCompose = remember(currentAccountForCompose, origin) {
                        if (origin == "announcements") {
                            currentAccountForCompose?.toHexKey()?.let { pubkey ->
                                composeStorageManager.loadAnnouncementRelays(pubkey)
                            } ?: emptyList()
                        } else emptyList()
                    }
                    val blossomServersForCompose = remember(currentAccountForCompose) {
                        currentAccountForCompose?.toHexKey()?.let { pubkey ->
                            composeStorageManager.loadBlossomServers(pubkey)
                        } ?: social.mycelium.android.data.DefaultMediaServers.BLOSSOM_SERVERS
                    }
                    val nip96ServersForCompose = remember(currentAccountForCompose) {
                        currentAccountForCompose?.toHexKey()?.let { pubkey ->
                            composeStorageManager.loadNip96Servers(pubkey)
                        } ?: social.mycelium.android.data.DefaultMediaServers.NIP96_SERVERS
                    }
                    ComposeNoteScreen(
                        onBack = { navController.popBackStack() },
                        accountStateViewModel = accountStateViewModel,
                        relayCategories = relayCategoriesForCompose,
                        relayProfiles = relayProfilesForCompose,
                        announcementRelays = announcementRelaysForCompose,
                        blossomServers = blossomServersForCompose,
                        nip96Servers = nip96ServersForCompose,
                        initialContent = initialContent,
                        draftId = draftId
                    )
                }

                // Drafts Screen
                composable("drafts") {
                    val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                    DraftsScreen(
                        drafts = draftsList,
                        onBackClick = { navController.popBackStack() },
                        onDraftClick = { draft ->
                            when (draft.type) {
                                social.mycelium.android.data.DraftType.NOTE -> {
                                    navController.navigate("compose?initialContent=${android.net.Uri.encode(android.net.Uri.encode(draft.content))}&draftId=${draft.id}") {
                                        launchSingleTop = true
                                    }
                                }
                                social.mycelium.android.data.DraftType.TOPIC -> {
                                    // Navigate to compose topic with draft pre-filled
                                    navController.navigate("compose_topic?draftId=${draft.id}") {
                                        launchSingleTop = true
                                    }
                                }
                                else -> {
                                    // For reply drafts, open the compose note screen with draft content
                                    navController.navigate("compose?initialContent=${android.net.Uri.encode(android.net.Uri.encode(draft.content))}&draftId=${draft.id}") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        },
                        onDeleteDraft = { draftId ->
                            social.mycelium.android.repository.DraftsRepository.deleteDraft(draftId)
                        }
                    )
                }

                composable(
                    route = "compose_topic?hashtag={hashtag}&draftId={draftId}",
                    arguments = listOf(
                        navArgument("hashtag") { type = NavType.StringType; defaultValue = "" },
                        navArgument("draftId") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val hashtagArg = backStackEntry.arguments?.getString("hashtag").orEmpty()
                    val initialHashtag = hashtagArg.takeIf { it.isNotEmpty() }
                    val topicDraftId = backStackEntry.arguments?.getString("draftId")?.takeIf { it.isNotBlank() }
                    val topicComposeContext = LocalContext.current
                    val topicStorageManager = remember(topicComposeContext) { RelayStorageManager(topicComposeContext) }
                    val currentAccountForTopic by accountStateViewModel.currentAccount.collectAsState()
                    val topicRelayCategories = remember(currentAccountForTopic) {
                        currentAccountForTopic?.toHexKey()?.let { pubkey ->
                            topicStorageManager.loadCategories(pubkey)
                        } ?: DefaultRelayCategories.getAllDefaultCategories()
                    }
                    val topicRelayProfiles = remember(currentAccountForTopic) {
                        currentAccountForTopic?.toHexKey()?.let { pubkey ->
                            topicStorageManager.loadProfiles(pubkey)
                        } ?: emptyList()
                    }
                    val outboxRelays = remember(currentAccountForTopic?.npub) {
                        accountStateViewModel.getOutboxRelaysForPublish()
                    }
                    val topicMyPubkeyHex = currentAccountForTopic?.toHexKey()
                    val topicMyAuthor = remember(topicMyPubkeyHex) {
                        topicMyPubkeyHex?.let { social.mycelium.android.repository.ProfileMetadataCache.getInstance().resolveAuthor(it) }
                    }
                    val topicBlossomServers = remember(currentAccountForTopic) {
                        currentAccountForTopic?.toHexKey()?.let { pubkey ->
                            topicStorageManager.loadBlossomServers(pubkey)
                        } ?: social.mycelium.android.data.DefaultMediaServers.BLOSSOM_SERVERS
                    }
                    val topicNip96Servers = remember(currentAccountForTopic) {
                        currentAccountForTopic?.toHexKey()?.let { pubkey ->
                            topicStorageManager.loadNip96Servers(pubkey)
                        } ?: social.mycelium.android.data.DefaultMediaServers.NIP96_SERVERS
                    }
                    ComposeTopicScreen(
                        initialHashtag = initialHashtag,
                        outboxRelays = outboxRelays,
                        relayCategories = topicRelayCategories,
                        relayProfiles = topicRelayProfiles,
                        myAuthor = topicMyAuthor,
                        blossomServers = topicBlossomServers,
                        nip96Servers = topicNip96Servers,
                        onPublish = { title, content, tags, relayUrls ->
                            accountStateViewModel.publishTopic(title, content, tags, relayUrls)
                        },
                        onBack = { navController.popBackStack() },
                        draftId = topicDraftId
                    )
                }

                // Compose kind:1 reply to topic with I tags (mesh network reply)
                composable(
                    route = "compose_topic_reply/{topicId}?draftId={draftId}",
                    arguments = listOf(
                        navArgument("topicId") { type = NavType.StringType },
                        navArgument("draftId") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val topicId = backStackEntry.arguments?.getString("topicId") ?: return@composable
                    val topicReplyDraftId = backStackEntry.arguments?.getString("draftId").orEmpty().takeIf { it.isNotEmpty() }
                    val topicsRepository = remember { social.mycelium.android.repository.TopicsRepository.getInstance(context) }
                    val allTopics by topicsRepository.topics.collectAsState()
                    val topic = allTopics[topicId]
                    
                    if (topic != null) {
                        val replyComposeContext = LocalContext.current
                        val replyStorageManager = remember(replyComposeContext) { RelayStorageManager(replyComposeContext) }
                        val currentAccountForReply by accountStateViewModel.currentAccount.collectAsState()
                        val replyRelayCategories = remember(currentAccountForReply) {
                            currentAccountForReply?.toHexKey()?.let { pubkey ->
                                replyStorageManager.loadCategories(pubkey)
                            } ?: DefaultRelayCategories.getAllDefaultCategories()
                        }
                        val replyRelayProfiles = remember(currentAccountForReply) {
                            currentAccountForReply?.toHexKey()?.let { pubkey ->
                                replyStorageManager.loadProfiles(pubkey)
                            } ?: emptyList()
                        }
                        val replyOutboxRelays = remember(currentAccountForReply?.npub) {
                            accountStateViewModel.getOutboxRelaysForPublish()
                        }
                        val replyMyPubkeyHex = currentAccountForReply?.toHexKey()
                        val replyMyAuthor = remember(replyMyPubkeyHex) {
                            replyMyPubkeyHex?.let { social.mycelium.android.repository.ProfileMetadataCache.getInstance().resolveAuthor(it) }
                        }
                        ComposeTopicReplyScreen(
                            topic = topic,
                            onBack = { navController.popBackStack() },
                            accountStateViewModel = accountStateViewModel,
                            myAuthor = replyMyAuthor,
                            myOutboxRelays = replyOutboxRelays,
                            relayCategories = replyRelayCategories,
                            relayProfiles = replyRelayProfiles,
                            draftId = topicReplyDraftId
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                composable(
                    route = "reply_compose?rootId={rootId}&rootPubkey={rootPubkey}&parentId={parentId}&parentPubkey={parentPubkey}&replyKind={replyKind}&draftId={draftId}",
                    arguments = listOf(
                        navArgument("rootId") { type = NavType.StringType },
                        navArgument("rootPubkey") { type = NavType.StringType },
                        navArgument("parentId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("parentPubkey") { type = NavType.StringType; defaultValue = "" },
                        navArgument("replyKind") { type = NavType.IntType; defaultValue = 1111 },
                        navArgument("draftId") { type = NavType.StringType; defaultValue = "" }
                    ),
                    popExitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) { backStackEntry ->
                    val appState by appViewModel.appState.collectAsState()
                    val rootId = backStackEntry.arguments?.getString("rootId") ?: return@composable
                    val rootPubkey = backStackEntry.arguments?.getString("rootPubkey") ?: return@composable
                    val parentId = backStackEntry.arguments?.getString("parentId").orEmpty().takeIf { it.isNotEmpty() }
                    val parentPubkey = backStackEntry.arguments?.getString("parentPubkey").orEmpty().takeIf { it.isNotEmpty() }
                    val composeReplyKind = backStackEntry.arguments?.getInt("replyKind") ?: 1111
                    val replyDraftId = backStackEntry.arguments?.getString("draftId").orEmpty().takeIf { it.isNotEmpty() }
                    val threadReplyContext = LocalContext.current
                    val threadReplyStorageManager = remember(threadReplyContext) { RelayStorageManager(threadReplyContext) }
                    val currentAccountForThreadReply by accountStateViewModel.currentAccount.collectAsState()
                    val threadReplyCategories = remember(currentAccountForThreadReply) {
                        currentAccountForThreadReply?.toHexKey()?.let { pubkey ->
                            threadReplyStorageManager.loadCategories(pubkey)
                        } ?: DefaultRelayCategories.getAllDefaultCategories()
                    }
                    val threadReplyProfiles = remember(currentAccountForThreadReply) {
                        currentAccountForThreadReply?.toHexKey()?.let { pubkey ->
                            threadReplyStorageManager.loadProfiles(pubkey)
                        } ?: emptyList()
                    }
                    val threadReplyOutbox = remember(currentAccountForThreadReply?.npub) {
                        accountStateViewModel.getOutboxRelaysForPublish()
                    }
                    val threadReplyMyHex = currentAccountForThreadReply?.toHexKey()
                    val threadReplyMyAuthor = remember(threadReplyMyHex) {
                        threadReplyMyHex?.let { social.mycelium.android.repository.ProfileMetadataCache.getInstance().resolveAuthor(it) }
                    }
                    ReplyComposeScreen(
                        replyToNote = appState.replyToNote,
                        rootId = rootId,
                        rootPubkey = rootPubkey,
                        parentId = parentId,
                        parentPubkey = parentPubkey,
                        draftId = replyDraftId,
                        onPublish = { rId, rPk, pId, pPk, content, relayUrls, taggedPubkeys ->
                            if (composeReplyKind == 1) {
                                accountStateViewModel.publishKind1Reply(rId, rPk, pId, pPk, content, relayUrls, taggedPubkeys)
                            } else {
                                accountStateViewModel.publishThreadReply(rId, rPk, pId, pPk, content, relayUrls, taggedPubkeys)
                            }
                        },
                        onBack = {
                            appViewModel.setReplyToNote(null)
                            navController.popBackStack()
                        },
                        myAuthor = threadReplyMyAuthor,
                        myOutboxRelays = threadReplyOutbox,
                        relayCategories = threadReplyCategories,
                        relayProfiles = threadReplyProfiles
                    )
                }
            } // end NavHost

            // Bottom nav — inside content Box (not Scaffold bottomBar) so ModalNavigationDrawer sidebar renders above it
            androidx.compose.animation.AnimatedVisibility(
                visible = showBottomNav && allowBottomNavVisible && !isDrawerOpen,
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(300)
                ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                exit = androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(200)
                ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f)
            ) {
                ScrollAwareBottomNavigationBar(
                    currentDestination = currentDestination,
                    onDestinationClick = { destination ->
                        if (destination == currentDestination) {
                            when (destination) {
                                "home" -> coroutineScope.launch { dashboardListState.scrollToItem(0) }
                                "topics" -> coroutineScope.launch { topicsListState.scrollToItem(0) }
                            }
                            return@ScrollAwareBottomNavigationBar
                        }
                        when (destination) {
                            "home" ->
                                navController.navigate("dashboard") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "messages" ->
                                navController.navigate("messages") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "wallet" ->
                                navController.navigate("wallet") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "announcements" ->
                                navController.navigate("announcements") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "topics" ->
                                navController.navigate("topics") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "notifications" ->
                                navController.navigate("notifications") {
                                    popUpTo("dashboard") { inclusive = false }
                                    launchSingleTop = true
                                }
                            "profile" ->
                                currentAccount?.toHexKey()?.let { pubkey ->
                                    navController.navigateToProfile(pubkey)
                                }
                        }
                    },
                    isVisible = true,
                    notificationCount = notificationUnseenCount,
                    topAppBarState = topAppBarState
                )
            }

            // Snackbar host — positioned above bottom nav so messages aren't hidden
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .zIndex(2f),
                snackbar = { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                }
            )

            // PiP mini-player overlay — floats above all screens (zIndex ensures it stays above media)
            PipStreamOverlay(
                onTapToReturn = { addressableId ->
                    val encoded = android.net.Uri.encode(addressableId)
                    navController.navigate("live_stream/$encoded") { launchSingleTop = true }
                },
                onVideoTapToReturn = { videoUrl ->
                    // Reclaim PiP player and return it to the pool so video_viewer
                    // can acquire it seamlessly instead of creating a duplicate.
                    val reclaimed = PipStreamManager.reclaimPlayer()
                    val reclaimedInstanceKey = reclaimed?.instanceKey
                    if (reclaimed != null && reclaimedInstanceKey != null) {
                        SharedPlayerPool.returnToPool(reclaimedInstanceKey, videoUrl, reclaimed.player)
                    }
                    appViewModel.openVideoViewer(listOf(videoUrl), 0, instanceKey = reclaimedInstanceKey)
                    navController.navigate("video_viewer") { launchSingleTop = true }
                },
                modifier = Modifier.zIndex(Float.MAX_VALUE)
            )
        }
    }
}

/** Navigation extension functions for type-safe navigation */
private fun NavController.navigateToProfile(authorId: String) {
    navigate("profile/$authorId")
}

/**
 * Push thread screen onto back stack. Back pops to the previous destination (dashboard, topics, or
 * another thread) so feed state and scroll position are preserved without bleed.
 */
private fun NavController.navigateToThread(noteId: String, replyKind: Int = 1, highlightReplyId: String? = null) {
    val suffix = highlightReplyId?.let { "&highlightReplyId=${android.net.Uri.encode(it)}" } ?: ""
    navigate("thread/$noteId?replyKind=$replyKind$suffix")
}
