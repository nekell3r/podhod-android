@file:kotlinx.serialization.UseSerializers(UUIDSerializer::class, InstantSerializer::class)
package dev.podhod.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

val archiveJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable data class Exercise(
    val id: UUID = UUID.randomUUID(), val name: String, val muscle: String,
    val illustration: String = "curl", val cue: String = "",
)
@Serializable data class PlannedExercise(
    val id: UUID = UUID.randomUUID(), val exercise: Exercise, val setCount: Int = 3,
    val targetReps: String = "8–12", val restSeconds: Int = 90,
    val restAfterExerciseSeconds: Int = restSeconds,
) {
    fun validate() {
        require(exercise.name.isNotBlank() && setCount in 1..12 && targetReps.isNotBlank() &&
            restSeconds in 0..600 && restAfterExerciseSeconds in 0..600) { "Проверь название, подходы, повторы и отдых (0–600 с)." }
    }
}
@Serializable data class TrainingDay(val weekday: Int, val title: String = "", val exercises: List<PlannedExercise> = emptyList()) {
    val name get() = weekdayNames[weekday - 1]
    val setCount get() = exercises.sumOf { it.setCount }
    fun validate() { require(weekday in 1..7); exercises.forEach { it.validate() }; require(exercises.map { it.id }.distinct().size == exercises.size) }
}
val weekdayNames = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
val weekdayShortNames = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
fun emptyTemplate() = (1..7).map { TrainingDay(it) }
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

