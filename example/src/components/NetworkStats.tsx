import { Group, Paper, RingProgress, Stack, Text, rem } from '@mantine/core';
import type { NetworkStats } from 'capacitor-p2p-counter';

interface NetworkStatsProps {
  stats: NetworkStats | null;
}

export function NetworkStats({ stats }: NetworkStatsProps) {
  if (!stats) {
    return <Text c="dimmed">Loading network statistics...</Text>;
  }

  const getLatencyColor = (latency: number) => {
    if (latency < 100) return 'green';
    if (latency < 300) return 'yellow';
    return 'red';
  };

  const getPacketLossColor = (loss: number) => {
    if (loss < 0.02) return 'green';
    if (loss < 0.05) return 'yellow';
    return 'red';
  };

  const getNetworkStrengthColor = (strength: number) => {
    if (strength > 0.8) return 'green';
    if (strength > 0.5) return 'yellow';
    return 'red';
  };

  return (
    <Stack>
      <Group grow>
        <Paper withBorder p="md">
          <Group justify="space-between">
            <Stack gap={0}>
              <Text size="xs" c="dimmed">Latency</Text>
              <Text size="lg" fw={500}>{stats.averageLatency.toFixed(0)}ms</Text>
            </Stack>
            <RingProgress
              size={46}
              thickness={4}
              sections={[{ 
                value: Math.min((stats.averageLatency / 500) * 100, 100), 
                color: getLatencyColor(stats.averageLatency) 
              }]}
            />
          </Group>
        </Paper>

        <Paper withBorder p="md">
          <Group justify="space-between">
            <Stack gap={0}>
              <Text size="xs" c="dimmed">Packet Loss</Text>
              <Text size="lg" fw={500}>{(stats.packetLoss * 100).toFixed(1)}%</Text>
            </Stack>
            <RingProgress
              size={46}
              thickness={4}
              sections={[{ 
                value: stats.packetLoss * 100, 
                color: getPacketLossColor(stats.packetLoss) 
              }]}
            />
          </Group>
        </Paper>
      </Group>

      {stats.networkStrength !== undefined && (
        <Paper withBorder p="md">
          <Group justify="space-between">
            <Stack gap={0}>
              <Text size="xs" c="dimmed">Network Strength</Text>
              <Text size="lg" fw={500}>{(stats.networkStrength * 100).toFixed(0)}%</Text>
            </Stack>
            <RingProgress
              size={46}
              thickness={4}
              sections={[{ 
                value: stats.networkStrength * 100, 
                color: getNetworkStrengthColor(stats.networkStrength) 
              }]}
            />
          </Group>
        </Paper>
      )}

      <Paper withBorder p="md">
        <Stack gap={rem(8)}>
          <Text size="sm" fw={500}>Additional Info</Text>
          <Group gap={rem(32)}>
            <Text size="sm">Keepalive: {stats.keepaliveInterval}ms</Text>
            {stats.messageCount !== undefined && (
              <Text size="sm">Messages: {stats.messageCount}</Text>
            )}
          </Group>
        </Stack>
      </Paper>
    </Stack>
  );
} 