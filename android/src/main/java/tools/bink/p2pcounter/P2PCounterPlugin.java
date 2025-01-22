package tools.bink.p2pcounter;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Parcelable;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSObject;
import org.webrtc.*;

@CapacitorPlugin(name = "P2PCounter")
public class P2PCounterPlugin extends Plugin {
    private PeerConnectionFactory peerConnectionFactory;
    private EglBase eglBase;
    private PeerConnectionManager peerConnectionManager;
    private NFCManager nfcManager;
    
    @Override
    public void load() {
        // Initialize WebRTC
        eglBase = EglBase.create();
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(getContext())
                .createInitializationOptions()
        );
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(null)
            .setVideoDecoderFactory(null)
            .createPeerConnectionFactory();

        peerConnectionManager = new PeerConnectionManager(peerConnectionFactory, this);
        nfcManager = new NFCManager(this, getActivity());
    }
    
    @PluginMethod
    public void createPeerConnection(PluginCall call) {
        String deviceId = call.getString("deviceId");
        boolean isInitiator = call.getBoolean("isInitiator", false);
        
        if (deviceId == null) {
            call.reject("Device ID is required");
            return;
        }
        
        peerConnectionManager.createPeerConnection(deviceId, isInitiator);
        call.resolve();
    }

    @PluginMethod
    public void handleRemoteSessionDescription(PluginCall call) {
        String deviceId = call.getString("deviceId");
        String sdp = call.getString("sdp");
        String type = call.getString("type");
        
        if (deviceId == null || sdp == null || type == null) {
            call.reject("Device ID, SDP, and type are required");
            return;
        }
        
        peerConnectionManager.handleRemoteSessionDescription(deviceId, sdp, type);
        call.resolve();
    }

    @PluginMethod
    public void handleRemoteIceCandidate(PluginCall call) {
        String deviceId = call.getString("deviceId");
        String sdp = call.getString("sdp");
        int sdpMLineIndex = call.getInt("sdpMLineIndex", 0);
        String sdpMid = call.getString("sdpMid");
        
        if (deviceId == null || sdp == null || sdpMid == null) {
            call.reject("Invalid ICE candidate data");
            return;
        }
        
        peerConnectionManager.handleRemoteIceCandidate(deviceId, sdp, sdpMLineIndex, sdpMid);
        call.resolve();
    }

    @PluginMethod
    public void sendData(PluginCall call) {
        String deviceId = call.getString("deviceId");
        String data = call.getString("data");
        
        if (deviceId == null || data == null) {
            call.reject("Device ID and data are required");
            return;
        }
        
        peerConnectionManager.sendData(deviceId, data);
        call.resolve();
    }

    @PluginMethod
    public void startNFCDiscovery(PluginCall call) {
        if (nfcManager.startDiscovery()) {
            call.resolve();
        } else {
            call.reject("Failed to start NFC discovery");
        }
    }

    @PluginMethod
    public void stopNFCDiscovery(PluginCall call) {
        nfcManager.stopDiscovery();
        call.resolve();
    }

    public void notifyWebRTCEvent(String eventName, JSObject data) {
        notifyListeners(eventName, data);
    }
    
    @Override
    protected void handleOnNewIntent(Intent intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            Parcelable[] rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (rawMessages != null) {
                NdefMessage message = (NdefMessage) rawMessages[0];
                nfcManager.handleNdefMessage(message);
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        nfcManager.stopDiscovery();
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        super.handleOnDestroy();
    }
} 