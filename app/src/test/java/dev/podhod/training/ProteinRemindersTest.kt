package dev.podhod.training

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import dev.podhod.training.notifications.ProteinReminders
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProteinRemindersTest {
    @Test fun `two local reminders are scheduled and disabling cancels them`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val reminders = ProteinReminders(context)
        reminders.setEnabled(true)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(2, alarms.size)
        val times = alarms.map { Instant.ofEpochMilli(it.triggerAtTime).atZone(ZoneId.systemDefault()).toLocalTime().toString() }.sorted()
        assertEquals(listOf("09:30", "21:30"), times)
        reminders.schedule()
        assertEquals(2, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        reminders.setEnabled(false)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
