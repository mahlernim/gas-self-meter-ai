package dev.mahlernim.gasselfmeter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Reminders {
    const val CHANNEL = "weekly-meter-check"
    fun schedule(context: Context, profile: Profile) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("weekly-meter-check")
        if (!profile.reminder) {
            context.getSystemService(NotificationManager::class.java).cancel(1)
            return
        }
        val now = ZonedDateTime.now(Korea)
        var next = now.withHour(profile.reminderHour).withMinute(0).withSecond(0).withNano(0)
        while (next <= now || next.dayOfWeek.value != profile.reminderDay) next = next.plusDays(1)
        val work = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
        manager.enqueueUniquePeriodicWork("weekly-meter-check", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, work)
    }
}
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val data = try { SecureStore(applicationContext).read() } catch (_: Exception) { return Result.failure() }
        if (!data.ready || !data.profile.reminder) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "일주일에 한 번 계량기 확인", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(applicationContext, 1, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(applicationContext, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_meter).setContentTitle("똑똑, 계량기를 확인할 시간이에요")
            .setContentText("실제 숫자를 한 번 확인하면 이번 주 추정이 더 나아져요.")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return Result.success()
    }
}
