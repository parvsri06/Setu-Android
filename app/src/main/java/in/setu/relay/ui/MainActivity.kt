package `in`.setu.relay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import `in`.setu.relay.R
import `in`.setu.relay.relay.Prefs
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayService
import `in`.setu.relay.relay.SurveyRepository
import `in`.setu.relay.store.Survey
import `in`.setu.relay.ui.survey.SurveyDraft
import `in`.setu.relay.ui.survey.SurveyHost
import `in`.setu.relay.ui.survey.ReceivedSurveyDetailScreen
import `in`.setu.relay.ui.survey.SurveyDetailScreen
import `in`.setu.relay.ui.survey.SurveyListScreen
import `in`.setu.relay.ui.survey.SurveyScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    FirstRun, Home, Sos, CheckIn, Carrying, Diagnostics, Rescuer, Settings, Surveys, Survey,
    SurveyDetail, ReceivedDetail, Rescue, ShareApp, RescueTools, Announce, SosDetail,
}

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        setContent {
            val prefs = remember { Prefs(app) }
            // Held above SetuTheme so that choosing a theme in Settings repaints
            // immediately rather than on the next launch.
            var themeMode by remember { mutableStateOf(ThemeMode.fromKey(prefs.themeMode)) }
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SetuTheme(dark = dark) {
                // Building the engine opens the database and, on first run,
                // derives the Ed25519 identity — hundreds of milliseconds of
                // BigInteger work on a cheap handset. It does not belong on the
                // main thread of a app whose users are under stress.
                var engine by remember { mutableStateOf<RelayEngine?>(null) }
                LaunchedEffect(Unit) {
                    engine = withContext(Dispatchers.Default) { RelayEngine.get(app) }
                }
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    engine?.let {
                        SetuApp(
                            engine = it,
                            prefs = prefs,
                            themeMode = themeMode,
                            onThemeMode = { mode ->
                                themeMode = mode
                                prefs.themeMode = mode.key
                            },
                        )
                    } ?: Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The service owns the radio, but the UI needs a fresh snapshot on every
        // return to the foreground even when nothing has arrived.
        lifecycleScope.launch(Dispatchers.Default) { RelayEngine.get(applicationContext).publish() }
    }
}

