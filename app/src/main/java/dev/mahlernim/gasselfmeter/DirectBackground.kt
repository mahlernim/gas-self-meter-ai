package dev.mahlernim.gasselfmeter

import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker

/** Called under SubmissionGate; this adapter never retries a mutation. */
internal object DirectBackground {
    fun automatic(context: Context, worker: ListenableWorker): ListenableWorker.Result {
        val store = SecureStore(context)
        val initial = store.read()
        val provider = Providers.get(initial.profile.providerId)
        if (!BackgroundState.connected(initial) || !provider.direct || !provider.automaticSubmission ||
            !initial.submissionSettings.automatic || worker.isStopped) return ListenableWorker.Result.success()
        var data = initial
        var text: String? = null
        try {
            data = DirectProviderBridge.checkStatus(context)
            if (worker.isStopped || !BackgroundState.sameAccount(data, initial) || !data.submissionSettings.automatic || data.profile.reconnectRequired)
                return ListenableWorker.Result.success()
            val target = data.cachedSelfRead ?: return ListenableWorker.Result.success()
            if (target.submitted) {
                context.getSystemService(NotificationManager::class.java).cancel(3)
                return ListenableWorker.Result.success()
            }
            if (target.end != today().toString()) return ListenableWorker.Result.success()
            val decision = DirectSubmissionPolicy.decide(data, target, automatic = true)
            if (decision.allowed) {
                data = DirectProviderBridge.submit(context, automatic = true, cancelled = { worker.isStopped })
                val record = data.submissions.lastOrNull { it.cycle == target.cycle }
                text = when (record?.status) {
                    "confirmed" -> "검침값 ${record.value} m³ 자동 제출을 완료했어요."
                    "pending", "uncertain" -> ReminderPolicy.UNCERTAIN
                    else -> ReminderPolicy.submissionText(data, data.cachedSelfRead, System.currentTimeMillis(), failed = true)
                }
                if (record?.status == "confirmed") context.getSystemService(NotificationManager::class.java).cancel(3)
            } else text = ReminderPolicy.submissionText(data, target, System.currentTimeMillis(), failed = true)
        } catch (e: Exception) {
            Diagnostics.record(context, initial.profile.providerId, "submit", e)
            if (BackgroundState.rejectedCredentials(e)) {
                data = store.update { BackgroundState.holdConnection(it, initial) }
                ProviderRefresh.schedule(context, data)
                SubmissionScheduler.schedule(context, data)
            } else data = store.read()
            if (data.cachedSelfRead?.end == today().toString())
                text = ReminderPolicy.submissionText(data, data.cachedSelfRead, System.currentTimeMillis(), failed = true)
        }
        if (!worker.isStopped && BackgroundState.sameAccount(store.read(), initial)) text?.let {
            notify(context, 2, SubmissionScheduler.CHANNEL, "자가검침 제출 안내", "똑똑 자가검침 AI", it, AppTabs.SUBMISSION)
        }
        return ListenableWorker.Result.success()
    }
}
