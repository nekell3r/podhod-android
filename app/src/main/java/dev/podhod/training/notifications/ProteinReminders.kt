package dev.podhod.training.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.podhod.training.MainActivity
import dev.podhod.training.R
import java.time.ZonedDateTime

class ProteinReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    val enabled get() = prefs.getBoolean("enabled", false)
    val canNotify get() = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exact get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    val status get() = when {
        !enabled -> "Напоминания выключены"
        !canNotify -> "Уведомления запрещены в настройках устройства"
        exact -> "Ежедневно в 09:30 и 21:30"
        else -> "Около 09:30 и 21:30 · для точного времени разреши будильники"
    }
    fun setEnabled(value: Boolean) {
        require(!value || canNotify) { "Разреши уведомления в настройках устройства." }
        check(prefs.edit().putBoolean("enabled", value).commit())
        if (value) schedule() else (0..1).forEach { alarms.cancel(pending(it)); NotificationManagerCompat.from(context).cancel(100 + it) }
    }
    fun schedule() {
        if (!enabled || !canNotify) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("protein", "Протеин", NotificationManager.IMPORTANCE_DEFAULT))
        for (period in 0..1) {
            val now = ZonedDateTime.now()
            var next = now.withHour(if (period == 0) 9 else 21).withMinute(30).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending(period))
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending(period))
        }
    }
    private fun pending(period: Int) = PendingIntent.getBroadcast(context, period,
        Intent(context, ProteinReminderReceiver::class.java).putExtra("period", period), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
class ProteinReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = ProteinReminders(context)
        if (!reminders.enabled || !reminders.canNotify) return
        val period = intent.getIntExtra("period", 0)
        if (period !in 0..1) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "protein").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (period == 0) "Протеин — утро" else "Протеин — вечер")
            .setContentText("Отметь приём протеина в разделе «Сегодня».").setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(100 + period, notification) } catch (_: SecurityException) { }
        reminders.schedule()
    }
}
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) ProteinReminders(context).schedule()
    }
}
