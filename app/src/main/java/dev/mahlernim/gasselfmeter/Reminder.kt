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

private inline fun <reified T : ListenableWorker> daily(context: Context, name: String, enabled: Boolean, hour: Int, minute: Int = 0, network: Boolean = false) {
    val manager = WorkManager.getInstance(context)
    if (!enabled) { manager.cancelUniqueWork(name); return }
    val now = ZonedDateTime.now(Korea)
    var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (next <= now) next = next.plusDays(1)
    val request = PeriodicWorkRequestBuilder<T>(24, TimeUnit.HOURS)
        .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().apply { if (network) setRequiredNetworkType(NetworkType.CONNECTED) }.build()).build()
    val preferences = context.getSharedPreferences("work-schedules", Context.MODE_PRIVATE)
    val signature = "$hour/$minute"
    val policy = if (preferences.getString(name, null) == signature) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
    manager.enqueueUniquePeriodicWork(name, policy, request)
    preferences.edit().putString(name, signature).apply()
}

object Reminders {
    const val CHANNEL = "weekly-meter-check"
    fun schedule(context: Context, profile: Profile) {
        WorkManager.getInstance(context).cancelUniqueWork("weekly-meter-check")
        daily<ReminderWorker>(context, "calibration-daily-check", profile.reminder, profile.reminderHour)
        context.getSystemService(NotificationManager::class.java).cancel(1)
    }
}

/** Tab indices shared by the bottom bar and by notification destinations. */
object AppTabs {
    const val EXTRA = "dev.mahlernim.gasselfmeter.TAB"
    const val METER = 0
    const val SUBMISSION = 1
}

object SubmissionScheduler {
    const val CHANNEL = "meter-submission"
    fun schedule(context: Context, data: AppData) {
        val connected = data.ready && !data.profile.reconnectRequired &&
            ((data.credentials != null && Providers.get(data.profile.providerId).passwordConnection) || data.gasappConnection != null || data.energyTalkConnection != null)
        WorkManager.getInstance(context).cancelUniqueWork("meter-auto-submit-now")
        daily<SubmissionWorker>(context, "meter-auto-submit", connected && data.submissionSettings.automatic &&
            Providers.get(data.profile.providerId).automaticSubmission, 10, network = true)
        daily<SubmissionReminderWorker>(context, "meter-submission-reminder", connected && data.submissionSettings.reminder,
            data.submissionSettings.reminderHour, data.submissionSettings.reminderMinute, network = true)
        if (!data.submissionSettings.reminder) context.getSystemService(NotificationManager::class.java).cancel(3)
    }
}

internal fun notify(context: Context, id: Int, channel: String, channelName: String, title: String, text: String,
    destination: Int = AppTabs.METER) {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
    // Carry the destination so a submission notice opens the submission tab instead of whichever
    // tab the activity happens to be restored on. Tapping it never submits anything by itself.
    val target = Intent(context, MainActivity::class.java).putExtra(AppTabs.EXTRA, destination)
    val intent = PendingIntent.getActivity(context, id, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_meter).setContentTitle(title)
        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(intent)
        .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
}

class SubmissionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!SubmissionGate.lock.tryLock()) return Result.retry()
        try {
            val store = SecureStore(applicationContext)
            var data = try { store.read() } catch (_: Exception) { return Result.failure() }
            if (data.profile.reconnectRequired) return Result.success()
            if (data.gasappConnection != null) return GasappBackground.automatic(applicationContext, this)
            val expected = data
            val credentials = data.credentials ?: return Result.success()
            if (!data.submissionSettings.automatic || !Providers.get(data.profile.providerId).automaticSubmission) return Result.success()
            var pending: SubmissionRecord? = null
            var target: SelfReadTarget? = data.cachedSelfRead
            var text: String? = null
            try {
                val provider = Providers.skens(data.profile.providerId)
                SkensClient(provider, credentials).use { client ->
                    val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract } ?: return Result.success()
                    target = client.selfReadTarget(contract)
                    data = store.update { latest -> if (BackgroundState.sameAccount(latest, expected)) latest.copy(cachedSelfRead = target) else latest }
                    if (!BackgroundState.sameAccount(data, expected) || !data.submissionSettings.automatic) return Result.success()
                    val current = target!!
                    val decision = SubmissionPolicy.decide(data, current, System.currentTimeMillis(), automatic = true)
                    if (current.submitted) { applicationContext.getSystemService(NotificationManager::class.java).cancel(3); return Result.success() }
                    if (dateOf(System.currentTimeMillis()).toString() != current.end) return Result.success()
                    if (!decision.allowed || decision.value == null) {
                        text = ReminderPolicy.submissionText(data, current, System.currentTimeMillis(), failed = true)
                    } else {
                        pending = SubmissionRecord(current.cycle, current.start, current.end, decision.value, System.currentTimeMillis(), "pending", "자동 제출 결과 확인 대기")
                        data = store.update { latest ->
                            val latestDecision = SubmissionPolicy.decide(latest, current, System.currentTimeMillis(), automatic = true)
                            if (isStopped || !BackgroundState.sameAccount(latest, expected) || !latestDecision.allowed || latestDecision.value != decision.value) latest
                            else latest.copy(submissions = (latest.submissions.filterNot { it.cycle == current.cycle } + pending!!).takeLast(100))
                        }
                        if (data.submissions.none { it == pending } || !BackgroundState.sameAccount(data, expected)) {
                            pending = null
                            return Result.success()
                        }
                        // Recheck cancellation and consent at the boundary where the request becomes in flight.
                        val beforeSend = store.read()
                        if (isStopped || !BackgroundState.sameAccount(beforeSend, expected) || !beforeSend.submissionSettings.automatic) {
                            store.update { latest ->
                                if (BackgroundState.sameAccount(latest, expected)) latest.copy(submissions = latest.submissions.filterNot { it == pending }) else latest
                            }
                            pending = null
                            return Result.success()
                        }
                        val outcome = client.submitReading(current, decision.value)
                        val status = outcome.status
                        text = when (status) {
                            "confirmed" -> "검침값 ${decision.value} m³ 자동 제출을 완료했어요."
                            "rejected" -> ReminderPolicy.DEADLINE
                            else -> ReminderPolicy.UNCERTAIN
                        }
                        store.update { BackgroundState.finish(it, expected, pending!!.copy(status = status, detail = text!!)) }
                        if (status == "confirmed") applicationContext.getSystemService(NotificationManager::class.java).cancel(3)
                    }
                }
            } catch (e: Exception) {
                Diagnostics.record(applicationContext, expected.profile.providerId, "submit", e)
                // A rejected password stops background login. An in-flight send still resolves below.
                if (BackgroundState.rejectedCredentials(e)) runCatching {
                    val held = store.update { BackgroundState.holdConnection(it, expected) }
                    SubmissionScheduler.schedule(applicationContext, held)
                    ProviderRefresh.schedule(applicationContext, held)
                }
                if (pending != null) {
                    store.update { BackgroundState.finish(it, expected, pending!!.copy(status = "uncertain", detail = ReminderPolicy.UNCERTAIN)) }
                    text = ReminderPolicy.UNCERTAIN
                } else if (target?.end == today().toString()) {
                    text = ReminderPolicy.submissionText(data, target, System.currentTimeMillis(), failed = true)
                }
            }
            if (BackgroundState.sameAccount(store.read(), expected)) text?.let { notify(applicationContext, 2, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", it, AppTabs.SUBMISSION) }
            return Result.success()
        } finally { SubmissionGate.lock.unlock() }
    }

}

class SubmissionReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!SubmissionGate.lock.tryLock()) return Result.retry()
        try {
            val store = SecureStore(applicationContext)
            var data = store.read()
            if (!data.submissionSettings.reminder || data.profile.reconnectRequired) return Result.success()
            if (data.gasappConnection != null) return GasappBackground.remind(applicationContext)
            if (data.energyTalkConnection != null || Providers.get(data.profile.providerId).samchully || Providers.get(data.profile.providerId).direct) {
                val expected = data
                data = if (data.energyTalkConnection != null) EnergyTalkBridge.checkStatus(applicationContext)
                    else if (Providers.get(data.profile.providerId).direct) DirectProviderBridge.checkStatus(applicationContext)
                    else SamchullyBridge.checkStatus(applicationContext)
                if (!BackgroundState.sameAccount(data, expected) || !data.submissionSettings.reminder) return Result.success()
                val text = ReminderPolicy.submissionText(data, data.cachedSelfRead, System.currentTimeMillis())
                if (text != null) notify(applicationContext, 3, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", text, AppTabs.SUBMISSION)
                else applicationContext.getSystemService(NotificationManager::class.java).cancel(3)
                return Result.success()
            }
            val expected = data
            val credentials = data.credentials ?: return Result.success()
            val provider = Providers.skens(data.profile.providerId)
            // Always query the supplier before reminding, including submissions made outside this app.
            val target = SkensClient(provider, credentials).use { client ->
                val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract } ?: return Result.success()
                client.selfReadTarget(contract)
            }
            data = store.update { latest -> if (BackgroundState.sameAccount(latest, expected)) latest.copy(cachedSelfRead = target) else latest }
            if (!BackgroundState.sameAccount(data, expected) || !data.submissionSettings.reminder || data.cachedSelfRead != target) return Result.success()
            val text = ReminderPolicy.submissionText(data, target, System.currentTimeMillis())
            if (text != null) notify(applicationContext, 3, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", text, AppTabs.SUBMISSION)
            else applicationContext.getSystemService(NotificationManager::class.java).cancel(3)
            return Result.success()
        } catch (e: Exception) {
            val store = runCatching { SecureStore(applicationContext) }.getOrNull()
            val current = store?.let { runCatching { it.read() }.getOrNull() }
            Diagnostics.record(applicationContext, current?.profile?.providerId ?: "unknown", "background", e)
            // Only transient failures are worth another attempt inside this period.
            if (!BackgroundState.rejectedCredentials(e) || store == null || current == null) return Result.retry()
            runCatching {
                val held = store.update { BackgroundState.holdConnection(it, current) }
                SubmissionScheduler.schedule(applicationContext, held)
                ProviderRefresh.schedule(applicationContext, held)
            }
            return Result.failure()
        }
        finally { SubmissionGate.lock.unlock() }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val data = try { SecureStore(applicationContext).read() } catch (_: Exception) { return Result.failure() }
        if (ReminderPolicy.calibrationDue(data, System.currentTimeMillis())) notify(applicationContext, 1, Reminders.CHANNEL,
            "보정 알림", "똑똑, 계량기를 확인할 시간이에요", "계량기를 보고 보정해 주세요. 실제 숫자를 입력하면 추정이 더 정확해져요.")
        return Result.success()
    }
}
