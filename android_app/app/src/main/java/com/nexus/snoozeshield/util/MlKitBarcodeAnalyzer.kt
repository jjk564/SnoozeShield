package com.nexus.snoozeshield.util

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * MlKitBarcodeAnalyzer â€” CameraX ImageAnalysis.Analyzer powered by Google ML Kit Barcode Scanning.
 *
 * Inspects real-time camera frames for UPC-A, UPC-E, EAN-13, EAN-8, Code-128, and QR Codes.
 * When a scanned barcode matches the registered target barcode (or any allowed wake barcode),
 * invokes `onBarcodeDetected(rawValue, formatName)` to disarm the alarm.
 */
class MlKitBarcodeAnalyzer(
    private val targetBarcode: String?,
    private val onBarcodeDetected: (rawValue: String) -> Unit
) : ImageAnalysis.Analyzer {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_QR_CODE
        )
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    @Volatile
    private var isDisarmed = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isDisarmed) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue?.trim() ?: continue
                    if (targetBarcode.isNullOrBlank() || raw == targetBarcode) {
                        isDisarmed = true
                        onBarcodeDetected(raw)
                        break
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
