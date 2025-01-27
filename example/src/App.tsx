import React, { useEffect, useState } from 'react';
import { P2PCounter, MessageStatusEvent } from 'p2p-counter';
import { Capacitor } from '@capacitor/core';
import {
  AppShell,
  Button,
  Card,
  Container,
  Group,
  Indicator,
  List,
  Stack,
  Switch,
  Text,
  Title,
  rem
} from '@mantine/core';
import { useInterval } from '@mantine/hooks';
import type {
  Attendee,
} from 'capacitor-p2p-counter';
import { IconNfc, IconNfcOff } from '@tabler/icons-react';
import { MeshVisualizer } from './components/MeshVisualizer';
import { PluginListenerHandle } from '@capacitor/core';
import { NetworkStats as NetworkStatsComponent } from './components/NetworkStats';
import { MessageMonitor } from './components/MessageMonitor';
import { PeerList } from './components/PeerList';
import { NetworkStats as NetworkStatsType } from './components/MeshVisualizer';
import { ConnectionMethods } from './components/ConnectionMethods';

export default function App() {
  const [isNFCActive, setNFCActive] = useState(false);
  const [connectedPeers, setConnectedPeers] = useState<string[]>([]);
  const [networkHealth, setNetworkHealth] = useState<NetworkStatsType>({
    averageLatency: 0,
    packetLoss: 0,
    keepaliveInterval: 5000,
    totalPeers: 0
  });
  const [attendees] = useState<Record<string, Attendee>>({});
  const [currentEventId, setCurrentEventId] = useState<string>('default-event');
  const [isManualMode, setIsManualMode] = useState(false);
  const [platform] = useState(Capacitor.getPlatform());
  const [meshTopology, setMeshTopology] = useState<any>(null);
  const [networkStats, setNetworkStats] = useState<NetworkStatsType | null>(null);
  const [activeMessages, setActiveMessages] = useState<any[]>([]);
  const [qrCode, setQRCode] = useState<string>('');

  const manualCount = React.useMemo(() => {
    return Object.values(attendees).filter(
      a => a.eventId === currentEventId && a.isManual && a.isPresent
    ).length;
  }, [attendees, currentEventId]);

  const ticketCount = React.useMemo(() => {
    return Object.values(attendees).filter(
      a => a.eventId === currentEventId && !a.isManual && a.isPresent
    ).length;
  }, [attendees, currentEventId]);

  useEffect(() => {
    const listeners: PluginListenerHandle[] = [];

    // Initialize mesh network
    async function initMesh() {
      try {
        await P2PCounter.initializeWebRTC();
        await P2PCounter.startNFCDiscovery();
        await P2PCounter.startKeepalive();
        
        // Configure mesh optimization
        await P2PCounter.configureMesh({
          optimizationInterval: 30000,
          targetRedundancy: 2,
          loadBalancing: true,
          adaptiveRouting: true
        });
      } catch (err) {
        console.error('Mesh initialization failed:', err);
      }
    }

    // Set up event listeners
    listeners.push(
      P2PCounter.addListener('nfcDiscovered', (data) => {
        console.log('NFC Discovered:', data);
      }),

      P2PCounter.addListener('peerConnected', (data) => {
        setConnectedPeers(prev => [...prev, data.deviceId]);
      }),

      P2PCounter.addListener('peerTimeout', (data) => {
        setConnectedPeers(prev => prev.filter(id => id !== data.deviceId));
      }),

      P2PCounter.addListener('meshDiscovery', (data) => {
        setMeshTopology(JSON.parse(data.data));
      }),

      P2PCounter.addListener('messageStatus', (data: MessageStatusEvent) => {
        setActiveMessages(prev => 
          prev.map(msg => 
            msg.id === data.messageId 
              ? { ...msg, status: data.status, error: data.error }
              : msg
          )
        );
      }),

      P2PCounter.addListener('meshHealth', (stats) => {
        console.log('Mesh Health:', stats);
      })
    );

    // Start network monitoring
    const statsInterval = setInterval(async () => {
      const stats = await P2PCounter.getNetworkStats();
      setNetworkStats(stats ? {
        ...stats,
        totalPeers: connectedPeers.length + 1
      } as NetworkStatsType : null);
      setNetworkHealth(stats ? {
        ...stats,
        totalPeers: connectedPeers.length + 1
      } as NetworkStatsType : networkHealth);
    }, 5000);

    initMesh();

    return () => {
      listeners.forEach(listener => listener.remove());
      clearInterval(statsInterval);
      P2PCounter.stopKeepalive();
      P2PCounter.stopNFCDiscovery();
    };
  }, []);

  // Poll network health every 5 seconds
  useInterval(async () => {
    if (connectedPeers.length > 0) {
      const stats = await P2PCounter.getNetworkStats();
      setNetworkHealth(stats ? {
        ...stats,
        totalPeers: connectedPeers.length + 1
      } as NetworkStatsType : networkHealth);
      setNetworkStats(stats ? {
        ...stats,
        totalPeers: connectedPeers.length + 1
      } as NetworkStatsType : null);
    }
  }, 5000);

  const getNetworkStatus = () => {
    if (connectedPeers.length === 0) return 'offline';
    if (networkHealth.averageLatency > 1000 || networkHealth.packetLoss > 0.1) return 'poor';
    if (networkHealth.averageLatency > 500 || networkHealth.packetLoss > 0.05) return 'fair';
    return 'good';
  };

  const getNetworkColor = () => {
    switch (getNetworkStatus()) {
      case 'offline': return 'gray';
      case 'poor': return 'red';
      case 'fair': return 'yellow';
      case 'good': return 'green';
    }
  };

  const toggleNFC = async () => {
    try {
      if (isNFCActive) {
        await P2PCounter.stopNFCDiscovery();
      } else {
        if (platform === 'ios') {
          // Show user instructions for iOS NFC scanning
          alert('Hold your iPhone near another device to scan');
        }
        await P2PCounter.startNFCDiscovery();
      }
      setNFCActive(!isNFCActive);
    } catch (err) {
      console.error('NFC error:', err);
      
      // Type guard for error object
      if (typeof err === 'object' && err !== null && 'code' in err) {
        // Handle iOS permission errors
        if (platform === 'ios' && (err as { code: number }).code === -4) {
          alert('Please enable NFC in your iPhone settings');
        }
      }
    }
  };

  const handleScanCode = async () => {
    // Simulating QR code scan
    const code = `TICKET-${Math.random().toString(36).substr(2, 9)}`;
    await P2PCounter.sendCounter({ 
      code, 
      isPresent: true,
      eventId: currentEventId,
    });
  };

  const handleManualIncrement = async () => {
    const code = `MANUAL-${Date.now()}`;
    await P2PCounter.sendCounter({
      code,
      isPresent: true,
      eventId: currentEventId
    });
  };

  const handleManualDecrement = async () => {
    const manualEntries = Object.values(attendees)
      .filter(a => a.eventId === currentEventId && a.code.startsWith('MANUAL-') && a.isPresent)
      .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

    if (manualEntries.length > 0) {
      const lastEntry = manualEntries[0];
      await P2PCounter.sendCounter({
        code: lastEntry.code,
        isPresent: false,
        eventId: currentEventId
      });
    }
  };

  const toggleAttendance = async (code: string, currentStatus: boolean) => {
    await P2PCounter.sendCounter({ 
      code, 
      isPresent: !currentStatus,
      eventId: currentEventId 
    });
  };

  // Optional: Add iOS-specific UI elements
  const renderNFCButton = () => {
    if (platform === 'ios') {
      return (
        <Button
          onClick={toggleNFC}
          leftSection={isNFCActive ? <IconNfc size={20} /> : <IconNfcOff size={20} />}
          color={isNFCActive ? 'blue' : 'gray'}
        >
          {isNFCActive ? 'Scanning...' : 'Scan Device'}
        </Button>
      );
    }

    // Default Android toggle switch
    return (
      <Switch
        label="NFC Discovery"
        checked={isNFCActive}
        onChange={toggleNFC}
      />
    );
  };

  const sendTestMessage = async () => {
    try {
      await P2PCounter.sendCounter({
        code: `TEST-${Date.now()}`,
        isPresent: true,
        eventId: currentEventId,
        priority: 'HIGH',
        retryPolicy: {
          maxAttempts: 3,
          backoffMs: 1000,
          timeout: 5000
        }
      });
    } catch (err) {
      console.error('Failed to send message:', err);
    }
  };

  const handleShare = async () => {
    try {
      await P2PCounter.shareConnectionInfo();
    } catch (err) {
      console.error('Share failed:', err);
    }
  };

  const handleScanQR = async () => {
    try {
      await P2PCounter.scanConnectionQR();
    } catch (err) {
      console.error('QR scan failed:', err);
    }
  };

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

  return (
    <AppShell>
      <Container size="sm">
        <Stack gap={rem(20)}>
          <Card withBorder>
            <Title order={2}>Event Settings</Title>
            <Group gap={rem(12)} mt={rem(16)}>
              <Switch
                label="Manual Mode"
                checked={isManualMode}
                onChange={(event) => setIsManualMode(event.currentTarget.checked)}
              />
              <Text>Event ID: {currentEventId}</Text>
              <Button onClick={() => setCurrentEventId(`event-${Date.now()}`)}>
                Change Event
              </Button>
            </Group>
          </Card>

          <Card withBorder>
            <Title order={2}>Device Status</Title>
            <Group gap={rem(8)} mt={rem(16)}>
              {renderNFCButton()}
              <Text>Connected Peers: {connectedPeers.length}</Text>
              <Group gap={rem(4)}>
                <Text>Network Health:</Text>
                <Indicator size={10} color={getNetworkColor()} />
                <Text fz="sm" c="dimmed">
                  {networkHealth.averageLatency.toFixed(0)}ms / {(networkHealth.packetLoss * 100).toFixed(1)}% loss
                </Text>
              </Group>
            </Group>
          </Card>

          <Card withBorder>
            {isManualMode ? (
              <>
                <Title order={2}>Manual Counter</Title>
                <Group justify="center" gap={rem(20)} mt={rem(24)}>
                  <Button
                    size="xl"
                    variant="outline"
                    color="red"
                    onClick={handleManualDecrement}
                    disabled={manualCount === 0}
                  >
                    -
                  </Button>
                  <Text fz="xl" fw={700} style={{ minWidth: rem(64), textAlign: 'center' }}>
                    {manualCount}
                  </Text>
                  <Button
                    size="xl"
                    variant="outline"
                    color="blue"
                    onClick={handleManualIncrement}
                  >
                    +
                  </Button>
                </Group>
              </>
            ) : (
              <>
                <Group justify="apart">
                  <Title order={2}>Ticket Scanning</Title>
                  <Button onClick={handleScanCode}>
                    Simulate Scan
                  </Button>
                </Group>
                <Text fz="xl" fw={700} mt={rem(16)}>
                  Total Present: {ticketCount}
                </Text>
                <List spacing={rem(8)} mt={rem(16)}>
                  {Object.values(attendees)
                    .filter(a => a.eventId === currentEventId && !a.isManual)
                    .map(attendee => (
                      <List.Item key={attendee.code}>
                        <Group justify="apart">
                          <Text>{attendee.code}</Text>
                          <Switch
                            label={attendee.isPresent ? 'Present' : 'Left'}
                            checked={attendee.isPresent}
                            onChange={() => toggleAttendance(
                              attendee.code,
                              attendee.isPresent
                            )}
                          />
                        </Group>
                      </List.Item>
                    ))}
                </List>
              </>
            )}
          </Card>

          {meshTopology && (
            <Card withBorder>
              <Title order={2}>Mesh Network</Title>
              <MeshVisualizer 
                topology={meshTopology}
                networkStats={networkStats}
                activeMessages={activeMessages}
                width={600}
                height={400}
              />
            </Card>
          )}

          <Card withBorder>
            <Title order={2}>Network Stats</Title>
            <NetworkStatsComponent stats={networkStats} />
          </Card>

          <Card withBorder>
            <Title order={2}>Peer Management</Title>
            <PeerList 
              peers={connectedPeers}
              onDisconnect={(peerId) => P2PCounter.disconnectPeer({ deviceId: peerId })}
            />
          </Card>

          <Card withBorder>
            <Title order={2}>Message Monitor</Title>
            <MessageMonitor messages={activeMessages} />
            <Button onClick={sendTestMessage}>Send Test Message</Button>
          </Card>

          <ConnectionMethods
            onShare={handleShare}
            onScanQR={handleScanQR}
            qrCode={qrCode}
          />
        </Stack>
      </Container>
    </AppShell>
  );
}