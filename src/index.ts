import { registerPlugin } from '@capacitor/core';

import type { P2PCounterPlugin } from './definitions';

const P2PCounter = registerPlugin<P2PCounterPlugin>('P2PCounter');

export * from './definitions';
export { P2PCounter };