package com.example.plugin;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.nfc.NfcAdapter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.PeerConnection;
import org.webrtc.MediaConstraints;
import org.webrtc.DataChannel;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "P2PCounter")
public class P2PCounterPlugin extends Plugin {
    private NfcAdapter nfcAdapter;
    private PeerConnectionFactory factory;
    private static final long MIN_KEEPALIVE_INTERVAL = 5000; // 5 seconds
    private static final long MAX_KEEPALIVE_INTERVAL = 30000; // 30 seconds
    private static final double LATENCY_THRESHOLD = 1000; // 1 second
    private static final double PACKET_LOSS_THRESHOLD = 0.1; // 10%
    private Map<String, Long> keepaliveIntervals;
    private Map<String, List<Long>> rttHistory;
    private Map<String, Integer> packetLossCount;
    private Map<String, PeerConnection> peerConnections;
    private Map<String, DataChannel> dataChannels;
    private Map<String, Boolean> processedCodes;
    private Map<String, Long> lastPingTimes;
    private Handler keepaliveHandler;
    private static final long KEEPALIVE_INTERVAL = 5000; // 5 seconds
    private static final long PEER_TIMEOUT = 15000; // 15 seconds
    
    @Override
    public void load() {
        super.load();
        nfcAdapter = NfcAdapter.getDefaultAdapter(getContext());
        keepaliveIntervals = new HashMap<>();
        rttHistory = new HashMap<>();
        packetLossCount = new HashMap<>();
        peerConnections = new HashMap<>();
        dataChannels = new HashMap<>();
        processedCodes = new HashMap<>();
        lastPingTimes = new HashMap<>();
        keepaliveHandler = new Handler(Looper.getMainLooper());
    }

