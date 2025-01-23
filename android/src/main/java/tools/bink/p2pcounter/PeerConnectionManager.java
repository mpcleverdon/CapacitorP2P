package tools.bink.p2pcounter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.JSObject;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import tools.bink.p2pcounter.MessagePriorityManager.Priority;

public class PeerConnectionManager implements PeerConnection.Observer {
    private static final String TAG = "PeerConnectionManager";
    private final PeerConnectionFactory factory;
    private final Map<String, PeerConnection> peerConnections;
    private final Map<String, DataChannel> dataChannels;
    private final P2PCounterPlugin plugin;
    private final Map<String, Long> lastPingTimes;
    private final Map<String, List<Long>> rttHistory;
    private final Map<String, Integer> packetLossCount;
    private static final long KEEPALIVE_INTERVAL = 5000; // 5 seconds base interval
    private static final long MAX_KEEPALIVE_INTERVAL = 30000; // 30 seconds max
    private static final long PEER_TIMEOUT = 45000; // 45 seconds timeout
    private Handler keepaliveHandler;
    private boolean isKeepaliveRunning;
    private final MeshTopologyManager topologyManager;
    private final String localDeviceId;
    private final MessageDeduplicator deduplicator;
    private final MeshDiscoveryManager discoveryManager;
    private final MessagePriorityManager priorityManager;
    private Timer messageProcessingTimer;
    private static final long PROCESSING_INTERVAL = 50; // 50ms
    private final MessageProcessor messageProcessor;
    private final String deviceId;

    public PeerConnectionManager(PeerConnectionFactory factory, P2PCounterPlugin plugin, Context context, String deviceId) {
        this.factory = factory;
        this.plugin = plugin;
        this.peerConnections = new HashMap<>();
        this.dataChannels = new HashMap<>();
        this.lastPingTimes = new HashMap<>();
        this.rttHistory = new HashMap<>();
        this.packetLossCount = new HashMap<>();
        this.keepaliveHandler = new Handler(Looper.getMainLooper());
        this.localDeviceId = String.valueOf(System.currentTimeMillis());
        this.topologyManager = new MeshTopologyManager(localDeviceId, plugin);
        this.deduplicator = new MessageDeduplicator();
        this.discoveryManager = new MeshDiscoveryManager(localDeviceId, plugin);
        this.priorityManager = new MessagePriorityManager();
        this.messageProcessor = new MessageProcessor(localDeviceId);
        this.deviceId = deviceId;
        startMessageProcessing();
    }

    public void createPeerConnection(String deviceId, boolean isInitiator) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        PeerConnection peerConnection = factory.createPeerConnection(config, this);
        if (peerConnection == null) {
            Log.e(TAG, "Failed to create peer connection");
            return;
        }

        peerConnections.put(deviceId, peerConnection);

