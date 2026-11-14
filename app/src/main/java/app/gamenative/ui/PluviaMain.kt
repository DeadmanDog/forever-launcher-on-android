package app.gamenative.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.components.BootingSplash
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFSLegacyMigrator
import com.winlator.xenvironment.ImageFsInstaller
import java.io.File
import java.util.EnumSet
import kotlin.reflect.KFunction2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val SNACKBAR_SHOW_TIMEOUT_MS = 15_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }

    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }

    var gameBackAction by remember { mutableStateOf<() -> Unit?>({}) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.LaunchApp -> {
                    if (state.showBootingSplash) {
                        // One game screen: a second LaunchApp event must not stack another one on top of it.
                        navController.navigate(PluviaScreen.XServer.route) { launchSingleTop = true }
                    }
                }

                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (PluviaApp.keepAlive) {
                        gameBackAction?.invoke() ?: run { navController.popBackStack() }
                    } else if (hasBack) {
                        navController.popBackStack()
                    }
                }
            }
        }
    }

    LaunchedEffect(navController) {

        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)

            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->

                viewModel.setCurrentScreen(destination.route)
            }
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }

        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            // Coming back from the game: the game forced landscape; with the phone's auto-rotate off the
            // system would leave the launcher in that position.
            val cameFromGame = state.resettedScreen == PluviaScreen.XServer
            viewModel.setScreen()

            // TODO: remove this if statement once XServerScreen orientation change bug is fixed
            if (state.currentScreen != PluviaScreen.XServer) {
                // Hide or show status bar based on if in game or not
                // The status bar stays hidden outside the game as well (immersive launcher).
                PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))

                // reset system ui visibility based on user preference
                // TODO: add option for user to set
                // reset available orientations: after the game first back to the launcher's portrait position,
                // then free again (so the phone's rotate button still works)
                if (cameFromGame) {
                    PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.PORTRAIT)))
                    kotlinx.coroutines.delay(400)
                }
                PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))
            }
            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    var onActionClick: (() -> Unit)? = null
    when (msgDialogState.type) {

        DialogType.SYNC_FAIL -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.EXECUTABLE_NOT_FOUND -> {
            onConfirmClick = null
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    val snackbarController = LocalSnackbarHostController.current
    var exitSnackbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarController) {
        SnackbarManager.messages.collect { message ->
            if (
                withTimeoutOrNull(SNACKBAR_SHOW_TIMEOUT_MS) {
                    snackbarController.hostState.showSnackbar(message)
                } == null
            ) {

            }
            // snackbar dismissed (timeout or new message) — reset exit flag
            exitSnackbarVisible = false
        }
    }

    BackHandler(enabled = state.loadingDialogVisible && !PluviaApp.keepAlive) {
        // TODO: Make prelaunch/loading operations cancellable so Back can exit safely.
    }

    PluviaTheme {
        // The in-app language reaches every screen, including the game screen, its Quick Menu and the launch splash.
        androidx.compose.runtime.CompositionLocalProvider(app.gamenative.ui.screen.wow.LocalWowHungarian provides
                app.gamenative.ui.screen.wow.WowUiLanguage.isHungarian(app.gamenative.ui.screen.wow.WowUiLanguage.current),
            app.gamenative.ui.screen.wow.LocalWowLanguage provides
                app.gamenative.ui.screen.wow.WowUiLanguage.code(app.gamenative.ui.screen.wow.WowUiLanguage.current)) {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingDialog(
                visible = state.loadingDialogVisible,
                progress = state.loadingDialogProgress,
                message = state.loadingDialogMessage,
            )

            MessageDialog(
                visible = msgDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                confirmBtnText = msgDialogState.confirmBtnText,
                onDismissClick = onDismissClick,
                dismissBtnText = msgDialogState.dismissBtnText,
                onActionClick = onActionClick,
                actionBtnText = msgDialogState.actionBtnText,
                icon = msgDialogState.type.icon,
                title = msgDialogState.title,
                message = msgDialogState.message,
            )

            var initialSplash by remember {
                // The player can choose the home page instead of starting at once (Settings > Quick).
                if (!app.gamenative.ui.screen.wow.WoWLauncherState.autoLaunchEnabled(context)) {
                    app.gamenative.ui.screen.wow.WoWLauncherState.shouldAutoLaunch = false
                }
                // A newer GitHub release that the player has not put off: show the home page with its update card instead.
                if (app.gamenative.ui.screen.wow.WowAppUpdater.pending(context) != null) {
                    app.gamenative.ui.screen.wow.WoWLauncherState.shouldAutoLaunch = false
                }
                mutableStateOf(app.gamenative.ui.screen.wow.WoWLauncherState.shouldAutoLaunch && app.gamenative.ui.screen.wow.GamePath.isReady(context))
            }
            var preLaunchJob by remember { mutableStateOf<Job?>(null) }

            // Tapping the app starts the game at once: the app-start splash is already landscape, no portrait frame.
            LaunchedEffect(Unit) {
                if (initialSplash) {
                    app.gamenative.PluviaApp.events.emit(
                        app.gamenative.events.AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation),
                    )
                }
            }

            DisposableEffect(Unit) {
                val clearSplash: (app.gamenative.events.AndroidEvent.ClearBootingSplash) -> Unit = { initialSplash = false }
                val forceClose: (app.gamenative.events.AndroidEvent.ForceCloseApp) -> Unit = {
                    initialSplash = false
                    preLaunchJob?.cancel()
                    preLaunchJob = null
                    app.gamenative.ui.screen.wow.WoWLauncherState.shouldAutoLaunch = false
                }
                app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.ClearBootingSplash, Unit>(clearSplash)
                app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.ForceCloseApp, Unit>(forceClose)
                onDispose {
                    app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.ClearBootingSplash, Unit>(clearSplash)
                    app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.ForceCloseApp, Unit>(forceClose)
                }
            }

            val splashVisible = state.showBootingSplash || initialSplash
            val launchFraction by produceState(-1f, splashVisible) {
                if (!splashVisible) {
                    app.gamenative.ui.screen.wow.WowLaunchProgress.end()
                    value = -1f
                    return@produceState
                }
                while (true) {
                    withFrameMillis { }
                    value = app.gamenative.ui.screen.wow.WowLaunchProgress.display()
                }
            }
            Box(modifier = Modifier.zIndex(10f)) {
                BootingSplash(
                    visible = splashVisible,
                    progress = launchFraction,
                    text = app.gamenative.ui.screen.wow.tx("Loading...", "Betöltés..."),
                    onAbort = {
                        initialSplash = false
                        preLaunchJob?.cancel()
                        preLaunchJob = null
                        viewModel.setLoadingDialogVisible(false)
                        app.gamenative.ui.screen.wow.WoWLauncherState.shouldAutoLaunch = false
                        viewModel.abortBoot()
                    },
                )
            }

            val startDestination = PluviaScreen.WoWLauncher.route

            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(route = PluviaScreen.WoWLauncher.route) {
                    app.gamenative.ui.screen.wow.WoWForeverScreen(
                        onLaunch = { appId ->
                            viewModel.setLaunchedAppId(appId)
                            viewModel.setOffline(true)
                            preLaunchJob?.cancel()
                            preLaunchJob = preLaunchApp(
                                context = context,
                                appId = appId,
                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,
                                setLoadingProgress = viewModel::setLoadingDialogProgress,
                                setLoadingMessage = viewModel::setLoadingDialogMessage,
                                setMessageDialogState = { msgDialogState = it },
                                onSuccess = viewModel::launchApp,
                            )
                        },
                    )
                }
                /** Game Screen **/
                composable(route = PluviaScreen.XServer.route) {

                    val xServerIsOffline by viewModel.isOffline.collectAsStateWithLifecycle()
                    val launchedAppId = state.launchedAppId
                    val loaded by produceState<Result<Container?>?>(null, launchedAppId) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {  ContainerManager(context.applicationContext).getContainerById(launchedAppId)  }
                        }
                    }
                    if (loaded == null) return@composable
                    val loadedContainer = loaded?.getOrNull()
                    if (loadedContainer == null) {
                        LaunchedEffect(launchedAppId) {

                            navController.navigate(PluviaScreen.WoWLauncher.route) {
                                popUpTo(PluviaScreen.XServer.route) { inclusive = true }
                            }
                        }
                        return@composable
                    }
                    XServerScreen(
                        appId = state.launchedAppId,
                        container = loadedContainer,
                        isOffline = xServerIsOffline,
                        registerBackAction = { cb ->

                            gameBackAction = cb
                        },
                        navigateBack = {
                            CoroutineScope(Dispatchers.Main).launch {
                                val currentRoute = navController.currentBackStackEntry
                                    ?.destination
                                    ?.route

                                if (currentRoute == PluviaScreen.XServer.route) {
                                    navController.popBackStack()
                                }
                            }
                        },
                        onWindowMapped = { context, window ->
                            viewModel.onWindowMapped(context, window, state.launchedAppId)
                        },
                        onExit = { onComplete ->
                            viewModel.exitApp(context, state.launchedAppId, onComplete)
                        },
                        onGameLaunchError = { error ->
                            viewModel.onGameLaunchError(error)
                        },
                    )
                }
            }

            if (snackbarController.rootOwnsHost) {
                SnackbarHost(
                    hostState = snackbarController.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(bottom = 16.dp),
                ) { data ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 4.dp,
                        ) {
                            Text(
                                text = data.visuals.message,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

        }
        }
    }
}

