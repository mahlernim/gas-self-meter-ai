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

object SubmissionScheduler {
    private const val WORK = "meter-auto-submit"
    const val CHANNEL = "meter-submission"
    fun schedule(context: Context, data: AppData) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(WORK)
        manager.cancelUniqueWork("$WORK-now")
        if (!data.submissionSettings.enabled || !data.submissionSettings.automatic || data.credentials == null) return
        val now = ZonedDateTime.now(Korea)
        var next = now.withHour(10).withMinute(0).withSecond(0).withNano(0)
        if (next <= now) next = next.plusDays(1)
        val work = PeriodicWorkRequestBuilder<SubmissionWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, work)
        val checkNow = OneTimeWorkRequestBuilder<SubmissionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.REPLACE, checkNow)
    }
}

class SubmissionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!SubmissionGate.lock.tryLock()) return Result.success()
        try {
        val store = SecureStore(applicationContext)
        var data = try { store.read() } catch (_: Exception) { return Result.failure() }
        val credentials = data.credentials ?: return Result.success()
        if (!data.submissionSettings.enabled || !data.submissionSettings.automatic) return Result.success()
        var pending: SubmissionRecord? = null
        val detail = try {
            BusanClient(credentials).use { client ->
                val contract = client.login().find { BusanClient.opaque("C000:${it.bp}:${it.ca}") == data.profile.contract }
                    ?: return Result.success()
                val target = client.selfReadTarget(contract)
                val decision = SubmissionPolicy.decide(data, target, System.currentTimeMillis(), automatic = true)
                if (!decision.allowed || decision.value == null) return Result.success()
                pending = SubmissionRecord(target.cycle, target.start, target.end, decision.value, System.currentTimeMillis(), "pending", "자동 입력 결과 확인 대기")
                data = data.copy(submissions = (data.submissions.filterNot { it.cycle == target.cycle } + pending!!).takeLast(100))
                store.write(data)
                val outcome = client.submitReading(target, decision.value)
                val status = when { !outcome.accepted -> "rejected"; outcome.confirmed -> "confirmed"; else -> "uncertain" }
                when (status) {
                    "confirmed" -> "검침값 ${decision.value} m³ 자동 입력을 완료했어요."
                    "rejected" -> "공급사가 자동 입력을 받지 않았어요. 직접 확인해 주세요."
                    else -> "자동 입력 결과를 확정하지 못했어요. 다시 보내지 않았습니다."
                }.also { message -> replace(store, data, pending!!.copy(status = status, detail = message)) }
            }
        } catch (_: Exception) {
            pending?.let { replace(store, data, it.copy(status = "uncertain", detail = "자동 입력 결과를 확정하지 못했어요. 다시 보내지 않았습니다.")) }
            if (pending == null) return Result.success()
            "자동 입력 결과를 확정하지 못했어요. 공급사 홈페이지에서 확인해 주세요."
        }
        notify(detail)
        return Result.success()
        } finally { SubmissionGate.lock.unlock() }
    }
    private fun replace(store: SecureStore, data: AppData, record: SubmissionRecord) {
        store.write(data.copy(submissions = (data.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100)))
    }
    private fun notify(text: String) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SubmissionScheduler.CHANNEL, "검침값 자동 입력 결과", NotificationManager.IMPORTANCE_DEFAULT))
        val pendingIntent = PendingIntent.getActivity(applicationContext, 2, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(2, NotificationCompat.Builder(applicationContext, SubmissionScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_meter).setContentTitle("똑똑 자가검침 AI")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val data = try { SecureStore(applicationContext).read() } catch (_: Exception) { return Result.failure() }
        if (!data.ready || !data.profile.reminder) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "계량기 실측 확인 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(applicationContext, 1, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(applicationContext, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_meter).setContentTitle("똑똑, 계량기를 확인할 시간이에요")
            .setContentText("계량기의 실제 숫자를 입력하면 이번 주 추정이 더 정확해져요.")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        return Result.success()
    }
}
