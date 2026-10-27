package dev.podhod.training.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podhod.core.*
import dev.podhod.training.TrainingViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable fun TodayScreen(model: TrainingViewModel, archive: TrainingArchive,
    edit: (TrainingDay, LocalDate?) -> Unit, start: (LocalDate) -> Unit, resume: () -> Unit,
    program: () -> Unit, account: () -> Unit, detail: (Exercise) -> Unit) {
    val sync by model.sync.status.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(model.today()) }
    var stopping by remember { mutableStateOf<WorkoutSession?>(null) }
    LaunchedEffect(Unit) { while (true) { today = model.today(); delay(30_000) } }
    val day = archive.scheduledWeek(today).day(today)
    val active = archive.active
    val empty = archive.week.all { it.exercises.isEmpty() } && archive.scheduledWeeks.all { it.workouts.isEmpty() }
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FitnessCenter, null, tint = Lime)
            Text("  ПОДХОД", fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text(today.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru-RU"))), color = Muted)
            IconButton(onClick = account) { Icon(Icons.Default.PersonOutline, "Аккаунт и синхронизация", tint = Lime) }
        }
        ScreenHeading("Сегодня", "${day.name}. Твой темп, твой прогресс.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (0L..6L).forEach { offset ->
                val date = weekStart(today).plusDays(offset)
                val planned = archive.scheduledWeek(date).day(date)
                Surface(Modifier.weight(1f), color = if (date == today) Lime else CardColor, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(weekdayShortNames[offset.toInt()], style = MaterialTheme.typography.labelSmall, color = if (date == today) Background else Muted)
                        Text(if (planned.exercises.isEmpty()) "—" else "●", color = if (date == today) Background else Lime)
                    }
                }
            }
        }
        TextButton(onClick = account) { Text(sync.message, color = Muted, style = MaterialTheme.typography.bodySmall) }
        when {
            active != null -> GymCard {
                Text("ТРЕНИРОВКА В ПРОЦЕССЕ", color = Lime, style = MaterialTheme.typography.labelSmall)
                Text(active.session.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Записано ${active.session.completedSets} из ${active.session.totalSets} подходов", color = Muted)
                PrimaryAction("Продолжить тренировку", onClick = resume)
                TextButton(onClick = { stopping = active.session }) { Text("Остановить", color = MaterialTheme.colorScheme.error) }
            }
            empty -> GymCard {
                Text("ТВОЯ ТОЧКА ОТСЧЁТА", color = Lime, style = MaterialTheme.typography.labelSmall)
                Text("Сильнее.\nПодход за подходом.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                ExerciseArt("bench")
                Text("Составь неделю тренировок. В зале останется открыть «Сегодня» и записывать результат.", color = Muted)
                PrimaryAction("Составить программу", onClick = program)
                TextButton(onClick = model::loadExample, modifier = Modifier.fillMaxWidth()) { Text("Взять пример на 3 дня") }
            }
            day.exercises.isNotEmpty() -> GymCard {
                Text("ВРЕМЯ ДЛЯ СЕБЯ", color = Lime, style = MaterialTheme.typography.labelSmall)
                Text(day.title.ifBlank { "Твоя тренировка" }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${day.exercises.size} упр. · ${day.setCount} подх.", color = Muted)
                ExerciseArt(day.exercises.first().exercise.illustration)
                PrimaryAction("Начать тренировку", onClick = { start(today) })
            }
            else -> GymCard {
                Text("День восстановления", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("На сегодня упражнений нет. Отдых — тоже часть программы.", color = Muted)
                (1L..7L).map { today.plusDays(it) }.firstOrNull { archive.scheduledWeek(it).day(it).exercises.isNotEmpty() }?.let {
                    Text("Дальше: ${weekdayNames[it.dayOfWeek.value - 1]} · ${archive.scheduledWeek(it).day(it).title}", color = Lime)
                }
                PrimaryAction("Добавить тренировку на сегодня", onClick = { edit(day, today) })
            }
        }
        ProteinCard(model, archive, today)
        if (day.exercises.isNotEmpty()) {
            Text("План на сегодня", style = MaterialTheme.typography.titleLarge)
            GymCard {
                day.exercises.forEachIndexed { index, plan ->
                    TextButton(onClick = { detail(plan.exercise) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", color = Lime, modifier = Modifier.width(30.dp))
                            Column { Text(plan.exercise.name, color = MaterialTheme.colorScheme.onSurface); Text("${plan.setCount} подх. × ${plan.targetReps}", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            TextButton(onClick = { edit(day, today) }) { Text("Изменить тренировку") }
        }
        if (archive.history.isNotEmpty()) GymCard {
            DataRow("Тренировки", archive.history.size.toString())
            DataRow("Подходы", archive.history.sumOf { it.completedSets }.toString())
            DataRow("Объём, т", number(archive.history.sumOf { it.volume } / 1000))
        }
        Text("Каждый записанный подход — шаг вперёд.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    stopping?.let { session -> StopWorkoutDialog(model, session, { stopping = null }, {}) }
}

@Composable private fun ProteinCard(model: TrainingViewModel, archive: TrainingArchive, today: LocalDate) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { model.reminders.setEnabled(true) }.onFailure { model.report(it.message ?: "Не удалось включить напоминания.") }
        refresh++
    }
    val day = archive.protein.firstOrNull { it.date == today.toString() }
    GymCard {
        Text("Протеин", style = MaterialTheme.typography.titleLarge)
        Text("Утро и вечер — отметки за сегодня", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = day?.morningTakenAt != null, onClick = { model.markProtein(true, day?.morningTakenAt == null) }, label = { Text("Утро ${if (day?.morningTakenAt != null) "✓" else ""}") }, modifier = Modifier.weight(1f))
            FilterChip(selected = day?.eveningTakenAt != null, onClick = { model.markProtein(false, day?.eveningTakenAt == null) }, label = { Text("Вечер ${if (day?.eveningTakenAt != null) "✓" else ""}") }, modifier = Modifier.weight(1f))
        }
        key(refresh) {
            Text(model.reminders.status, color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (!model.reminders.enabled && Build.VERSION.SDK_INT >= 33 && !model.reminders.canNotify) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else runCatching { model.reminders.setEnabled(!model.reminders.enabled) }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                refresh++
            }) { Text(if (model.reminders.enabled) "Выключить напоминания" else "Включить напоминания") }
            if (model.reminders.enabled && !model.reminders.exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")))
            }) { Text("Разрешить точное время") }
        }
    }
}
