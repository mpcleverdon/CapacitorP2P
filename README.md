# Capacitor P2P Counter Plugin

A Capacitor plugin that enables mesh network communication between multiple mobile devices using WebRTC with NFC handshake for initial device discovery. Supports real-time counter synchronization and duplicate entry detection across all connected devices.

## Installation

```bash
npm install p2p-counter
npx cap sync
```

## API

### NFC Methods

- `startNFCDiscovery()`: Start NFC discovery mode
- `stopNFCDiscovery()`: Stop NFC discovery mode
- `sendNFCMessage({ message: string })`: Send NFC message for initial handshake

### WebRTC Methods

- `initializeWebRTC()`: Initialize WebRTC connection
- `createPeerConnection({ deviceId: string, isInitiator: boolean })`: Create P2P connection with another device
- `sendCounter({ code: string, isPresent: boolean })`: Broadcast counter data and attendance status to all connected peers
- `disconnectPeer({ deviceId: string })`: Disconnect from a specific peer
- `startKeepalive()`: Start sending keepalive signals to maintain mesh stability
- `stopKeepalive()`: Stop sending keepalive signals

### Events

- `nfcDiscovered`: Fired when NFC tag is discovered
- `counterReceived`: Fired when counter data is received from any peer
- `peerConnected`: Fired when peer connection is established
- `peerDisconnected`: Fired when peer connection is lost
- `peerTimeout`: Fired when a peer hasn't responded to keepalive signals
- `duplicateDetected`: Fired when a duplicate entry is detected

### Network Health Monitoring

The plugin provides network health statistics through the `getNetworkStats()` method:

```typescript
const stats = await P2PCounter.getNetworkStats();
console.log(stats.averageLatency);    // Average latency in ms
console.log(stats.packetLoss);        // Packet loss rate (0-1)
console.log(stats.keepaliveInterval); // Current keepalive interval in ms
```

Recommended thresholds:

- Good: < 500ms latency, < 5% packet loss
- Fair: < 1000ms latency, < 10% packet loss
- Poor: > 1000ms latency or > 10% packet loss

## Connection Methods

The plugin supports multiple methods for establishing the initial WebRTC connection:

### 1. NFC Connection (Default)
```typescript
// Start NFC discovery
await P2PCounter.startNFCDiscovery();

// Listen for NFC discovery
P2PCounter.addListener('nfcDiscovered', async (data) => {
  await P2PCounter.initializeWebRTC();
  await P2PCounter.createPeerConnection({
    deviceId: data.deviceId,
    isInitiator: true
  });
});
```

### 2. Share API Connection
```typescript
// Share connection info using native share sheet
await P2PCounter.shareConnectionInfo();

// Receive shared connection info
P2PCounter.addListener('connectionReceived', async (data) => {
  await P2PCounter.receiveConnectionInfo({ sharedData: data.url });
});
```

### 3. QR Code Connection
```typescript
// Generate QR code
const { qrData } = await P2PCounter.generateConnectionQR();
// Display qrData in your UI (base64 image)

// Scan QR code
await P2PCounter.scanConnectionQR();
```

### Example Implementation
```typescript
const initiateConnection = async () => {
  try {
    // Try Share API first on mobile
    if (platform === 'ios' || platform === 'android') {
      await P2PCounter.shareConnectionInfo();
    } else {
      // Fallback to QR code
      const { qrData } = await P2PCounter.generateConnectionQR();
      setQRCode(qrData); // Display in UI
    }
  } catch (err) {
    console.error('Connection initiation failed:', err);
  }
};
```

Each method will automatically establish the WebRTC connection once the initial handshake is complete.

## Usage Example

```typescript
import { P2PCounter } from 'capacitor-p2p-counter';

// Start NFC discovery
await P2PCounter.startNFCDiscovery();

// Listen for NFC discovery
P2PCounter.addListener('nfcDiscovered', async (data) => {
  // Initialize WebRTC when NFC is discovered
  await P2PCounter.initializeWebRTC();
  await P2PCounter.createPeerConnection({
    deviceId: data.deviceId,
    isInitiator: true
  });
});

// Listen for counter updates
P2PCounter.addListener('counterReceived', (data) => {
  console.log('Received code:', data.code, 'Present:', data.isPresent, 'at:', data.timestamp);
});

// Start keepalive system
await P2PCounter.startKeepalive();

// Listen for peer timeouts
P2PCounter.addListener('peerTimeout', (data) => {
  console.log('Peer timed out:', data.deviceId);
});
// Listen for duplicates
P2PCounter.addListener('duplicateDetected', (data) => {
  console.log('Duplicate code detected:', data.code);
});

// Monitor network health
setInterval(async () => {
  const stats = await P2PCounter.getNetworkStats();
  console.log('Network Health:', stats);
}, 5000);

// Send counter update
await P2PCounter.sendCounter({ code: 'QR123', isPresent: true });

// Update when attendee leaves
await P2PCounter.sendCounter({ code: 'QR123', isPresent: false });

// Disconnect from a peer
await P2PCounter.disconnectPeer({ deviceId: 'device123' });
```

## Requirements

- iOS 13+
- Android API level 24+
- Capacitor 5+

## Network Analysis and Scaling

### Keepalive System Analysis

The plugin uses a 5-second ping/pong keepalive system. Here's the network traffic analysis:

1. **Message Sizes**:
   - Ping/Pong: ~65 bytes (including WebRTC overhead)
   - Counter Message: ~140 bytes

2. **Traffic Per Device**:

   Bandwidth per device = 52 × (n-1) bytes/second

   Where n is the number of connected devices

3. **Practical Limits**:
   - Optimal performance: 50-75 devices
   - Theoretical maximum: ~245 devices (using 10% of 1Mbps connection)

