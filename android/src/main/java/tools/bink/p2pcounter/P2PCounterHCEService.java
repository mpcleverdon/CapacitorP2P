package tools.bink.p2pcounter;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;
import org.json.JSONObject;
import java.util.Arrays;

public class P2PCounterHCEService extends HostApduService {
    private static final String TAG = "P2PCounterHCEService";
    private static final byte[] SELECT_APDU = {
        (byte)0x00, (byte)0xA4, (byte)0x04, (byte)0x00, (byte)0x07,
        (byte)0xF0, (byte)0x01, (byte)0x02, (byte)0x03, (byte)0x04, (byte)0x05, (byte)0x06
    };
    private static final byte[] SUCCESS_SW = {(byte)0x90, (byte)0x00};
    private static NFCCallback callback;

    public static void setCallback(NFCCallback cb) {
        callback = cb;
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (Arrays.equals(SELECT_APDU, commandApdu)) {
            return createDeviceInfoResponse();
        }
        
        String receivedMessage = new String(commandApdu);
        try {
            if (callback != null) {
                callback.onMessageReceived(receivedMessage);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing message", e);
        }
        
        return SUCCESS_SW;
    }

    private byte[] createDeviceInfoResponse() {
        try {
            JSONObject info = new JSONObject();
            info.put("deviceId", String.valueOf(System.currentTimeMillis()));
            info.put("timestamp", System.currentTimeMillis());
            
            byte[] infoBytes = info.toString().getBytes();
            byte[] response = new byte[infoBytes.length + 2];
            System.arraycopy(infoBytes, 0, response, 0, infoBytes.length);
            System.arraycopy(SUCCESS_SW, 0, response, infoBytes.length, 2);
            
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error creating response", e);
            return SUCCESS_SW;
        }
    }

    @Override
    public void onDeactivated(int reason) {
        Log.d(TAG, "Deactivated: " + reason);
    }

    public interface NFCCallback {
        void onMessageReceived(String message);
    }
} 