package dev.podhod.training.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.podhod.core.*
import dev.podhod.training.TrainingViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.encodeToString

@Composable fun ProgramScreen(model: TrainingViewModel, archive: TrainingArchive, edit: (TrainingDay, LocalDate?) -> Unit,
    start: (LocalDate) -> Unit, detail: (Exercise) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(weekStart(model.today()).toString()) }
    var template by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(selected)
    val week = archive.scheduledWeek(date)
    val days = if (template) archive.week else (0L..6L).map { week.day(date.plusDays(it)) }
    LaunchedEffect(selected) { model.ensureWeek(date) }
    DisposableEffect(selected) { model.sync.focusedWeek = selected; onDispose { model.sync.focusedWeek = null } }
    ScreenColumn {
        ScreenHeading("Твоя неделя", if (template) "Основа для новых недель." else "План на конкретные даты.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!template, { template = false }, { Text("По датам") })
            FilterChip(template, { template = true }, { Text("Шаблон недели") })
        }
        if (!template) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { selected = date.minusWeeks(1).toString() }) { Icon(Icons.Default.ChevronLeft, "Предыдущая неделя") }
                TextButton(onClick = { selected = weekStart(model.today()).toString() }) { Text("${dateLabel(date)} – ${dateLabel(date.plusDays(6))}") }
                IconButton(onClick = { selected = date.plusWeeks(1).toString() }) { Icon(Icons.Default.ChevronRight, "Следующая неделя") }
            }
        }
        GymCard {
            Text("Дней с тренировками: ${days.count { it.exercises.isNotEmpty() }}")
            Text(if (template) "Изменения не затронут уже созданные недели" else "Изменения относятся только к этой неделе", color = Muted, style = MaterialTheme.typography.bodySmall)
            VolumeSummary(model.catalog.volume(days.flatMap { it.exercises }))
        }
        days.forEach { day ->
            val dayDate = date.plusDays((day.weekday - 1).toLong())
            GymCard {
                TextButton(onClick = { edit(day, if (template) null else dayDate) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(day.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        if (!template) Text(dateLabel(dayDate) + if (dayDate == model.today()) " · СЕГОДНЯ" else "", color = Lime)
                        Text(if (day.exercises.isEmpty()) "Отдых или новая тренировка" else day.title.ifBlank { "Тренировка" }, color = Muted)
                    }
                    Icon(if (day.exercises.isEmpty()) Icons.Default.Add else Icons.Default.Edit, "Редактировать")
                }
                day.exercises.forEach { plan ->
                    TextButton(onClick = { detail(plan.exercise) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(8.dp)) {
                        Column(Modifier.fillMaxWidth()) { Text(plan.exercise.name, color = MaterialTheme.colorScheme.onSurface); Text("${plan.setCount} × ${plan.targetReps} · отдых ${plan.restSeconds} / ${plan.restAfterExerciseSeconds} с", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (day.exercises.isNotEmpty() && !template) {
                    var moving by remember { mutableStateOf(false) }
                    Row {
                        TextButton(onClick = { start(dayDate) }, modifier = Modifier.weight(1f)) { Text(if (archive.active != null) "Продолжить тренировку" else "Начать") }
                        Box {
                            TextButton(onClick = { moving = true }) { Text("Перенести") }
                            DropdownMenu(moving, { moving = false }) {
                                (0L..6L).forEach { offset ->
                                    val target = date.plusDays(offset)
                                    DropdownMenuItem(text = { Text("${weekdayNames[offset.toInt()]}, ${dateLabel(target)}") }, onClick = {
                                        moving = false
                                        val id = week.workouts.first { it.plannedDate == dayDate.toString() }.id
                                        model.change({ WorkoutEngine.move(it, selected, id, target) })
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru-RU")))

@Composable fun DayEditorScreen(model: TrainingViewModel, original: TrainingDay, date: LocalDate?, back: () -> Unit, detail: (Exercise) -> Unit) {
    var text by rememberSaveable { mutableStateOf(archiveJson.encodeToString(original)) }
    val draft = archiveJson.decodeFromString<TrainingDay>(text)
    var library by rememberSaveable { mutableStateOf(false) }
    val saveDraft: (TrainingDay) -> Unit = { text = archiveJson.encodeToString(it) }
    FullScreen(if (date == null) original.name else "${original.name}, ${dateLabel(date)}", back, actions = {
        TextButton(onClick = { model.change({ WorkoutEngine.saveDay(it, draft.copy(title = draft.title.trim()), date, original) }, back) }) { Text("Сохранить") }
    }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding()) {
            OutlinedTextField(draft.title, { saveDraft(draft.copy(title = it)) }, label = { Text("Название тренировки") }, modifier = Modifier.fillMaxWidth())
            if (draft.exercises.isEmpty()) Text("Добавь упражнения. Пустой день будет днём отдыха.", color = Muted)
            draft.exercises.forEachIndexed { index, plan ->
                val update: (PlannedExercise) -> Unit = { changed -> saveDraft(draft.copy(exercises = draft.exercises.map { if (it.id == plan.id) changed else it })) }
                GymCard {
                    Text("${index + 1}. ${plan.exercise.name}", style = MaterialTheme.typography.titleMedium)
                    NumberStepper("Подходы", plan.setCount, 1..12, 1) { update(plan.copy(setCount = it)) }
                    OutlinedTextField(plan.targetReps, { update(plan.copy(targetReps = it)) }, label = { Text("Повторы, цель") }, modifier = Modifier.fillMaxWidth())
                    NumberStepper("Между подходами, с", plan.restSeconds, 0..600, 15) { update(plan.copy(restSeconds = it)) }
                    NumberStepper("После упражнения, с", plan.restAfterExerciseSeconds, 0..600, 15) { update(plan.copy(restAfterExerciseSeconds = it)) }
                    OutlinedTextField(plan.exercise.cue, { update(plan.copy(exercise = plan.exercise.copy(cue = it))) }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { detail(plan.exercise) }) { Text("Мышцы, техника и видео") }
                    Row {
                        IconButton(onClick = {
                            val items = draft.exercises.toMutableList(); java.util.Collections.swap(items, index, index - 1); saveDraft(draft.copy(exercises = items))
                        }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Переместить выше") }
                        IconButton(onClick = {
                            val items = draft.exercises.toMutableList(); java.util.Collections.swap(items, index, index + 1); saveDraft(draft.copy(exercises = items))
                        }, enabled = index < draft.exercises.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Переместить ниже") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { saveDraft(draft.copy(exercises = draft.exercises.filterNot { it.id == plan.id })) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            PrimaryAction("Добавить упражнение", onClick = { library = true })
            GymCard { VolumeSummary(model.catalog.volume(draft.exercises)) }
            Text("Редактируется ${if (date == null) "шаблон для новых недель" else "только выбранный день"}. Активная тренировка и история сохранят свои снимки.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (library) FullScreenDialog({ library = false }) {
        ExerciseLibraryScreen(model, draft.exercises, { exercise ->
            val latest = archiveJson.decodeFromString<TrainingDay>(text)
            saveDraft(latest.copy(exercises = latest.exercises + PlannedExercise(exercise = exercise)))
        }, { library = false })
    }
}
@Composable fun NumberStepper(label: String, value: Int, range: IntRange, step: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label: $value", modifier = Modifier.weight(1f).padding(top = 12.dp))
        IconButton(onClick = { change((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Default.Remove, "Уменьшить: $label") }
        IconButton(onClick = { change((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Default.Add, "Увеличить: $label") }
    }
}
