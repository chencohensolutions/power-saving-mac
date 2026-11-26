package com.chencohensolution.batterytime

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.batterytime.ui.BatteryTimeTheme
import com.chencohensolution.batterytime.ui.DashboardScreen
import com.chencohensolution.batterytime.ui.PairScreen

class MainActivity : ComponentActivity() {
    private val vm: CompanionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            BatteryTimeTheme {
                val controller by vm.controller.collectAsStateWithLifecycle()
                val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
                LaunchedEffect(keepScreenOn, controller) {
                    if (keepScreenOn && controller != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val c = controller
                if (c == null) {
                    PairScreen(vm)
                } else {
                    DashboardScreen(
                        controller = c,
                        client = vm.client,
                        keepScreenOn = keepScreenOn,
                        onKeepScreenOn = vm::setKeepScreenOn,
                        onUnpair = vm::unpair,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** batterytime://pair?… from the Mac's QR code, scanned with the camera app */
    private fun handleLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        Pairing.fromLink(link)?.let(vm::pair)
    }
}
