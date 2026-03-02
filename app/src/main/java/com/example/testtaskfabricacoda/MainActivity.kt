package com.example.testtaskfabricacoda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.testtaskfabricacoda.ui.theme.TestTaskFabricaCodaTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestTaskFabricaCodaTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        state = uiState,
                        onCidChange = viewModel::onCidChange,
                        onPingIntervalChange = viewModel::onPingIntervalChange,
                        onConnectClick = viewModel::connect,
                        onFetchClick = viewModel::fetchByCid,
                        onPingToggleClick = viewModel::togglePing,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: MainUiState,
    onCidChange: (String) -> Unit,
    onPingIntervalChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onFetchClick: () -> Unit,
    onPingToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "IPFS Test Task", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = state.nodeAddress,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Node multiaddress") },
            readOnly = true,
            maxLines = 3,
        )
        OutlinedTextField(
            value = state.cid,
            onValueChange = onCidChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("CID") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.pingIntervalSeconds,
            onValueChange = onPingIntervalChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ping interval (sec, 1..3)") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnectClick, enabled = !state.isConnecting) {
                if (state.isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Connect")
                }
            }
            Button(onClick = onFetchClick, enabled = !state.isFetching) {
                if (state.isFetching) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Fetch by CID")
                }
            }
            Button(onClick = onPingToggleClick) {
                Text(if (state.isPinging) "Stop Ping" else "Start Ping")
            }
        }

        HorizontalDivider()
        Text(text = "Connection: ${state.connectionStatus}")
        Text(text = "Latency: ${state.latencyMs?.let { "$it ms" } ?: "N/A"}")
        state.errorMessage?.let {
            Text(
                text = "Error: $it",
                color = MaterialTheme.colorScheme.error,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.fetchResult.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "CID response:")
            Text(text = state.fetchResult)
        }
        if (state.logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Logs", style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text("${state.logs.size}") })
            }
            Text(text = state.logs.joinToString(separator = "\n"))
        }
    }
}