    @PluginMethod
    public void startKeepalive(PluginCall call) {
        keepaliveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendKeepaliveToAllPeers();
                checkPeerTimeouts();
                keepaliveHandler.postDelayed(this, KEEPALIVE_INTERVAL);
            }
        }, KEEPALIVE_INTERVAL);
        call.resolve();
    }

    @PluginMethod
    public void stopKeepalive(PluginCall call) {
        keepaliveHandler.removeCallbacksAndMessages(null);
        call.resolve();
    }

    private void sendKeepaliveToAllPeers() {
        JSONObject ping = new JSONObject();
        ping.put("type", "ping");
        long timestamp = System.currentTimeMillis();
        ping.put("timestamp", timestamp);

        for (Map.Entry<String, DataChannel> entry : dataChannels.entrySet()) {
            String deviceId = entry.getKey();
            DataChannel channel = entry.getValue();
            
            // Get adaptive interval for this peer
            long interval = keepaliveIntervals.getOrDefault(deviceId, MIN_KEEPALIVE_INTERVAL);
            
            // Only send if it's time for this peer
            if (timestamp - lastPingTimes.getOrDefault(deviceId, 0L) < interval) {
                continue;
            }
            
            DataChannel.Buffer buffer = new DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(
                    ping.toString().getBytes(StandardCharsets.UTF_8)
                ),
                false
            );
            channel.send(buffer);
            lastPingTimes.put(deviceId, timestamp);
        }
    }

    private void updatePeerMetrics(String deviceId, long rtt) {
        // Update RTT history
        List<Long> history = rttHistory.getOrDefault(deviceId, new ArrayList<>());
        history.add(rtt);
        if (history.size() > 10) { // Keep last 10 measurements
            history.remove(0);
        }
        rttHistory.put(deviceId, history);
        
        // Calculate average RTT
        double avgRtt = history.stream().mapToLong(Long::valueOf).average().orElse(0);
        
        // Get packet loss rate
        int lossCount = packetLossCount.getOrDefault(deviceId, 0);
        double lossRate = history.isEmpty() ? 0 : lossCount / (double)history.size();
        
        // Adjust keepalive interval
        long currentInterval = keepaliveIntervals.getOrDefault(deviceId, MIN_KEEPALIVE_INTERVAL);
        long newInterval = currentInterval;
        
        if (avgRtt > LATENCY_THRESHOLD || lossRate > PACKET_LOSS_THRESHOLD) {
            newInterval = Math.min(currentInterval * 2, MAX_KEEPALIVE_INTERVAL);
        } else if (avgRtt < LATENCY_THRESHOLD / 2 && lossRate < PACKET_LOSS_THRESHOLD / 2) {
            newInterval = Math.max(currentInterval / 2, MIN_KEEPALIVE_INTERVAL);
        }
        
        keepaliveIntervals.put(deviceId, newInterval);
    }

    private void checkPeerTimeouts() {
        long currentTime = System.currentTimeMillis();
        List<String> timedOutPeers = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastPingTimes.entrySet()) {
            if (currentTime - entry.getValue() > PEER_TIMEOUT) {
                String deviceId = entry.getKey();
                timedOutPeers.add(deviceId);
            }
        }

        for (String deviceId : timedOutPeers) {
            JSObject timeoutEvent = new JSObject();
            timeoutEvent.put("event", "peerTimeout");
            timeoutEvent.put("deviceId", deviceId);
            notifyListeners("peerTimeout", timeoutEvent);
            disconnectPeer(deviceId);
        }
    }

    private void handlePing(String deviceId, JSONObject data) {
        long timestamp = data.getLong("timestamp");
        lastPingTimes.put(deviceId, timestamp);
        
        // Send pong response
        JSONObject pong = new JSONObject();
        pong.put("type", "pong");
        pong.put("originalTimestamp", timestamp);
        pong.put("timestamp", System.currentTimeMillis());
        
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null) {
            DataChannel.Buffer buffer = new DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(
                    pong.toString().getBytes(StandardCharsets.UTF_8)
                ),
                false
            );
            channel.send(buffer);
        }
    }

    private void handlePong(String deviceId, JSONObject data) {
        long now = System.currentTimeMillis();
        long originalTimestamp = data.getLong("originalTimestamp");
        long rtt = now - originalTimestamp;
        
        lastPingTimes.put(deviceId, now);
        updatePeerMetrics(deviceId, rtt);
    }

    @PluginMethod
    public void startNFCDiscovery(PluginCall call) {
        if (nfcAdapter == null) {
            call.reject("NFC not available");
            return;
        }
        
        Activity activity = getActivity();
        nfcAdapter.enableForegroundDispatch(
            activity,
            getPendingIntent(),
            getIntentFilters(),
            null
        );
        
        call.resolve();
    }

    @PluginMethod
    public void stopNFCDiscovery(PluginCall call) {
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(getActivity());
        }
        call.resolve();
    }

    @PluginMethod
    public void sendNFCMessage(PluginCall call) {
        String message = call.getString("message");
        if (message == null) {
            call.reject("Message is required");
            return;
        }
        
        NdefRecord record = NdefRecord.createTextRecord(null, message);
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[] { record });
        
        // In real implementation, this would use NfcAdapter's setNdefPushMessage
        // However, this requires the device to support Android Beam
        call.resolve();
    }

    @PluginMethod
    public void initializeWebRTC(PluginCall call) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        );
        
        PeerConnection.RTCConfiguration config = 
            new PeerConnection.RTCConfiguration(iceServers);
        
        peerConnection = factory.createPeerConnection(
            config,
            new PeerConnection.Observer() {
                // Implement observer methods as needed
            }
        );
        
        call.resolve();
    }

    @PluginMethod
    public void createPeerConnection(PluginCall call) {
        String deviceId = call.getString("deviceId");
        Boolean isInitiator = call.getBoolean("isInitiator");
        if (deviceId == null || isInitiator == null) {
            call.reject("Device ID and isInitiator are required");
            return;
        }
        
        PeerConnection newConnection = factory.createPeerConnection(
            config,
            new PeerConnection.Observer() {
                @Override
                public void onDataChannel(DataChannel channel) {
                    dataChannels.put(deviceId, channel);
                    setupDataChannelEvents(channel, deviceId);
                }
            }
        );
        
        peerConnections.put(deviceId, newConnection);
        
        if (isInitiator) {
            DataChannel.Init init = new DataChannel.Init();
            DataChannel channel = newConnection.createDataChannel("counter", init);
            dataChannels.put(deviceId, channel);
            setupDataChannelEvents(channel, deviceId);
        }
        
        JSObject result = new JSObject();
        result.put("event", "peerConnected");
        result.put("deviceId", deviceId);
        notifyListeners("peerConnected", result);
        
        call.resolve();
    }

    @PluginMethod
    public void sendCounter(PluginCall call) {
        String code = call.getString("code");
        Boolean isPresent = call.getBoolean("isPresent");
        if (code == null || isPresent == null) {
            call.reject("Code and isPresent status are required");
            return;
        }
        
        // Update attendance status
        processedCodes.put(code, isPresent);
            
        JSONObject data = new JSONObject();
        data.put("code", code);
        data.put("timestamp", System.currentTimeMillis());
        data.put("isPresent", isPresent);
            
        // Broadcast to all connected peers
        for (DataChannel channel : dataChannels.values()) {
            DataChannel.Buffer buffer = new DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(
                    data.toString().getBytes(StandardCharsets.UTF_8)
                ),
                false
            );
            channel.send(buffer);
        }
        
        call.resolve();
    }
    
    @PluginMethod
    public void disconnectPeer(PluginCall call) {
        String deviceId = call.getString("deviceId");
        if (deviceId == null) {
            call.reject("Device ID is required");
            return;
        }
        
        disconnectPeer(deviceId);
        call.resolve();
    }
    
    private void disconnectPeer(String deviceId) {
        PeerConnection connection = peerConnections.get(deviceId);
        if (connection != null) {
            connection.close();
            peerConnections.remove(deviceId);
        }
        
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null) {
            channel.close();
            dataChannels.remove(deviceId);
        }
        
        lastPingTimes.remove(deviceId);
    }
    
    private void setupDataChannelEvents(DataChannel channel, String deviceId) {
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] bytes = new byte[buffer.data.remaining()];
                buffer.data.get(bytes);
                String message = new String(bytes, StandardCharsets.UTF_8);
                
                try {
                    JSONObject data = new JSONObject(message);
                    String type = data.optString("type");
                    
                    if ("ping".equals(type)) {
                        handlePing(deviceId, data);
                        return;
                    }
                    if ("pong".equals(type)) {
                        handlePong(deviceId, data);
                        return;
                    }
                    
                    String code = data.getString("code");
                    boolean isPresent = data.getBoolean("isPresent");
                    
                    // Update attendance status
                    processedCodes.put(code, isPresent);
                    
                    JSObject counterEvent = new JSObject();
                    counterEvent.put("event", "counterReceived");
                    counterEvent.put("code", code);
                    counterEvent.put("timestamp", data.getLong("timestamp"));
                    counterEvent.put("isPresent", isPresent);
                    notifyListeners("counterReceived", counterEvent);
                } catch (JSONException e) {
                    Log.e("P2PCounter", "Error parsing message", e);
                }
            }
        });
    }
    
    @Override
    protected void handleOnNewIntent(Intent intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            NdefMessage[] messages = getNdefMessages(intent);
            if (messages != null && messages.length > 0) {
                NdefRecord record = messages[0].getRecords()[0];
                String payload = new String(record.getPayload());
                
                JSObject result = new JSObject();
                result.put("event", "nfcDiscovered");
                result.put("message", payload);
                notifyListeners("nfcDiscovered", result);
            }
        }
    }

    @PluginMethod
    public void getNetworkStats(PluginCall call) {
        JSObject stats = new JSObject();
        double totalLatency = 0;
        double totalLoss = 0;
        int peerCount = peerConnections.size();
        
        if (peerCount > 0) {
            for (String deviceId : peerConnections.keySet()) {
                List<Long> history = rttHistory.getOrDefault(deviceId, new ArrayList<>());
                if (!history.isEmpty()) {
                    totalLatency += history.stream().mapToLong(Long::valueOf).average().orElse(0);
                }
                
                int lossCount = packetLossCount.getOrDefault(deviceId, 0);
                totalLoss += history.isEmpty() ? 0 : lossCount / (double)history.size();
            }
            
            stats.put("averageLatency", totalLatency / peerCount);
            stats.put("packetLoss", totalLoss / peerCount);
            stats.put("keepaliveInterval", keepaliveIntervals.values().stream()
                .mapToLong(Long::valueOf)
                .average()
                .orElse(MIN_KEEPALIVE_INTERVAL));
        } else {
            stats.put("averageLatency", 0);
            stats.put("packetLoss", 0);
            stats.put("keepaliveInterval", MIN_KEEPALIVE_INTERVAL);
        }
        
        call.resolve(stats);
    }
}