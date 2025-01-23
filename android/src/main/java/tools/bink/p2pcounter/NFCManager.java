package tools.bink.p2pcounter;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Log;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import java.io.IOException;

public class NFCManager implements P2PCounterHCEService.NFCCallback {
    private static final String TAG = "NFCManager";
    private final NfcAdapter nfcAdapter;
    private final P2PCounterPlugin plugin;
    private final Activity activity;
    private String deviceId;

    public NFCManager(P2PCounterPlugin plugin, Activity activity) {
        this.plugin = plugin;
        this.activity = activity;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        this.deviceId = String.valueOf(System.currentTimeMillis());
        P2PCounterHCEService.setCallback(this);
    }

    public boolean startDiscovery() {
        if (nfcAdapter == null) {
            Log.e(TAG, "NFC is not available on this device");
            return false;
        }

        if (!nfcAdapter.isEnabled()) {
            Log.e(TAG, "NFC is not enabled");
            return false;
        }

        return true;
    }

    public void stopDiscovery() {
        // No need to stop anything for HCE
    }

    @Override
    public void onMessageReceived(String message) {
        try {
            JSONObject data = new JSONObject(message);
            String peerDeviceId = data.getString("deviceId");
            long timestamp = data.getLong("timestamp");

            JSObject result = new JSObject();
            result.put("deviceId", peerDeviceId);
            result.put("timestamp", timestamp);
            plugin.notifyWebRTCEvent("nfcDiscovered", result);
        } catch (Exception e) {
            Log.e(TAG, "Error handling NFC message", e);
        }
    }

    public void handleNdefMessage(NdefMessage message) {
        try {
            NdefRecord record = message.getRecords()[0];
            String payload = new String(record.getPayload());
            onMessageReceived(payload);
        } catch (Exception e) {
            Log.e(TAG, "Error handling NDEF message", e);
        }
    }
} 