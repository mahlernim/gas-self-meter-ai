package dev.mahlernim.gasselfmeter

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
internal fun EnergyTalkProviderDialog(provider: Provider, onDismiss: () -> Unit,
    onConnected: (EnergyTalkConnection, EnergyTalkSnapshot) -> Unit) {
    var tenant by remember(provider.id) { mutableStateOf(provider.energyTalkTenants.singleOrNull()) }
    val selected = tenant
    if (selected != null) {
        EnergyTalkConnectDialog(selected, onDismiss, onResult = {}, onConnected = onConnected)
    } else {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("서비스 지역을 선택해 주세요") },
            text = { Column { provider.energyTalkTenants.forEach { id ->
                TextButton(onClick = { tenant = id }) { Text(AlphaConnections.energyTenants.getValue(id)) }
            } } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
    }
}
