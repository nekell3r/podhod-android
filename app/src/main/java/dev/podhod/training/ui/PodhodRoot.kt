package dev.podhod.training.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.podhod.core.*
import dev.podhod.training.TrainingViewModel
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString

@Composable fun PodhodRoot(model: TrainingViewModel) {
    val archive by model.archive.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("today") }
    var returnRoute by rememberSaveable { mutableStateOf("today") }
    var editorDay by rememberSaveable { mutableStateOf("") }
    var editorDate by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.reconcile()
            model.ensureWeek(model.today())
            model.reminders.schedule()
            while (true) { model.synchronize(); delay(10_000) }
        }
    }
    val onDetail: (Exercise) -> Unit = { detail = archiveJson.encodeToString(it) }
    val onEdit: (TrainingDay, LocalDate?) -> Unit = { day, date ->
        editorDay = archiveJson.encodeToString(day); editorDate = date?.toString(); returnRoute = route; route = "editor"
    }
    val onStart: (LocalDate) -> Unit = { date -> model.start(date) { route = "workout" } }
    BackHandler(route !in listOf("today", "program", "progress")) { route = if (route == "editor") returnRoute else "today" }
    when (route) {
        "editor" -> DayEditorScreen(model, archiveJson.decodeFromString(editorDay), editorDate?.let(LocalDate::parse),
            back = { route = returnRoute }, detail = onDetail)
        "account" -> AccountScreen(model, back = { route = "today" })
        "workout" -> WorkoutScreen(model, archive, back = { route = "today" }, detail = onDetail, finished = { session ->
            route = "progress"; history = archiveJson.encodeToString(session)
        })
        else -> Scaffold(bottomBar = {
            NavigationBar(containerColor = Background) {
                val routes = listOf("today", "program", "progress")
                val titles = listOf("Сегодня", "Программа", "Прогресс")
                val icons = listOf(Icons.Default.WbSunny, Icons.Default.CalendarMonth, Icons.AutoMirrored.Filled.ShowChart)
                routes.forEachIndexed { index, key -> NavigationBarItem(selected = route == key, onClick = { route = key },
                    icon = { Icon(icons[index], titles[index]) }, label = { Text(titles[index]) }, colors = NavigationBarItemDefaults.colors(indicatorColor = CardColor,
                        selectedIconColor = Lime, selectedTextColor = Lime, unselectedIconColor = Muted, unselectedTextColor = Muted)) }
            }
        }) { padding ->
            Box(Modifier.padding(padding)) {
                when (route) {
                    "program" -> ProgramScreen(model, archive, onEdit, onStart, onDetail)
                    "progress" -> ProgressScreen(archive, onHistory = { history = archiveJson.encodeToString(it) })
                    else -> TodayScreen(model, archive, onEdit, onStart, { route = "workout" }, { route = "program" }, { route = "account" }, onDetail)
                }
            }
        }
    }
    if (detail != null) FullScreenDialog({ detail = null }) { ExerciseDetailScreen(model.catalog, archiveJson.decodeFromString(detail!!), { detail = null }) }
    if (history != null) FullScreenDialog({ history = null }) { HistoryScreen(archiveJson.decodeFromString(history!!), { history = null }) }
    if (error != null) AlertDialog(onDismissRequest = model::clearError, title = { Text("Проверь данные") }, text = { Text(error!!) },
        confirmButton = { TextButton(onClick = model::clearError) { Text("Понятно") } })
}
@Composable fun FullScreenDialog(dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Background, content = content)
    }
}
