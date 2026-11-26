package com.chencohensolution.batterytime.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.batterytime.CompanionViewModel
import com.chencohensolution.batterytime.FoundMac
import com.chencohensolution.batterytime.PairStatus
import com.chencohensolution.batterytime.Pairing
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** First screen: pair with BatteryTime on the Mac by scanning its QR code, or by its address and code */
@Composable
fun PairScreen(vm: CompanionViewModel) {
    val status by vm.pairStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var macName by remember { mutableStateOf("Mac") }
    var scanError by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scanError = null
        vm.clearPairError()
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context as Activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val p = barcode.rawValue?.let(Pairing::fromLink)
                if (p != null) vm.pair(p) else scanError = "That isn't BatteryTime's code. Open Phone Companion on the Mac."
            }
            .addOnFailureListener { scanError = "The scanner didn't open. Type the address and code instead." }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.Laptop, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("BatteryTime", style = MaterialTheme.typography.headlineMedium)
            Text(
                "See and control your Mac's battery, Stay On and Battery Saver while the lid is closed.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("On your Mac", style = MaterialTheme.typography.titleSmall)
                    Text("1. Click BatteryTime in the menu bar → Phone Companion…")
                    Text("2. Turn on Allow phone control")
                    Text("3. Scan the code it shows")
                }
            }

            when (val s = status) {
                is PairStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Connecting to ${s.pairing.host}…")
                }
                is PairStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                PairStatus.Idle -> scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }

            Button(onClick = ::scan, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = status !is PairStatus.Checking) {
                Icon(Icons.Outlined.QrCodeScanner, null)
                Spacer(Modifier.size(8.dp))
                Text("Scan the Mac's code")
            }

            if (!typing) {
                TextButton(onClick = { typing = true }) { Text("Type the address and code instead") }
            } else {
                HorizontalDivider()
                NearbyMacs(vm) { mac ->
                    address = if (mac.port == Pairing.DEFAULT_PORT) mac.host else "${mac.host}:${mac.port}"
                    macName = mac.name
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; vm.clearPairError() },
                    label = { Text("Mac's address") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(9); vm.clearPairError() },
                    label = { Text("Code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                val typed = Pairing.fromTyped(address, code, macName)
                OutlinedButton(
                    onClick = { typed?.let(vm::pair) },
                    enabled = typed != null && status !is PairStatus.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }
    }
}

/** BatteryTime Macs on this Wi-Fi, found over Bonjour: tapping one fills in its address */
@Composable
private fun NearbyMacs(vm: CompanionViewModel, onPick: (FoundMac) -> Unit) {
    val macs by remember { vm.finder.macs() }.collectAsState(initial = emptyList())
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(macs) {
        // Only one Mac around: fill it in
        if (picked == null && macs.size == 1) { picked = macs[0].name; onPick(macs[0]) }
    }
    if (macs.isEmpty()) {
        Text(
            "Looking for Macs on this Wi-Fi…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (mac in macs) {
        ListItem(
            headlineContent = { Text(mac.name) },
            supportingContent = { Text(mac.host) },
            leadingContent = { Icon(Icons.Outlined.Laptop, null) },
            trailingContent = { if (picked == mac.name) Text("Selected", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.fillMaxWidth().clickable { picked = mac.name; onPick(mac) },
        )
    }
}
