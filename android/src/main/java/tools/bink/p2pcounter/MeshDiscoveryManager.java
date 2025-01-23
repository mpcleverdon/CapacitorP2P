package tools.bink.p2pcounter;

import android.util.Log;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.*;

public class MeshDiscoveryManager {
    private static final String TAG = "MeshDiscoveryManager";
    private final String localDeviceId;
    private final P2PCounterPlugin plugin;
    private final Map<String, PeerInfo> discoveredPeers;
    private final Map<String, Long> lastAnnouncementTime;
    private Timer discoveryTimer;
    private static final long ANNOUNCEMENT_INTERVAL = 10000; // 10 seconds
    private static final long PEER_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_PEERS = 10;

    private static class PeerInfo {
        String deviceId;
        long lastSeen;
        int connectionCount;
        double networkStrength;
        Set<String> connectedPeers;

        PeerInfo(String deviceId) {
            this.deviceId = deviceId;
            this.lastSeen = System.currentTimeMillis();
            this.connectionCount = 0;
            this.networkStrength = 0.0;
            this.connectedPeers = new HashSet<>();
        }
    }

    public MeshDiscoveryManager(String localDeviceId, P2PCounterPlugin plugin) {
        this.localDeviceId = localDeviceId;
        this.plugin = plugin;
        this.discoveredPeers = new HashMap<>();
        this.lastAnnouncementTime = new HashMap<>();
    }

    public void startDiscovery() {
        if (discoveryTimer != null) return;

        discoveryTimer = new Timer(true);
        discoveryTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                announcePresence();
                cleanupStaleEntries();
            }
        }, 0, ANNOUNCEMENT_INTERVAL);
    }

    public void stopDiscovery() {
        if (discoveryTimer != null) {
            discoveryTimer.cancel();
            discoveryTimer = null;
        }
    }

    private void announcePresence() {
        try {
            JSONObject announcement = new JSONObject();
            announcement.put("type", "meshAnnouncement");
            announcement.put("deviceId", localDeviceId);
            announcement.put("timestamp", System.currentTimeMillis());
            announcement.put("connectionCount", discoveredPeers.size());
            announcement.put("networkStrength", calculateNetworkStrength());

            JSONArray connectedPeers = new JSONArray();
            for (PeerInfo peer : discoveredPeers.values()) {
                connectedPeers.put(peer.deviceId);
            }
            announcement.put("connectedPeers", connectedPeers);

            JSObject event = new JSObject();
            event.put("type", "meshAnnouncement");
            event.put("data", announcement.toString());
            plugin.notifyWebRTCEvent("meshDiscovery", event);

        } catch (JSONException e) {
            Log.e(TAG, "Error creating mesh announcement", e);
        }
    }

    public void handleAnnouncement(String deviceId, JSONObject announcement) {
        try {
            PeerInfo peer = discoveredPeers.computeIfAbsent(deviceId, PeerInfo::new);
            peer.lastSeen = System.currentTimeMillis();
            peer.connectionCount = announcement.getInt("connectionCount");
            peer.networkStrength = announcement.getDouble("networkStrength");
            
            // Update connected peers
            peer.connectedPeers.clear();
            JSONArray connectedPeers = announcement.getJSONArray("connectedPeers");
            for (int i = 0; i < connectedPeers.length(); i++) {
                peer.connectedPeers.add(connectedPeers.getString(i));
            }

            // Evaluate if we should connect to this peer
            evaluateConnection(peer);

        } catch (JSONException e) {
            Log.e(TAG, "Error handling mesh announcement", e);
        }
    }

    private void evaluateConnection(PeerInfo peer) {
        // Don't connect if we're at max peers
        if (discoveredPeers.size() >= MAX_PEERS) return;

        // Calculate connection score based on various factors
        double score = calculateConnectionScore(peer);

        // If score is above threshold, request connection
        if (score > 0.7) { // 70% threshold
            requestConnection(peer.deviceId);
        }
    }

    private double calculateConnectionScore(PeerInfo peer) {
        double score = 0.0;

        // Factor 1: Network strength (30%)
        score += peer.networkStrength * 0.3;

        // Factor 2: Connection count optimization (20%)
        // Prefer peers with fewer connections to balance the network
        score += (1.0 - (peer.connectionCount / (double)MAX_PEERS)) * 0.2;

        // Factor 3: Network diversity (30%)
        // Prefer peers that connect us to new parts of the network
        Set<String> uniquePeers = new HashSet<>(peer.connectedPeers);
        uniquePeers.removeAll(getKnownPeers());
        score += (uniquePeers.size() / (double)MAX_PEERS) * 0.3;

        // Factor 4: Connection stability (20%)
        // Prefer peers that have been consistently available
        long uptime = System.currentTimeMillis() - peer.lastSeen;
        score += Math.min(uptime / (double)PEER_TIMEOUT, 1.0) * 0.2;

        return score;
    }

    private Set<String> getKnownPeers() {
        Set<String> knownPeers = new HashSet<>();
        for (PeerInfo peer : discoveredPeers.values()) {
            knownPeers.add(peer.deviceId);
            knownPeers.addAll(peer.connectedPeers);
        }
        return knownPeers;
    }

    private double calculateNetworkStrength() {
        if (discoveredPeers.isEmpty()) return 0.0;

        double totalStrength = 0.0;
        for (PeerInfo peer : discoveredPeers.values()) {
            // Consider connection count and peer's network strength
            double peerStrength = (peer.connectionCount / (double)MAX_PEERS) * 0.5
                               + peer.networkStrength * 0.5;
            totalStrength += peerStrength;
        }

        return Math.min(totalStrength / MAX_PEERS, 1.0);
    }

    private void requestConnection(String peerId) {
        JSObject request = new JSObject();
        request.put("type", "connectionRequest");
        request.put("sourceId", localDeviceId);
        request.put("targetId", peerId);
        plugin.notifyWebRTCEvent("connectionRequest", request);
    }

    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        discoveredPeers.entrySet().removeIf(entry ->
            now - entry.getValue().lastSeen > PEER_TIMEOUT
        );
    }

    public JSONObject getDiscoverySnapshot() {
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("localDeviceId", localDeviceId);
            snapshot.put("networkStrength", calculateNetworkStrength());
            
            JSONArray peers = new JSONArray();
            for (PeerInfo peer : discoveredPeers.values()) {
                JSONObject peerInfo = new JSONObject();
                peerInfo.put("deviceId", peer.deviceId);
                peerInfo.put("connectionCount", peer.connectionCount);
                peerInfo.put("networkStrength", peer.networkStrength);
                peerInfo.put("lastSeen", peer.lastSeen);
                peers.put(peerInfo);
            }
            snapshot.put("discoveredPeers", peers);
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating discovery snapshot", e);
        }
        return snapshot;
    }
} 