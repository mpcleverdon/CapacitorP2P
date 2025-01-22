import { WebPlugin } from '@capacitor/core';
import type { P2PCounterPlugin } from './definitions';

export class P2PCounterWeb extends WebPlugin implements P2PCounterPlugin {
  constructor() {
    super({
      name: 'P2PCounter',
      platforms: ['web']
    });
  }

  async startNFCDiscovery(): Promise<void> {
    throw new Error('NFC not supported in web browser');
  }

  async stopNFCDiscovery(): Promise<void> {
    throw new Error('NFC not supported in web browser');
  }

  async sendNFCMessage(): Promise<void> {
    throw new Error('NFC not supported in web browser');
  }

  async initializeWebRTC(): Promise<void> {
    // Web implementation would go here
    throw new Error('Method not implemented.');
  }

  async createPeerConnection(): Promise<void> {
    // Web implementation would go here
    throw new Error('Method not implemented.');
  }

  async sendCounter(): Promise<void> {
    // Web implementation would go here
    throw new Error('Method not implemented.');
  }
}