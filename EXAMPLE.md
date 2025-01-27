# P2P Counter Example App

## Connection Methods

The example app demonstrates three ways to establish peer connections:

1. NFC Discovery (Default)
2. Share API
3. QR Code Scanning

### Components

#### ConnectionMethods.tsx
Provides UI for sharing connection info and scanning QR codes:
```typescript
<ConnectionMethods
  onShare={handleShare}
  onScanQR={handleScanQR}
  qrCode={qrCode}
/>
```

Features:
- Share button: Uses native share sheet
- Scan QR button: Opens device camera
- QR code display: Shows connection QR code

### Implementation

1. Share Connection:
```typescript
const handleShare = async () => {
  try {
    await P2PCounter.shareConnectionInfo();
  } catch (err) {
    console.error('Share failed:', err);
  }
};
```

2. QR Code Scanning:
```typescript
const handleScanQR = async () => {
  try {
    await P2PCounter.scanConnectionQR();
  } catch (err) {
    console.error('QR scan failed:', err);
  }
};
```

3. QR Code Generation:
```typescript
useEffect(() => {
  const generateQR = async () => {
    try {
      const { qrData } = await P2PCounter.generateConnectionQR();
      setQRCode(qrData);
    } catch (err) {
      console.error('QR generation failed:', err);
    }
  };
  generateQR();
}, []);
```

### Usage

1. Share Connection:
   - Click "Share Connection"
   - Select sharing method from native sheet
   - Other device receives connection info

2. QR Code:
   - Display QR code on one device
   - Click "Scan QR Code" on other device
   - Allow camera access
   - Scan displayed QR code

3. NFC (Original method):
   - Enable NFC on both devices
   - Bring devices close together
   - Follow on-screen prompts 