4. **Limiting Factors**:
   - Network latency
   - Message processing overhead
   - Connection management
   - Counter messages priority

5. **Adaptive Keepalive System**:
   The plugin implements an intelligent keepalive system that automatically adjusts based on network conditions:

   - Base interval: 5 seconds
   - Dynamic range: 5-30 seconds
   - Adjustment factors:
     - Network latency
     - Packet loss rate
     - Number of connected peers

   The system:
   - Monitors RTT (Round Trip Time) for each peer
   - Tracks packet loss patterns
   - Adjusts intervals per-peer when needed
   - Prevents cascade failures

6. **Scaling Recommendations**:
   - Increase keepalive interval to 10-15 seconds for larger networks
   - Implement hierarchical mesh with subnet coordinators
   - Use binary protocol instead of JSON for better efficiency

## Notes

- NFC capabilities must be enabled in your app's capabilities (iOS) and manifest (Android)
- WebRTC connections require proper STUN/TURN server configuration for NAT traversal

## Advanced Features Documentation

## Mesh Network Management

### MeshDiscoveryManager

Handles peer discovery and network topology management:

- `startDiscovery()`: Initiates peer discovery process
- `stopDiscovery()`: Stops peer discovery
- `handleAnnouncement(deviceId: string, data: JSONObject)`: Processes peer announcements
- `getDiscoverySnapshot()`: Returns current network topology state
- `cleanupStaleEntries()`: Removes inactive peers

Configuration:

```typescript
const discoveryConfig = {
announcementInterval: 10000, // 10 seconds
peerTimeout: 30000, // 30 seconds
maxPeers: 10 // Maximum direct connections
}
```

### MeshTopologyManager

Manages mesh network topology and message routing:

- `addPeer(deviceId: string, connectedPeers: string[])`: Adds new peer to mesh
- `removePeer(deviceId: string)`: Removes peer from mesh
- `shouldRelayMessage(sourceId: string, targetId: string)`: Determines if local node should relay message
- `getOptimalRoute(targetId: string)`: Calculates optimal message path
- `reorganizeMesh()`: Optimizes mesh connections

Configuration:

```typescript
const topologyConfig = {
maxHops: 5, // Maximum message relay hops
minPeersPerNode: 2, // Minimum direct connections
maxPeersPerNode: 5, // Maximum direct connections
reorganizationCooldown: 10000 // 10 seconds between reorganizations
}
```

## Message Management

### MessagePriorityManager

Handles message prioritization and delivery guarantees:

- Priority Levels:
  - `CRITICAL`: Immediate delivery, max retries
  - `HIGH`: Prioritized delivery, multiple retries
  - `MEDIUM`: Standard delivery, limited retries
  - `LOW`: Best-effort delivery, no retries

```typescript
await P2PCounter.sendCounter({
code: 'QR123',
isPresent: true,
priority: 'HIGH'
});
```

### MessageProcessor

Handles message fragmentation and reassembly:

- Automatic message chunking (16KB chunks)
- Compression for messages > 1KB
- Guaranteed order delivery
- Message assembly and validation

### MessageDeduplicator

Prevents duplicate message processing:

- Message fingerprinting
- Time-based deduplication window
- Cross-device synchronization

## Network Analysis Tools

Enhanced statistics available through `getNetworkStats()`:

```typescript
interface DetailedNetworkStats {
averageLatency: number; // ms
packetLoss: number; // 0-1
keepaliveInterval: number; // ms
messageCount: number; // Total messages processed
networkStrength: number; // 0-1
peerStats: {
deviceId: string;
latency: number;
packetLoss: number;
connectionQuality: number;
}[];
topologyHealth: {
redundancy: number; // Network path redundancy
avgHopCount: number; // Average hops between nodes
fragmentationRisk: number; // Risk of network splitting
};
}
```

### Message Flow Visualization

The plugin provides a visualization system for debugging and monitoring:

```typescript
P2PCounter.addListener('messageStatus', (event: MessageStatusEvent) => {
console.log('Message:', event.messageId);
console.log('Status:', event.status);
console.log('Path:', event.path);
console.log('Attempts:', event.attempts);
console.log('Latency:', event.latency);
});
```

## Advanced Usage Examples

### Implementing Custom Message Priority

```typescript
// Configure message priority
const message = {
type: 'counter',
code: 'QR123',
priority: 'HIGH',
retryPolicy: {
maxAttempts: 3,
backoffMs: 1000,
timeout: 5000
}
};
// Send with priority
await P2PCounter.sendPrioritizedMessage(message);
```

### Mesh Network Optimization

```typescript
// Configure mesh optimization
await P2PCounter.configureMesh({
optimizationInterval: 30000, // 30 seconds
targetRedundancy: 2, // Minimum path redundancy
loadBalancing: true, // Enable load balancing
adaptiveRouting: true // Enable adaptive routing
});
// Monitor mesh health
P2PCounter.addListener('meshHealth', (stats) => {
console.log('Redundancy:', stats.redundancy);
console.log('Average Hop Count:', stats.avgHopCount);
console.log('Network Stability:', stats.stability);
});
```

## Performance Considerations

1. **Message Priority System**:
   - Critical messages: < 100ms delivery target
   - High priority: < 500ms delivery target
   - Standard messages: < 2000ms delivery target

2. **Resource Usage**:
   - Memory: ~50MB per 100 connected peers
   - CPU: 2-5% average utilization
   - Battery: ~2% per hour with 10 peers

3. **Scaling Characteristics**:
   - Linear bandwidth growth per peer
   - Quadratic message processing overhead
   - Optimized for 50-75 device mesh networks
