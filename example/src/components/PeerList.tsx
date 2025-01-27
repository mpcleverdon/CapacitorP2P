import { ActionIcon, Group, Paper, Stack, Text, rem } from '@mantine/core';
import { IconX } from '@tabler/icons-react';

interface PeerListProps {
  peers: string[];
  onDisconnect: (peerId: string) => void;
}

export function PeerList({ peers, onDisconnect }: PeerListProps) {
  const formatPeerId = (id: string) => {
    return id.length > 12 ? `${id.slice(0, 6)}...${id.slice(-6)}` : id;
  };

  return (
    <Stack>
      {peers.length === 0 ? (
        <Text c="dimmed">No connected peers</Text>
      ) : (
        peers.map(peerId => (
          <Paper key={peerId} withBorder p="md">
            <Group justify="space-between">
              <Text size="sm">{formatPeerId(peerId)}</Text>
              <ActionIcon
                variant="subtle"
                color="red"
                onClick={() => onDisconnect(peerId)}
                size={rem(24)}
              >
                <IconX size={rem(16)} />
              </ActionIcon>
            </Group>
          </Paper>
        ))
      )}
    </Stack>
  );
} 