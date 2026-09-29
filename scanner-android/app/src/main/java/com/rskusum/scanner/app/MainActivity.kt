package com.rskusum.scanner.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rskusum.scanner.ScannerFlow
import com.rskusum.scanner.ScannerOptions
import com.rskusum.scanner.ui.ScannerTheme

/**
 * The RS Kusum Scanner app: the scanner library in standalone mode (home screen with the PDF
 * library, QR scanner, saving to Downloads). See the README for embedding the library in your
 * own app with the ScanDocument contract instead.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ScannerTheme {
                ScannerFlow(options = ScannerOptions(standalone = true))
            }
        }
    }
}
