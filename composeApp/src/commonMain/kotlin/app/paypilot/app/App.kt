package app.paypilot.app

import app.paypilot.app.data.AppState
import app.paypilot.app.data.Screen
import app.paypilot.app.platform.PlatformServices
import app.paypilot.app.ui.CaptureScreen
import app.paypilot.app.ui.DocumentsScreen
import app.paypilot.app.ui.InvoiceCaseScreen
import app.paypilot.app.ui.InvoiceListScreen
import app.paypilot.app.ui.LockedScreen
import app.paypilot.app.ui.PayPilotTheme
import app.paypilot.app.ui.SecondFactorScreen
import app.paypilot.app.ui.SecurityScreen
import app.paypilot.app.ui.SetPinScreen
import app.paypilot.app.ui.SignInScreen
import app.paypilot.app.ui.TodayScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

enum class Tab(val label: String, val glyph: String) {
    TODAY("Aujourd'hui", "◎"), INVOICES("Factures", "▤"), CAPTURE("Ajouter", "+"), DOCUMENTS("Documents", "◈"), ACCOUNT("Compte", "◐")
}

@Composable
fun PayPilotApp(platform: PlatformServices) {
    val state = remember { AppState(platform) }
    PayPilotTheme {
        Surface(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); state.markActive() } }
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            LaunchedEffect(Unit) { while (true) { delay(5_000); state.checkAutoLock() } }
            Box(Modifier.safeDrawingPadding()) {
                when (state.screen) {
                    Screen.SIGN_IN -> SignInScreen(state)
                    Screen.SECOND_FACTOR -> SecondFactorScreen(state)
                    Screen.SET_PIN -> SetPinScreen(state)
                    Screen.LOCKED -> LockedScreen(state)
                    Screen.MAIN -> MainShell(state)
                }
            }
        }
    }
}

@Composable
private fun MainShell(state: AppState) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    var openInvoice by remember { mutableStateOf<String?>(null) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && openInvoice == null,
                        onClick = { tab = t; openInvoice = null },
                        icon = { Text(t.glyph) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val id = openInvoice
            if (id != null) {
                InvoiceCaseScreen(state, id) { openInvoice = null }
            } else when (tab) {
                Tab.TODAY -> TodayScreen(state, openInvoice = { openInvoice = it }, openDocuments = { tab = Tab.DOCUMENTS })
                Tab.INVOICES -> InvoiceListScreen(state) { openInvoice = it }
                Tab.CAPTURE -> CaptureScreen(state) { newId -> tab = Tab.INVOICES; openInvoice = newId }
                Tab.DOCUMENTS -> DocumentsScreen(state)
                Tab.ACCOUNT -> SecurityScreen(state)
            }
        }
    }
}
