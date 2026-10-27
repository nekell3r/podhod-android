package dev.podhod.core

import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.util.UUID
import kotlin.test.*

class WorkoutTest {
    private val date = LocalDate.parse("2026-10-27")
    private val now = Instant.parse("2026-10-27T09:00:00Z")
    private val exercise = Exercise(name = "Жим", muscle = "Грудь")
    private fun started(count: Int = 2): TrainingArchive {
        val days = emptyTemplate().map { if (it.weekday == 2) it.copy(exercises = listOf(PlannedExercise(exercise = exercise, setCount = count))) else it }
        return WorkoutEngine.start(TrainingArchive(week = days), date, now)
    }
    private fun complete(archive: TrainingArchive, at: Instant = now.plusSeconds(10)): TrainingArchive {
        val e = archive.active!!.session.exercises.first()
        val set = e.sets.first { !it.isCompleted }
        val draft = WorkoutEngine.updateSet(archive, e.id, set.id, "10", "20,5", at)
        return WorkoutEngine.toggleSet(draft, e.id, set.id, at)
    }
    @Test fun `set validation accepts zero bodyweight and decimal comma`() {
        assertTrue(LoggedSet(reps = "1", weight = "0").isValid)
        assertTrue(LoggedSet(reps = "9999", weight = "2000").isValid)
        assertTrue(LoggedSet(reps = "12", weight = "2,5").isValid)
        listOf("NaN", "Infinity", "-1", "2001", "").forEach { assertFalse(LoggedSet(reps = "10", weight = it).isValid) }
        listOf("0", "10000", "2.5", "").forEach { assertFalse(LoggedSet(reps = it, weight = "0").isValid) }
    }
    @Test fun `program changes preserve active snapshot`() {
        val a = started()
        val original = a.week[1]
        val updated = WorkoutEngine.saveDay(a, original.copy(title = "Другая", exercises = emptyList()), null, original)
        assertEquals(a.active, updated.active)
        assertEquals(a.scheduledWeeks, updated.scheduledWeeks)
    }
    @Test fun `stale editor cannot overwrite newer day`() {
        val a = started()
        val original = a.week[1]
        val changed = WorkoutEngine.saveDay(a, original.copy(title = "Новое"), null, original)
        assertFails { WorkoutEngine.saveDay(changed, original.copy(title = "Старый черновик"), null, original) }
    }
    @Test fun `template creates independent calendar weeks`() {
        val a = started()
        val next = WorkoutEngine.ensureWeek(a, date.plusWeeks(1))
        assertNotEquals(a.scheduledWeeks.first().workouts.first().id, next.scheduledWeeks.last().workouts.first().id)
        assertEquals(DayOfWeek.MONDAY, java.time.LocalDate.parse(next.scheduledWeeks.last().weekStart).dayOfWeek)
    }
    @Test fun `move rejects occupied day and different week`() {
        var a = started()
        val day = TrainingDay(3, exercises = listOf(PlannedExercise(exercise = exercise)))
        a = WorkoutEngine.saveDay(a, day, date.plusDays(1), TrainingDay(3))
        val week = a.scheduledWeeks.first(); val id = week.workouts.first().id
        assertFails { WorkoutEngine.move(a, week.weekStart, id, date.plusDays(1)) }
        assertFails { WorkoutEngine.move(a, week.weekStart, id, date.plusWeeks(1)) }
    }
    @Test fun `completion starts between set rest and undo clears it`() {
        val a = complete(started())
        assertEquals(RestKind.betweenSets, a.active!!.restKind)
        assertEquals(now.plusSeconds(100), a.active!!.restUntil)
        val e = a.active!!.session.exercises.first()
        val undone = WorkoutEngine.toggleSet(a, e.id, e.sets.first().id, now.plusSeconds(20))
        assertNull(undone.active!!.restUntil)
        assertEquals(0, undone.active!!.session.completedSets)
    }
    @Test fun `last exercise never starts extra rest`() {
        val a = complete(started(1))
        assertNull(a.active!!.restUntil)
        assertTrue(a.active!!.session.isResolved)
    }
    @Test fun `skip preserves completed sets and drafts and unskip selects it`() {
        val a = complete(started())
        val session = a.active!!.session; val e = session.exercises.first()
        val skipped = WorkoutEngine.skip(a, session.id, e.id, true, now.plusSeconds(20))
        assertEquals(e.sets, skipped.active!!.session.exercises.first().sets)
        assertNull(skipped.active!!.restUntil)
        val restored = WorkoutEngine.skip(skipped, session.id, e.id, false, now.plusSeconds(30))
        assertEquals(0, restored.active!!.session.currentExercise)
        assertFalse(restored.active!!.session.exercises.first().isSkipped)
    }
    @Test fun `cannot skip completed exercise`() {
        val a = complete(started(1)); val s = a.active!!.session
        assertFails { WorkoutEngine.skip(a, s.id, s.exercises.first().id, true, now) }
    }
    @Test fun `changing rest preserves captured running deadline`() {
        val a = complete(started()); val s = a.active!!.session
        val changed = WorkoutEngine.updateRest(a, s.id, s.exercises.first().id, 30, 60, now.plusSeconds(20))
        assertEquals(a.active!!.restUntil, changed.active!!.restUntil)
    }
    @Test fun `finish requires completed sets and explicit partial consent`() {
        val a = started()
        assertFails { WorkoutEngine.finish(a, a.active!!.session.id, true, now) }
        val completed = complete(a)
        assertFails { WorkoutEngine.finish(completed, a.active!!.session.id, false, now.plusSeconds(20)) }
        val finished = WorkoutEngine.finish(completed, a.active!!.session.id, true, now.plusSeconds(20))
        assertNull(finished.active)
        assertEquals(1, finished.history.size)
        finished.validate()
        assertTrue(finished.history.first().timing!!.intervals.all { it.endedAt != null })
    }
    @Test fun `stale stop cannot discard different session`() {
        assertFails { WorkoutEngine.discard(started(), UUID.randomUUID()) }
    }
    @Test fun `rest expires at deadline after background time`() {
        val a = complete(started())
        val reconciled = a.active!!.reconcile(now.plusSeconds(200))
        val totals = reconciled.session.timingTotals(now.plusSeconds(200))!!
        assertEquals(90.0, totals.rest)
        assertEquals(110.0, totals.exercise)
        assertEquals(0.0, totals.untracked)
        reconciled.session.validate()
    }
    @Test fun `backwards wall clock cannot overlap timing`() {
        val a = complete(started())
        val stopped = WorkoutEngine.stopRest(a, now.minusSeconds(100))
        stopped.active!!.session.validate()
    }
    @Test fun `legacy history retains unknown time`() {
        val a = complete(started(1)); val s = a.active!!.session.copy(timing = null, finishedAt = now.plusSeconds(30))
        assertNull(s.timingTotals())
    }
    @Test fun `remote command settles old timing before switch`() {
        val a = started(); val s = a.active!!.session
        val incoming = a.active!!.copy(session = s.copy(exercises = s.exercises.map { it.copy(isSkipped = true) }))
        val restored = WorkoutEngine.restoreActive(a, incoming, now.plusSeconds(60))
        assertEquals(60.0, restored.active!!.session.timingTotals(now.plusSeconds(60))!!.exercise)
        assertEquals(TimingKind.other, restored.active!!.session.timing!!.intervals.last().kind)
    }
    @Test fun `protein days are independent and can be unmarked`() {
        val a = WorkoutEngine.protein(TrainingArchive(), date, true, true, now)
        val b = WorkoutEngine.protein(a, date.plusDays(1), false, true, now.plusSeconds(86400))
        assertEquals(2, b.protein.size)
        assertNull(WorkoutEngine.protein(b, date, true, false, now).protein.first().morningTakenAt)
    }
    @Test fun `archive roundtrip preserves identities drafts and intervals`() {
        val a = complete(started())
        assertEquals(a, archiveJson.decodeFromString<TrainingArchive>(archiveJson.encodeToString(TrainingArchive.serializer(), a)))
    }
    @Test fun `unknown archive version is rejected`() { assertFails { TrainingArchive(version = 999).validate() } }
    @Test fun `time under another account remains untracked`() {
        val paused = WorkoutEngine.pauseTimingForAccountSwitch(started(), now.plusSeconds(10))
        val resumed = paused.active!!.reconcile(now.plusSeconds(100))
        val totals = resumed.session.timingTotals(now.plusSeconds(110))!!
        assertEquals(20.0, totals.exercise)
        assertEquals(90.0, totals.untracked)
        resumed.session.validate()
    }
}
