package com.example.testtaskfabricacoda

import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MainUiState(
    val nodeAddress: String = TaskConfig.nodeMultiAddress,
    val cid: String = TaskConfig.testCid,
    val pingIntervalSeconds: String = "2",
    val connectionStatus: String = "Disconnected",
    val latencyMs: Long? = null,
    val fetchResult: String = "",
    val errorMessage: String? = null,
    val isConnecting: Boolean = false,
    val isFetching: Boolean = false,
    val isPinging: Boolean = false,
    val logs: List<String> = emptyList(),
)

class MainViewModel(
    private val nodeClient: NodeClient = NodeClient(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var pingJob: Job? = null
    private var lastConnectClickMs: Long = 0L

    fun onCidChange(value: String) {
        _uiState.update { it.copy(cid = value, errorMessage = null) }
    }

    fun onPingIntervalChange(value: String) {
        if (value.all { it.isDigit() } || value.isBlank()) {
            _uiState.update { it.copy(pingIntervalSeconds = value, errorMessage = null) }
        }
    }

    fun connect() {
        if (_uiState.value.isConnecting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectClickMs < CONNECT_DEBOUNCE_MS) {
            addLog("Connect throttled")
            return
        }
        lastConnectClickMs = now

        viewModelScope.launch {
            addLog("Connect requested")
            _uiState.update { it.copy(isConnecting = true, errorMessage = null, connectionStatus = "Connecting...") }
            runCatching { nodeClient.connect(_uiState.value.nodeAddress) }
                .onSuccess { latency ->
                    addLog("Connected, latency=${latency}ms")
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectionStatus = "Connected",
                            latencyMs = latency,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        addLog("Connect cancelled")
                        return@onFailure
                    }
                    Log.e(TAG, "Connect failed", error)
                    addLog("Connect failed: ${error.toUserMessage()}")
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectionStatus = "Disconnected",
                            errorMessage = error.toUserMessage(),
                        )
                    }
                }
        }
    }

    fun fetchByCid() {
        if (_uiState.value.isFetching) return

        viewModelScope.launch {
            addLog("Fetch requested, cid=${_uiState.value.cid.trim()}")
            _uiState.update { it.copy(isFetching = true, errorMessage = null) }
            runCatching { nodeClient.fetchByCid(_uiState.value.nodeAddress, _uiState.value.cid.trim()) }
                .onSuccess { response ->
                    addLog("Fetch success, response=${response.length} chars")
                    _uiState.update { it.copy(isFetching = false, fetchResult = response, errorMessage = null) }
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        addLog("Fetch cancelled")
                        return@onFailure
                    }
                    Log.e(TAG, "Fetch failed", error)
                    addLog("Fetch failed: ${error.toUserMessage()}")
                    _uiState.update { it.copy(isFetching = false, errorMessage = error.toUserMessage()) }
                }
        }
    }

    fun togglePing() {
        if (pingJob?.isActive == true) {
            stopPing()
        } else {
            startPing()
        }
    }

    private fun startPing() {
        val interval = parseIntervalOrDefault(_uiState.value.pingIntervalSeconds)
        addLog("Ping started, interval=${interval}s")
        pingJob = viewModelScope.launch {
            _uiState.update { it.copy(isPinging = true, errorMessage = null) }
            while (isActive) {
                runCatching { nodeClient.ping(_uiState.value.nodeAddress) }
                    .onSuccess { latency ->
                        addLog("Ping ok: ${latency}ms")
                        _uiState.update {
                            it.copy(
                                connectionStatus = "Connected",
                                latencyMs = latency,
                                errorMessage = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        Log.e(TAG, "Ping failed", error)
                        addLog("Ping failed: ${error.toUserMessage()}")
                        _uiState.update {
                            it.copy(
                                connectionStatus = "Disconnected",
                                errorMessage = error.toUserMessage(),
                            )
                        }
                    }
                delay(interval * 1_000L)
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
        addLog("Ping stopped")
        _uiState.update { it.copy(isPinging = false) }
    }

    override fun onCleared() {
        stopPing()
        super.onCleared()
    }

    private fun parseIntervalOrDefault(raw: String): Int {
        val parsed = raw.toIntOrNull() ?: 2
        return parsed.coerceIn(1, 3)
    }

    private fun Throwable.toUserMessage(): String {
        val root = rootCause()
        val msg = (root.message ?: message).orEmpty()

        if (root is IllegalArgumentException) {
            if (msg.contains("CID", ignoreCase = true)) {
                return "Invalid CID format."
            }
            if (msg.contains("multiaddress", ignoreCase = true) || msg.contains("peer id", ignoreCase = true)) {
                return "Invalid node multiaddress."
            }
            return "Invalid input."
        }
        if (root is UnknownHostException) {
            return "Cannot resolve node host."
        }
        if (root is SocketTimeoutException) {
            return "Network timeout while contacting node."
        }
        if (root is ConnectException) {
            return "Cannot connect to node port."
        }
        if (msg.contains("ScheduledThreadPoolExecutor", ignoreCase = true) ||
            msg.contains("Connection is closed", ignoreCase = true) ||
            msg.contains("rejected", ignoreCase = true)
        ) {
            return "Connection restarted due to rapid reconnect. Retry once."
        }
        return msg.ifBlank { "Network error. Please try again." }
    }

    private fun Throwable.rootCause(): Throwable {
        var current: Throwable = this
        while (current is ExecutionException && current.cause != null) {
            current = current.cause!!
        }
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun addLog(message: String) {
        val line = "${System.currentTimeMillis()} | $message"
        Log.d(TAG, line)
        _uiState.update { state ->
            val updated = (state.logs + line).takeLast(MAX_LOG_LINES)
            state.copy(logs = updated)
        }
    }

    companion object {
        private const val TAG = "TestTaskVM"
        private const val MAX_LOG_LINES = 120
        private const val CONNECT_DEBOUNCE_MS = 800L
    }
}
