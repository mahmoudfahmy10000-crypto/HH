package com.bluewave.remote

/** One user-requested launch may wait for discovery; it is never replayed across TV sessions. */
class PendingAppLaunch {
    private var pending: Pair<String, TvApp>? = null

    @Synchronized
    fun begin(ip: String, app: TvApp): Boolean {
        if (pending != null) return false
        pending = ip to app
        return true
    }

    @Synchronized
    fun consume(ip: String): TvApp? {
        val value = pending
        pending = null
        return value?.takeIf { it.first == ip }?.second
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
