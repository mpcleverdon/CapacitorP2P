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
    addListener(eventName: 'nfcDiscovered', listenerFunc: (data: {
        deviceId: string;
        timestamp: number;
        systemDialogPresented?: boolean;
        tagType?: string;
    }) => void): Promise<void>;
    addListener(eventName: 'nfcPushComplete', listenerFunc: (data: {
        deviceId: string;
    }) => void): Promise<void>;
    addListener(eventName: 'nfcError', listenerFunc: (error: NFCError) => void): Promise<void>;
    getPlatform(): Promise<{
        platform: 'ios' | 'android' | 'web';
    }>;
}
