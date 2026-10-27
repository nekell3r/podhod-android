@file:kotlinx.serialization.UseSerializers(UUIDSerializer::class)
package dev.podhod.core

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class SyncCheckpoint(
    val accountID: String? = null, val template: TemplateRecord? = null,
    val weeks: List<TrainingWeek> = emptyList(), val active: ActiveRecord? = null,
    val historyIDs: Set<UUID> = emptySet(), val protein: List<ProteinDay> = emptyList(),
)
enum class SyncDecision { acknowledge, download, upload, conflict }

fun <T> syncDecision(local: T, remote: T, baseline: T?, remoteRevision: Long, baselineRevision: Long?, initialLocalEmpty: Boolean): SyncDecision = when {
    local == remote -> SyncDecision.acknowledge
    baselineRevision != null && local == baseline -> SyncDecision.download
    baselineRevision != null && baselineRevision == remoteRevision -> SyncDecision.upload
    baselineRevision != null -> SyncDecision.conflict
    remoteRevision == 0L -> SyncDecision.upload
    initialLocalEmpty -> SyncDecision.download
    else -> SyncDecision.conflict
}

data class ActiveConflictChoices(val server: Boolean, val local: Boolean)
fun activeConflictChoices(local: ActiveWorkoutState?, remote: ActiveWorkoutState?) = ActiveConflictChoices(
    server = local?.let { remote?.session?.containsCompletedSets(it.session) ?: (it.session.completedSets == 0) } ?: true,
    local = remote?.let { local?.session?.containsCompletedSets(it.session) ?: (it.session.completedSets == 0) } ?: true,
)
