import type { PluginListenerHandle } from '@capacitor/core';
export interface Peer {
    deviceId: string;
    connection: RTCPeerConnection;
    dataChannel: RTCDataChannel;
    latency: number;
    packetLoss: number;
}
export interface CounterData {
    code: string;
    isPresent: boolean;
    eventId: string;
    isManual: boolean;
    timestamp: number;
}
export interface InitialState {
    type: 'initial_state';
    attendees: Record<string, CounterData>;
}
export interface NFCError {
    code: number;
    message: string;
    type?: 'ios' | 'android';
}
export interface P2PCounterPlugin {
    startNFCDiscovery(): Promise<void>;
    stopNFCDiscovery(): Promise<void>;
    sendNFCMessage(options: {
        message: string;
    }): Promise<void>;
    initializeWebRTC(): Promise<void>;
    createPeerConnection(options: {
        deviceId: string;
        isInitiator: boolean;
    }): Promise<void>;
    sendCounter(options: {
        code: string;
        isPresent: boolean;
        eventId: string;
    }): Promise<void>;
    sendInitialState(options: {
        deviceId: string;
        state: Record<string, CounterData>;
    }): Promise<void>;
    disconnectPeer(options: {
        deviceId: string;
    }): Promise<void>;
    startKeepalive(): Promise<void>;
    stopKeepalive(): Promise<void>;
    getNetworkStats(): Promise<{
        averageLatency: number;
        packetLoss: number;
        keepaliveInterval: number;
    }>;
    addListener(eventName: 'nfcDiscovered', listenerFunc: (event: NFCDiscoveredEvent) => void): PluginListenerHandle;
    addListener(eventName: 'nfcError', listenerFunc: (event: NFCErrorEvent) => void): PluginListenerHandle;
    addListener(eventName: 'nfcPushComplete', listenerFunc: (event: NFCPushCompleteEvent) => void): PluginListenerHandle;
    addListener(eventName: 'counterReceived', listenerFunc: (event: CounterEvent) => void): PluginListenerHandle;
    addListener(eventName: 'peerConnected', listenerFunc: (event: PeerEvent) => void): PluginListenerHandle;
    addListener(eventName: 'peerTimeout', listenerFunc: (event: PeerEvent) => void): PluginListenerHandle;
    addListener(eventName: 'meshDiscovery', listenerFunc: (event: MeshDiscoveryEvent) => void): PluginListenerHandle;
    addListener(eventName: 'meshMessage', listenerFunc: (event: MessageEvent) => void): PluginListenerHandle;
    addListener(eventName: 'messageStatus', listenerFunc: (event: MessageStatusEvent) => void): PluginListenerHandle;
    getPlatform(): Promise<{
        platform: 'ios' | 'android' | 'web';
    }>;
}
export interface Attendee {
    code: string;
    isPresent: boolean;
    timestamp?: number;
    eventId?: string;
    isManual?: boolean;
}
export interface NetworkStats {
    averageLatency: number;
    packetLoss: number;
    keepaliveInterval: number;
    messageCount?: number;
    networkStrength?: number;
}
export interface NFCDiscoveredEvent {
    deviceId: string;
    systemDialogPresented?: boolean;
}
export interface NFCErrorEvent {
    code: number;
    message?: string;
}
export interface NFCPushCompleteEvent {
    deviceId: string;
}
export interface PeerEvent {
    deviceId: string;
    isInitiator?: boolean;
}
export interface CounterEvent {
    type?: 'initial_state';
    code?: string;
    isPresent?: boolean;
    timestamp?: number;
    attendees?: Record<string, Attendee>;
}
export interface MeshDiscoveryEvent {
    data: string;
}
export interface MessageEvent {
    data: string;
}
export interface MessageStatusEvent {
    messageId: string;
    status: 'pending' | 'success' | 'failed';
    error?: string;
    attempts?: number;
}
export interface P2PCounterPluginEvents {
    nfcDiscovered: NFCDiscoveredEvent;
    nfcError: NFCErrorEvent;
    nfcPushComplete: NFCPushCompleteEvent;
    peerConnected: PeerEvent;
    peerTimeout: PeerEvent;
    counterReceived: CounterEvent;
    meshDiscovery: MeshDiscoveryEvent;
    meshMessage: MessageEvent;
    messageStatus: MessageStatusEvent;
}
