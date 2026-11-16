package app.hushmessenger.xposed.core

import android.content.SharedPreferences

/**
 * A point-in-time snapshot of the user's switches.
 *
 * The hook process reads this once at package load. RemotePreferences delivers
 * changes to registered listeners, but a hook installed for a running process
 * must not change shape underneath a call, so changes are picked up on the next
 * Messenger restart. This is deliberate.
 */
class FeatureState(private val prefs: SharedPreferences?) {

    fun isOn(key: String): Boolean = prefs?.getBoolean(key, false) ?: false

    val anyEnabled: Boolean get() = Features.all.any { isOn(it.key) }

    fun enabledKeys(): Set<String> = Features.all.filter { isOn(it.key) }.map { it.key }.toSet()
}
