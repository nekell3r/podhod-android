@file:kotlinx.serialization.UseSerializers(UUIDSerializer::class, InstantSerializer::class)
package dev.podhod.core

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable enum class TimingKind { exercise, rest, other }
@Serializable data class TimingInterval(
    val id: UUID = UUID.randomUUID(), val kind: TimingKind, val exerciseID: UUID? = null,
    val restKind: RestKind? = null, val startedAt: Instant, val endedAt: Instant? = null, val restDeadline: Instant? = null,
)
data class TimingPhase(val kind: TimingKind, val exerciseID: UUID? = null, val restKind: RestKind? = null, val deadline: Instant? = null)
data class ExerciseTime(val exercise: Double = 0.0, val rest: Double = 0.0)
data class TimingTotals(val exercise: Double, val rest: Double, val other: Double, val untracked: Double, val exercises: Map<UUID, ExerciseTime>)
@Serializable data class WorkoutTiming(val version: Int = 1, val trackedFrom: Instant, val intervals: List<TimingInterval> = emptyList()) {
    fun boundary(at: Instant): Instant = maxOf(at.truncatedTo(ChronoUnit.MILLIS), trackedFrom, intervals.lastOrNull()?.let { it.endedAt ?: it.startedAt } ?: trackedFrom)
    fun transition(phase: TimingPhase?, at: Instant): WorkoutTiming {
        val now = boundary(at)
        val changed = intervals.toMutableList()
        val last = changed.lastOrNull()
        if (last != null && last.endedAt == null) {
            if (phase != null && last.kind == phase.kind && last.exerciseID == phase.exerciseID && last.restKind == phase.restKind && last.restDeadline == phase.deadline) return this
            if (now > last.startedAt) changed[changed.lastIndex] = last.copy(endedAt = now) else changed.removeAt(changed.lastIndex)
        }
        if (phase != null) changed += TimingInterval(kind = phase.kind, exerciseID = phase.exerciseID,
            restKind = phase.restKind, startedAt = now, restDeadline = phase.deadline)
        return copy(intervals = changed)
    }
    fun validate(session: WorkoutSession) {
        require(version == 1 && trackedFrom >= session.startedAt && (session.finishedAt == null || trackedFrom <= session.finishedAt))
        require(intervals.map { it.id }.distinct().size == intervals.size)
        var previousEnd = trackedFrom
        intervals.forEachIndexed { i, interval ->
            require(interval.startedAt >= previousEnd)
            if (interval.endedAt == null) require(i == intervals.lastIndex && session.finishedAt == null)
            else {
                require(interval.endedAt >= interval.startedAt && (session.finishedAt == null || interval.endedAt <= session.finishedAt))
                previousEnd = interval.endedAt
            }
            when (interval.kind) {
                TimingKind.other -> require(interval.exerciseID == null && interval.restKind == null && interval.restDeadline == null)
                TimingKind.exercise -> require(session.exercises.any { it.id == interval.exerciseID } && interval.restKind == null && interval.restDeadline == null)
                TimingKind.rest -> require(session.exercises.any { it.id == interval.exerciseID } && interval.restKind != null &&
                    interval.restDeadline != null && interval.restDeadline >= interval.startedAt && (interval.endedAt == null || interval.endedAt <= interval.restDeadline))
            }
        }
    }
}
val WorkoutSession.exercisePhase: TimingPhase get() = exercises.getOrNull(currentExercise)?.takeUnless { it.isResolved }
    ?.let { TimingPhase(TimingKind.exercise, it.id) } ?: TimingPhase(TimingKind.other)

fun ActiveWorkoutState.reconcile(at: Instant): ActiveWorkoutState {
    var timing = session.timing ?: WorkoutTiming(trackedFrom = maxOf(at.truncatedTo(ChronoUnit.MILLIS), session.startedAt))
    val now = timing.boundary(maxOf(at, session.startedAt))
    timing.intervals.lastOrNull()?.takeIf { it.endedAt == null && it.kind == TimingKind.rest }?.restDeadline?.let {
        if (now >= it) timing = timing.transition(session.exercisePhase, it)
    }
    val phase = if (restUntil != null && restUntil > now) TimingPhase(TimingKind.rest,
        restExerciseID ?: session.exercises[session.currentExercise].id, restKind ?: RestKind.betweenSets, restUntil) else session.exercisePhase
    return copy(session = session.copy(timing = timing.transition(phase, now)))
}

fun WorkoutSession.timingTotals(at: Instant = Instant.now()): TimingTotals? {
    val measurement = timing ?: return null
    val until = finishedAt ?: at
    var exercise = 0.0; var rest = 0.0; var other = 0.0
    val perExercise = mutableMapOf<UUID, ExerciseTime>()
    measurement.intervals.forEach { interval ->
        val end = minOf(interval.endedAt ?: until, until)
        if (interval.kind == TimingKind.rest) {
            val seconds = elapsedSeconds(interval.startedAt, minOf(end, interval.restDeadline!!))
            rest += seconds
            interval.exerciseID?.let { id -> perExercise[id] = (perExercise[id] ?: ExerciseTime()).let { it.copy(rest = it.rest + seconds) } }
            if (interval.endedAt == null && end > interval.restDeadline) {
                val remainder = elapsedSeconds(interval.restDeadline, end)
                exercisePhase.exerciseID?.let { id ->
                    exercise += remainder
                    perExercise[id] = (perExercise[id] ?: ExerciseTime()).let { it.copy(exercise = it.exercise + remainder) }
                } ?: run { other += remainder }
            }
        } else {
            val seconds = elapsedSeconds(interval.startedAt, end)
            if (interval.kind == TimingKind.exercise) {
                exercise += seconds
                interval.exerciseID?.let { id -> perExercise[id] = (perExercise[id] ?: ExerciseTime()).let { it.copy(exercise = it.exercise + seconds) } }
            } else other += seconds
        }
    }
    return TimingTotals(exercise, rest, other, (elapsedSeconds(startedAt, until) - exercise - rest - other).coerceAtLeast(0.0), perExercise)
}
fun elapsedSeconds(from: Instant, until: Instant) = (Duration.between(from, until).toMillis() / 1000.0).coerceAtLeast(0.0)
fun timeText(seconds: Double): String {
    val n = seconds.coerceAtLeast(0.0).toLong()
    return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60)
}
