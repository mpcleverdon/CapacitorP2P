import React, { useEffect, useState } from 'react';
import { P2PCounter } from 'capacitor-p2p-counter';
import { Capacitor } from '@capacitor/core';
import {
  AppShell,
  Button,
  ButtonProps,
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
import type { Attendee } from './types';
import { IconNfc, IconNfcOff } from '@tabler/icons-react';

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
    const listeners = [];

    const nfcDiscoveryListener = P2PCounter.addListener('nfcDiscovered', async (data) => {
      console.log('NFC discovered:', data);
      
      // iOS shows a system dialog, wait for it
      if (platform === 'ios' && data.systemDialogPresented) {
        console.log('iOS NFC system dialog shown');
        return;
      }
      
      // When we discover a peer via NFC, initialize WebRTC
      await P2PCounter.initializeWebRTC();
      
      // Create peer connection as initiator since we received the NFC
      await P2PCounter.createPeerConnection({
        deviceId: data.deviceId,
        isInitiator: true
      });
    });
    listeners.push(nfcDiscoveryListener);

    const nfcErrorListener = P2PCounter.addListener('nfcError', (error: { code: number }) => {
      console.error('NFC error:', error);
      
      // Handle iOS-specific errors
      if (platform === 'ios') {
        switch (error.code) {
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
    });
    listeners.push(nfcErrorListener);

    const nfcPushListener = P2PCounter.addListener('nfcPushComplete', (data) => {
      console.log('NFC push completed with device:', data.deviceId);
    });
    listeners.push(nfcPushListener);

    P2PCounter.addListener('counterReceived', (data) => {
      // Handle initial state sync
      if (data.type === 'initial_state') {
        setAttendees(data.attendees);
        return;
      }
      
      setAttendees(prev => ({
        ...prev,
        [data.code]: {
          code: data.code,
          isPresent: data.isPresent,
          timestamp: data.timestamp
        }
      }));
    });

    P2PCounter.addListener('peerConnected', (data) => {
      setConnectedPeers(prev => [...prev, data.deviceId]);
      // If we're the initiator, send our current state
      if (data.isInitiator) {
        P2PCounter.sendInitialState({
          deviceId: data.deviceId,
          state: attendees
        });
      }
    });

    P2PCounter.addListener('peerTimeout', (data) => {
      setConnectedPeers(prev => prev.filter(id => id !== data.deviceId));
    });

    // Start keepalive
    P2PCounter.startKeepalive();

    // Cleanup listeners
    return () => {
      listeners.forEach(listener => {
        if (typeof listener.remove === 'function') {
          listener.remove();
        }
      });
      P2PCounter.stopKeepalive();
    };
  }, [platform]);

  // Poll network health every 5 seconds
  useInterval(async () => {
    if (connectedPeers.length > 0) {
      const stats = await P2PCounter.getNetworkStats();
      setNetworkHealth(stats);
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
      .sort((a, b) => b.timestamp - a.timestamp);

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
        </Stack>
      </Container>
    </AppShell>
  );
}