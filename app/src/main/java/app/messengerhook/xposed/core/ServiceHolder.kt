package app.messengerhook.xposed.core

import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the framework's service binder into Compose.
 *
 * XposedServiceHelper delivers the binder from the XposedProvider that the
 * libxposed-service library merges into our manifest. The holder keeps only the
 * bound [XposedService]; no storage is touched here.
 */
object ServiceHolder {

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    fun bind(service: XposedService) {
        _service.value = service
    }

    fun unbind(service: XposedService) {
        if (_service.value === service) _service.value = null
    }
}
