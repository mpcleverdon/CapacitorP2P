import React, { useEffect, useState } from 'react';
import { P2PCounter } from 'capacitor-p2p-counter';
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
  NFCErrorEvent,
  CounterEvent,
  PeerEvent,
  MessageEvent,
  MessageStatusEvent,
  NFCDiscoveredEvent,
  NFCPushCompleteEvent
} from 'capacitor-p2p-counter';
import { IconNfc, IconNfcOff } from '@tabler/icons-react';
import { MeshVisualizer } from './components/MeshVisualizer';
import { PluginListenerHandle } from '@capacitor/core';
import { CounterData } from 'capacitor-p2p-counter/dist/esm/definitions';

interface Message {
  id: string;
  path: string[];
  progress: number;
  timestamp: number;
  status?: 'pending' | 'success' | 'failed';
  attempts?: number;
  error?: string;
}

export default function App() {
  const [isNFCActive, setNFCActive] = useState(false);
  const [connectedPeers, setConnectedPeers] = useState<string[]>([]);
  const [networkHealth, setNetworkHealth] = useState<{
    averageLatency: number;
    packetLoss: number;
    keepaliveInterval: number;
  }>({ averageLatency: 0, packetLoss: 0, keepaliveInterval: 5000 });
  const [attendees, setAttendees] = useState<Record<string, Attendee>>({});
  const [currentEventId, setCurrentEventId] = useState<string>('default-event');
  const [isManualMode, setIsManualMode] = useState(false);
  const [platform] = useState(Capacitor.getPlatform());
  const [meshTopology, setMeshTopology] = useState<any>(null);
  const [networkStats, setNetworkStats] = useState({
    totalPeers: 0,
    averageLatency: 0,
    packetLoss: 0,
    messageCount: 0,
    networkStrength: 0
  });
  const [activeMessages, setActiveMessages] = useState<Message[]>([]);

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

    const nfcDiscoveryListener = P2PCounter.addListener(
      'nfcDiscovered',
      async (event: NFCDiscoveredEvent) => {
        console.log('NFC discovered:', event);
        
        // iOS shows a system dialog, wait for it
        if (platform === 'ios' && event.systemDialogPresented) {
          console.log('iOS NFC system dialog shown');
          return;
        }
        
        // When we discover a peer via NFC, initialize WebRTC
        await P2PCounter.initializeWebRTC();
        
        // Create peer connection as initiator since we received the NFC
        await P2PCounter.createPeerConnection({
          deviceId: event.deviceId,
          isInitiator: true
        });
      }
    );
    listeners.push(nfcDiscoveryListener);

    const nfcErrorListener = P2PCounter.addListener(
      'nfcError',
      (event: NFCErrorEvent) => {
        console.error('NFC error:', event);
        
        // Handle iOS-specific errors
        if (platform === 'ios') {
          switch (event.code) {
            case -1:  // Session timeout
              setNFCActive(false);
              break;
            case -2:  // Invalid user cancel
              setNFCActive(false);
              break;
            case -3:  // System is busy
              // Maybe try again after delay
              setTimeout(() => toggleNFC(), 1000);
              break;
            default:
              // Handle other errors
              setNFCActive(false);
          }
        }
      }
    );
    listeners.push(nfcErrorListener);

    const nfcPushListener = P2PCounter.addListener(
      'nfcPushComplete',
      (event: NFCPushCompleteEvent) => {
        console.log('NFC push completed with device:', event.deviceId);
      }
    );
    listeners.push(nfcPushListener);

    const counterListener = P2PCounter.addListener(
      'counterReceived',
      (event: CounterEvent) => {
        // Handle initial state sync
        if (event.type === 'initial_state') {
          setAttendees(event.attendees || {});
          return;
        }
        
        setAttendees(prev => ({
          ...prev,
          [event.code || '']: {
            code: event.code || '',
            isPresent: event.isPresent || false,
            timestamp: event.timestamp || Date.now(),
            eventId: currentEventId,
            isManual: false
          } as Attendee
        }));
      }
    );
    listeners.push(counterListener);

    const peerConnectedListener = P2PCounter.addListener(
      'peerConnected',
      (event: PeerEvent) => {
        setConnectedPeers(prev => [...prev, event.deviceId]);
        // If we're the initiator, send our current state
        if (event.isInitiator) {
          P2PCounter.sendInitialState({
            deviceId: event.deviceId,
            state: Object.fromEntries(
              Object.entries(attendees).map(([key, value]) => [
                key,
                {
                  code: value.code,
                  isPresent: value.isPresent,
                  eventId: value.eventId || currentEventId,
                  isManual: value.isManual || false,
                  timestamp: value.timestamp || Date.now()
                } as CounterData
              ])
            )
          });
        }
      }
    );
    listeners.push(peerConnectedListener);

    const peerTimeoutListener = P2PCounter.addListener('peerTimeout', (data: PeerEvent) => {
      setConnectedPeers(prev => prev.filter(id => id !== data.deviceId));
    });
    listeners.push(peerTimeoutListener);

    // Start keepalive
    P2PCounter.startKeepalive();

    const meshDiscoveryListener = P2PCounter.addListener('meshDiscovery', (data) => {
      setMeshTopology(JSON.parse(data.data));
    });

    const messageListener = P2PCounter.addListener('meshMessage', (data: MessageEvent) => {
      const message = JSON.parse(data.data);
      if (message._path) {
        setActiveMessages(prev => [...prev, {
          id: message._messageId,
          path: message._path,
          progress: 0,
          timestamp: Date.now(),
          status: 'pending',
          attempts: 1
        }]);
      }
    });

    const messageStatusListener = P2PCounter.addListener('messageStatus', (data: MessageStatusEvent) => {
      const { messageId, status, error, attempts } = data;
      setActiveMessages(prev => 
        prev.map(msg => 
          msg.id === messageId 
            ? { ...msg, status, error, attempts: attempts || msg.attempts }
            : msg
        )
      );
    });

    // Cleanup listeners
    return () => {
      listeners.forEach(listener => {
        if (typeof listener.remove === 'function') {
          listener.remove();
        }
      });
      P2PCounter.stopKeepalive();
      meshDiscoveryListener.remove();
      messageListener.remove();
      messageStatusListener.remove();
    };
  }, [platform]);

  // Poll network health every 5 seconds
  useInterval(async () => {
    if (connectedPeers.length > 0) {
      const stats = await P2PCounter.getNetworkStats();
      setNetworkHealth(stats);
      setNetworkStats({
        totalPeers: connectedPeers.length,
        averageLatency: stats.averageLatency,
        packetLoss: stats.packetLoss * 100,
        messageCount: activeMessages.length,
        networkStrength: 0.5 // Placeholder value, replace with actual calculation
      });
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
        </Stack>
      </Container>
    </AppShell>
  );
}