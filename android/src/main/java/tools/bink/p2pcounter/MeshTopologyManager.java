package tools.bink.p2pcounter;

import android.util.Log;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.*;

public class MeshTopologyManager {
    private static final String TAG = "MeshTopologyManager";
    private final Map<String, Set<String>> peerConnections; // deviceId -> connected peers
    private final Map<String, Integer> hopCount; // deviceId -> hops from this device
    private final String localDeviceId;
    private final P2PCounterPlugin plugin;
    private static final int MAX_HOPS = 5; // Maximum hops for mesh propagation
    private static final int MIN_PEERS_PER_NODE = 2; // Minimum desired direct connections
    private static final int MAX_PEERS_PER_NODE = 5; // Maximum direct connections
    private final Map<String, Long> lastReorganizationTime;
    private static final long REORGANIZATION_COOLDOWN = 10000; // 10 seconds

    public MeshTopologyManager(String localDeviceId, P2PCounterPlugin plugin) {
        this.localDeviceId = localDeviceId;
        this.plugin = plugin;
        this.peerConnections = new HashMap<>();
        this.hopCount = new HashMap<>();
        hopCount.put(localDeviceId, 0);
        this.lastReorganizationTime = new HashMap<>();
    }

    public void addPeer(String deviceId, List<String> connectedPeers) {
        synchronized (peerConnections) {
            peerConnections.putIfAbsent(deviceId, new HashSet<>());
            peerConnections.get(deviceId).addAll(connectedPeers);
            updateHopCounts();
            notifyTopologyChange();
        }
    }

    public void removePeer(String deviceId) {
        synchronized (peerConnections) {
            peerConnections.remove(deviceId);
            // Remove this peer from others' connections
            for (Set<String> connections : peerConnections.values()) {
                connections.remove(deviceId);
            }
            hopCount.remove(deviceId);
            updateHopCounts();
            notifyTopologyChange();
        }
    }

    private void updateHopCounts() {
        // Reset hop counts
        hopCount.clear();
        hopCount.put(localDeviceId, 0);

        // BFS to calculate hop counts
        Queue<String> queue = new LinkedList<>();
        queue.add(localDeviceId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentHops = hopCount.get(current);

            if (currentHops >= MAX_HOPS) continue;

            Set<String> neighbors = peerConnections.getOrDefault(current, new HashSet<>());
            for (String neighbor : neighbors) {
                if (!hopCount.containsKey(neighbor)) {
                    hopCount.put(neighbor, currentHops + 1);
                    queue.add(neighbor);
                }
            }
        }
    }

