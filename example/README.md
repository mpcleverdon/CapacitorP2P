# P2P Counter Example App

This example demonstrates the complete usage of the P2P Counter Capacitor plugin, including:
- NFC device discovery
- WebRTC mesh networking
- Real-time attendance tracking
- Peer connection management
- Keepalive system
- Network health monitoring
- Message prioritization
- Mesh topology visualization

## Features

### Core Features
- Toggle NFC discovery
- View connected peers
- Monitor network health in real-time
- Simulate ticket scanning
- Track attendee presence/absence
- Real-time sync across all connected devices

### Advanced Features
- Mesh Network Visualization
  - Real-time network topology map
  - Connection strength indicators
  - Message flow visualization
  - Network health statistics

- Network Health Monitoring
  - Average latency tracking
  - Packet loss detection
  - Connection quality metrics
  - Peer connection status
  - Network bandwidth usage: ~500KB/hour per peer for keepalive messages

- Message Management
  - Priority-based message queuing
  - Automatic message retries
  - Message deduplication
  - Chunked message transfer
  - Compression for large messages

- Mesh Network Features
  - Automatic peer discovery
  - Optimal route calculation
  - Network self-healing
  - Connection load balancing
  - Multi-hop message relay

### Event Management
- Multiple event support
- Manual and automatic counting modes
- Real-time attendance updates
- Historical attendance tracking
- Event-specific statistics

## Platform-Specific Notes

### iOS
- Requires iOS 13.0 or later
- NFC capability must be enabled in your provisioning profile
- Uses system NFC scanning UI
- Shows scan button instead of toggle switch

### Android
- Requires Android API level 22 or later
- Uses HCE (Host Card Emulation) for NFC
- Shows toggle switch for NFC discovery
- Background service for continuous mesh networking

## Running the Example

1. Install dependencies:
```bash
npm install
```

2. Build the plugin and example:
```bash
npm run build
cd example
npm install
npm run build
```

3. Add platforms:
```bash
npx cap add android
npx cap add ios
```

4. Sync changes:
```bash
npx cap sync
```

5. Run on device:
```bash
# For Android
npx cap run android

# For iOS
npx cap run ios
```

## Network Requirements
- Peer-to-peer communication uses WebRTC
- STUN server used for NAT traversal
- Minimal bandwidth requirements:
  - Base: ~500KB/hour per peer
  - Peak: Depends on message frequency and size
  - Compressed messages for efficiency
  - Adaptive keepalive intervals

## Troubleshooting
- Ensure NFC is enabled on devices
- Check network connectivity
- Verify permissions are granted
- Monitor logcat/console for detailed logs
- Check network health statistics for connection issues