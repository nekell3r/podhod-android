package dev.podhod.core

import java.util.UUID
import kotlin.test.*

class CatalogAndSyncTest {
    private val catalog = archiveJson.decodeFromString<ExerciseCatalog>(javaClass.getResource("/exercise-catalog.json")!!.readText())
    @Test fun `bundled catalog validates and contains all canonical entries`() { catalog.validate(); assertEquals(58, catalog.exercises.size) }
    @Test fun `search combines muscles and equipment and folds yo`() {
        val e = catalog.exercises.first()
        assertTrue(catalog.search(e.exercise.name.uppercase()).contains(e))
        assertEquals(catalog.search("лёжа"), catalog.search("лежа"))
        val muscle = e.primaryMuscles.first()
        assertTrue(catalog.search(muscle = muscle, apparatus = e.equipment.firstOrNull()).all { muscle in it.primaryMuscles + it.secondaryMuscles })
    }
    @Test fun `aliases resolve without changing snapshot`() {
        val a = catalog.exerciseIDAliases.first()
        assertEquals(a.exerciseID, catalog.entry(a.id)!!.exercise.id)
        val old = catalog.entry(a.id)!!.exercise.copy(id = a.id, cue = "Личная заметка")
        catalog.volume(listOf(PlannedExercise(exercise = old)))
        assertEquals(a.id, old.id); assertEquals("Личная заметка", old.cue)
    }
    @Test fun `volume counts primary once and separates unknown exercise`() {
        val e = catalog.exercises.first()
        val custom = Exercise(name = "Своё", muscle = "")
        val result = catalog.volume(listOf(PlannedExercise(exercise = e.exercise, setCount = 3), PlannedExercise(exercise = custom, setCount = 2)))
        assertEquals(5, result.totalSets); assertEquals(1, result.unmappedExercises); assertEquals(2, result.unmappedSets)
        assertEquals(3, result.muscles.first { it.id == e.primaryMuscles.first() }.direct)
    }
    @Test fun `catalog rejects alias chains and unsafe videos`() {
        val e = catalog.exercises.first()
        assertFails { catalog.copy(exerciseIDAliases = listOf(ExerciseAlias(UUID.randomUUID(), UUID.randomUUID()))).validate() }
        val v = catalog.exercises.first { it.videos.isNotEmpty() }
        assertFails { catalog.copy(exercises = listOf(v.copy(videos = listOf(v.videos.first().copy(url = "http://example.com/video"))))).validate() }
    }
    @Test fun `three way sync never silently overwrites concurrent edits`() {
        assertEquals(SyncDecision.conflict, syncDecision("local", "remote", "old", 2, 1, false))
        assertEquals(SyncDecision.upload, syncDecision("local", "old", "old", 1, 1, false))
        assertEquals(SyncDecision.download, syncDecision("old", "remote", "old", 2, 1, false))
        assertEquals(SyncDecision.acknowledge, syncDecision("same", "same", "old", 2, 1, false))
        assertEquals(SyncDecision.conflict, syncDecision("local", "remote", null, 1, null, false))
        assertEquals(SyncDecision.download, syncDecision("", "remote", null, 1, null, true))
    }
    @Test fun `active conflict forbids losing completed sets`() {
        val e = Exercise(name = "Жим", muscle = "Грудь")
        val set = LoggedSet(reps = "10", weight = "20", isCompleted = true)
        val session = WorkoutSession(title = "Тест", weekday = 1, startedAt = java.time.Instant.now(), exercises = listOf(SessionExercise(exercise = e, targetReps = "10", restSeconds = 90, sets = listOf(set))))
        val local = ActiveWorkoutState(session)
        val remote = local.copy(session = session.copy(exercises = session.exercises.map { it.copy(sets = listOf(set.copy(isCompleted = false))) }))
        assertFalse(activeConflictChoices(local, remote).server)
        assertTrue(activeConflictChoices(local, remote).local)
    }
}
