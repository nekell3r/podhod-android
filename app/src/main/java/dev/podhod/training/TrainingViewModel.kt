package dev.podhod.training

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.podhod.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrainingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PodhodApplication
    private val workspaceVersion = app.workspaceVersion.value
    val repository = app.repository
    val catalog = app.catalog
    val auth = app.auth
    val sync = app.sync
    val reminders = app.reminders
    val archive = repository.state
    private val mutableError = MutableStateFlow(app.workspaces.loadError ?: repository.loadError)
    val error = mutableError.asStateFlow()
    private val writes = Mutex()
    private var syncDelay: Job? = null
    private var resolvingAccount = false
    private var accountResolved = false
    fun clearError() { mutableError.value = null }
    fun report(message: String) { mutableError.value = message }
    fun today() = LocalDate.now(ZoneId.of(archive.value.timezone))
    fun change(action: (TrainingArchive) -> TrainingArchive, after: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                writes.withLock { withContext(Dispatchers.IO) {
                    check(app.workspaces.loadError == null) { app.workspaces.loadError ?: "Список аккаунтов недоступен." }
                    check(app.workspaceVersion.value == workspaceVersion) { "Аккаунт изменился. Действие из прежнего экрана не применено." }
                    repository.update(action)
                } }
                after()
                syncDelay?.cancel()
                syncDelay = viewModelScope.launch { delay(1200); synchronize() }
            } catch (error: Exception) { mutableError.value = error.message ?: "Не удалось сохранить изменения." }
        }
    }
    fun ensureWeek(date: LocalDate) = change({ WorkoutEngine.ensureWeek(it, date) })
    fun start(date: LocalDate, after: () -> Unit) = change({ WorkoutEngine.start(it, date, Instant.now()) }, after)
    fun select(index: Int) = change({ WorkoutEngine.select(it, index, Instant.now()) })
    fun updateSet(exercise: UUID, set: UUID, reps: String? = null, weight: String? = null) = change({ WorkoutEngine.updateSet(it, exercise, set, reps, weight, Instant.now()) })
    fun toggleSet(exercise: UUID, set: UUID) = change({ WorkoutEngine.toggleSet(it, exercise, set, Instant.now()) })
    fun skip(session: UUID, exercise: UUID, value: Boolean) = change({ WorkoutEngine.skip(it, session, exercise, value, Instant.now()) })
    fun stopRest() = change({ WorkoutEngine.stopRest(it, Instant.now()) })
    fun finish(session: UUID, partial: Boolean, after: () -> Unit) = change({ WorkoutEngine.finish(it, session, partial, Instant.now()) }, after)
    fun discard(session: UUID, after: () -> Unit) = change({ WorkoutEngine.discard(it, session) }, after)
    fun markProtein(morning: Boolean, taken: Boolean) = change({ WorkoutEngine.protein(it, today(), morning, taken, Instant.now()) })
    fun loadExample() = change({ a ->
        require(a.week.all { it.exercises.isEmpty() } && a.scheduledWeeks.all { it.workouts.isEmpty() })
        val example = emptyTemplate().map { day ->
            val indices = when (day.weekday) { 1 -> listOf(0, 2, 4, 6); 3 -> listOf(1, 3, 8, 9); 5 -> listOf(0, 5, 10, 7); else -> emptyList() }
            day.copy(title = if (day.weekday == 3) "Ноги и ягодицы" else if (indices.isNotEmpty()) "Верх тела" else "",
                exercises = indices.map { PlannedExercise(exercise = catalog.exercises[it].exercise) })
        }
        a.copy(week = example, scheduledWeeks = a.scheduledWeeks.map { TrainingWeek.fromTemplate(LocalDate.parse(it.weekStart), example, a.timezone) })
    })
    fun synchronize() {
        if (app.workspaceVersion.value != workspaceVersion || resolvingAccount || app.workspaces.loadError != null) return
        viewModelScope.launch {
            if (!auth.status.value.signedIn) return@launch
            resolvingAccount = true
            try {
                if (!accountResolved) {
                    val id = auth.verifiedAccountID()
                    writes.withLock { withContext(Dispatchers.IO) { app.activateAccount(id) } }
                    accountResolved = true
                }
                if (app.workspaceVersion.value == workspaceVersion) withContext(Dispatchers.IO) { sync.synchronize() }
            } catch (_: Exception) { }
            finally { resolvingAccount = false }
        }
    }
    fun resolve(server: Boolean) { viewModelScope.launch { withContext(Dispatchers.IO) { sync.resolve(server) } } }
    suspend fun loginIntent(): Intent = auth.authorizationIntent()
    suspend fun telegramLoginIntent(): Intent = auth.telegramAuthorizationIntent()
    fun completeLogin(intent: Intent?) { viewModelScope.launch {
        try {
            auth.complete(intent)
            val id = auth.verifiedAccountID()
            writes.withLock { withContext(Dispatchers.IO) { app.activateAccount(id) } }
            synchronize()
        } catch (_: Exception) { report("Не удалось завершить вход и открыть архив аккаунта. Локальные данные сохранены.") }
    } }
    fun signOut() {
        auth.signOut()
        viewModelScope.launch {
            try { writes.withLock { withContext(Dispatchers.IO) { app.activateAccount(null) } } }
            catch (_: Exception) { report("Не удалось открыть гостевой архив. Все файлы сохранены.") }
        }
    }
    fun reconcile() { if (archive.value.active != null) change({ it.copy(active = it.active?.reconcile(Instant.now())) }) }
}
