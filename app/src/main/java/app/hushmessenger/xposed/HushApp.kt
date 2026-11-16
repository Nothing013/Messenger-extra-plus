package app.hushmessenger.xposed

import android.app.Application
import app.hushmessenger.xposed.core.ServiceHolder
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Receives the framework's service binder and forwards it to Compose.
 *
 * `io.github.libxposed:service` merges the exported `XposedProvider` into our
 * manifest; the provider forwards the binder that [XposedServiceHelper] hands to
 * this listener. Only in-memory state is touched here.
 */
class HushApp : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                ServiceHolder.bind(service)
            }

            override fun onServiceDied(service: XposedService) {
                ServiceHolder.unbind(service)
            }
        })
    }
}
