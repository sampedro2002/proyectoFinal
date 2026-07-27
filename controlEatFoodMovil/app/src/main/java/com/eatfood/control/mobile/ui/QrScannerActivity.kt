package com.eatfood.control.mobile.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eatfood.control.mobile.ui.theme.EatFoodTheme
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Escáner de QR de la app (CameraX + ML Kit empaquetado en el APK).
 *
 * Sustituye al escáner de Play Services (play-services-code-scanner), que no trae cámara
 * propia: delega la UI en Google Play Services y en el módulo descargable "barcode_ui", que
 * falta o no se puede descargar en muchos equipos (Redmi/POCO con MIUI, Infinix/Tecno, ROMs
 * sin GMS completo). Esta pantalla no depende de Play Services, así que se comporta igual en
 * todos los dispositivos con cámara.
 *
 * Devuelve el texto del QR en [EXTRA_RESULT] con RESULT_OK, o RESULT_CANCELED si el usuario
 * sale o la cámara no se puede abrir. Requiere el permiso CAMERA ya concedido por quien lanza.
 */
class QrScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "qr_result"
        private const val TAG = "QrScannerActivity"
    }

    private lateinit var analysisExecutor: ExecutorService
    private var scanner: BarcodeScanner? = null

    /** Evita devolver dos veces el resultado: el analizador entrega varios frames seguidos. */
    private val delivered = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        setContent { EatFoodTheme { ScannerUi() } }
    }

    @Composable
    private fun ScannerUi() {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        startCamera(view)
                    }
                }
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Apunta al QR del servidor",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { finish() }) { Text("Cancelar") }
            }
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo obtener el proveedor de cámara", e)
                failAndFinish()
                return@addListener
            }

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // Equipos sin cámara trasera, o con la cámara ocupada por otra app.
                Log.e(TAG, "No se pudo abrir la cámara", e)
                failAndFinish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val client = scanner
        if (media == null || client == null || delivered.get()) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        client.process(image)
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.ifBlank { null } }
                if (value != null) deliver(value)
            }
            .addOnFailureListener { e -> Log.w(TAG, "Fallo al decodificar el frame", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun deliver(value: String) {
        if (!delivered.compareAndSet(false, true)) return
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
        finish()
    }

    private fun failAndFinish() {
        Toast.makeText(this, "No se pudo abrir la cámara de este dispositivo.", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        scanner?.close()
        scanner = null
    }
}
