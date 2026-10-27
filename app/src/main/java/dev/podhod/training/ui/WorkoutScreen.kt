package dev.podhod.training.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.podhod.core.*
import dev.podhod.training.TrainingViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable fun WorkoutScreen(model: TrainingViewModel, archive: TrainingArchive, back: () -> Unit,
    detail: (Exercise) -> Unit, finished: (WorkoutSession) -> Unit) {
    val active = archive.active
    if (active == null) { FullScreen("Тренировка", back) { ScreenColumn(Modifier.padding(it)) { Text("Активной тренировки нет.") } }; return }
    val session = active.session
    val exercise = session.exercises[session.currentExercise]
    var now by remember { mutableStateOf(Instant.now()) }
    var stopping by remember { mutableStateOf<WorkoutSession?>(null) }
    var restSettings by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(session.id) { while (true) { now = Instant.now(); delay(1000) } }
    val finish: (Boolean) -> Unit = { partial -> model.finish(session.id, partial) { model.archive.value.history.firstOrNull { it.id == session.id }?.let(finished) } }
    FullScreen("Тренировка", back, actions = { TextButton(onClick = { stopping = session }) { Text("Остановить", color = MaterialTheme.colorScheme.error) } }) { padding ->
        key(session.currentExercise) {
            ScreenColumn(Modifier.padding(padding).imePadding()) {
                DataRow(session.title, timeText(elapsedSeconds(session.startedAt, now)))
                Text("Выполнено: ${session.completedSets} / ${session.totalSets}", color = Lime)
                LinearProgressIndicator(progress = { (session.completedSets + session.skippedSets).toFloat() / session.totalSets.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = Lime)
                if (session.skippedSets > 0) Text("Пропущено подходов: ${session.skippedSets}. Выбери упражнение, чтобы вернуть его.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.exercises.forEachIndexed { index, item ->
                        FilterChip(selected = index == session.currentExercise, onClick = { focus.clearFocus(); model.select(index) },
                            label = { Text("${index + 1}${if (item.isCompleted) " ✓" else if (item.isSkipped) " ↷" else ""}") },
                            modifier = Modifier.semantics { contentDescription = "Упражнение ${index + 1}: ${item.exercise.name}" })
                    }
                }
                Text("${exercise.exercise.muscle.uppercase()} / ${session.currentExercise + 1} ИЗ ${session.exercises.size}", color = Lime, style = MaterialTheme.typography.labelSmall)
                Text(exercise.exercise.name, fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                ExerciseArt(exercise.exercise.illustration)
                TextButton(onClick = { focus.clearFocus(); detail(exercise.exercise) }) { Text("Мышцы, техника и видео") }
                Text(exercise.exercise.cue, color = Muted, style = MaterialTheme.typography.bodyMedium)
                val currentTime = session.timingTotals(now)?.exercises?.get(exercise.id) ?: ExerciseTime()
                Text("На упражнении ${timeText(currentTime.exercise)} · отдых ${timeText(currentTime.rest)}", color = Lime, style = MaterialTheme.typography.bodySmall)
                val restUntil = active.restUntil
                if (restUntil != null) GymCard {
                    val remaining = ceil(elapsedSeconds(now, restUntil)).toInt()
                    Text(if (remaining > 0) { if (active.restKind == RestKind.betweenExercises) "Отдых перед следующим упражнением" else "Отдых между подходами" } else "Отдых завершён", color = Lime)
                    Text(timeText(remaining.toDouble()), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = model::stopRest) { Text(if (remaining > 0) "Пропустить отдых" else "ОК") }
                }
                if (exercise.isSkipped) GymCard {
                    Text("Упражнение пропущено", style = MaterialTheme.typography.titleMedium)
                    Text("Выполненные подходы и введённые значения сохранены.", color = Muted)
                    TextButton(onClick = { model.skip(session.id, exercise.id, false) }) { Text("Вернуть упражнение") }
                }
                Text("Рабочие подходы · цель ${exercise.targetReps}", style = MaterialTheme.typography.titleMedium)
                exercise.sets.forEachIndexed { index, set ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}", modifier = Modifier.width(22.dp).padding(top = 22.dp), color = if (set.isCompleted) Lime else Muted)
                        SetNumberField(set.id.toString() + "weight", set.weight, "Кг", !set.isCompleted && !exercise.isSkipped, Modifier.weight(1f)) { model.updateSet(exercise.id, set.id, weight = it) }
                        SetNumberField(set.id.toString() + "reps", set.reps, "Повторы", !set.isCompleted && !exercise.isSkipped, Modifier.weight(1f)) { model.updateSet(exercise.id, set.id, reps = it) }
                        Checkbox(set.isCompleted, onCheckedChange = { focus.clearFocus(); model.toggleSet(exercise.id, set.id) }, enabled = !exercise.isSkipped,
                            modifier = Modifier.padding(top = 10.dp).semantics { contentDescription = if (set.isCompleted) "Изменить подход ${index + 1}" else "Записать подход ${index + 1}" })
                    }
                }
                Text("Собственный вес: укажи 0 кг. Отметь каждый выполненный подход.", color = Muted, style = MaterialTheme.typography.bodySmall)
                archive.history.asSequence().flatMap { it.exercises.asSequence() }.firstOrNull { it.exercise.id == exercise.exercise.id && it.sets.any { set -> set.isCompleted } }?.let { previous ->
                    GymCard {
                        Text("В прошлый раз", color = Lime)
                        Text(previous.sets.filter { it.isCompleted }.joinToString(" · ") { "${number(it.weightValue ?: 0.0)} кг × ${it.repValue}" }, color = Muted)
                    }
                }
                Row {
                    TextButton(onClick = { restSettings = true }) { Text("Настроить отдых") }
                    Spacer(Modifier.weight(1f))
                    if (!exercise.isCompleted) TextButton(onClick = { focus.clearFocus(); model.skip(session.id, exercise.id, !exercise.isSkipped) }) { Text(if (exercise.isSkipped) "Вернуть" else "Пропустить") }
                }
                if (session.isResolved) {
                    PrimaryAction(if (session.completedSets > 0) "Завершить тренировку" else "Закрыть без записи", onClick = { if (session.completedSets > 0) finish(false) else stopping = session })
                } else {
                    val next = ((session.currentExercise + 1 until session.exercises.size) + (0 until session.currentExercise)).firstOrNull { !session.exercises[it].isResolved }
                    if (next != null) PrimaryAction("Следующее упражнение", onClick = { focus.clearFocus(); model.select(next) })
                    TextButton(onClick = { stopping = session }, modifier = Modifier.fillMaxWidth()) { Text("Завершить досрочно") }
                }
                Text("Можно свернуть: все записи сохраняются на телефоне.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    stopping?.let { StopWorkoutDialog(model, it, { stopping = null }, { saved -> if (saved != null) finished(saved) else back() }) }
    if (restSettings) {
        var between by rememberSaveable(exercise.id.toString()) { mutableIntStateOf(exercise.restSeconds) }
        var after by rememberSaveable(exercise.id.toString()) { mutableIntStateOf(exercise.restAfterExerciseSeconds) }
        AlertDialog(onDismissRequest = { restSettings = false }, title = { Text("Отдых") }, text = {
            Column { NumberStepper("Между подходами", between, 0..600, 15) { between = it }; NumberStepper("После упражнения", after, 0..600, 15) { after = it }; Text("Текущий таймер сохранит своё время окончания.", color = Muted) }
        }, confirmButton = { TextButton(onClick = { model.change({ WorkoutEngine.updateRest(it, session.id, exercise.id, between, after, Instant.now()) }) { restSettings = false } }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { restSettings = false }) { Text("Отмена") } })
    }
}
@Composable private fun SetNumberField(identity: String, value: String, label: String, enabled: Boolean, modifier: Modifier, change: (String) -> Unit) {
    var text by rememberSaveable(identity) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    OutlinedTextField(text, { text = it; change(it) }, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (label == "Кг") KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.onFocusChanged { focused = it.isFocused })
}
@Composable fun StopWorkoutDialog(model: TrainingViewModel, session: WorkoutSession, dismiss: () -> Unit, stopped: (WorkoutSession?) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Остановить тренировку?") }, text = {
        Text(if (session.completedSets > 0) "Выполнено подходов: ${session.completedSets}. Можно сохранить их в историю. Отмена без записи удалит текущую тренировку и введённые значения."
        else "Выполненных подходов пока нет. Отмена удалит текущую тренировку и введённые значения.")
    }, confirmButton = { Column {
        if (session.completedSets > 0) TextButton(onClick = { model.finish(session.id, true) {
            dismiss(); stopped(model.archive.value.history.firstOrNull { it.id == session.id })
        } }) { Text("Сохранить выполненное и завершить") }
        TextButton(onClick = { model.discard(session.id) { dismiss(); stopped(null) } }) { Text("Отменить без записи", color = MaterialTheme.colorScheme.error) }
    } }, dismissButton = { TextButton(onClick = dismiss) { Text("Продолжить") } })
}
