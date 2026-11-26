package dev.naijun.dununlocker.proxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TetheringProxyUiState(
    val isRunning: Boolean = false,
    val port: Int = TetheringProxyState.DEFAULT_PORT,
    val endpoints: List<ProxyEndpoint> = emptyList(),
    val activeConnections: Int = 0,
    val errorMessage: String? = null
)

object TetheringProxyState {
    const val DEFAULT_PORT = 18889
    private const val PREFS_NAME = "tethering_proxy_prefs"
    private const val KEY_PORT = "proxy_port"

    private val _state = MutableStateFlow(TetheringProxyUiState())
    val state: StateFlow<TetheringProxyUiState> = _state.asStateFlow()

    fun loadSavedPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val valid = if (saved in 1024..65535) saved else DEFAULT_PORT
        if (!_state.value.isRunning) {
            _state.value = _state.value.copy(port = valid)
        }
        return valid
    }

    fun savePort(context: Context, port: Int) {
        if (port !in 1024..65535) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PORT, port)
            .apply()
        if (!_state.value.isRunning) {
            _state.value = _state.value.copy(port = port)
        }
    }

    fun refreshEndpoints() {
        _state.value = _state.value.copy(endpoints = NetworkAddressResolver.detectEndpoints())
    }

    internal fun setRunning(port: Int) {
        _state.value = _state.value.copy(
            isRunning = true,
            port = port,
            endpoints = NetworkAddressResolver.detectEndpoints(),
            errorMessage = null
        )
    }

    internal fun setStopped(errorMessage: String? = null) {
        _state.value = _state.value.copy(
            isRunning = false,
            activeConnections = 0,
            errorMessage = errorMessage
        )
    }

    internal fun setActiveConnections(count: Int) {
        _state.value = _state.value.copy(activeConnections = count)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
