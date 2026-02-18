package social.mycelium.android.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
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
import social.mycelium.android.ui.screens.GeneralSettingsScreen
import social.mycelium.android.ui.screens.AccountPreferencesScreen
import social.mycelium.android.ui.screens.AppearanceSettingsScreen
import social.mycelium.android.ui.screens.MediaSettingsScreen
import social.mycelium.android.ui.screens.ComposeNoteScreen
import social.mycelium.android.ui.screens.ComposeTopicScreen
import social.mycelium.android.ui.screens.ComposeTopicReplyScreen
import social.mycelium.android.ui.screens.DashboardScreen
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
import social.mycelium.android.ui.screens.RelayHealthScreen
import social.mycelium.android.ui.screens.RelayManagementScreen
import social.mycelium.android.ui.screens.SettingsScreen
import social.mycelium.android.ui.screens.LiveExplorerScreen
import social.mycelium.android.ui.screens.LiveStreamScreen
import social.mycelium.android.ui.screens.OnboardingScreen
import social.mycelium.android.ui.components.PipStreamManager
import social.mycelium.android.ui.components.PipStreamOverlay
import social.mycelium.android.ui.screens.QrCodeScreen
import social.mycelium.android.ui.screens.ReplyComposeScreen
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

    // Observe async toast messages (e.g. reaction failures)
    val toastMsg by accountStateViewModel.toastMessage.collectAsState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            accountStateViewModel.clearToast()
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
            }
        }
        DisposableEffect(activityLauncher, launcher) {
            val launcherFn: (Intent) -> Unit = { intent ->
                try {
                    launcher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Amber signer not found", Toast.LENGTH_SHORT).show()
                }
            }
            activityLauncher.registerForegroundLauncher(launcherFn)
            onDispose {
                activityLauncher.unregisterForegroundLauncher(launcherFn)
            }
        }
    }

    // Overlay thread state – hoisted so the Scaffold can hide the bottom nav when a thread overlay is active
    var overlayThreadNoteId by remember { mutableStateOf<String?>(null) }
    var overlayTopicThreadNoteId by remember { mutableStateOf<String?>(null) }

    // Determine current route to show/hide bottom nav
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Clear overlays when navigating away from their host screen.
    // Preserve overlay when navigating to screens that should return to the thread on back
    // (profile, image/video viewer, thread, reply compose, QR).
    val overlayPreserveRoutes = setOf("dashboard", "image_viewer", "video_viewer", "user_qr", "reply_compose")
    LaunchedEffect(currentRoute) {
        val route = currentRoute ?: return@LaunchedEffect
        val preserveDashboardOverlay = route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
        if (!preserveDashboardOverlay) {
            overlayThreadNoteId = null
        }
        val preserveTopicOverlay = route == "topics" || route in overlayPreserveRoutes
                || route.startsWith("profile/")
                || route.startsWith("thread/")
        if (!preserveTopicOverlay) {
            overlayTopicThreadNoteId = null
        }
    }

    // Main screens that should show the bottom navigation
    val mainScreenRoutes = setOf("dashboard", "notifications", "relays?tab={tab}", "messages", "wallet", "profile/{authorId}", "user_profile", "topics")
    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
    val showBottomNav = currentAccount != null
        && onboardingComplete
        && currentRoute != "onboarding"
        && (currentRoute in mainScreenRoutes || currentRoute?.startsWith("profile") == true || currentRoute?.startsWith("relays") == true)
        && currentRoute?.startsWith("thread") != true
        && overlayThreadNoteId == null
        && overlayTopicThreadNoteId == null

    // Defer showing bottom bar when returning from thread so pop transition settles (avoids flash)
    var allowBottomNavVisible by remember { mutableStateOf(true) }
    val isMainScreen = (currentRoute in mainScreenRoutes || currentRoute?.startsWith("profile") == true || currentRoute?.startsWith("relays") == true) &&
            currentRoute?.startsWith("thread") != true && overlayThreadNoteId == null && overlayTopicThreadNoteId == null
    LaunchedEffect(currentRoute, overlayThreadNoteId, overlayTopicThreadNoteId) {
        if (!isMainScreen) {
            allowBottomNavVisible = false
        } else if (!allowBottomNavVisible) {
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
                currentRoute?.startsWith("relays") == true -> "relays"
                currentRoute?.startsWith("profile") == true -> "profile"
                currentRoute in mainScreenRoutes -> currentRoute ?: "home"
                else -> "home"
            }

    // Real notification count from NotificationsRepository (subscription started below when account + relays ready)
    val notificationUnseenCount by NotificationsRepository.unseenCount.collectAsState(initial = 0)

    // Cached reply counts (updated when user opens a thread); used by Dashboard and Profile cards
    val replyCountByNoteId by social.mycelium.android.repository.ReplyCountCache.replyCountByNoteId.collectAsState()
    // Zap/reaction counts from kind-7 and kind-9735; used by Dashboard and Profile cards
    val noteCountsByNoteId by social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.collectAsState()

    // Initialize notifications persistence (SharedPreferences for seen IDs)
    LaunchedEffect(Unit) { NotificationsRepository.init(context) }

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
        // Retry up to 10 times (5 seconds total) waiting for relay data to load.
        // Notifications need to query ALL user relays for p-tagged events — outbox relays
        // are where our notes live, so others' reactions/replies land there too.
        var allUserRelayUrls = emptyList<String>()
        for (attempt in 1..10) {
            val categories = storageManager.loadCategories(pubkey)
            val outboxRelays = storageManager.loadOutboxRelays(pubkey)
            val inboxRelays = storageManager.loadInboxRelays(pubkey)
            // NOTE: indexer relays are intentionally excluded from feed/notification
            // connections — they are only for NIP-65 lookups during onboarding.

            val categoryUrls = categories.flatMap { it.relays }.map { it.url }
            val outboxUrls = outboxRelays.map { it.url }
            val inboxUrls = inboxRelays.map { it.url }

            allUserRelayUrls = (categoryUrls + outboxUrls + inboxUrls)
                .map { it.trim().removeSuffix("/") }
                .distinct()

            Log.d(
                "MyceliumNav",
                "Notif sub attempt $attempt: categories=${categoryUrls.size}, outbox=${outboxUrls.size}, " +
                    "inbox=${inboxUrls.size}, total=${allUserRelayUrls.size}"
            )

            if (allUserRelayUrls.isNotEmpty()) break
            kotlinx.coroutines.delay(500)
        }
        if (allUserRelayUrls.isNotEmpty()) {
            val indexerUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            NotificationsRepository.setCacheRelayUrls(indexerUrls)
            NotificationsRepository.startSubscription(pubkey, allUserRelayUrls)
            Log.d("MyceliumNav", "Notif subscription started with ${allUserRelayUrls.size} relays")
            // Load kind:30073 anchor subscriptions (favorites) for this user
            accountStateViewModel.requestMySubscriptions()
        } else {
            Log.w("MyceliumNav", "Notif subscription FAILED: no relay URLs found after 10 attempts for ${pubkey.take(8)}")
        }
    }

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

    // Show toast when needed
    LaunchedEffect(shouldShowExitToast) {
        if (shouldShowExitToast) {
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            shouldShowExitToast = false
        }
    }

    // Handle back press on main screens
    BackHandler(enabled = currentRoute in mainScreenRoutes) {
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

            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBottomNav && allowBottomNavVisible,
                    enter = androidx.compose.animation.slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = androidx.compose.animation.core.tween(300)
                    ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                    exit = androidx.compose.animation.slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = androidx.compose.animation.core.tween(200)
                    ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
                ) {
                    ScrollAwareBottomNavigationBar(
                        currentDestination = currentDestination,
                        onDestinationClick = { destination ->
                            when (destination) {
                                "home" -> {
                                    if (currentDestination == "home") {
                                        coroutineScope.launch {
                                            dashboardListState.animateScrollToItem(0)
                                        }
                                    } else {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                                "messages" -> { /* TODO: Navigate to DMs when screen is ready */ }
                                "wallet" ->
                                    navController.navigate("wallet") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                "relays" ->
                                    navController.navigate("relays") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                "topics" -> {
                                    if (currentDestination == "topics") {
                                        coroutineScope.launch {
                                            topicsListState.animateScrollToItem(0)
                                        }
                                    } else {
                                        navController.navigate("topics") {
                                            popUpTo("dashboard") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                                "notifications" ->
                                    navController.navigate("notifications") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                "profile" ->
                                    currentAccount?.toHexKey()?.let { pubkey ->
                                        navController.navigateToProfile(pubkey)
                                    } ?: run {
                                        navController.navigate("dashboard")
                                    }
                            }
                        },
                        isVisible = true,
                        notificationCount = notificationUnseenCount,
                        topAppBarState = topAppBarState
                    )
                }
            }
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = scaffoldPadding.calculateTopPadding())) {
            // Don't render NavHost until account state is resolved — prevents dashboard flash
            if (!accountsRestored) {
                // Show nothing (or a loading indicator) while accounts are being restored
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Intentionally blank — splash screen covers this
                }
            } else {
            NavHost(
                    navController = navController,
                    startDestination = if (needsOnboarding) "onboarding" else "dashboard",
                    enterTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                // Fade through transition for navigation bar navigation
                                fadeIn(animationSpec = tween(210, delayMillis = 90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideIntoContainer(
                                        towards =
                                                AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardDecelerate
                                                )
                                ) + fadeIn(animationSpec = tween(300))
                            }
                        }
                    },
                    exitTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                // Fade through transition for navigation bar navigation
                                fadeOut(animationSpec = tween(90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideOutOfContainer(
                                        towards =
                                                AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardAccelerate
                                                )
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        }
                    },
                    popEnterTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        val noAnimRoutes = setOf("image_viewer", "video_viewer", "reply_compose?rootId={rootId}&rootPubkey={rootPubkey}&parentId={parentId}&parentPubkey={parentPubkey}")
                        when {
                            initialState.destination.route in noAnimRoutes -> {
                                EnterTransition.None
                            }
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                fadeIn(animationSpec = tween(210, delayMillis = 90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardDecelerate
                                                )
                                ) + fadeIn(animationSpec = tween(300))
                            }
                        }
                    },
                    popExitTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        val noAnimRoutes = setOf("image_viewer", "video_viewer", "reply_compose?rootId={rootId}&rootPubkey={rootPubkey}&parentId={parentId}&parentPubkey={parentPubkey}")
                        when {
                            initialState.destination.route in noAnimRoutes -> {
                                ExitTransition.None
                            }
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                fadeOut(animationSpec = tween(90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardAccelerate
                                                )
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        }
                    }
            ) {
                // Dashboard - Home feed (thread opens as overlay so feed stays visible for slide-back)
                composable("dashboard") {
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

                    Box(modifier = Modifier.fillMaxSize()) {
                        DashboardScreen(
                            isSearchMode = appState.isSearchMode,
                            onSearchModeChange = { appViewModel.updateSearchMode(it) },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onNavigateTo = { screen ->
                                when {
                                    screen == "settings" -> navController.navigate("settings")
                                    screen.startsWith("relays") -> navController.navigate(screen)
                                    screen == "notifications" -> navController.navigate("notifications")
                                    screen == "messages" -> navController.navigate("messages")
                                    screen == "user_profile" -> currentAccount?.toHexKey()?.let { navController.navigateToProfile(it) }
                                    screen == "compose" -> navController.navigate("compose")
                                    screen == "topics" -> navController.navigate("topics") {
                                        popUpTo("dashboard") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    screen.startsWith("live_stream/") -> navController.navigate(screen)
                                    screen == "live_explorer" -> navController.navigate("live_explorer") {
                                        launchSingleTop = true
                                    }
                                    screen == "relay_discovery" -> navController.navigate("relay_discovery")
                                    screen == "onboarding" -> navController.navigate("onboarding") {
                                        launchSingleTop = true
                                    }
                                    screen.startsWith("settings/") -> navController.navigate(screen)
                                }
                            },
                            onThreadClick = { note, _ ->
                                feedStateViewModel.saveHomeScrollPosition(
                                    dashboardListState.firstVisibleItemIndex,
                                    dashboardListState.firstVisibleItemScrollOffset
                                )
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) })
                                overlayThreadNoteId = note.id
                            },
                            onImageTap = { note, _, _ ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) })
                                overlayThreadNoteId = note.id
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
                                    dashboardListState.animateScrollToItem(0)
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
                            isDashboardVisible = (currentRoute == "dashboard"),
                            onQrClick = { navController.navigate("user_qr") },
                            onSidebarRelayHealthClick = {
                                navController.navigate("settings/relay_health") {
                                    launchSingleTop = true
                                }
                            },
                            onSidebarRelayDiscoveryClick = {
                                navController.navigate("relay_discovery") {
                                    launchSingleTop = true
                                }
                            },
                            onRelayClick = { relayUrl ->
                                val encoded = android.net.Uri.encode(relayUrl)
                                navController.navigate("relay_log/$encoded")
                            }
                        )

                        // Intercept system back gesture when overlay thread is showing
                        BackHandler(enabled = overlayThreadNoteId != null) {
                            overlayThreadNoteId = null
                        }

                        // Thread overlay: feed stays underneath so slide-back reveals it; slide in from right like nav thread.
                        // Keep last overlay note in state so exit animation has content to run on (don't clear content before exit).
                        val overlayNote = appState.selectedNote
                        val showThreadOverlay = overlayThreadNoteId != null && overlayNote != null && overlayNote.id == overlayThreadNoteId
                        var lastOverlayNoteId by remember { mutableStateOf<String?>(null) }
                        var lastOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                        if (showThreadOverlay && overlayThreadNoteId != null && overlayNote != null) {
                            lastOverlayNoteId = overlayThreadNoteId
                            lastOverlayNote = overlayNote
                        }
                        val contentNoteId = if (showThreadOverlay) overlayThreadNoteId else lastOverlayNoteId
                        val contentNote = if (showThreadOverlay) overlayNote else lastOverlayNote
                        AnimatedVisibility(
                            visible = showThreadOverlay,
                            enter = slideInHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            ) { fullWidth -> fullWidth },
                            exit = slideOutHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            ) { fullWidth -> fullWidth }
                        ) {
                            if (contentNoteId != null && contentNote != null) {
                                val noteId = contentNoteId
                                val relayUrls = ((appState.threadRelayUrls ?: emptyList()) + fallbackRelayUrls).distinct()
                                val savedScrollState = threadStateHolder.getScrollState(noteId)
                                val threadListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                                )
                                val commentStates = threadStateHolder.getCommentStates(noteId)
                                var expandedControlsCommentId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                                }
                                var expandedControlsReplyId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                                }
                                val threadTopAppBarState = rememberTopAppBarState()
                                val authState by accountStateViewModel.authState.collectAsState()
                                DisposableEffect(noteId) {
                                    onDispose {
                                        threadStateHolder.saveScrollState(noteId, threadListState)
                                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                                        threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                                    }
                                }
                                ThreadSlideBackBox(onBack = { overlayThreadNoteId = null }) {
                                    ModernThreadViewScreen(
                                        note = contentNote,
                                        comments = emptyList(),
                                        listState = threadListState,
                                        commentStates = commentStates,
                                        expandedControlsCommentId = expandedControlsCommentId,
                                        onExpandedControlsChange = { expandedControlsCommentId = if (expandedControlsCommentId == it) null else it },
                                        expandedControlsReplyId = expandedControlsReplyId,
                                        onExpandedControlsReplyChange = { replyId ->
                                            expandedControlsReplyId = if (expandedControlsReplyId == replyId) null else replyId
                                        },
                                        topAppBarState = threadTopAppBarState,
                                        replyKind = 1,
                                        relayUrls = relayUrls,
                                        cacheRelayUrls = cacheRelayUrls,
                                        onBackClick = { overlayThreadNoteId = null },
                                        onProfileClick = { navController.navigateToProfile(it) },
                                        onNoteClick = { clickedNote ->
                                            // Store both the current overlay note and the clicked note
                                            if (contentNote != null) appViewModel.storeNoteForThread(contentNote)
                                            appViewModel.storeNoteForThread(clickedNote)
                                            overlayThreadNoteId = null
                                            // Push original thread onto backstack, then quoted thread on top
                                            navController.navigate("thread/${contentNote!!.id}?replyKind=1") {
                                                launchSingleTop = true
                                            }
                                            navController.navigate("thread/${clickedNote.id}?replyKind=1")
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
                                            if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                        },
                                        onCustomZapSend = { note, amount, zapType, msg ->
                                            val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                            if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        },
                                        onZap = { nId, amount ->
                                            if (contentNote != null && contentNote.id == nId) {
                                                val err = accountStateViewModel.sendZap(contentNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                        zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                        myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
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
                                        onHeaderQrCodeClick = { navController.navigate("user_qr") },
                                        onHeaderSettingsClick = { navController.navigate("settings") },
                                        mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                                        onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                                        onRelayNavigate = { relayUrl ->
                                            val encoded = android.net.Uri.encode(relayUrl)
                                            navController.navigate("relay_log/$encoded")
                                        }
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

                    // Read returned indexer URLs from discovery selection screen
                    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
                    val returnedIndexers by savedStateHandle
                        ?.getStateFlow<ArrayList<String>>("selected_indexers", arrayListOf())
                        ?.collectAsState()
                        ?: remember { mutableStateOf(arrayListOf()) }

                    android.util.Log.d("OnboardingNav", "returnedIndexers=${returnedIndexers.size}: ${returnedIndexers.take(3)}")

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
                        returnedIndexerUrls = returnedIndexers.toList(),
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

                        // Helper: fetch a note by id from cache or relays
                        suspend fun fetchNote(id: String): social.mycelium.android.data.Note? {
                            // Check ViewModel store first
                            appState.notesById[id]?.let { return it }
                            if (appState.selectedNote?.id == id) return appState.selectedNote
                            // Cache
                            NotesRepository.getInstance().getNoteFromCache(id)?.let { return it }
                            // Relay fetch
                            val pubkey = currentAccountForRoot?.toHexKey() ?: return null
                            val sm = social.mycelium.android.repository.RelayStorageManager(context)
                            val categories = sm.loadCategories(pubkey)
                            val relayUrls = categories.flatMap { it.relays }.map { it.url }.distinct()
                            if (relayUrls.isEmpty()) return null
                            return NotesRepository.getInstance().fetchNoteById(id, relayUrls)
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
                        val visited = mutableSetOf<String>(rawNote.id) // cycle detection
                        var hops = 0
                        var trueRoot: social.mycelium.android.data.Note? = null

                        while (hops < MAX_ROOT_WALK_HOPS) {
                            hops++
                            if (currentId in visited) break // cycle
                            visited.add(currentId)

                            val fetched = fetchNote(currentId)
                            if (fetched == null) {
                                // Can't fetch this note — use the last known id as root
                                break
                            }

                            val nextRootId = fetched.rootNoteId?.takeIf { it != fetched.id && it.isNotBlank() }
                            if (nextRootId == null) {
                                // This note is NOT a reply — it's the true root!
                                trueRoot = fetched
                                break
                            }

                            // This note is also a reply — keep walking up
                            currentId = nextRootId!!
                        }

                        if (trueRoot != null) {
                            resolvedRootNote = trueRoot
                            appViewModel.storeNoteForThread(trueRoot)
                        } else {
                            // Couldn't find true root — fall back to rawNote
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

                        // Thread gets its own TopAppBarState so scrolling thread doesn't mutate
                        // the feed's header state — prevents header flash on gesture back
                        val threadTopAppBarState = rememberTopAppBarState()

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
                            topAppBarState = threadTopAppBarState,
                            replyKind = replyKind,
                            highlightReplyId = resolvedHighlightReplyId,
                            relayUrls = relayUrls,
                            cacheRelayUrls = cacheRelayUrls,
                            onBackClick = { navController.popBackStack() },
                            onLike = { /* TODO: Handle like */},
                            onShare = { /* TODO: Handle share */},
                            onComment = { /* TODO: Handle comment */},
                            onProfileClick = { authorId ->
                                // Navigate to profile - adds to backstack
                                navController.navigateToProfile(authorId)
                            },
                            onNoteClick = { clickedNote ->
                                // Store note in map (doesn't overwrite selectedNote) and navigate
                                appViewModel.storeNoteForThread(clickedNote)
                                navController.navigate("thread/${clickedNote.id}?replyKind=1")
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
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { nId, amount ->
                                if (resolvedNote.id == nId) {
                                    val err = accountStateViewModel.sendZap(resolvedNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSendZap = { targetNote, amount ->
                                val err = accountStateViewModel.sendZap(targetNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                            zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                            myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                            onCommentLike = { /* TODO: Handle comment like */},
                            onCommentReply = { /* Handled inside ModernThreadViewScreen when replyKind==1111 */},
                            onPublishThreadReply = if (replyKind == 1111) { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                accountStateViewModel.publishThreadReply(rootId, rootPubkey, parentId, parentPubkey, content)
                            } } else null,
                            onOpenReplyCompose = if (replyKind == 1111) { { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                appViewModel.setReplyToNote(replyToNote)
                                val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}")
                            } } else null,
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
                            }
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

                // Full-screen image viewer (Save, HD, back) — instant back, no slide
                composable(
                    "image_viewer",
                    enterTransition = { fadeIn(animationSpec = tween(150)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    val appState by appViewModel.appState.collectAsState()
                    val urls = appState.imageViewerUrls
                    if (urls != null) {
                        val selectedNoteId = appState.selectedNote?.id
                        ImageContentViewerScreen(
                            urls = urls,
                            initialIndex = appState.imageViewerInitialIndex,
                            onBackClick = {
                                appViewModel.clearImageViewer()
                                navController.popBackStack()
                            },
                            onPageChanged = { page ->
                                if (selectedNoteId != null) {
                                    appViewModel.updateMediaPage(selectedNoteId, page)
                                }
                            }
                        )
                    }
                }

                // Full-screen video viewer — instant back, no slide
                composable(
                    "video_viewer",
                    enterTransition = { fadeIn(animationSpec = tween(150)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    // Kill PiP when any media goes fullscreen
                    LaunchedEffect(Unit) { PipStreamManager.kill() }
                    val appState by appViewModel.appState.collectAsState()
                    val urls = appState.videoViewerUrls
                    if (urls != null) {
                        VideoContentViewerScreen(
                            urls = urls,
                            initialIndex = appState.videoViewerInitialIndex,
                            onBackClick = {
                                appViewModel.clearVideoViewer()
                                navController.popBackStack()
                            }
                        )
                    }
                }

                // NIP-53 Live broadcast explorer
                composable(
                    route = "live_explorer",
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(300)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(300)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(300)
                        )
                    }
                ) {
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
                    arguments = listOf(navArgument("addressableId") { type = NavType.StringType }),
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(300)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(300)
                        )
                    }
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

                    val authorNotes = dashboardState.notes.filter { it.author.id.lowercase() == author.id.lowercase() }
                    val profileListState = rememberLazyListState()
                    val followList = dashboardState.followList
                    val isFollowing = followList.isNotEmpty() && author.id.lowercase() in followList.map { it.lowercase() }.toSet()
                    // Relay orb tap navigates to relay_log page via onRelayClick callback
                    val zapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val zappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val zappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = authorNotes,
                            listState = profileListState,
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { noteId, amount ->
                                val n = authorNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                } else {
                                    // Refresh follow list so the button updates
                                    currentAccount?.toHexKey()?.let { pubkey ->
                                        dashboardViewModel.loadFollowList(pubkey, cacheUrls, forceRefresh = true)
                                    }
                                }
                            },
                            onMessageClick = { /* TODO: Open DM */ }
                    )
                    // Relay orb tap now navigates to relay_log page via onRelayClick callback
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

                    val userNotes = emptyList<social.mycelium.android.data.Note>()
                    val userProfileListState = rememberLazyListState()
                    // Relay orb tap navigates to relay_log page via onRelayClick callback
                    val userZapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val userZappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val userZappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = userNotes,
                            listState = userProfileListState,
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { noteId, amount ->
                                val n = userNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
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
                            onMessageClick = { }
                    )
                    // Relay orb tap now navigates to relay_log page via onRelayClick callback
                }

                // Settings — feed and relay connections persist; no disconnect when visiting settings.
                composable("settings") {
                    SettingsScreen(
                            onBackClick = { navController.popBackStack() },
                            onNavigateTo = { screen ->
                                when (screen) {
                                    "general" -> navController.navigate("settings/general")
                                    "appearance" -> navController.navigate("settings/appearance")
                                    "media" -> navController.navigate("settings/media")
                                    "account_preferences" ->
                                            navController.navigate("settings/account_preferences")
                                    "about" -> navController.navigate("settings/about")
                                    "relay_health" -> navController.navigate("settings/relay_health")
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
                composable("settings/general") {
                    GeneralSettingsScreen(onBackClick = { navController.popBackStack() })
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

                composable("settings/about") {
                    AboutScreen(
                        onBackClick = { navController.popBackStack() },
                        onProfileClick = { pubkey -> navController.navigate("profile/$pubkey") }
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
                        onProfileClick = { authorId ->
                            navController.navigateToProfile(authorId)
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
                            navController.navigate("relay_log/$encoded")
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
                    RelayLogScreen(
                        relayUrl = relayUrl,
                        onBack = { navController.popBackStack() }
                    )
                }

                // Notifications - Can navigate to threads and profiles
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

                    NotificationsScreen(
                            listState = notificationsListState,
                            selectedTabIndex = notificationsSelectedTab,
                            onTabSelected = { notificationsSelectedTab = it },
                            onBackClick = {
                                navController.popBackStack()
                            },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onOpenThreadForRootId = { rootNoteId, replyKind, replyNoteId, targetNote ->
                                // Store the target note (may be the root or an intermediate reply)
                                // so the thread composable can start immediately. The thread
                                // composable's recursive root walk-up will find the TRUE root
                                // and display the full thread, scrolling to the tapped reply.
                                if (targetNote != null) {
                                    appViewModel.storeNoteForThread(targetNote)
                                }
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(rootNoteId, replyKind, replyNoteId)
                                // Async-fetch the rootNoteId note so the thread composable
                                // has it available for the walk-up (may already be cached)
                                coroutineScope.launch(Dispatchers.IO) {
                                    var note = NotesRepository.getInstance().getNoteFromCache(rootNoteId)
                                    if (note == null) {
                                        val pubkey = currentAccount?.toHexKey() ?: return@launch
                                        val categories = storageManager.loadCategories(pubkey)
                                        val relayUrls = categories.flatMap { it.relays }.map { it.url }.distinct()
                                        if (relayUrls.isNotEmpty()) {
                                            note = NotesRepository.getInstance().fetchNoteById(rootNoteId, relayUrls)
                                        }
                                    }
                                    if (note != null) {
                                        withContext(Dispatchers.Main.immediate) {
                                            appViewModel.storeNoteForThread(note)
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
                            topAppBarState = topAppBarState
                    )
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
                                onNavigateTo = { destination ->
                                    if (destination == "dashboard") {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        navController.navigate(destination)
                                    }
                                },
                                onThreadClick = { note, relayUrls ->
                                    appViewModel.updateSelectedNote(note)
                                    appViewModel.updateThreadRelayUrls(relayUrls)
                                    overlayTopicThreadNoteId = note.id
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
                                onQrClick = { navController.navigate("user_qr") },
                                onSidebarRelayHealthClick = {
                                    navController.navigate("settings/relay_health") {
                                        launchSingleTop = true
                                    }
                                },
                                onSidebarRelayDiscoveryClick = {
                                    navController.navigate("relay_discovery") {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToCreateTopic = { hashtag ->
                                    val encoded = android.net.Uri.encode(hashtag ?: "")
                                    navController.navigate("compose_topic?hashtag=$encoded")
                                },
                                onRelayClick = { relayUrl ->
                                    val encoded = android.net.Uri.encode(relayUrl)
                                    navController.navigate("relay_log/$encoded")
                                }
                        )

                        // Intercept system back gesture when overlay thread is showing
                        BackHandler(enabled = overlayTopicThreadNoteId != null) {
                            overlayTopicThreadNoteId = null
                        }

                        // Thread overlay: feed stays underneath so slide-back reveals it
                        val overlayNote = appState.selectedNote
                        val showTopicOverlay = overlayTopicThreadNoteId != null && overlayNote != null && overlayNote.id == overlayTopicThreadNoteId
                        var lastTopicOverlayNoteId by remember { mutableStateOf<String?>(null) }
                        var lastTopicOverlayNote by remember { mutableStateOf<social.mycelium.android.data.Note?>(null) }
                        if (showTopicOverlay && overlayTopicThreadNoteId != null && overlayNote != null) {
                            lastTopicOverlayNoteId = overlayTopicThreadNoteId
                            lastTopicOverlayNote = overlayNote
                        }
                        val topicContentNoteId = if (showTopicOverlay) overlayTopicThreadNoteId else lastTopicOverlayNoteId
                        val topicContentNote = if (showTopicOverlay) overlayNote else lastTopicOverlayNote
                        AnimatedVisibility(
                            visible = showTopicOverlay,
                            enter = slideInHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            ) { fullWidth -> fullWidth },
                            exit = slideOutHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            ) { fullWidth -> fullWidth }
                        ) {
                            if (topicContentNoteId != null && topicContentNote != null) {
                                val noteId = topicContentNoteId
                                val relayUrls = ((appState.threadRelayUrls ?: emptyList()) + topicFallbackRelayUrls).distinct()
                                val savedScrollState = threadStateHolder.getScrollState(noteId)
                                val threadListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                                )
                                val commentStates = threadStateHolder.getCommentStates(noteId)
                                var expandedControlsCommentId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                                }
                                var expandedControlsReplyId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                                }
                                val threadTopAppBarState = rememberTopAppBarState()
                                val authState by accountStateViewModel.authState.collectAsState()
                                DisposableEffect(noteId) {
                                    onDispose {
                                        threadStateHolder.saveScrollState(noteId, threadListState)
                                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                                        threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                                    }
                                }
                                ThreadSlideBackBox(onBack = { overlayTopicThreadNoteId = null }) {
                                    ModernThreadViewScreen(
                                        note = topicContentNote,
                                        comments = emptyList(),
                                        listState = threadListState,
                                        commentStates = commentStates,
                                        expandedControlsCommentId = expandedControlsCommentId,
                                        onExpandedControlsChange = { expandedControlsCommentId = if (expandedControlsCommentId == it) null else it },
                                        expandedControlsReplyId = expandedControlsReplyId,
                                        onExpandedControlsReplyChange = { replyId ->
                                            expandedControlsReplyId = if (expandedControlsReplyId == replyId) null else replyId
                                        },
                                        topAppBarState = threadTopAppBarState,
                                        replyKind = 1111,
                                        relayUrls = relayUrls,
                                        cacheRelayUrls = topicCacheRelayUrls,
                                        onBackClick = { overlayTopicThreadNoteId = null },
                                        onProfileClick = { navController.navigateToProfile(it) },
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
                                            if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                        },
                                        onCustomZapSend = { note, amount, zapType, msg ->
                                            val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                            if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        },
                                        onZap = { nId, amount ->
                                            if (topicContentNote != null && topicContentNote.id == nId) {
                                                val err = accountStateViewModel.sendZap(topicContentNote, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                        zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                        myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
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
                                        onHeaderQrCodeClick = { navController.navigate("user_qr") },
                                        onHeaderSettingsClick = { navController.navigate("settings") },
                                        mediaPageForNote = { noteId -> appViewModel.getMediaPage(noteId) },
                                        onMediaPageChanged = { noteId, page -> appViewModel.updateMediaPage(noteId, page) },
                                        onRelayNavigate = { relayUrl ->
                                            val encoded = android.net.Uri.encode(relayUrl)
                                            navController.navigate("relay_log/$encoded")
                                        }
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
                                navController.navigate("reply_compose?rootId=$rootId&rootPubkey=$rootPubkey")
                            },
                            onReplyKind1Click = {
                                // Navigate to kind:1 reply with I tags
                                val encoded = android.net.Uri.encode(topicId)
                                navController.navigate("compose_topic_reply/$encoded")
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onImageTap = { note, urls, index ->
                                appViewModel.updateSelectedNote(note)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, index ->
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, index ->
                                navController.navigate("video_viewer")
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

                composable("compose") {
                    val composeContext = LocalContext.current
                    val composeStorageManager = remember(composeContext) { RelayStorageManager(composeContext) }
                    val currentAccountForCompose by accountStateViewModel.currentAccount.collectAsState()
                    val relayCategoriesForCompose = remember(currentAccountForCompose) {
                        currentAccountForCompose?.toHexKey()?.let { pubkey ->
                            composeStorageManager.loadCategories(pubkey)
                        } ?: DefaultRelayCategories.getAllDefaultCategories()
                    }
                    ComposeNoteScreen(
                        onBack = { navController.popBackStack() },
                        accountStateViewModel = accountStateViewModel,
                        relayCategories = relayCategoriesForCompose
                    )
                }

                composable(
                    route = "compose_topic?hashtag={hashtag}",
                    arguments = listOf(
                        navArgument("hashtag") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val hashtagArg = backStackEntry.arguments?.getString("hashtag").orEmpty()
                    val initialHashtag = hashtagArg.takeIf { it.isNotEmpty() }
                    ComposeTopicScreen(
                        initialHashtag = initialHashtag,
                        onPublish = { title, content, tags ->
                            accountStateViewModel.publishTopic(title, content, tags)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                // Compose kind:1 reply to topic with I tags (mesh network reply)
                composable(
                    route = "compose_topic_reply/{topicId}",
                    arguments = listOf(
                        navArgument("topicId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val topicId = backStackEntry.arguments?.getString("topicId") ?: return@composable
                    val topicsRepository = remember { social.mycelium.android.repository.TopicsRepository.getInstance(context) }
                    val allTopics by topicsRepository.topics.collectAsState()
                    val topic = allTopics[topicId]
                    
                    if (topic != null) {
                        ComposeTopicReplyScreen(
                            topic = topic,
                            onBack = { navController.popBackStack() },
                            accountStateViewModel = accountStateViewModel
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
                    route = "reply_compose?rootId={rootId}&rootPubkey={rootPubkey}&parentId={parentId}&parentPubkey={parentPubkey}",
                    arguments = listOf(
                        navArgument("rootId") { type = NavType.StringType },
                        navArgument("rootPubkey") { type = NavType.StringType },
                        navArgument("parentId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("parentPubkey") { type = NavType.StringType; defaultValue = "" }
                    ),
                    popExitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }
                ) { backStackEntry ->
                    val appState by appViewModel.appState.collectAsState()
                    val rootId = backStackEntry.arguments?.getString("rootId") ?: return@composable
                    val rootPubkey = backStackEntry.arguments?.getString("rootPubkey") ?: return@composable
                    val parentId = backStackEntry.arguments?.getString("parentId").orEmpty().takeIf { it.isNotEmpty() }
                    val parentPubkey = backStackEntry.arguments?.getString("parentPubkey").orEmpty().takeIf { it.isNotEmpty() }
                    ReplyComposeScreen(
                        replyToNote = appState.replyToNote,
                        rootId = rootId,
                        rootPubkey = rootPubkey,
                        parentId = parentId,
                        parentPubkey = parentPubkey,
                        onPublish = { rId, rPk, pId, pPk, content ->
                            accountStateViewModel.publishThreadReply(rId, rPk, pId, pPk, content)
                        },
                        onBack = {
                            appViewModel.setReplyToNote(null)
                            navController.popBackStack()
                        }
                    )
                }
            }
            } // end if (accountsRestored)

            // PiP mini-player overlay — floats above all screens (zIndex ensures it stays above media)
            PipStreamOverlay(
                onTapToReturn = { addressableId ->
                    val encoded = android.net.Uri.encode(addressableId)
                    navController.navigate("live_stream/$encoded")
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
