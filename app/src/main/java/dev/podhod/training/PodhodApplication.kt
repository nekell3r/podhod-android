package dev.podhod.training

import android.app.Application
import dev.podhod.core.*
import dev.podhod.training.data.*
import dev.podhod.training.notifications.ProteinReminders
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PodhodApplication : Application() {
    val workspaces by lazy { AccountWorkspaces(File(noBackupFilesDir, "training")) }
    private var selectedRepository: TrainingRepository? = null
    private var selectedSync: CloudSynchronization? = null
    private val mutableWorkspaceVersion = MutableStateFlow(0)
    val workspaceVersion = mutableWorkspaceVersion.asStateFlow()
    val repository: TrainingRepository get() = selectedRepository ?: TrainingRepository(workspaces.currentDirectory).also { selectedRepository = it }
    val catalog by lazy { archiveJson.decodeFromString<ExerciseCatalog>(assets.open("exercise-catalog.json").bufferedReader().use { it.readText() }).also { it.validate() } }
    val auth by lazy { CloudAuthentication(this, BuildConfig.API_URL) }
    val sync: CloudSynchronization get() = selectedSync ?: CloudSynchronization(repository, auth).also { selectedSync = it }
    val reminders by lazy { ProteinReminders(this) }

    @Synchronized fun activateAccount(accountID: String?) {
        check(workspaces.loadError == null) { workspaces.loadError ?: "Список аккаунтов недоступен." }
        if (!workspaces.isLegacy && workspaces.accountID == accountID) return
        check(repository.loadError == null) { repository.loadError ?: "Сохранение недоступно." }
        repository.update { WorkoutEngine.pauseTimingForAccountSwitch(it, java.time.Instant.now()) }
        val directory = workspaces.prepare(accountID, repository.state.value.sync.accountID)
        val next = TrainingRepository(directory)
        check(next.loadError == null) { next.loadError ?: "Архив аккаунта недоступен." }
        check(next.state.value.sync.accountID == null || next.state.value.sync.accountID == accountID) { "Архив принадлежит другому аккаунту." }
        workspaces.commit(accountID)
        selectedRepository = next
        selectedSync = CloudSynchronization(next, auth)
        mutableWorkspaceVersion.value++
    }
}
