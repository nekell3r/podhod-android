@file:kotlinx.serialization.UseSerializers(UUIDSerializer::class)
package dev.podhod.core

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class Taxonomy(val id: String, val name: String)
@Serializable data class Attribution(val author: String, val license: String, val licenseURL: String, val sourceURL: String)
@Serializable data class ExerciseVideo(val id: String, val url: String, val author: String, val license: String, val licenseURL: String, val sourceURL: String, val playback: String? = null)
@Serializable data class ExerciseAlias(val id: UUID, val exerciseID: UUID)
@Serializable data class CatalogEntry(val exercise: Exercise, val aliases: List<String>, val primaryMuscles: List<String>, val secondaryMuscles: List<String>,
    val equipment: List<String>, val instructions: List<String>, val videos: List<ExerciseVideo>, val sourceURL: String, val attribution: Attribution)
data class MuscleVolume(val id: String, val name: String, val direct: Int, val indirect: Int) { val weighted get() = direct + indirect * 0.5 }
data class PlannedVolume(val catalogVersion: String, val totalSets: Int, val muscles: List<MuscleVolume>, val unmappedExercises: Int, val unmappedSets: Int)
@Serializable data class ExerciseCatalog(val version: String, val muscles: List<Taxonomy>, val equipment: List<Taxonomy>,
    val exercises: List<CatalogEntry>, val exerciseIDAliases: List<ExerciseAlias> = emptyList()) {
    fun entry(id: UUID): CatalogEntry? = exercises.firstOrNull { it.exercise.id == (exerciseIDAliases.firstOrNull { a -> a.id == id }?.exerciseID ?: id) }
    fun search(query: String = "", muscle: String? = null, apparatus: String? = null): List<CatalogEntry> {
        val normalized = searchText(query.trim())
        return exercises.filter { e ->
            (muscle == null || muscle in e.primaryMuscles + e.secondaryMuscles) && (apparatus == null || apparatus in e.equipment) &&
                (normalized.isEmpty() || (listOf(e.exercise.name, e.exercise.muscle) + e.aliases +
                    muscles.filter { it.id in e.primaryMuscles + e.secondaryMuscles }.map { it.name } +
                    equipment.filter { it.id in e.equipment }.map { it.name }).any { searchText(it).contains(normalized) })
        }
    }
    fun volume(plans: List<PlannedExercise>): PlannedVolume {
        val direct = mutableMapOf<String, Int>(); val indirect = mutableMapOf<String, Int>()
        var unknown = 0; var unknownSets = 0
        plans.forEach { plan ->
            val entry = entry(plan.exercise.id)
            if (entry == null || entry.primaryMuscles.isEmpty() && entry.secondaryMuscles.isEmpty()) { unknown++; unknownSets += plan.setCount }
            else {
                val primary = entry.primaryMuscles.toSet()
                primary.forEach { direct[it] = (direct[it] ?: 0) + plan.setCount }
                (entry.secondaryMuscles.toSet() - primary).forEach { indirect[it] = (indirect[it] ?: 0) + plan.setCount }
            }
        }
        return PlannedVolume(version, plans.sumOf { it.setCount }, muscles.mapNotNull {
            val d = direct[it.id] ?: 0; val i = indirect[it.id] ?: 0
            if (d + i == 0) null else MuscleVolume(it.id, it.name, d, i)
        }, unknown, unknownSets)
    }
    fun validate() {
        require(version.isNotBlank() && exercises.isNotEmpty())
        require(muscles.map { it.id }.distinct().size == muscles.size && equipment.map { it.id }.distinct().size == equipment.size)
        require((muscles + equipment).all { it.id.isNotBlank() && it.name.isNotBlank() })
        val ids = exercises.map { it.exercise.id }.toSet()
        require(ids.size == exercises.size && exerciseIDAliases.map { it.id }.distinct().size == exerciseIDAliases.size)
        require(exerciseIDAliases.all { it.id !in ids && it.exerciseID in ids })
        exercises.forEach { e ->
            require(e.exercise.name.isNotBlank() && (e.primaryMuscles + e.secondaryMuscles).all { id -> muscles.any { it.id == id } } && e.equipment.all { id -> equipment.any { it.id == id } })
            require(e.videos.map { it.id }.distinct().size == e.videos.size)
            e.videos.forEach { v ->
                require(v.id.isNotBlank() && v.author.isNotBlank() && v.license.isNotBlank() && v.playback in listOf(null, "external"))
                listOf(v.url, v.licenseURL, v.sourceURL).forEach { requireHttps(it) }
            }
        }
    }
}
fun requireHttps(address: String): URI = URI(address).also { require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null) { "Требуется HTTPS без credentials." } }
private fun searchText(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
