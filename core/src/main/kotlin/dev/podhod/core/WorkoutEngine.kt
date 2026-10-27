package dev.podhod.core

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object WorkoutEngine {
    fun pauseTimingForAccountSwitch(archive: TrainingArchive, now: Instant): TrainingArchive {
        val active = archive.active?.reconcile(now) ?: return archive
        val timing = active.session.timing!!
        return archive.copy(active = active.copy(session = active.session.copy(timing = timing.transition(null, timing.boundary(now)))))
    }
    fun ensureWeek(archive: TrainingArchive, date: LocalDate): TrainingArchive {
        val week = archive.scheduledWeek(date)
        return if (archive.scheduledWeeks.any { it.weekStart == week.weekStart }) archive
        else archive.copy(scheduledWeeks = (archive.scheduledWeeks + week).sortedBy { it.weekStart })
    }
    fun saveDay(archive: TrainingArchive, day: TrainingDay, date: LocalDate?, expected: TrainingDay): TrainingArchive {
        day.validate()
        val current = date?.let { archive.scheduledWeek(it).day(it) } ?: archive.week.first { it.weekday == day.weekday }
        require(current == expected) { "Программа изменилась, пока был открыт редактор. Черновик сохранён на экране; открой актуальный день." }
        if (date == null) return archive.copy(week = archive.week.map { if (it.weekday == day.weekday) day else it })
        val stored = ensureWeek(archive, date)
        val key = weekStart(date).toString()
        val week = stored.scheduledWeek(date)
        val old = week.workouts.firstOrNull { it.plannedDate == date.toString() }
        val workouts = week.workouts.filterNot { it.plannedDate == date.toString() } +
            if (day.exercises.isEmpty()) emptyList() else listOf(ScheduledWorkout(id = old?.id ?: UUID.randomUUID(),
                plannedDate = date.toString(), title = day.title.trim(), exercises = day.exercises))
        return stored.copy(scheduledWeeks = stored.scheduledWeeks.map {
            if (it.weekStart == key) it.copy(workouts = workouts.sortedBy { w -> w.plannedDate }) else it
        }, editedWeekStarts = stored.editedWeekStarts + key)
    }
    fun move(archive: TrainingArchive, start: String, id: UUID, target: LocalDate): TrainingArchive {
        require(weekStart(target).toString() == start) { "Выбери дату в пределах этой недели." }
        val week = archive.scheduledWeeks.first { it.weekStart == start }
        require(week.workouts.any { it.id == id })
        require(week.workouts.none { it.id != id && it.plannedDate == target.toString() }) { "На этой дате уже есть тренировка." }
        return archive.copy(scheduledWeeks = archive.scheduledWeeks.map {
            if (it.weekStart == start) it.copy(workouts = it.workouts.map { w -> if (w.id == id) w.copy(plannedDate = target.toString()) else w }.sortedBy { w -> w.plannedDate }) else it
        }, editedWeekStarts = archive.editedWeekStarts + start)
    }
    fun start(archive: TrainingArchive, date: LocalDate, now: Instant): TrainingArchive {
        if (archive.active != null) return archive
        val stored = ensureWeek(archive, date)
        val workout = stored.scheduledWeek(date).workouts.firstOrNull { it.plannedDate == date.toString() }
        require(workout != null) { "Сначала добавь упражнения в программу этого дня." }
        return stored.copy(active = ActiveWorkoutState(WorkoutSession.start(workout.day(), now, workout)).reconcile(now))
    }
    fun select(archive: TrainingArchive, index: Int, now: Instant): TrainingArchive {
        val active = requireNotNull(archive.active).reconcile(now)
        require(index in active.session.exercises.indices)
        return archive.copy(active = active.copy(session = active.session.copy(currentExercise = index)).reconcile(now))
    }
    fun updateSet(archive: TrainingArchive, exerciseID: UUID, setID: UUID, reps: String?, weight: String?, now: Instant): TrainingArchive {
        val active = requireNotNull(archive.active).reconcile(now)
        val session = active.session
        val exercise = session.exercises.first { it.id == exerciseID }
        require(!exercise.isSkipped)
        val set = exercise.sets.first { it.id == setID }
        require(!set.isCompleted) { "Сначала сними отметку выполненного подхода." }
        return archive.copy(active = active.copy(session = session.copy(exercises = session.exercises.map { e ->
            if (e.id != exerciseID) e else e.copy(sets = e.sets.map { if (it.id == setID) it.copy(reps = reps ?: it.reps, weight = weight ?: it.weight) else it })
        })))
    }
    fun toggleSet(archive: TrainingArchive, exerciseID: UUID, setID: UUID, now: Instant): TrainingArchive {
        var active = requireNotNull(archive.active).reconcile(now)
        val session = active.session
        val exercise = session.exercises.first { it.id == exerciseID }
        require(!exercise.isSkipped)
        val set = exercise.sets.first { it.id == setID }
        val completing = !set.isCompleted
        require(!completing || set.isValid) { "Укажи 1–9999 повторов и вес 0–2000 кг. Для собственного веса укажи 0." }
        val updatedExercise = exercise.copy(sets = exercise.sets.map { if (it.id == setID) it.copy(isCompleted = completing) else it })
        val updated = session.copy(exercises = session.exercises.map { if (it.id == exerciseID) updatedExercise else it })
        active = active.copy(session = updated)
        val boundary = updated.timing!!.boundary(now)
        if (completing) {
            val hasNext = updated.exercises.any { it.id != exerciseID && !it.isResolved }
            val seconds = if (updatedExercise.isCompleted) { if (hasNext) exercise.restAfterExerciseSeconds else 0 } else exercise.restSeconds
            active = active.copy(restUntil = if (seconds > 0) boundary.plusSeconds(seconds.toLong()) else null,
                restKind = if (seconds > 0) { if (updatedExercise.isCompleted) RestKind.betweenExercises else RestKind.betweenSets } else null,
                restExerciseID = if (seconds > 0) exerciseID else null)
        } else if (active.restExerciseID == null || active.restExerciseID == exerciseID) active = active.copy(restUntil = null, restKind = null, restExerciseID = null)
        return archive.copy(active = active.reconcile(boundary))
    }
    fun skip(archive: TrainingArchive, sessionID: UUID, exerciseID: UUID, skipped: Boolean, now: Instant): TrainingArchive {
        var active = requireNotNull(archive.active).reconcile(now)
        require(active.session.id == sessionID) { "Активная тренировка уже изменилась." }
        val session = active.session
        val index = session.exercises.indexOfFirst { it.id == exerciseID }
        require(index >= 0)
        require(!skipped || !session.exercises[index].isCompleted) { "Выполненное упражнение нельзя пропустить." }
        val exercises = session.exercises.map { if (it.id == exerciseID) it.copy(isSkipped = skipped) else it }
        val next = if (!skipped) index else if (session.currentExercise == index)
            ((index + 1 until exercises.size) + (0 until index)).firstOrNull { !exercises[it].isResolved } ?: index else session.currentExercise
        active = active.copy(session = session.copy(exercises = exercises, currentExercise = next))
        if (skipped && (active.restExerciseID == null || active.restExerciseID == exerciseID)) active = active.copy(restUntil = null, restKind = null, restExerciseID = null)
        return archive.copy(active = active.reconcile(now))
    }
    fun updateRest(archive: TrainingArchive, sessionID: UUID, exerciseID: UUID, between: Int, after: Int, now: Instant): TrainingArchive {
        require(between in 0..600 && after in 0..600) { "Время отдыха должно быть от 0 до 600 секунд." }
        val active = requireNotNull(archive.active).reconcile(now)
        require(active.session.id == sessionID && active.session.exercises.any { it.id == exerciseID })
        return archive.copy(active = active.copy(session = active.session.copy(exercises = active.session.exercises.map {
            if (it.id == exerciseID) it.copy(restSeconds = between, restAfterExerciseSeconds = after) else it
        })))
    }
    fun stopRest(archive: TrainingArchive, now: Instant): TrainingArchive {
        val active = requireNotNull(archive.active).reconcile(now)
        return archive.copy(active = active.copy(restUntil = null, restKind = null, restExerciseID = null).reconcile(now))
    }
    fun finish(archive: TrainingArchive, sessionID: UUID, partial: Boolean, now: Instant): TrainingArchive {
        val active = requireNotNull(archive.active).reconcile(now)
        val session = active.session
        require(session.id == sessionID) { "Активная тренировка уже изменилась." }
        require(session.completedSets > 0) { "Запиши хотя бы один подход перед завершением." }
        require(session.isResolved || partial) { "Остались незаписанные подходы. Можно завершить досрочно." }
        val end = session.timing!!.boundary(now)
        val finished = session.copy(finishedAt = end, timing = session.timing.transition(null, end))
        return archive.copy(active = null, history = listOf(finished) + archive.history)
    }
    fun discard(archive: TrainingArchive, sessionID: UUID): TrainingArchive {
        require(archive.active?.session?.id == sessionID) { "Активная тренировка уже изменилась." }
        return archive.copy(active = null)
    }
    fun protein(archive: TrainingArchive, date: LocalDate, morning: Boolean, taken: Boolean, now: Instant): TrainingArchive {
        val old = archive.protein.firstOrNull { it.date == date.toString() } ?: ProteinDay(date.toString())
        val event = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val changed = if (morning) old.copy(morningTakenAt = if (taken) old.morningTakenAt ?: event else null)
        else old.copy(eveningTakenAt = if (taken) old.eveningTakenAt ?: event else null)
        return archive.copy(protein = (archive.protein.filterNot { it.date == changed.date } + changed).sortedBy { it.date })
    }
    fun restoreActive(archive: TrainingArchive, incoming: ActiveWorkoutState?, now: Instant): TrainingArchive {
        incoming?.session?.validate()
        require(incoming == null || (incoming.session.finishedAt == null && archive.history.none { it.id == incoming.session.id }))
        val settled = archive.active?.reconcile(now)
        var restored = incoming
        if (restored != null) {
            var timing = restored.session.timing
            if (restored.session.id == settled?.session?.id) timing = settled.session.timing
            else if (timing != null) {
                val boundary = timing.boundary(now)
                val last = timing.intervals.lastOrNull()
                timing = timing.transition(null, if (last?.kind == TimingKind.rest && last.endedAt == null) minOf(boundary, last.restDeadline!!) else boundary)
            }
            restored = restored.copy(session = restored.session.copy(timing = timing)).reconcile(now)
        }
        return archive.copy(active = restored)
    }
}
