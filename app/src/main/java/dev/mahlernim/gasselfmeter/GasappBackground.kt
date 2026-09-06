package dev.mahlernim.gasselfmeter

import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Entry points are called while SubmissionGate is held, including account reconciliation. */
internal object GasappBackground {
    fun reminderText(data: AppData, target: GasappTarget?, time: Long, failed: Boolean = false): String? {
        target ?: return null
        if (!target.registered || !target.eligible || target.submitted || target.start == null || target.end == null) return null
        val date = dateOf(time)
        if (date !in LocalDate.parse(target.start)..LocalDate.parse(target.end)) return null
        val record = data.submissions.lastOrNull { it.cycle == target.cycle }
        if (record?.status == "confirmed") return null
        if (record?.status in setOf("pending", "uncertain")) return ReminderPolicy.UNCERTAIN
        val days = ChronoUnit.DAYS.between(date, LocalDate.parse(target.end))
        if (days == 0L && (failed || !GasappSubmissionPolicy.decide(data, target, time, automatic = true).allowed)) return ReminderPolicy.DEADLINE
        return "자가검침 입력 기간이에요. 마감까지 ${days}일 남았어요."
    }

    fun automatic(context: Context, worker: ListenableWorker): ListenableWorker.Result {
        val store = SecureStore(context)
        val initial = store.read()
        if (!initial.ready || initial.gasappConnection == null || !initial.submissionSettings.automatic ||
            !Providers.get(initial.profile.providerId).automaticSubmission || worker.isStopped) return ListenableWorker.Result.success()
        var data = initial
        var text: String? = null
        try {
            data = GasappBridge.check(context)
            if (!BackgroundState.sameAccount(data, initial) || !data.submissionSettings.automatic || worker.isStopped) return ListenableWorker.Result.success()
            val target = data.cachedGasappTarget ?: return ListenableWorker.Result.success()
            if (target.submitted) { context.getSystemService(NotificationManager::class.java).cancel(3); return ListenableWorker.Result.success() }
            if (target.end != today().toString()) return ListenableWorker.Result.success()
            val decision = GasappSubmissionPolicy.decide(data, target, automatic = true)
            if (decision.allowed) {
                data = GasappBridge.submit(context, automatic = true, cancelled = { worker.isStopped })
                val result = data.submissions.lastOrNull { it.cycle == target.cycle }
                text = when (result?.status) {
                    "confirmed" -> "검침값 ${result.value} m³ 자동 제출을 완료했어요."
                    "uncertain", "pending" -> ReminderPolicy.UNCERTAIN
                    else -> reminderText(data, data.cachedGasappTarget, System.currentTimeMillis(), failed = true)
                }
                if (result?.status == "confirmed") context.getSystemService(NotificationManager::class.java).cancel(3)
            } else text = reminderText(data, target, System.currentTimeMillis(), failed = true)
        } catch (e: Exception) {
            Diagnostics.record(context, data.profile.providerId, "submit", e)
            data = store.read()
            if (data.cachedGasappTarget?.end == today().toString()) text = reminderText(data, data.cachedGasappTarget, System.currentTimeMillis(), failed = true)
        }
        if (!worker.isStopped && BackgroundState.sameAccount(store.read(), initial)) text?.let {
            notify(context, 2, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", it, AppTabs.SUBMISSION)
        }
        return ListenableWorker.Result.success()
    }

    fun remind(context: Context): ListenableWorker.Result {
        val initial = SecureStore(context).read()
        if (!initial.ready || !initial.submissionSettings.reminder || initial.gasappConnection == null) return ListenableWorker.Result.success()
        // Query each time so submissions outside this app stop reminders.
        val data = GasappBridge.check(context)
        if (!BackgroundState.sameAccount(data, initial) || !data.submissionSettings.reminder) return ListenableWorker.Result.success()
        val text = reminderText(data, data.cachedGasappTarget, System.currentTimeMillis())
        if (text != null) notify(context, 3, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", text, AppTabs.SUBMISSION)
        else context.getSystemService(NotificationManager::class.java).cancel(3)
        return ListenableWorker.Result.success()
    }
}
