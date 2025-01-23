import { registerPlugin } from '@capacitor/core';

import type { P2PCounterPlugin } from './definitions';

export type {
  P2PCounterPlugin,
  Attendee,
  NetworkStats,
  NFCDiscoveredEvent,
  NFCErrorEvent,
  NFCPushCompleteEvent,
  PeerEvent,
  CounterEvent,
  MeshDiscoveryEvent,
  MessageEvent,
  MessageStatusEvent
} from './definitions';

export const P2PCounter = registerPlugin<P2PCounterPlugin>('P2PCounter');