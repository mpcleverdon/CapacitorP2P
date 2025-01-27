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
import android.util.Log;

@CapacitorPlugin(name = "P2PCounter")
public class P2PCounterPlugin extends Plugin {
    private PeerConnectionFactory peerConnectionFactory;
    private EglBase eglBase;
    private PeerConnectionManager peerConnectionManager;
    private NFCManager nfcManager;
    
    @Override
    public void load() {
        try {
            Log.d("P2PCounterPlugin", "Starting plugin load");
            
            if (getContext() == null) {
                Log.e("P2PCounterPlugin", "Context is null");
                return;
            }
            
            // Initialize WebRTC
            try {
                eglBase = EglBase.create();
                Log.d("P2PCounterPlugin", "EglBase created");
            } catch (Exception e) {
                Log.e("P2PCounterPlugin", "Error creating EglBase", e);
                return;
            }
            
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(getContext())
                        .createInitializationOptions()
                );
                Log.d("P2PCounterPlugin", "PeerConnectionFactory initialized");
            } catch (Exception e) {
                Log.e("P2PCounterPlugin", "Error initializing PeerConnectionFactory", e);
                return;
            }
            
            try {
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(null)
                    .setVideoDecoderFactory(null)
                    .createPeerConnectionFactory();
                Log.d("P2PCounterPlugin", "PeerConnectionFactory created");
            } catch (Exception e) {
                Log.e("P2PCounterPlugin", "Error creating PeerConnectionFactory", e);
                return;
            }

            try {
                String deviceId = String.valueOf(System.currentTimeMillis());
                peerConnectionManager = new PeerConnectionManager(
                    peerConnectionFactory, 
                    this,
                    getContext(),
                    deviceId
                );
                Log.d("P2PCounterPlugin", "PeerConnectionManager created");
            } catch (Exception e) {
                Log.e("P2PCounterPlugin", "Error creating PeerConnectionManager", e);
                return;
            }
            
            try {
                Activity activity = getActivity();
                if (activity == null) {
                    Log.e("P2PCounterPlugin", "Activity is null");
                    return;
                }
                nfcManager = new NFCManager(this, activity);
                Log.d("P2PCounterPlugin", "Plugin load complete");
            } catch (Exception e) {
                Log.e("P2PCounterPlugin", "Error creating NFCManager", e);
            }
        } catch (Exception e) {
            Log.e("P2PCounterPlugin", "Error loading plugin", e);
        }
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
        
        peerConnectionManager.setRemoteDescription(deviceId, sdp, type);
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
        
        peerConnectionManager.addIceCandidate(deviceId, sdp, sdpMLineIndex, sdpMid);
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
        
        peerConnectionManager.sendMessage(deviceId, data);
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

    @PluginMethod
    public void startKeepalive(PluginCall call) {
        peerConnectionManager.startKeepalive();
        call.resolve();
    }

    @PluginMethod
    public void stopKeepalive(PluginCall call) {
        peerConnectionManager.stopKeepalive();
        call.resolve();
    }

    @PluginMethod
    public void getNetworkStats(PluginCall call) {
        try {
            JSObject stats = peerConnectionManager.getNetworkStats();
            call.resolve(stats);
        } catch (Exception e) {
            Log.e("P2PCounterPlugin", "Error getting network stats", e);
            call.reject("Failed to get network stats", e);
        }
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

    public void setRemoteDescription(String deviceId, String sdp, String type) {
        // Implementation
    }

    public void addIceCandidate(String deviceId, String sdp, int sdpMLineIndex, String sdpMid) {
        // Implementation
    }

    public void sendMessage(String deviceId, String data) {
        // Implementation
    }
} 