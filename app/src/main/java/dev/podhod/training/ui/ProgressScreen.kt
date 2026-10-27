package dev.podhod.training.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.podhod.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun ProgressScreen(archive: TrainingArchive, onHistory: (WorkoutSession) -> Unit) {
    val history = archive.history.sortedByDescending { it.startedAt }
    var exerciseID by rememberSaveable { mutableStateOf<String?>(null) }
    val exercises = history.flatMap { it.exercises }.filter { it.sets.any { s -> s.isCompleted } }.map { it.exercise }.distinctBy { it.id }.sortedBy { it.name }
    val exercise = exercises.firstOrNull { it.id.toString() == exerciseID } ?: exercises.firstOrNull()
    ScreenColumn {
        ScreenHeading("Твой прогресс", "Маленькие шаги. Заметные изменения.")
        if (history.isEmpty()) GymCard {
            Text("Здесь будет твой рост", style = MaterialTheme.typography.headlineMedium)
            Text("Заверши первую тренировку — появятся история, рабочие веса и график по каждому упражнению.", color = Muted)
        } else {
            GymCard {
                DataRow("Тренировки", history.size.toString())
                DataRow("Записанные подходы", history.sumOf { it.completedSets }.toString())
                DataRow("Объём, кг", number(history.sumOf { it.volume }))
            }
            GymCard {
                Text("Время в зале", style = MaterialTheme.typography.titleLarge)
                val totals = history.mapNotNull { it.timingTotals() }
                val elapsed = history.sumOf { elapsedSeconds(it.startedAt, it.finishedAt!!) }
                DataRow("Всего", timeText(elapsed))
                DataRow("На упражнениях", timeText(totals.sumOf { it.exercise }))
                DataRow("Отдых", timeText(totals.sumOf { it.rest }))
                DataRow("Переходы и прочее", timeText(totals.sumOf { it.other }))
                val unknown = history.sumOf { it.timingTotals()?.untracked ?: elapsedSeconds(it.startedAt, it.finishedAt!!) }
                if (unknown > .001) DataRow("Не измерялось", timeText(unknown))
                Text("Время на упражнении включает подготовку. Для старых тренировок разбиение не измерялось.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (exercise != null) GymCard {
                var menu by remember { mutableStateOf(false) }
                Text("Рабочий вес, кг", style = MaterialTheme.typography.titleLarge)
                Box {
                    TextButton(onClick = { menu = true }) { Text(exercise.name) }
                    DropdownMenu(menu, { menu = false }) {
                        exercises.forEach { e -> DropdownMenuItem(text = { Text(e.name) }, onClick = { exerciseID = e.id.toString(); menu = false }) }
                    }
                }
                val points = history.reversed().mapNotNull { session ->
                    session.exercises.filter { it.exercise.id == exercise.id }.flatMap { it.sets }.filter { it.isCompleted }.mapNotNull { it.weightValue }.maxOrNull()?.let { session to it }
                }
                Text("${number(points.lastOrNull()?.second ?: 0.0)} кг в последней тренировке", fontWeight = FontWeight.Bold)
                WeightChart(points)
                Text("Максимальный вес выполненного подхода за тренировку. Сравнивай при схожем числе повторов.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("История · ${history.size}", style = MaterialTheme.typography.titleLarge)
            history.forEach { session ->
                GymCard {
                    TextButton(onClick = { onHistory(session) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(8.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(sessionDate(session.startedAt), color = Lime)
                            Text("${session.completedSets} подх. · ${timeText(elapsedSeconds(session.startedAt, session.finishedAt!!))} · ${number(session.volume)} кг", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun WeightChart(points: List<Pair<WorkoutSession, Double>>) {
    val max = maxOf(10.0, (points.maxOfOrNull { it.second } ?: 0.0) * 1.2)
    Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = "Рабочий вес: " + points.joinToString { "${sessionDate(it.first.startedAt)}: ${number(it.second)} кг" } }) {
        val bottom = size.height - 12.dp.toPx()
        for (i in 0..3) { val y = bottom * i / 3; drawLine(Muted.copy(alpha = .16f), Offset(0f, y), Offset(size.width, y)) }
        val start = points.firstOrNull()?.first?.startedAt?.toEpochMilli() ?: 0
        val end = points.lastOrNull()?.first?.startedAt?.toEpochMilli() ?: start
        fun coordinate(point: Pair<WorkoutSession, Double>): Offset = Offset(
            if (start == end) size.width / 2 else ((point.first.startedAt.toEpochMilli() - start).toDouble() / (end - start) * (size.width - 12.dp.toPx()) + 6.dp.toPx()).toFloat(),
            bottom - (point.second / max * (bottom - 8.dp.toPx())).toFloat())
        val path = Path()
        points.forEachIndexed { index, point -> val position = coordinate(point); if (index == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y) }
        drawPath(path, Lime, style = Stroke(3.dp.toPx()))
        points.forEach { drawCircle(Lime, 4.dp.toPx(), coordinate(it)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.firstOrNull()?.let { sessionDate(it.first.startedAt) } ?: "", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("0–${number(max)} кг", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(points.lastOrNull()?.let { sessionDate(it.first.startedAt) } ?: "", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
@Composable fun HistoryScreen(session: WorkoutSession, back: () -> Unit) {
    FullScreen(session.title, back) { padding ->
        ScreenColumn(Modifier.padding(padding)) {
            ScreenHeading("Тренировка сохранена", sessionDate(session.startedAt))
            GymCard {
                DataRow("Выполнено подходов", "${session.completedSets} из ${session.totalSets}")
                if (session.skippedSets > 0) DataRow("Пропущено подходов", session.skippedSets.toString())
                DataRow("Объём", "${number(session.volume)} кг")
                TimingSummary(session)
            }
            session.exercises.forEach { exercise ->
                GymCard {
                    Text(exercise.exercise.name, style = MaterialTheme.typography.titleMedium)
                    val totals = session.timingTotals()
                    if (totals != null) {
                        val time = totals.exercises[exercise.id] ?: ExerciseTime()
                        Text("Учтено: ${timeText(time.exercise)} · отдых ${timeText(time.rest)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else Text("Время упражнения не измерялось", color = Muted)
                    exercise.sets.forEachIndexed { index, set -> DataRow("Подход ${index + 1}", if (set.isCompleted) "${number(set.weightValue ?: 0.0)} кг × ${set.repValue} ✓" else if (exercise.isSkipped) "Пропущен" else "Не выполнен") }
                }
            }
            PrimaryAction("Готово", onClick = back)
        }
    }
}
@Composable private fun TimingSummary(session: WorkoutSession) {
    DataRow("Всего времени", timeText(elapsedSeconds(session.startedAt, session.finishedAt ?: Instant.now())))
    val totals = session.timingTotals()
    if (totals == null) Text("Разбиение времени не измерялось в этой тренировке.", color = Muted)
    else {
        DataRow("На упражнениях", timeText(totals.exercise)); DataRow("Отдых", timeText(totals.rest)); DataRow("Переходы и прочее", timeText(totals.other))
        if (totals.untracked > .001) DataRow("Не измерялось", timeText(totals.untracked))
        Text("Время на упражнении включает подготовку.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
private fun sessionDate(instant: Instant): String = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru-RU")))
