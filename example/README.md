# P2P Counter Example App

This example demonstrates the complete usage of the P2P Counter Capacitor plugin, including:
- NFC device discovery
- WebRTC mesh networking
- Real-time attendance tracking
- Peer connection management
- Keepalive system

## Features
- Toggle NFC discovery
- View connected peers
- Monitor network health in real-time
- Simulate ticket scanning
- Track attendee presence/absence
- Real-time sync across all connected devices

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

## Running the Example
************
1. Install dependencies:
```