import { Badge, Group, Paper, Progress, Stack, Text, rem } from '@mantine/core';

interface Message {
  id: string;
  path: string[];
  progress: number;
  timestamp: number;
  status: 'pending' | 'success' | 'failed';
  attempts?: number;
  error?: string;
}

interface MessageMonitorProps {
  messages: Message[];
}

export function MessageMonitor({ messages }: MessageMonitorProps) {
  const getStatusColor = (status: Message['status']) => {
    switch (status) {
      case 'success': return 'green';
      case 'failed': return 'red';
      default: return 'yellow';
    }
  };

  const formatTimestamp = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString();
  };

  const formatPath = (path: string[]) => {
    if (path.length <= 2) return path.join(' → ');
    return `${path[0]} → ... → ${path[path.length - 1]}`;
  };

  return (
    <Stack>
      {messages.length === 0 ? (
        <Text c="dimmed">No active messages</Text>
      ) : (
        messages.map(message => (
          <Paper key={message.id} withBorder p="md">
            <Stack gap={rem(8)}>
              <Group justify="space-between">
                <Text size="sm" fw={500}>Message {message.id.slice(-6)}</Text>
                <Badge color={getStatusColor(message.status)}>
                  {message.status}
                </Badge>
              </Group>

              <Group gap={rem(16)}>
                <Text size="xs" c="dimmed">Path: {formatPath(message.path)}</Text>
                <Text size="xs" c="dimmed">Time: {formatTimestamp(message.timestamp)}</Text>
                {message.attempts && (
                  <Text size="xs" c="dimmed">Attempts: {message.attempts}</Text>
                )}
              </Group>

              {message.status === 'pending' && (
                <Progress 
                  value={message.progress} 
                  size="sm" 
                  animated={message.status === 'pending'}
                />
              )}

              {message.error && (
                <Text size="xs" c="red">
                  Error: {message.error}
                </Text>
              )}
            </Stack>
          </Paper>
        ))
      )}
    </Stack>
  );
} 