fun preLaunchApp(
    context: Context,
    appId: String,
    setLoadingDialogVisible: (Boolean) -> Unit,
    setLoadingProgress: (Float) -> Unit,
    setLoadingMessage: (String) -> Unit,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onSuccess: KFunction2<Context, String, Unit>,
): Job {
    setLoadingDialogVisible(true)

    return CoroutineScope(Dispatchers.IO).launch {
        // Review 2026-11-13: every way this ends without a game must also give the launcher back (it waits on "Loading..." with Play
        // disabled until then): [fail] shows the dialog and emits ForceCloseApp, which the launcher answers with its reset.
        fun fail(state: MessageDialogState) {
            setLoadingDialogVisible(false)
            setMessageDialogState(state)
            PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
        }
        try {

            val containerManager = ContainerManager(context)
            val container = ContainerUtils.getOrCreateContainer(context, appId, containerManager)
            if (app.gamenative.display.WowDisplayBackend.isGamescope(container)) {
                try {
                    app.gamenative.display.WowGamescopePayload.ensure(context)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    fail(MessageDialogState(visible = true, type = DialogType.SYNC_FAIL,
                        title = "Gamescope", message = e.message ?: "Gamescope runtime preparation failed",
                        dismissBtnText = context.getString(R.string.ok)))
                    return@launch
                }
            }


            val legacyImageFsRoot = File(context.filesDir, "imagefs")
            val migrationOk = ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(
                context,
                legacyImageFsRoot,
                container.wineVersion,
            )
            if (!migrationOk) {

                fail(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.install_failed_title),
                        message = context.getString(R.string.install_failed_message),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
                return@launch
            }

            val effectiveExe = container.executablePath
            if (effectiveExe.isBlank()) {

                fail(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.EXECUTABLE_NOT_FOUND,
                        title = context.getString(R.string.game_executable_not_found_title),
                        message = context.getString(R.string.game_executable_not_found),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
                return@launch
            }

            // The root file system ships inside the APK; nothing is downloaded.
            if (context.assets.list("")?.contains("imagefs_bionic.txz") != true) {
                fail(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.download_failed_title),
                        message = "The root file system is missing from the app package.",
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
                return@launch
            }

            setLoadingMessage(context.getString(R.string.main_installing_bionic))
            // The wait is interruptible: an Abort cancels this job, and nothing may navigate or activate afterwards.
            // The extraction itself keeps going to its end; the next launch waits for it (ImageFsInstaller lock).
            val installFuture = ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { progress ->
                if (isActive) setLoadingProgress(progress / 100f)
            }
            val imageFsInstallSuccess = kotlinx.coroutines.runInterruptible { installFuture.get() }

            if (!imageFsInstallSuccess) {

                fail(
                    MessageDialogState(
                        visible = true,
                        type = DialogType.SYNC_FAIL,
                        title = context.getString(R.string.install_failed_title),
                        message = context.getString(R.string.install_failed_message),
                        dismissBtnText = context.getString(R.string.ok),
                    ),
                )
                return@launch
            }

            // An aborted launch must not activate the container or start a game.
            if (!isActive) return@launch
            setLoadingMessage(context.getString(R.string.main_loading))
            setLoadingProgress(-1f)

            // must activate container before downloading save files
            containerManager.activateContainer(container)

            setLoadingDialogVisible(false)
            if (!isActive) return@launch
            onSuccess(context, appId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // E.g. an ExecutionException from the root file system install, or a container that could not be read.
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            fail(MessageDialogState(visible = true, type = DialogType.SYNC_FAIL,
                title = context.getString(R.string.install_failed_title),
                message = cause.message ?: context.getString(R.string.install_failed_message),
                dismissBtnText = context.getString(R.string.ok)))
        }
    }
}
