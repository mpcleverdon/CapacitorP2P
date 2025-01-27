import { Button, Card, Group, Image, Stack, Text } from '@mantine/core';
import { IconQrcode, IconShare } from '@tabler/icons-react';

interface ConnectionMethodsProps {
  onShare: () => void;
  onScanQR: () => void;
  qrCode?: string;
}

export function ConnectionMethods({ onShare, onScanQR, qrCode }: ConnectionMethodsProps) {
  return (
    <Card withBorder>
      <Stack>
        <Text fw={500}>Connection Methods</Text>
        <Group>
          <Button 
            leftSection={<IconShare size={20} />}
            onClick={onShare}
          >
            Share Connection
          </Button>
          <Button
            leftSection={<IconQrcode size={20} />}
            onClick={onScanQR}
          >
            Scan QR Code
          </Button>
        </Group>
        {qrCode && (
          <Image
            src={qrCode}
            alt="Connection QR Code"
            width={200}
            mx="auto"
          />
        )}
      </Stack>
    </Card>
  );
} 