package dev.podhod.training.data

import dev.podhod.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

enum class Resource { template, week, active, protein, history }
data class SyncConflict(val resource: Resource, val key: String = "", val message: String, val serverAllowed: Boolean = true, val localAllowed: Boolean = true)
data class SyncStatus(val busy: Boolean = false, val message: String = "Сохранено на телефоне", val conflict: SyncConflict? = null)
private class ConflictFound : Exception()

class CloudSynchronization(private val repository: TrainingRepository, private val auth: CloudAuthentication) {
    private val mutable = MutableStateFlow(SyncStatus())
    val status = mutable.asStateFlow()
    private val mutex = Mutex()
    var focusedWeek: String? = null
    private fun api(): CloudAPI {
        val generation = auth.generation
        return CloudAPI(auth.serverURL, { auth.accessToken() }, { auth.generation == generation && auth.status.value.signedIn })
    }
    suspend fun synchronize() {
        if (!auth.status.value.signedIn || status.value.conflict != null || !mutex.tryLock()) return
        mutable.value = status.value.copy(busy = true, message = "Синхронизация…")
        try {
            val api = api()
            bindAccount(api)
            history(api)
            val initialTemplate = repository.state.value.week
            template(api)
            val archive = repository.state.value
            val today = LocalDate.now(ZoneId.of(archive.timezone))
            val current = weekStart(today).toString()
            archive.scheduledWeeks.filter { week ->
                val baseline = archive.sync.weeks.firstOrNull { it.weekStart == week.weekStart }
                week.weekStart == current || week.weekStart == focusedWeek || baseline == null || week.workouts != baseline.workouts || week.timezone != baseline.timezone
            }.forEach { week(api, it.weekStart, initialTemplate) }
            active(api)
            val days = repository.state.value.protein.map { it.date }.toSet() + today.toString()
            days.forEach { protein(api, it) }
            mutable.value = SyncStatus(message = "Синхронизировано")
        } catch (_: ConflictFound) {
            mutable.value = status.value.copy(message = "Есть конфликт изменений")
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            mutable.value = status.value.copy(message = if (error is CloudFailure && error.status == 409)
                "Сервер изменился. Повторная проверка при следующей синхронизации."
                else if (error is IllegalStateException) error.message ?: "Синхронизация приостановлена"
                else "Сохранено на телефоне · синхронизация недоступна")
        } finally { mutable.value = status.value.copy(busy = false); mutex.unlock() }
    }
    private suspend fun bindAccount(api: CloudAPI) {
        val id = api.request("/v1/profile")["id"]!!.jsonPrimitive.content
        val old = repository.state.value.sync.accountID
        check(old == null || old == id) { "Этот телефон связан с другим аккаунтом. Локальные данные не отправлены." }
        if (old == null) repository.update { it.copy(sync = it.sync.copy(accountID = id)) }
    }
    private fun conflict(resource: Resource, key: String = "", local: ActiveWorkoutState? = null, remote: ActiveWorkoutState? = null): Nothing {
        val choices = activeConflictChoices(local, remote)
        mutable.value = status.value.copy(conflict = SyncConflict(resource, key,
            message = when (resource) {
                Resource.template -> "Шаблон изменён на сервере и телефоне. Выбери версию."
                Resource.week -> "Неделя с $key изменена в двух местах. Переносы и упражнения сохранены."
                Resource.active -> "Активная тренировка отличается. Можно выбрать только версию, сохраняющую все выполненные подходы."
                Resource.protein -> "Отметки протеина за $key изменены в двух местах."
                Resource.history -> "Завершённая тренировка отличается на сервере и телефоне. Обе версии сохранены; история не перезаписана."
            }, serverAllowed = resource != Resource.history && (resource != Resource.active || choices.server),
            localAllowed = resource != Resource.history && (resource != Resource.active || choices.local)))
        throw ConflictFound()
    }
    private suspend fun history(api: CloudAPI) {
        val remote = archiveJson.decodeFromJsonElement<List<WorkoutSession>>(api.request("/v1/history")["sessions"]!!)
        val local = repository.state.value.history
        remote.forEach { session ->
            session.validate()
            if (local.any { it.id == session.id && it != session }) {
                repository.conflictBackup(archiveJson.encodeToJsonElement(remote)); conflict(Resource.history)
            }
        }
        repository.update { a ->
            val indexed = a.history.associateBy { it.id }
            check(remote.none { indexed[it.id]?.let { old -> old != it } == true })
            a.copy(history = (a.history + remote.filter { it.id !in indexed }).sortedByDescending { it.startedAt })
        }
        val known = remote.map { it.id }.toMutableSet()
        repository.state.value.history.filter { it.id !in known }.forEach {
            api.write<WorkoutSession>("/v1/history/${it.id}", null, buildJsonObject { put("session", archiveJson.encodeToJsonElement(it)) })
            known += it.id
        }
        repository.update { it.copy(sync = it.sync.copy(historyIDs = known)) }
    }
    private suspend fun template(api: CloudAPI) {
        val snapshot = repository.state.value
        val local = snapshot.week
        val remote = api.read<TemplateRecord>("/v1/template")
        val base = snapshot.sync.template
        val decision = syncDecision(local, remote.days, base?.days, remote.revision, base?.revision, local.all { it.exercises.isEmpty() })
        if (decision == SyncDecision.conflict) conflict(Resource.template)
        val ack = if (decision == SyncDecision.upload) api.write<TemplateRecord>("/v1/template", remote.revision,
            buildJsonObject { put("days", archiveJson.encodeToJsonElement(local)) }) else remote
        repository.update { a ->
            if (decision == SyncDecision.download && a.week != local) a
            else a.copy(week = if (decision == SyncDecision.download) ack.days else a.week, sync = a.sync.copy(template = ack))
        }
    }
    private suspend fun week(api: CloudAPI, key: String, initialTemplate: List<TrainingDay>) {
        val snapshot = repository.state.value
        var local = snapshot.scheduledWeeks.first { it.weekStart == key }
        val remote = api.read<TrainingWeek>("/v1/weeks/$key?timezone=${URLEncoder.encode(local.timezone, "UTF-8")}")
        remote.validate()
        val base = snapshot.sync.weeks.firstOrNull { it.weekStart == key }
        val untouched = key !in snapshot.editedWeekStarts && TrainingWeek.fromTemplate(LocalDate.parse(key), initialTemplate, local.timezone).workouts.map { it.copy(id = java.util.UUID(0, 0)) } == local.workouts.map { it.copy(id = java.util.UUID(0, 0)) }
        if (remote.revision == 0L && base == null && untouched && initialTemplate != snapshot.week) {
            val previous = local
            local = TrainingWeek.fromTemplate(LocalDate.parse(key), snapshot.week, local.timezone).let { generated ->
                generated.copy(workouts = generated.workouts.map { workout ->
                    previous.workouts.firstOrNull { it.plannedDate == workout.plannedDate }?.let { workout.copy(id = it.id) } ?: workout
                })
            }
            repository.update { a ->
                check(a.scheduledWeeks.firstOrNull { it.weekStart == key } == previous) { "Неделя изменилась во время синхронизации. Повтори проверку." }
                a.copy(scheduledWeeks = a.scheduledWeeks.map { if (it.weekStart == key) local else it })
            }
        }
        val localContent = local.copy(revision = 0)
        val remoteContent = remote.copy(revision = 0)
        val decision = syncDecision(localContent, remoteContent, base?.copy(revision = 0), remote.revision, base?.revision, untouched)
        if (decision == SyncDecision.conflict) conflict(Resource.week, key)
        val ack = if (decision == SyncDecision.upload) api.write<TrainingWeek>("/v1/weeks/$key", remote.revision, buildJsonObject {
            put("timezone", local.timezone); put("workouts", archiveJson.encodeToJsonElement(local.workouts))
        }) else remote
        repository.update { a ->
            if (decision == SyncDecision.download && a.scheduledWeeks.firstOrNull { it.weekStart == key } != local) a
            else a.copy(scheduledWeeks = a.scheduledWeeks.map { if (it.weekStart == key && decision == SyncDecision.download) ack else it },
                sync = a.sync.copy(weeks = a.sync.weeks.filterNot { it.weekStart == key } + ack))
        }
    }
    private suspend fun active(api: CloudAPI) {
        val snapshot = repository.state.value; val local = snapshot.active
        var remote = api.read<ActiveRecord>("/v1/active")
        if (local == null && remote.state?.session?.id in snapshot.sync.historyIDs) {
            remote = api.write("/v1/active", remote.revision, buildJsonObject { put("state", JsonNull) })
        }
        val base = snapshot.sync.active
        val decision = syncDecision(local, remote.state, base?.state, remote.revision, base?.revision, local == null)
        if (decision == SyncDecision.conflict) conflict(Resource.active, local = local, remote = remote.state)
        val ack = if (decision == SyncDecision.upload) api.write<ActiveRecord>("/v1/active", remote.revision,
            buildJsonObject { put("state", archiveJson.encodeToJsonElement(local)) }) else remote
        repository.update { a ->
            if (decision == SyncDecision.download && a.active != local) a
            else (if (decision == SyncDecision.download) WorkoutEngine.restoreActive(a, ack.state, Instant.now()) else a)
                .let { it.copy(sync = it.sync.copy(active = ack)) }
        }
    }
    private suspend fun protein(api: CloudAPI, key: String) {
        val snapshot = repository.state.value
        val saved = snapshot.protein.firstOrNull { it.date == key }
        val local = saved ?: ProteinDay(key)
        val remote = api.read<ProteinDay>("/v1/protein/$key")
        val base = snapshot.sync.protein.firstOrNull { it.date == key }
        val decision = syncDecision(local.copy(revision = 0), remote.copy(revision = 0), base?.copy(revision = 0), remote.revision, base?.revision, saved == null)
        if (decision == SyncDecision.conflict) conflict(Resource.protein, key)
        val ack = if (decision == SyncDecision.upload) api.write<ProteinDay>("/v1/protein/$key", remote.revision, buildJsonObject {
            put("morningTakenAt", local.morningTakenAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("eveningTakenAt", local.eveningTakenAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        }) else remote
        repository.update { a ->
            if (decision == SyncDecision.download && a.protein.firstOrNull { it.date == key } != saved) a
            else a.copy(protein = if (decision == SyncDecision.download) a.protein.filterNot { it.date == key } + ack else a.protein,
                sync = a.sync.copy(protein = a.sync.protein.filterNot { it.date == key } + ack))
        }
    }
    suspend fun resolve(useServer: Boolean) {
        val conflict = status.value.conflict ?: return
        if (!(if (useServer) conflict.serverAllowed else conflict.localAllowed) || !mutex.tryLock()) return
        mutable.value = status.value.copy(busy = true)
        try {
            val api = api(); bindAccount(api)
            val before = repository.state.value
            val path = when (conflict.resource) {
                Resource.template -> "/v1/template"
                Resource.week -> "/v1/weeks/${conflict.key}?timezone=${URLEncoder.encode(before.timezone, "UTF-8")}"
                Resource.active -> "/v1/active"
                Resource.protein -> "/v1/protein/${conflict.key}"
                Resource.history -> return
            }
            val remote = api.request(path)
            repository.conflictBackup(remote)
            repository.update { a ->
                when (conflict.resource) {
                    Resource.template -> {
                        check(!useServer || a.week == before.week) { "Программа изменилась во время выбора. Повтори выбор." }
                        val record = archiveJson.decodeFromJsonElement<TemplateRecord>(remote)
                        a.copy(week = if (useServer) record.days else a.week, sync = a.sync.copy(template = record))
                    }
                    Resource.week -> {
                        check(!useServer || a.scheduledWeeks == before.scheduledWeeks)
                        val record = archiveJson.decodeFromJsonElement<TrainingWeek>(remote)
                        a.copy(scheduledWeeks = if (useServer) a.scheduledWeeks.filterNot { it.weekStart == record.weekStart } + record else a.scheduledWeeks,
                            sync = a.sync.copy(weeks = a.sync.weeks.filterNot { it.weekStart == record.weekStart } + record))
                    }
                    Resource.active -> {
                        check(!useServer || a.active == before.active)
                        val record = archiveJson.decodeFromJsonElement<ActiveRecord>(remote)
                        val choices = activeConflictChoices(a.active, record.state)
                        check(if (useServer) choices.server else choices.local) { "Выбранная версия потеряет выполненные подходы. Обе версии сохранены." }
                        (if (useServer) WorkoutEngine.restoreActive(a, record.state, Instant.now()) else a).let { it.copy(sync = it.sync.copy(active = record)) }
                    }
                    Resource.protein -> {
                        check(!useServer || a.protein == before.protein)
                        val record = archiveJson.decodeFromJsonElement<ProteinDay>(remote)
                        a.copy(protein = if (useServer) a.protein.filterNot { it.date == record.date } + record else a.protein,
                            sync = a.sync.copy(protein = a.sync.protein.filterNot { it.date == record.date } + record))
                    }
                    Resource.history -> a
                }
            }
            mutable.value = SyncStatus(message = "Проверяем выбранную версию…")
        } catch (error: Exception) { mutable.value = status.value.copy(message = error.message ?: "Не удалось разрешить конфликт.")
        } finally { mutable.value = status.value.copy(busy = false); mutex.unlock() }
        if (status.value.conflict == null) synchronize()
    }
}