        if (isInitiator) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            init.maxRetransmits = 0; // Reliable messaging
            DataChannel dataChannel = peerConnection.createDataChannel("mesh", init);
            dataChannels.put(deviceId, dataChannel);
        }

        // Notify about new peer
        JSObject peerEvent = new JSObject();
        peerEvent.put("deviceId", deviceId);
        peerEvent.put("isInitiator", isInitiator);
        plugin.notifyWebRTCEvent("peerConnected", peerEvent);

        // After successful connection
        topologyManager.addPeer(deviceId, new ArrayList<>(peerConnections.keySet()));
    }

    // Handle mesh network message broadcasting
    public void broadcastToMesh(String message, String sourceDeviceId) {
        if (!deduplicator.isNewMessage(message, sourceDeviceId)) {
            return;
        }

        try {
            JSONObject messageObj = new JSONObject(message);
            String type = messageObj.optString("type");
            Priority priority = getPriorityFromString(messageObj.optString("_priority", "MEDIUM"));
            
            // Add message metadata
            messageObj.put("_messageId", generateMessageId());
            messageObj.put("_timestamp", System.currentTimeMillis());
            messageObj.put("_sourceId", sourceDeviceId);
            messageObj.put("_priority", priority.toString());
            
            // Process message (compress and fragment if needed)
            List<JSONObject> chunks = messageProcessor.processOutgoingMessage(
                messageObj.toString()
            );

            Set<String> targetPeers = new HashSet<>(topologyManager.getDirectPeers());
            targetPeers.remove(sourceDeviceId);

            // Queue each chunk
            for (JSONObject chunk : chunks) {
                priorityManager.queueMessage(
                    chunk.toString(),
                    priority,
                    targetPeers
                );
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error preparing message", e);
        }
    }

    private Priority getPriorityFromString(String priorityStr) {
        try {
            return Priority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Default to MEDIUM if unknown priority
            return Priority.MEDIUM;
        }
    }

    private void sendToDevice(String deviceId, String message) {
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            channel.send(new DataChannel.Buffer(buffer, false));
        }
    }

    // Network health monitoring
    public JSObject getNetworkStats() {
        JSObject stats = new JSObject();
        double avgLatency = 0;
        double avgPacketLoss = 0;
        int peerCount = peerConnections.size();

        if (peerCount > 0) {
            for (String deviceId : peerConnections.keySet()) {
                List<Long> rtts = rttHistory.getOrDefault(deviceId, new ArrayList<>());
                int losses = packetLossCount.getOrDefault(deviceId, 0);

                if (!rtts.isEmpty()) {
                    double peerLatency = rtts.stream().mapToLong(Long::longValue).average().getAsDouble();
                    avgLatency += peerLatency;
                    avgPacketLoss += (double) losses / rtts.size();
                }
            }
            avgLatency /= peerCount;
            avgPacketLoss /= peerCount;
        }

        stats.put("averageLatency", avgLatency);
        stats.put("packetLoss", avgPacketLoss);
        stats.put("peerCount", peerCount);
        return stats;
    }

    // Add keepalive methods
    public void startKeepalive() {
        if (isKeepaliveRunning) return;
        isKeepaliveRunning = true;
        scheduleKeepalive();
    }

    public void stopKeepalive() {
        isKeepaliveRunning = false;
        keepaliveHandler.removeCallbacksAndMessages(null);
    }

    private void scheduleKeepalive() {
        if (!isKeepaliveRunning) return;

        keepaliveHandler.postDelayed(() -> {
            sendKeepaliveToAllPeers();
            checkPeerTimeouts();
            scheduleKeepalive();
        }, KEEPALIVE_INTERVAL);
    }

    private void sendKeepaliveToAllPeers() {
        long now = System.currentTimeMillis();
        JSONObject ping = new JSONObject();
        try {
            ping.put("type", "ping");
            ping.put("timestamp", now);
            String message = ping.toString();

            for (Map.Entry<String, DataChannel> entry : dataChannels.entrySet()) {
                String deviceId = entry.getKey();
                DataChannel channel = entry.getValue();

                // Only send if we haven't received a ping recently
                Long lastPing = lastPingTimes.get(deviceId);
                if (lastPing == null || now - lastPing >= KEEPALIVE_INTERVAL) {
                    if (channel.state() == DataChannel.State.OPEN) {
                        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
                        channel.send(new DataChannel.Buffer(buffer, false));
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating keepalive message", e);
        }
    }

    private void checkPeerTimeouts() {
        long now = System.currentTimeMillis();
        List<String> timedOutPeers = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastPingTimes.entrySet()) {
            if (now - entry.getValue() > PEER_TIMEOUT) {
                timedOutPeers.add(entry.getKey());
            }
        }

        // Handle disconnected peers
        for (String deviceId : timedOutPeers) {
            handlePeerTimeout(deviceId);
        }
    }

    private void handlePeerTimeout(String deviceId) {
        // Clean up peer connection
        PeerConnection connection = peerConnections.get(deviceId);
        if (connection != null) {
            connection.close();
            peerConnections.remove(deviceId);
        }

        // Clean up data channel
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null) {
            channel.close();
            dataChannels.remove(deviceId);
        }

        // Clean up metrics
        lastPingTimes.remove(deviceId);
        rttHistory.remove(deviceId);
        packetLossCount.remove(deviceId);

        // Notify about peer timeout
        JSObject timeoutEvent = new JSObject();
        timeoutEvent.put("deviceId", deviceId);
        timeoutEvent.put("reason", "timeout");
        plugin.notifyWebRTCEvent("peerTimeout", timeoutEvent);

        topologyManager.handlePeerDisconnection(deviceId);
    }

    // Update DataChannelObserver to handle keepalive messages
    private class DataChannelObserver implements DataChannel.Observer {
        private final String deviceId;

        DataChannelObserver(String deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            String message = new String(data, StandardCharsets.UTF_8);

            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type");

                if ("messageChunk".equals(type)) {
                    String assembledMessage = messageProcessor.processIncomingChunk(json);
                    if (assembledMessage != null) {
                        // Process complete message
                        handleAssembledMessage(assembledMessage);
                    }
                    return;
                }

                String sourceId = json.optString("_sourceId", deviceId);
                int hopCount = json.optInt("_hopCount", 0);

                // Check if this is a duplicate message
                if (!deduplicator.isNewMessage(message, sourceId)) {
                    return;
                }

                // Increment hop count
                json.put("_hopCount", hopCount + 1);

                if ("ping".equals(type)) {
                    handlePing(deviceId, json);
                } else if ("pong".equals(type)) {
                    handlePong(deviceId, json);
                } else if ("meshAnnouncement".equals(type)) {
                    discoveryManager.handleAnnouncement(deviceId, json);
                    return;
                } else if ("messageAck".equals(type)) {
                    String messageId = json.getString("messageId");
                    priorityManager.handleAck(messageId, deviceId);
                    return;
                } else {
                    // Handle regular mesh messages
                    JSObject messageEvent = new JSObject();
                    messageEvent.put("deviceId", deviceId);
                    messageEvent.put("data", json.toString());
                    plugin.notifyWebRTCEvent("meshMessage", messageEvent);

                    // Relay message if within hop limit
                    if (hopCount < topologyManager.getMaxHops()) {
                        broadcastToMesh(json.toString(), sourceId);
                    }
                }

                // Send acknowledgment for received message
                if (json.has("_messageId")) {
                    sendAck(json.getString("_messageId"), deviceId);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing message", e);
            }
        }

        @Override
        public void onStateChange() {
            DataChannel channel = dataChannels.get(deviceId);
            if (channel != null) {
                JSObject stateEvent = new JSObject();
                stateEvent.put("deviceId", deviceId);
                stateEvent.put("state", channel.state().toString());
                plugin.notifyWebRTCEvent("dataChannelStateChange", stateEvent);
            }
        }

        @Override
        public void onBufferedAmountChange(long amount) {
            // Implementation required by newer API
        }
    }

    private void handlePing(String deviceId, JSONObject ping) {
        try {
            long timestamp = ping.getLong("timestamp");
            lastPingTimes.put(deviceId, System.currentTimeMillis());

            // Send pong response
            JSONObject pong = new JSONObject();
            pong.put("type", "pong");
            pong.put("originalTimestamp", timestamp);
            pong.put("timestamp", System.currentTimeMillis());

            DataChannel channel = dataChannels.get(deviceId);
            if (channel != null && channel.state() == DataChannel.State.OPEN) {
                ByteBuffer buffer = ByteBuffer.wrap(pong.toString().getBytes(StandardCharsets.UTF_8));
                channel.send(new DataChannel.Buffer(buffer, false));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error handling ping", e);
        }
    }

    private void handlePong(String deviceId, JSONObject pong) {
        try {
            long originalTimestamp = pong.getLong("originalTimestamp");
            long rtt = System.currentTimeMillis() - originalTimestamp;

            // Update RTT history
            List<Long> history = rttHistory.getOrDefault(deviceId, new ArrayList<>());
            history.add(rtt);
            if (history.size() > 10) { // Keep last 10 measurements
                history.remove(0);
            }
            rttHistory.put(deviceId, history);

            // Update last ping time
            lastPingTimes.put(deviceId, System.currentTimeMillis());
        } catch (JSONException e) {
            Log.e(TAG, "Error handling pong", e);
        }
    }

    private void sendAck(String messageId, String targetPeerId) {
        try {
            JSONObject ack = new JSONObject();
            ack.put("type", "messageAck");
            ack.put("messageId", messageId);
            ack.put("timestamp", System.currentTimeMillis());
            
            sendToDevice(targetPeerId, ack.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating ack message", e);
        }
    }

    private void handleAssembledMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            String sourceId = json.optString("_sourceId", deviceId);

            // Process the assembled message as before
            if ("ping".equals(type)) {
                handlePing(deviceId, json);
            } else if ("pong".equals(type)) {
                handlePong(deviceId, json);
            } else {
                // ... rest of message handling ...
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error handling assembled message", e);
        }
    }

    // PeerConnection.Observer methods
    @Override
    public void onIceCandidate(IceCandidate iceCandidate) {
        JSObject candidate = new JSObject();
        candidate.put("sdp", iceCandidate.sdp);
        candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        candidate.put("sdpMid", iceCandidate.sdpMid);
        plugin.notifyWebRTCEvent("iceCandidate", candidate);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
        // Instead use DataChannelObserver class
    }

    // Other required Observer methods with empty implementations
    @Override
    public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
    @Override
    public void onIceConnectionReceivingChange(boolean b) {}
    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
    @Override
    public void onAddStream(MediaStream mediaStream) {}
    @Override
    public void onRemoveStream(MediaStream mediaStream) {}
    @Override
    public void onRenegotiationNeeded() {}
    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}

    // Add missing Observer methods
    @Override
    public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        // Handle removed ICE candidates if needed
    }

    @Override
    public void onTrack(RtpTransceiver transceiver) {
        // Handle track events if needed
    }

    // Fix createAnswer method
    public void createAnswer(PeerConnection peerConnection) {
        MediaConstraints constraints = new MediaConstraints();
        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sd) {}
                    @Override
                    public void onSetSuccess() {}
                    @Override
                    public void onCreateFailure(String s) {}
                    @Override
                    public void onSetFailure(String s) {}
                }, sessionDescription);
            }
            @Override
            public void onSetSuccess() {}
            @Override
            public void onCreateFailure(String s) {}
            @Override
            public void onSetFailure(String s) {}
        }, constraints);
    }

    // Add new method to handle connection requests
    public void handleConnectionRequest(String sourceId) {
        if (topologyManager.shouldAcceptConnection(sourceId)) {
            createPeerConnection(sourceId, false);
        }
    }

    private String generateMessageId() {
        return localDeviceId + "_" + System.currentTimeMillis() + "_" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }

    public void startDiscovery() {
        discoveryManager.startDiscovery();
    }

    public void stopDiscovery() {
        discoveryManager.stopDiscovery();
    }

    private void startMessageProcessing() {
        messageProcessingTimer = new Timer(true);
        messageProcessingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                processNextMessage();
            }
        }, 0, PROCESSING_INTERVAL);
    }

    private void processNextMessage() {
        MessagePriorityManager.PrioritizedMessage message = priorityManager.getNextMessage();
        if (message != null) {
            for (String peerId : message.targetPeers) {
                sendToDevice(peerId, message.message);
            }
        }
    }

    protected void handleOnDestroy() {
        deduplicator.stop();
        discoveryManager.stopDiscovery();
        priorityManager.stop();
        if (messageProcessingTimer != null) {
            messageProcessingTimer.cancel();
            messageProcessingTimer = null;
        }
        messageProcessor.cleanup();
    }

    public void setRemoteDescription(String deviceId, String sdp, String type) {
        // Implementation
        PeerConnection peerConnection = peerConnections.get(deviceId);
        if (peerConnection != null) {
            SessionDescription sessionDescription = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sdp
            );
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription sd) {}
                @Override public void onSetSuccess() {}
                @Override public void onCreateFailure(String s) {}
                @Override public void onSetFailure(String s) {}
            }, sessionDescription);
        }
    }

    public void addIceCandidate(String deviceId, String sdp, int sdpMLineIndex, String sdpMid) {
        PeerConnection peerConnection = peerConnections.get(deviceId);
        if (peerConnection != null) {
            IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
            peerConnection.addIceCandidate(candidate);
        }
    }

    public void sendMessage(String deviceId, String data) {
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
            channel.send(new DataChannel.Buffer(buffer, false));
        }
    }
} 