    public List<String> getOptimalRoute(String targetDeviceId) {
        if (!peerConnections.containsKey(targetDeviceId)) {
            return Collections.emptyList();
        }

        // Dijkstra's algorithm for shortest path
        Map<String, Integer> distance = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<String> queue = new PriorityQueue<>(
            Comparator.comparingInt(a -> distance.getOrDefault(a, Integer.MAX_VALUE))
        );

        for (String deviceId : peerConnections.keySet()) {
            distance.put(deviceId, Integer.MAX_VALUE);
        }
        distance.put(localDeviceId, 0);
        queue.add(localDeviceId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(targetDeviceId)) break;

            Set<String> neighbors = peerConnections.getOrDefault(current, new HashSet<>());
            for (String neighbor : neighbors) {
                int alt = distance.get(current) + 1;
                if (alt < distance.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                    distance.put(neighbor, alt);
                    previous.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        // Build path
        List<String> path = new ArrayList<>();
        String current = targetDeviceId;
        while (previous.containsKey(current)) {
            path.add(0, current);
            current = previous.get(current);
        }
        if (!path.isEmpty()) {
            path.add(0, localDeviceId);
        }
        return path;
    }

    public JSONObject getTopologySnapshot() {
        JSONObject topology = new JSONObject();
        try {
            topology.put("localDeviceId", localDeviceId);
            
            JSONObject connections = new JSONObject();
            for (Map.Entry<String, Set<String>> entry : peerConnections.entrySet()) {
                JSONArray peers = new JSONArray();
                for (String peer : entry.getValue()) {
                    peers.put(peer);
                }
                connections.put(entry.getKey(), peers);
            }
            topology.put("connections", connections);
            
            JSONObject hops = new JSONObject();
            for (Map.Entry<String, Integer> entry : hopCount.entrySet()) {
                hops.put(entry.getKey(), entry.getValue());
            }
            topology.put("hopCounts", hops);
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating topology snapshot", e);
        }
        return topology;
    }

    private void notifyTopologyChange() {
        JSObject event = new JSObject();
        event.put("topology", getTopologySnapshot());
        plugin.notifyWebRTCEvent("topologyChange", event);
    }

    public boolean shouldRelayMessage(String sourceDeviceId, String targetDeviceId) {
        // Check if we're on the optimal path between source and target
        List<String> optimalPath = getOptimalRoute(targetDeviceId);
        int sourceIndex = optimalPath.indexOf(sourceDeviceId);
        int localIndex = optimalPath.indexOf(localDeviceId);
        int targetIndex = optimalPath.indexOf(targetDeviceId);
        
        return sourceIndex != -1 && localIndex != -1 && targetIndex != -1 
            && sourceIndex < localIndex && localIndex < targetIndex;
    }

    public Set<String> getDirectPeers() {
        return peerConnections.getOrDefault(localDeviceId, new HashSet<>());
    }

    public int getHopCount(String deviceId) {
        return hopCount.getOrDefault(deviceId, Integer.MAX_VALUE);
    }

    public void handlePeerDisconnection(String deviceId) {
        synchronized (peerConnections) {
            removePeer(deviceId);
            reorganizeMesh();
        }
    }

    private void reorganizeMesh() {
        long now = System.currentTimeMillis();
        if (now - lastReorganizationTime.getOrDefault(localDeviceId, 0L) < REORGANIZATION_COOLDOWN) {
            return; // Prevent too frequent reorganizations
        }
        lastReorganizationTime.put(localDeviceId, now);

        // Check if we need more direct connections
        Set<String> directPeers = getDirectPeers();
        if (directPeers.size() < MIN_PEERS_PER_NODE) {
            findNewPeers();
        }

        // Balance the mesh if needed
        balanceMeshConnections();

        // Notify about topology changes
        notifyTopologyChange();
    }

    private void findNewPeers() {
        Set<String> potentialPeers = new HashSet<>();
        Set<String> directPeers = getDirectPeers();

        // Find peers of peers (2-hop discovery)
        for (String peer : directPeers) {
            Set<String> peerConnections = this.peerConnections.getOrDefault(peer, new HashSet<>());
            potentialPeers.addAll(peerConnections);
        }

        // Remove existing connections and self
        potentialPeers.removeAll(directPeers);
        potentialPeers.remove(localDeviceId);

        // Sort potential peers by connection count (prefer less connected peers)
        List<String> sortedPeers = new ArrayList<>(potentialPeers);
        sortedPeers.sort((a, b) -> {
            int countA = this.peerConnections.getOrDefault(a, new HashSet<>()).size();
            int countB = this.peerConnections.getOrDefault(b, new HashSet<>()).size();
            return Integer.compare(countA, countB);
        });

        // Request new connections
        for (String newPeer : sortedPeers) {
            if (directPeers.size() >= MIN_PEERS_PER_NODE) break;
            requestNewConnection(newPeer);
        }
    }

    private void balanceMeshConnections() {
        Set<String> directPeers = getDirectPeers();
        if (directPeers.size() > MAX_PEERS_PER_NODE) {
            // Find peers with too many connections
            List<String> overconnectedPeers = new ArrayList<>(directPeers);
            overconnectedPeers.sort((a, b) -> {
                int countA = this.peerConnections.getOrDefault(a, new HashSet<>()).size();
                int countB = this.peerConnections.getOrDefault(b, new HashSet<>()).size();
                return Integer.compare(countB, countA); // Descending order
            });

            // Remove excess connections
            while (directPeers.size() > MAX_PEERS_PER_NODE) {
                String peerToRemove = overconnectedPeers.remove(0);
                requestDisconnection(peerToRemove);
            }
        }
    }

    private void requestNewConnection(String targetPeerId) {
        JSObject request = new JSObject();
        request.put("type", "connectionRequest");
        request.put("sourceId", localDeviceId);
        request.put("targetId", targetPeerId);
        plugin.notifyWebRTCEvent("connectionRequest", request);
    }

    private void requestDisconnection(String peerId) {
        JSObject request = new JSObject();
        request.put("type", "disconnectionRequest");
        request.put("sourceId", localDeviceId);
        request.put("targetId", peerId);
        plugin.notifyWebRTCEvent("disconnectionRequest", request);
    }

    public void handleConnectionRequest(String sourceId) {
        Set<String> directPeers = getDirectPeers();
        if (directPeers.size() < MAX_PEERS_PER_NODE) {
            JSObject response = new JSObject();
            response.put("type", "connectionResponse");
            response.put("sourceId", localDeviceId);
            response.put("targetId", sourceId);
            response.put("accepted", true);
            plugin.notifyWebRTCEvent("connectionResponse", response);
        }
    }

    public boolean shouldAcceptConnection(String peerId) {
        Set<String> directPeers = getDirectPeers();
        if (directPeers.size() >= MAX_PEERS_PER_NODE) {
            return false;
        }

        // Check if this connection would improve mesh stability
        int peerConnections = this.peerConnections.getOrDefault(peerId, new HashSet<>()).size();
        return peerConnections < MAX_PEERS_PER_NODE;
    }

    public JSONObject getMeshHealth() {
        JSONObject health = new JSONObject();
        try {
            int totalPeers = peerConnections.size();
            int avgConnections = 0;
            if (totalPeers > 0) {
                for (Set<String> connections : peerConnections.values()) {
                    avgConnections += connections.size();
                }
                avgConnections /= totalPeers;
            }

            health.put("totalPeers", totalPeers);
            health.put("directPeers", getDirectPeers().size());
            health.put("averageConnections", avgConnections);
            health.put("meshStability", calculateMeshStability());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating mesh health snapshot", e);
        }
        return health;
    }

    private double calculateMeshStability() {
        if (peerConnections.isEmpty()) return 1.0;

        int totalConnections = 0;
        int optimalConnections = peerConnections.size() * MIN_PEERS_PER_NODE;

        for (Set<String> connections : peerConnections.values()) {
            totalConnections += connections.size();
        }

        return Math.min(1.0, totalConnections / (double) optimalConnections);
    }

    public int getMaxHops() {
        return MAX_HOPS;
    }
} 