@Serializable data class ScheduledWorkout(
    val id: UUID = UUID.randomUUID(), val plannedDate: String, val title: String,
    val exercises: List<PlannedExercise>,
) {
    fun day() = TrainingDay(LocalDate.parse(plannedDate).dayOfWeek.value, title, exercises)
}
@Serializable data class TrainingWeek(val weekStart: String, val timezone: String, val revision: Long = 0, val workouts: List<ScheduledWorkout>) {
    fun day(date: LocalDate) = workouts.firstOrNull { it.plannedDate == date.toString() }?.day() ?: TrainingDay(date.dayOfWeek.value)
    fun validate() {
        require(LocalDate.parse(weekStart).dayOfWeek == DayOfWeek.MONDAY && revision >= 0)
        ZoneId.of(timezone)
        require(workouts.map { it.id }.distinct().size == workouts.size && workouts.map { it.plannedDate }.distinct().size == workouts.size)
        workouts.forEach { require(dev.podhod.core.weekStart(LocalDate.parse(it.plannedDate)).toString() == weekStart && it.exercises.isNotEmpty()); it.day().validate() }
    }
    companion object {
        fun fromTemplate(date: LocalDate, template: List<TrainingDay>, timezone: String): TrainingWeek {
            val start = weekStart(date)
            return TrainingWeek(start.toString(), timezone, workouts = template.filter { it.exercises.isNotEmpty() }.map {
                ScheduledWorkout(plannedDate = start.plusDays((it.weekday - 1).toLong()).toString(), title = it.title, exercises = it.exercises)
            })
        }
    }
}
@Serializable data class LoggedSet(
    val id: UUID = UUID.randomUUID(), val reps: String = "", val weight: String = "", val isCompleted: Boolean = false,
) {
    val repValue get() = reps.trim().toIntOrNull()
    val weightValue get() = weight.trim().replace(',', '.').toDoubleOrNull()
    val isValid get() = (repValue ?: 0) in 1..9999 && weightValue?.let { it.isFinite() && it in 0.0..2000.0 } == true
    val volume get() = if (isCompleted) (repValue ?: 0) * (weightValue ?: 0.0) else 0.0
}
@Serializable data class SessionExercise(
    val id: UUID = UUID.randomUUID(), val exercise: Exercise, val targetReps: String,
    val restSeconds: Int, val restAfterExerciseSeconds: Int = restSeconds,
    val sets: List<LoggedSet>, val isSkipped: Boolean = false,
) {
    val isCompleted get() = sets.all { it.isCompleted }
    val isResolved get() = isCompleted || isSkipped
    val skippedSets get() = if (isSkipped) sets.count { !it.isCompleted } else 0
}
@Serializable data class WorkoutSession(
    val id: UUID = UUID.randomUUID(), val title: String, val weekday: Int,
    val startedAt: Instant, val finishedAt: Instant? = null, val timing: WorkoutTiming? = null,
    val scheduledWorkoutID: UUID? = null, val plannedDate: String? = null,
    val currentExercise: Int = 0, val exercises: List<SessionExercise>,
) {
    val completedSets get() = exercises.sumOf { e -> e.sets.count { it.isCompleted } }
    val totalSets get() = exercises.sumOf { it.sets.size }
    val skippedSets get() = exercises.sumOf { it.skippedSets }
    val volume get() = exercises.sumOf { e -> e.sets.sumOf { it.volume } }
    val isResolved get() = exercises.all { it.isResolved }
    fun containsCompletedSets(other: WorkoutSession): Boolean {
        if (id != other.id) return other.completedSets == 0
        val own = exercises.flatMap { it.sets }.associateBy { it.id }
        return other.exercises.flatMap { it.sets }.filter { it.isCompleted }.all { own[it.id] == it }
    }
    fun validate() {
        require(weekday in 1..7 && exercises.isNotEmpty() && currentExercise in exercises.indices)
        require(finishedAt == null || finishedAt >= startedAt)
        require(exercises.map { it.id }.distinct().size == exercises.size)
        val sets = exercises.flatMap { it.sets }
        require(sets.map { it.id }.distinct().size == sets.size)
        exercises.forEach { e ->
            require(e.exercise.name.isNotBlank() && e.sets.size in 1..12 && e.restSeconds in 0..600 && e.restAfterExerciseSeconds in 0..600)
            require(e.sets.all { !it.isCompleted || it.isValid })
        }
        timing?.validate(this)
    }
    companion object {
        fun start(day: TrainingDay, at: Instant, workout: ScheduledWorkout? = null) = WorkoutSession(
            title = day.title.ifBlank { day.name }, weekday = day.weekday,
            startedAt = at.truncatedTo(ChronoUnit.MILLIS).let { if (it < at) it.plusMillis(1) else it },
            scheduledWorkoutID = workout?.id, plannedDate = workout?.plannedDate,
            exercises = day.exercises.map { p -> SessionExercise(exercise = p.exercise, targetReps = p.targetReps,
                restSeconds = p.restSeconds, restAfterExerciseSeconds = p.restAfterExerciseSeconds, sets = List(p.setCount) { LoggedSet() }) },
        )
    }
}
@Serializable enum class RestKind { betweenSets, betweenExercises }
@Serializable data class ActiveWorkoutState(val session: WorkoutSession, val restUntil: Instant? = null, val restKind: RestKind? = null, val restExerciseID: UUID? = null)
@Serializable data class TemplateRecord(val revision: Long = 0, val days: List<TrainingDay> = emptyTemplate())
@Serializable data class ActiveRecord(val revision: Long = 0, val state: ActiveWorkoutState? = null)
@Serializable data class ProteinDay(val date: String, val revision: Long = 0, val morningTakenAt: Instant? = null, val eveningTakenAt: Instant? = null)
@Serializable data class TrainingArchive(
    val version: Int = 1, val week: List<TrainingDay> = emptyTemplate(),
    val scheduledWeeks: List<TrainingWeek> = emptyList(), val editedWeekStarts: Set<String> = emptySet(),
    val active: ActiveWorkoutState? = null, val history: List<WorkoutSession> = emptyList(),
    val protein: List<ProteinDay> = emptyList(), val timezone: String = ZoneId.systemDefault().id,
    val sync: SyncCheckpoint = SyncCheckpoint(),
) {
    fun scheduledWeek(date: LocalDate) = scheduledWeeks.firstOrNull { it.weekStart == weekStart(date).toString() }
        ?: TrainingWeek.fromTemplate(date, week, timezone)
    fun validate() {
        require(version == 1) { "Сохранение создано другой версией приложения. Исходный файл не изменён." }
        ZoneId.of(timezone)
        require(week.map { it.weekday }.sorted() == (1..7).toList())
        week.forEach { it.validate() }; scheduledWeeks.forEach { it.validate() }
        require(scheduledWeeks.map { it.weekStart }.distinct().size == scheduledWeeks.size)
        active?.session?.let { it.validate(); require(it.finishedAt == null) }
        active?.restExerciseID?.let { id -> require(active.session.exercises.any { it.id == id }) }
        history.forEach { it.validate(); require(it.finishedAt != null && it.completedSets > 0) }
        require(history.map { it.id }.distinct().size == history.size && history.none { it.id == active?.session?.id })
        require(protein.map { it.date }.distinct().size == protein.size)
        protein.forEach { require(LocalDate.parse(it.date).toString() == it.date && it.revision >= 0) }
    }
}
