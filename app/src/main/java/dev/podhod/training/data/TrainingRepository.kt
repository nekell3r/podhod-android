package dev.podhod.training.data

import dev.podhod.core.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

class TrainingRepository(private val directory: File) {
    private val file = File(directory, "training.json")
    private val mutable = MutableStateFlow(TrainingArchive())
    val state = mutable.asStateFlow()
    var loadError: String? = null
        private set
    init {
        try {
            if (file.exists()) mutable.value = archiveJson.decodeFromString<TrainingArchive>(file.readText()).also { it.validate() }
        } catch (_: Exception) {
            loadError = "Не удалось прочитать сохранение. Исходный файл защищён от перезаписи."
        }
    }
    @Synchronized fun update(change: (TrainingArchive) -> TrainingArchive) {
        check(loadError == null) { loadError ?: "Сохранение недоступно." }
        val old = mutable.value
        val next = change(old)
        if (next == old) return
        next.validate()
        directory.mkdirs()
        val bytes = archiveJson.encodeToString(next).toByteArray()
        val temp = File(directory, "training.pending")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = next
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("Не удалось сохранить изменения. Повтори действие.", error)
        }
    }
    @Synchronized fun conflictBackup(remote: JsonElement) {
        check(loadError == null)
        val folder = File(directory, "conflicts/${UUID.randomUUID()}").also { check(it.mkdirs()) }
        File(folder, "local.json").writeText(archiveJson.encodeToString(mutable.value))
        File(folder, "server.json").writeText(remote.toString())
    }
}
