package dev.podhod.training.data

import dev.podhod.core.archiveJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable private data class WorkspaceSelection(val version: Int = 1, val accountID: String? = null, val guestDirectory: String = "guest")

class AccountWorkspaces(private val root: File) {
    private val manifest = File(root, "workspace.json")
    private var selection: WorkspaceSelection? = null
    private var rotateGuestOnCommit = false
    val accountID get() = selection?.accountID
    val isLegacy get() = selection == null
    var loadError: String? = null
        private set
    init {
        try {
            if (manifest.exists()) selection = archiveJson.decodeFromString<WorkspaceSelection>(manifest.readText()).also {
                require(it.version == 1 && it.guestDirectory.matches(Regex("guest(?:-[0-9a-f-]{36})?")))
            }
        } catch (_: Exception) { loadError = "Не удалось прочитать список аккаунтов. Исходные файлы защищены от перезаписи." }
    }
    val currentDirectory: File get() = if (isLegacy) root else directory(accountID)
    private fun directory(id: String?): File {
        if (id == null) return File(root, selection?.guestDirectory ?: "guest")
        require(id.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "accounts/$digest")
    }
    @Synchronized fun prepare(id: String?, legacyAccountID: String?): File {
        check(loadError == null) { loadError ?: "Список аккаунтов недоступен." }
        val target = directory(id)
        rotateGuestOnCommit = false
        if (isLegacy) {
            rotateGuestOnCommit = id != null
            val old = File(root, "training.json")
            if (old.exists()) {
                val backup = File(root, "legacy-backups/${UUID.randomUUID()}")
                check(backup.mkdirs())
                old.copyTo(File(backup, "training.json"))
                val owner = directory(legacyAccountID ?: id)
                owner.mkdirs()
                val destination = File(owner, "training.json")
                if (destination.exists()) check(destination.readBytes().contentEquals(old.readBytes())) { "Найден другой архив аккаунта. Оба файла сохранены." }
                else old.copyTo(destination)
            }
        } else if (accountID == null && id != null) {
            val guest = File(currentDirectory, "training.json")
            val destination = File(target, "training.json")
            if (guest.exists() && (!destination.exists() || guest.readBytes().contentEquals(destination.readBytes()))) {
                val archive = archiveJson.decodeFromString<dev.podhod.core.TrainingArchive>(guest.readText())
                archive.validate()
                check(archive.sync.accountID == null)
                target.mkdirs()
                if (!destination.exists()) {
                    guest.copyTo(destination)
                    rotateGuestOnCommit = true
                } else if (guest.readBytes().contentEquals(destination.readBytes())) rotateGuestOnCommit = true
            }
        }
        target.mkdirs()
        return target
    }
    @Synchronized fun commit(id: String?) {
        check(loadError == null)
        root.mkdirs()
        val next = WorkspaceSelection(accountID = id,
            guestDirectory = if (rotateGuestOnCommit) "guest-${UUID.randomUUID()}" else selection?.guestDirectory ?: "guest")
        val pending = File(root, "workspace.pending")
        FileOutputStream(pending).use { it.write(archiveJson.encodeToString(next).toByteArray()); it.fd.sync() }
        Files.move(pending.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        selection = next
        rotateGuestOnCommit = false
    }
}