@Composable
private fun SetuApp(
    engine: RelayEngine,
    prefs: Prefs,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val state by engine.state.collectAsState()

    // Saveable, not just remembered. A configuration change — switching the
    // system theme, rotating, changing font size — recreates the Activity, and a
    // plain remember would drop the user back on Home mid-task. Stored by name
    // because the enum ordinal would silently change meaning if a screen were
    // ever inserted in the middle.
    var screen by rememberSaveable(
        saver = Saver(save = { it.value.name }, restore = { mutableStateOf(Screen.valueOf(it)) }),
    ) {
        mutableStateOf(if (prefs.onboarded) Screen.Home else Screen.FirstRun)
    }

    // The system back gesture is how Android users expect to leave a screen, and
    // without this every screen exited the app instead of going up one level.
    // BackHandler comes from activity-compose, already a dependency — the
    // Navigation component is cut in docs/08 and four screens do not need it.
    //
    // Enabled only where there is somewhere to go back to: on Home and during
    // first-run, back keeps its default behaviour of leaving the app, because a
    // half-finished onboarding should not be escapable into an unconfigured Home.
    BackHandler(enabled = screen != Screen.Home && screen != Screen.FirstRun) {
        screen = when (screen) {
            Screen.Rescuer -> Screen.Diagnostics
            // The wizard handles its own step-by-step back; reaching here means
            // it is on its first step, so leaving goes to the list it came from.
            Screen.SurveyDetail, Screen.ReceivedDetail, Screen.Survey -> Screen.Surveys
            Screen.SosDetail -> Screen.Sos
            else -> Screen.Home
        }
    }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context)) }
    // Mirrored into Compose state so the start/stop label actually changes.
    var relayWanted by remember { mutableStateOf(prefs.relayWanted) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsGranted = hasAllPermissions(context) }

    // Surveys live below the UI, like the relay does. Built once and off the
    // main thread: opening the database and deriving the identity key id are
    // both too slow to do during composition.
    val surveys = remember { SurveyRepository(engine.store.database, engine.identity.keyId, context.applicationContext) }
    var surveyList by remember { mutableStateOf<List<Survey>>(emptyList()) }
    var receivedList by remember {
        mutableStateOf<List<`in`.setu.relay.wire.SurveyRecord.Decoded>>(emptyList())
    }
    var openSurveyId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSosMsgId by rememberSaveable { mutableStateOf<String?>(null) }
    var surveyReload by remember { mutableStateOf(0) }
    var rescueReload by remember { mutableStateOf(0) }

    LaunchedEffect(surveyReload, screen) {
        if (screen == Screen.Surveys || screen == Screen.Home) {
            withContext(Dispatchers.Default) {
                // Older builds packed records in a format peers can no longer
                // read, and surveys saved before packing existed have none at
                // all. Both would leave this phone advertising an empty digest
                // while visibly holding surveys.
                surveys.ensureRecordsPacked()
                val local = surveys.all()
                val fromPeers = surveys.received()
                withContext(Dispatchers.Main) {
                    surveyList = local
                    receivedList = fromPeers
                }
            }
        }
    }

    // Entering or clearing the rescuer key changes what publish() builds, so
    // the engine has to be asked for a fresh snapshot rather than waiting for
    // the next radio event.
    LaunchedEffect(rescueReload) {
        if (rescueReload > 0) withContext(Dispatchers.Default) { engine.publish() }
    }

    val surveyHost = remember {
        object : SurveyHost {
            override suspend fun load(surveyId: String): Survey? = surveys.get(surveyId)

            override fun saveDraft(draft: SurveyDraft) {
                surveys.save(
                    draft.toSurvey(`in`.setu.relay.store.SurveyStatus.DRAFT, null),
                    draft.aadhaar,
                    claimAadhaar = false,
                )
            }

            override fun saveComplete(draft: SurveyDraft): Boolean {
                if (surveys.isDuplicate(draft.aadhaar, draft.surveyId)) return false
                // Where and when, attached automatically — the surveyor never
                // types a coordinate and an officer always needs one.
                val row = surveys.stampNow(
                    draft.toSurvey(`in`.setu.relay.store.SurveyStatus.COMPLETE, null),
                )
                if (!surveys.save(row, draft.aadhaar, claimAadhaar = true)) return false
                // Pack the relay subset now so it is verifiably sealed and the
                // right size, even though the bulk plane that carries it is
                // phase 5 and nothing transmits it yet.
                surveys.get(row.surveyId)?.let { surveys.packForRelay(it) }
                return true
            }
        }
    }

    // Home and Settings both offer this, so it is defined once. Stopping the
    // relay withdraws consent to carry for others; DPDP requires that consent be
    // revocable, so the control has to stay reachable and honest.
    val toggleRelay = {
        if (relayWanted) {
            relayWanted = false
            prefs.relayWanted = false
            RelayService.stop(context)
        } else {
            relayWanted = true
            prefs.relayWanted = true
            RelayService.start(context)
        }
    }

    LaunchedEffect(relayWanted, permissionsGranted) {
        if (relayWanted && permissionsGranted && !RelayService.running) {
            RelayService.start(context)
        }
    }

    // Home scrolls its own body between a fixed app bar and a fixed bottom bar,
    // so it must NOT sit inside this scroller: a `weight` inside an infinitely
    // tall parent resolves to zero height, which collapsed the entire Home body
    // to nothing while both bars still drew. It also supplies its own padding,
    // because its bars run edge to edge.
    val ownsItsScroll = screen == Screen.Home

    Column(
        Modifier
            .fillMaxSize()
            // Android draws edge to edge by default from API 35, so without this
            // the header sits under the status bar and the last button under the
            // gesture bar. Insets first, then scroll, then content padding.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (ownsItsScroll) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .then(if (ownsItsScroll) Modifier else Modifier.padding(16.dp)),
    ) {
        when (screen) {
            Screen.FirstRun -> FirstRunScreen(
                prefs = prefs,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = { launcher.launch(requiredPermissions()) },
                onOpenBatterySettings = { openBatterySettings(context) },
                onDone = {
                    prefs.onboarded = true
                    prefs.consented = true
                    prefs.relayWanted = true
                    relayWanted = true
                    if (permissionsGranted) RelayService.start(context)
                    screen = Screen.Home
                },
            )

            Screen.Home -> HomeScreen(
                state = state,
                surveyCount = surveyList.size,
                relayWanted = relayWanted,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = { launcher.launch(requiredPermissions()) },
                onToggleRelay = toggleRelay,
                onGo = { screen = it },
            )

            Screen.Sos -> SosScreen(
                engine = engine,
                state = state,
                onAddDetail = { msgIdHex ->
                    openSosMsgId = msgIdHex
                    screen = Screen.SosDetail
                },
                onBack = { screen = Screen.Home },
            )
            Screen.CheckIn -> CheckInScreen(engine, state, prefs) { screen = Screen.Home }
            Screen.Carrying -> CarryingScreen(engine, state) { screen = Screen.Home }
            Screen.Diagnostics -> DiagnosticsScreen(engine, state, { screen = Screen.Rescuer }) { screen = Screen.Home }
            Screen.Rescuer -> RescuerScreen(state) { screen = Screen.Diagnostics }

            Screen.Surveys -> SurveyListScreen(
                surveys = surveyList,
                received = receivedList,
                // Tapping opens the table, not the wizard. Reading what was
                // collected is the common case; editing is a deliberate second
                // step from there.
                onOpen = { openSurveyId = it; screen = Screen.SurveyDetail },
                onOpenReceived = { openSurveyId = it; screen = Screen.ReceivedDetail },
                onNew = { openSurveyId = null; screen = Screen.Survey },
                onBack = { screen = Screen.Home },
            )

            Screen.SurveyDetail -> {
                val s = surveyList.firstOrNull { it.surveyId == openSurveyId }
                if (s == null) {
                    screen = Screen.Surveys
                } else {
                    SurveyDetailScreen(
                        survey = s,
                        editable = true,
                        onEdit = { screen = Screen.Survey },
                        onBack = { screen = Screen.Surveys },
                    )
                }
            }

            Screen.ReceivedDetail -> {
                val r = receivedList.firstOrNull { it.surveyId == openSurveyId }
                if (r == null) {
                    screen = Screen.Surveys
                } else {
                    ReceivedSurveyDetailScreen(r) { screen = Screen.Surveys }
                }
            }

            Screen.ShareApp -> ShareAppScreen { screen = Screen.Home }

            Screen.RescueTools -> RescueToolsScreen(engine, state) { screen = Screen.Home }

            Screen.Announce -> AnnounceScreen(engine, prefs) { screen = Screen.Home }

            Screen.SosDetail -> {
                val id = openSosMsgId
                if (id == null) {
                    screen = Screen.Sos
                } else {
                    SosDetailScreen(
                        engine = engine,
                        msgIdHex = id,
                        onDone = { screen = Screen.Sos },
                        onBack = { screen = Screen.Sos },
                    )
                }
            }

            Screen.Rescue -> RescueScreen(
                prefs = prefs,
                state = state,
                onKeyChanged = { rescueReload++ },
                onBack = { screen = Screen.Home },
            )

            Screen.Survey -> SurveyScreen(
                host = surveyHost,
                startSurveyId = openSurveyId,
                onExit = {
                    surveyReload++
                    screen = Screen.Surveys
                },
            )

            Screen.Settings -> SettingsScreen(
                prefs = prefs,
                relayWanted = relayWanted,
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                onToggleRelay = toggleRelay,
                onRescue = { screen = Screen.Rescue },
                onBack = { screen = Screen.Home },
            )
        }
    }
}

// -------------------------------------------------------------- permissions

fun requiredPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_ADVERTISE
        list += Manifest.permission.BLUETOOTH_CONNECT
    }
    list += Manifest.permission.ACCESS_FINE_LOCATION
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}

fun hasAllPermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Walks the user to the battery-optimisation exemption. Not optional on
 * Xiaomi/Oppo/Vivo — see docs/05-platform-constraints.md. The direct
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent is refused by some OEM builds, so
 * this falls back to the settings list rather than crashing.
 */
fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:" + context.packageName))
    for (intent in listOf(direct, fallback, appDetails)) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
