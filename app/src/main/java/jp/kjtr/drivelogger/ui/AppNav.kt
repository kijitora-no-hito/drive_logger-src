package jp.kjtr.drivelogger.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import jp.kjtr.drivelogger.R

@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val interrupted by vm.interrupted.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    fun goHome() {
        if (!nav.popBackStack(MainViewModel.ROUTE_HOME, false)) nav.navigate(MainViewModel.ROUTE_HOME) { launchSingleTop = true }
    }
    fun goDrive() {
        nav.navigate(MainViewModel.ROUTE_DRIVE) { launchSingleTop = true; popUpTo(MainViewModel.ROUTE_HOME) }
    }

    LaunchedEffect(Unit) {
        vm.navRequests.collect { route ->
            when {
                route == MainViewModel.ROUTE_HOME -> goHome()
                route == MainViewModel.ROUTE_DRIVE -> goDrive()
                // ドライブを終えた → 詳細（戻るとホーム。走行画面は残さない）
                route.startsWith("trip/") && route.contains("finished=true") -> nav.navigate(route) { popUpTo(MainViewModel.ROUTE_HOME) }
                else -> nav.navigate(route) { launchSingleTop = true }
            }
        }
    }
    // ドライブ中（駐車中を含む）にアプリを開いたら、ホームではなく走行中の画面へ（運転中にホームで操作させない）。
    // 起動時・裏から戻ったとき（通知のタップ・ランチャー）。ホーム以外（一覧・設定など）を見ていたらそのまま
    // 0.6.0〜 ドライブは記録していなくても開いたまま残るので、移るのは記録中・駐車中のときだけ（何も記録していなければホームの［ドライブに戻る］から）
    LifecycleStartEffect(Unit) {
        val s = vm.session.state.value
        if (s != null && (s.recording || s.parked) && nav.currentDestination?.route == MainViewModel.ROUTE_HOME) goDrive()
        onStopOrDispose { }
    }

    NavHost(navController = nav, startDestination = MainViewModel.ROUTE_HOME) {
        composable(MainViewModel.ROUTE_HOME) {
            HomeScreen(
                vm,
                onResumeDrive = { goDrive() },
                onTrips = { nav.navigate("trips") },
                onOpenTrip = { nav.navigate("trip/$it") },
                onClips = { nav.navigate("clips") },
                onSummary = { nav.navigate("summary") },
                onCamera = { nav.navigate("camera") { launchSingleTop = true } },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable(MainViewModel.ROUTE_DRIVE) {
            DriveScreen(
                vm, onTrips = { nav.navigate("trips") }, onClips = { nav.navigate("clips") }, onSettings = { nav.navigate("settings") },
                onCamera = { nav.navigate("camera") { launchSingleTop = true } }, onCard = { nav.navigate("card/$it") },
                onHome = { goHome() },
                onExit = { if (nav.currentDestination?.route == MainViewModel.ROUTE_DRIVE) goHome() },
            )
        }
        composable("camera") { CameraScreen(vm, onBack = { nav.popBackStack() }) }
        composable("card/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ShareCardScreen(vm, entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("trips") { TripListScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("trip/$it") }, onSummary = { nav.navigate("summary") }) }
        composable("summary") { SummaryScreen(vm, onBack = { nav.popBackStack() }, onOpenTrip = { nav.navigate("trip/$it") }) }
        composable(
            "trip/{id}?finished={finished}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("finished") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            TripDetailScreen(
                vm, id, justFinished = entry.arguments?.getBoolean("finished") ?: false,
                onBack = { if (!nav.popBackStack()) goHome() },
                onPlayClip = { clipId, atMs -> nav.navigate("player/$clipId?at=$atMs") },
                onCard = { nav.navigate("card/$it") },
                onGoDrive = { goDrive() },
            )
        }
        composable("clips") {
            ClipListScreen(vm, onBack = { nav.popBackStack() }, onPlay = { nav.navigate("player/$it") }, onSns = { nav.navigate("sns/$it") })
        }
        composable(
            "player/{id}?at={at}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("at") { type = NavType.LongType; defaultValue = 0L }),
        ) { entry ->
            PlayerScreen(
                vm, entry.arguments?.getLong("id") ?: 0L, startAtMs = entry.arguments?.getLong("at") ?: 0L,
                onBack = { nav.popBackStack() }, onSns = { nav.navigate("sns/$it") },
            )
        }
        composable("sns/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            SnsExportScreen(vm, entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
    }

    // 初回の安全上の注意
    if (!settings.safetyAccepted) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.safety_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.safety_text) + "\n\n" + stringResource(R.string.privacy_short)) } },
            confirmButton = { TextButton(onClick = { vm.updateSettings { it.copy(safetyAccepted = true) } }) { Text(stringResource(R.string.safety_ok)) } },
        )
    } else {
        // 記録中のまま落ちたログ（0.6.0〜 ドライブは開いたまま。記録を続けるか・止めたままにするか・ドライブを終了するか）
        interrupted?.let { r ->
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.unfinished_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.unfinished_text, r.trip.name,
                            stringResource(if (r.interruptedVideo) R.string.unfinished_what_video else R.string.unfinished_what_gps),
                        ),
                    )
                },
                confirmButton = { TextButton(onClick = { vm.continueInterrupted() }) { Text(stringResource(R.string.unfinished_continue)) } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { vm.finishInterrupted() }) { Text(stringResource(R.string.unfinished_finish)) }
                        TextButton(onClick = { vm.keepInterruptedStopped() }) { Text(stringResource(R.string.unfinished_keep)) }
                    }
                },
            )
        }
    }
}
