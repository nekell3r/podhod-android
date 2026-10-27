package dev.podhod.training.data

import dev.podhod.core.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TrainingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `relaunch restores exact archive`() {
        val root = temporary.newFolder()
        val repository = TrainingRepository(root)
        repository.update { it.copy(week = it.week.map { day -> day.copy(title = "Моя программа") }) }
        assertEquals(repository.state.value, TrainingRepository(root).state.value)
    }
    @Test fun `corrupt and future archive are never overwritten`() {
        for (text in listOf("broken json", "{\"version\":999}")) {
            val root = temporary.newFolder()
            val file = File(root, "training.json").apply { writeText(text) }
            val repository = TrainingRepository(root)
            assertNotNull(repository.loadError)
            assertFails { repository.update { it.copy(timezone = "UTC") } }
            assertEquals(text, file.readText())
        }
    }
    @Test fun `failed write leaves state and archive unchanged`() {
        val root = temporary.newFolder()
        val repository = TrainingRepository(root)
        repository.update { it.copy(timezone = "UTC") }
        val before = File(root, "training.json").readText()
        File(root, "training.pending").mkdir()
        assertFails { repository.update { it.copy(timezone = "Europe/Moscow") } }
        assertEquals("UTC", repository.state.value.timezone)
        assertEquals(before, File(root, "training.json").readText())
    }
    @Test fun `concurrent mutations retain both changes`() {
        val root = temporary.newFolder()
        val repository = TrainingRepository(root)
        val workers = (1..7).map { number -> Thread {
            repository.update { it.copy(week = it.week.map { day -> if (day.weekday == number) day.copy(title = number.toString()) else day }) }
        }.apply { start() } }
        workers.forEach { it.join() }
        assertEquals((1..7).map { it.toString() }, repository.state.value.week.map { it.title })
        assertEquals(repository.state.value, TrainingRepository(root).state.value)
    }
}
