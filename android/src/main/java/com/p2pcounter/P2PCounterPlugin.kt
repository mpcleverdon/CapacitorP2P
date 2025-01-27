package tools.bink.p2pcounter

import android.content.Intent
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color

@CapacitorPlugin(name = "P2PCounter")
class P2PCounterPlugin : Plugin() {
    // ... existing code

    @PluginMethod
    fun shareConnectionInfo(call: PluginCall) {
        val connectionData = JSONObject().apply {
            put("deviceId", getDeviceId())
            put("timestamp", System.currentTimeMillis())
            put("webrtcConfig", webrtcConfig.toString())
        }

        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "p2pcounter://${Base64.encodeToString(connectionData.toString().toByteArray(), Base64.DEFAULT)}")
        }

        context.startActivity(Intent.createChooser(intent, "Share Connection Info"))
        call.resolve()
    }

    @PluginMethod
    fun receiveConnectionInfo(call: PluginCall) {
        val sharedData = call.getString("sharedData") ?: run {
            call.reject("No shared data provided")
            return
        }

        try {
            val data = JSONObject(String(Base64.decode(sharedData.replace("p2pcounter://", ""), Base64.DEFAULT)))
            createPeerConnection(data.getString("deviceId"), true)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Invalid connection data", e)
        }
    }

    @PluginMethod
    fun generateConnectionQR(call: PluginCall) {
        val connectionData = JSONObject().apply {
            put("deviceId", getDeviceId())
            put("timestamp", System.currentTimeMillis())
            put("webrtcConfig", webrtcConfig.toString())
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(connectionData.toString(), BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)

        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        // Convert bitmap to base64
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val base64QR = Base64.encodeToString(output.toByteArray(), Base64.DEFAULT)

        call.resolve(JSObject().apply {
            put("qrData", "data:image/png;base64,$base64QR")
        })
    }

    @PluginMethod
    fun scanConnectionQR(call: PluginCall) {
        // Launch QR scanner activity
        val intent = Intent(context, QRScannerActivity::class.java)
        startActivityForResult(call, intent, "scanQRResult")
    }

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)

        // Handle QR scan result
        val savedCall = bridge.getSavedCall("scanQRResult") ?: return
        if (requestCode == "scanQRResult" && resultCode == Activity.RESULT_OK) {
            val scannedData = data?.getStringExtra("SCAN_RESULT")
            try {
                val connectionData = JSONObject(scannedData)
                createPeerConnection(connectionData.getString("deviceId"), true)
                savedCall.resolve()
            } catch (e: Exception) {
                savedCall.reject("Invalid QR code data", e)
            }
        }
    }
} 