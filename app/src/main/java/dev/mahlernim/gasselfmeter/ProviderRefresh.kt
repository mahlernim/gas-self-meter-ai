package dev.mahlernim.gasselfmeter

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** Read-only provider synchronization. This path never calls submitReading. */
object ProviderRefresh {
    private const val WORK = "provider-daily-refresh"
    fun schedule(context: Context, data: AppData) {
        val manager = WorkManager.getInstance(context)
        if (!data.ready || (data.energyTalkConnection == null && data.gasappConnection == null && (data.credentials == null || !Providers.get(data.profile.providerId).passwordConnection))) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val work = PeriodicWorkRequestBuilder<ProviderRefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun refresh(context: Context, force: Boolean = false): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val data = store.read()
        if (Providers.get(data.profile.providerId).direct) return@withLock DirectProviderBridge.refresh(context, force)
        if (data.energyTalkConnection != null) return@withLock EnergyTalkBridge.refresh(context, force)
        if (data.gasappConnection != null) return@withLock GasappBridge.refresh(context, force).also { updated ->
            if (updated.cachedGasappTarget?.submitted == true) context.getSystemService(android.app.NotificationManager::class.java).cancel(3)
        }
        val credentials = data.credentials ?: return@withLock data
        if (data.ready && Providers.get(data.profile.providerId).samchully) {
            if (!force && data.profile.syncTime?.let { System.currentTimeMillis() - it < 86_400_000L } == true) return@withLock data
            val login = SamchullyBridge.login(credentials)
            val contract = login.contracts.singleOrNull { it.key == data.profile.contract }
                ?: throw ProviderFailure("contracts", "provider_mismatch")
            val snapshot = SamchullyBridge.snapshot(login, contract)
            return@withLock store.update { latest ->
                if (BackgroundState.sameAccount(latest, data)) SamchullyBridge.merge(latest, snapshot, credentials) else latest
            }
        }
        if (!data.ready || !Providers.get(data.profile.providerId).skens) return@withLock data
        if (!force && data.profile.syncTime?.let { System.currentTimeMillis() - it < 86_400_000L } == true) return@withLock data
        val provider = Providers.skens(data.profile.providerId)
        SkensClient(provider, credentials).use { client ->
            val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract }
                ?: error("저장된 계약을 찾지 못했어요. 공급사를 다시 연결해 주세요.")
            val result = client.history(contract, data.periods.map { it.billMonth }.filter { it.isNotBlank() }.toSet()) {}
            store.update { latest ->
                if (!BackgroundState.sameAccount(latest, data)) latest
                else {
                    val months = result.periods.map { it.billMonth }.toSet()
                    val keep = latest.periods.filter { old -> old.billMonth !in months && result.periods.none { it.first <= old.last && old.first <= it.last } }
                    val periods = (keep + result.periods).sortedBy { it.start }
                    Estimator.validatePeriods(periods)
                    latest.copy(profile = latest.profile.copy(meter = result.meter, customerNumber = contract.ca,
                        plannedDate = result.planned, syncTime = System.currentTimeMillis()), periods = periods,
                        cachedSelfRead = result.selfRead)
                }
            }.also { updated ->
                if (updated.cachedSelfRead?.submitted == true) context.getSystemService(android.app.NotificationManager::class.java).cancel(3)
            }
        }
    }
}

class ProviderRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        ProviderRefresh.refresh(applicationContext)
        Result.success()
    } catch (e: Exception) {
        val provider = runCatching { SecureStore(applicationContext).read().profile.providerId }.getOrDefault("unknown")
        Diagnostics.record(applicationContext, provider, "background", e)
        Result.retry()
    }
}
