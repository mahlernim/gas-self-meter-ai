package dev.mahlernim.gasselfmeter.diagnostic

import android.content.Context

/** Once written, this intentionally has no in-app reset path. */
internal class AttemptMarker(context: Context) {
    private val prefs = context.getSharedPreferences("busan_diagnostic_attempt", Context.MODE_PRIVATE)
    fun exists(): Boolean = synchronized(lock) { prefs.getBoolean("dispatched", false) }
    fun markBeforeDispatch(value: Int): Boolean = synchronized(lock) {
        if (prefs.getBoolean("dispatched", false)) false else prefs.edit()
            .putBoolean("dispatched", true)
            .putInt("request_value", value)
            .commit()
    }

    private companion object { val lock = Any() }
}
