package dev.podhod.training.data

import dev.podhod.core.*
import java.io.File
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountWorkspacesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `legacy archive is backed up and assigned to its original owner`() {
        val root = temporary.newFolder()
        val old = TrainingRepository(root)
        old.update { it.copy(sync = it.sync.copy(accountID = "owner-a"), week = it.week.map { day -> day.copy(title = "A") }) }
        val original = File(root, "training.json").readBytes()
        val workspaces = AccountWorkspaces(root)
        val b = workspaces.prepare("owner-b", "owner-a")
        assertFalse(File(b, "training.json").exists())
        workspaces.commit("owner-b")
        val a = workspaces.prepare("owner-a", null)
        assertEquals(old.state.value, TrainingRepository(a).state.value)
        assertTrue(File(root, "legacy-backups").walkTopDown().any { it.isFile && it.readBytes().contentEquals(original) })
        assertTrue(File(root, "training.json").readBytes().contentEquals(original))
    }
    @Test fun `switching accounts restores their own archives and guest remains separate`() {
        val root = temporary.newFolder()
        val workspaces = AccountWorkspaces(root)
        val a = workspaces.prepare("a", null)
        TrainingRepository(a).update { it.copy(week = it.week.map { day -> day.copy(title = "A") }) }
        workspaces.commit("a")
        val guest = workspaces.prepare(null, "a")
        workspaces.commit(null)
        assertTrue(TrainingRepository(guest).state.value.week.all { it.title.isEmpty() })
        val b = workspaces.prepare("b", null)
        TrainingRepository(b).update { it.copy(week = it.week.map { day -> day.copy(title = "B") }) }
        workspaces.commit("b")
        assertTrue(TrainingRepository(workspaces.prepare("a", null)).state.value.week.all { it.title == "A" })
        assertEquals("b", AccountWorkspaces(root).accountID)
        assertNotEquals(a, b)
    }
    @Test fun `corrupt workspace selector cannot be overwritten`() {
        val root = temporary.newFolder()
        val file = File(root, "workspace.json").apply { writeText("broken") }
        val workspaces = AccountWorkspaces(root)
        assertNotNull(workspaces.loadError)
        assertFails { workspaces.prepare("a", null) }
        assertFails { workspaces.commit("a") }
        assertEquals("broken", file.readText())
    }
    @Test fun `guest data is preserved when a new account adopts it`() {
        val root = temporary.newFolder()
        val workspaces = AccountWorkspaces(root)
        val guest = workspaces.prepare(null, null)
        workspaces.commit(null)
        TrainingRepository(guest).update { it.copy(week = it.week.map { day -> day.copy(title = "Guest") }) }
        val target = workspaces.prepare("new-user", null)
        assertEquals(TrainingRepository(guest).state.value, TrainingRepository(target).state.value)
        assertTrue(File(guest, "training.json").exists())
        workspaces.commit("new-user")
        val nextGuest = workspaces.prepare(null, "new-user")
        workspaces.commit(null)
        assertNotEquals(guest, nextGuest)
        val other = workspaces.prepare("another-user", null)
        assertFalse(File(other, "training.json").exists())
    }
}
