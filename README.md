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
   ```
   Bandwidth per device = 52 × (n-1) bytes/second
   ```
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
