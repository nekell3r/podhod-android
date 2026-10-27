package dev.podhod.training

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.podhod.core.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun resetTestArchive() {
        compose.runOnIdle {
            (compose.activity.application as PodhodApplication).repository.update { TrainingArchive() }
        }
        compose.waitForIdle()
    }
    @Test fun `today program and searchable catalog are accessible offline`() {
        compose.onNodeWithText("Сильнее.\nПодход за подходом.").assertExists()
        screenshot("today")
        compose.onNodeWithText("Составить программу").performScrollTo().performClick()
        compose.onNodeWithText("Твоя неделя").assertExists()
        screenshot("program")
        compose.onNodeWithText("Понедельник").performScrollTo().performClick()
        compose.onNodeWithText("Добавить упражнение").performScrollTo().performClick()
        compose.onNodeWithText("Каталог упражнений").assertExists()
        compose.onNodeWithText("Название, мышцы, оборудование").performTextInput("жим")
        screenshot("catalog")
    }
    @Test fun `record workout finish history and mark protein without network`() {
        val application = compose.activity.application as PodhodApplication
        val repository = application.repository
        val today = LocalDate.now(java.time.ZoneId.of(repository.state.value.timezone))
        val exercise = application.catalog.exercises.first().exercise
        compose.runOnIdle {
            repository.update { a ->
                val original = a.scheduledWeek(today).day(today)
                WorkoutEngine.saveDay(a, TrainingDay(today.dayOfWeek.value, "Проверка тренировки", listOf(PlannedExercise(exercise = exercise, setCount = 1))), today, original)
            }
        }
        compose.onNodeWithText("Начать тренировку").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.active != null }
        compose.onNodeWithText("Кг").performScrollTo().performTextInput("20,5")
        compose.onNodeWithText("Повторы").performScrollTo().performTextInput("10")
        compose.onNodeWithContentDescription("Записать подход 1").performClick()
        compose.waitUntil(5000) { repository.state.value.active?.session?.completedSets == 1 }
        screenshot("workout")
        compose.onNodeWithText("Завершить тренировку").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.history.size == 1 }
        compose.onNodeWithText("Тренировка сохранена").assertExists()
        assertEquals(205.0, repository.state.value.history.first().volume)
        screenshot("history")
        compose.onNodeWithText("Готово").performScrollTo().performClick()
        compose.onNodeWithText("Сегодня").performClick()
        compose.onNode(hasText("Утро", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.protein.firstOrNull()?.morningTakenAt != null }
        assertNotNull(repository.state.value.protein.first().morningTakenAt)
        val restored = dev.podhod.training.data.TrainingRepository(File(application.noBackupFilesDir, "training"))
        assertEquals(repository.state.value, restored.state.value)
    }
    @Test fun `switch accounts clears visible state and restores unfinished workout`() {
        val app = compose.activity.application as PodhodApplication
        val date = LocalDate.now()
        compose.runOnIdle {
            app.activateAccount("test-account-a")
            app.repository.update { a ->
                val day = TrainingDay(date.dayOfWeek.value, "Private A", listOf(PlannedExercise(exercise = app.catalog.exercises.first().exercise)))
                WorkoutEngine.start(WorkoutEngine.saveDay(a, day, date, a.scheduledWeek(date).day(date)), date, Instant.now())
            }
        }
        compose.onNodeWithText("ТРЕНИРОВКА В ПРОЦЕССЕ").assertExists()
        compose.runOnIdle { app.activateAccount(null); app.activateAccount("test-account-b") }
        compose.onAllNodesWithText("Private A").assertCountEquals(0)
        compose.runOnIdle { app.activateAccount("test-account-a") }
        compose.onNodeWithText("ТРЕНИРОВКА В ПРОЦЕССЕ").assertExists()
        assertNotNull(app.repository.state.value.active)
    }
    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
