package tools.bink.p2pcounter;

import android.util.Log;
import com.getcapacitor.JSObject;
import org.webrtc.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeerConnectionManager implements PeerConnection.Observer, DataChannel.Observer {
    private static final String TAG = "PeerConnectionManager";
    private final PeerConnectionFactory factory;
    private final Map<String, PeerConnection> peerConnections;
    private final Map<String, DataChannel> dataChannels;
    private final P2PCounterPlugin plugin;

    public PeerConnectionManager(PeerConnectionFactory factory, P2PCounterPlugin plugin) {
        this.factory = factory;
        this.plugin = plugin;
        this.peerConnections = new HashMap<>();
        this.dataChannels = new HashMap<>();
    }

    public void createPeerConnection(String deviceId, boolean isInitiator) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection peerConnection = factory.createPeerConnection(config, this);
        if (peerConnection == null) {
            Log.e(TAG, "Failed to create peer connection");
            return;
        }

        peerConnections.put(deviceId, peerConnection);

        if (isInitiator) {
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            DataChannel dataChannel = peerConnection.createDataChannel("counter", init);
            dataChannel.registerObserver(this);
            dataChannels.put(deviceId, dataChannel);
        }
    }

    public void handleRemoteSessionDescription(String deviceId, String sdp, String type) {
        PeerConnection peerConnection = peerConnections.get(deviceId);
        if (peerConnection == null) return;

        SessionDescription.Type sdpType = SessionDescription.Type.fromCanonicalForm(type);
        SessionDescription sessionDescription = new SessionDescription(sdpType, sdp);

        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {}

            @Override
            public void onSetSuccess() {
                if (sdpType == SessionDescription.Type.OFFER) {
                    peerConnection.createAnswer(new MediaConstraints(), new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sdp) {
                            peerConnection.setLocalDescription(new SdpObserver() {
                                @Override
                                public void onCreateSuccess(SessionDescription sessionDescription) {}
                                @Override
                                public void onSetSuccess() {}
                                @Override
                                public void onCreateFailure(String s) {}
                                @Override
                                public void onSetFailure(String s) {}
                            }, sdp);

                            // Notify JS layer about the answer
                            JSObject result = new JSObject();
                            result.put("type", "answer");
                            result.put("sdp", sdp.description);
                            result.put("deviceId", deviceId);
                            plugin.notifyWebRTCEvent("sessionDescription", result);
                        }

                        @Override
                        public void onSetSuccess() {}
                        @Override
                        public void onCreateFailure(String s) {}
                        @Override
                        public void onSetFailure(String s) {}
                    });
                }
            }

            @Override
            public void onCreateFailure(String s) {}
            @Override
            public void onSetFailure(String s) {}
        }, sessionDescription);
    }

    public void handleRemoteIceCandidate(String deviceId, String sdp, int sdpMLineIndex, String sdpMid) {
        PeerConnection peerConnection = peerConnections.get(deviceId);
        if (peerConnection == null) return;

        IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
        peerConnection.addIceCandidate(candidate);
    }

    public void sendData(String deviceId, String data) {
        DataChannel channel = dataChannels.get(deviceId);
        if (channel != null && channel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
            DataChannel.Buffer dcBuffer = new DataChannel.Buffer(buffer, false);
            channel.send(dcBuffer);
        }
    }

    // PeerConnection.Observer methods
    @Override
    public void onIceCandidate(IceCandidate iceCandidate) {
        JSObject candidate = new JSObject();
        candidate.put("sdp", iceCandidate.sdp);
        candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        candidate.put("sdpMid", iceCandidate.sdpMid);
        plugin.notifyWebRTCEvent("iceCandidate", candidate);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
        dataChannel.registerObserver(this);
        String deviceId = "pending"; // You'll need to map this to the correct device ID
        dataChannels.put(deviceId, dataChannel);
    }

    // DataChannel.Observer methods
    @Override
    public void onMessage(DataChannel.Buffer buffer) {
        byte[] bytes;
        if (buffer.binary) {
            return;
        }
        bytes = new byte[buffer.data.remaining()];
        buffer.data.get(bytes);
        String message = new String(bytes);

        JSObject data = new JSObject();
        data.put("message", message);
        plugin.notifyWebRTCEvent("dataChannelMessage", data);
    }

    @Override
    public void onStateChange() {
        // Handle state changes if needed
    }

    // Other required Observer methods with empty implementations
    @Override
    public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
    @Override
    public void onIceConnectionReceivingChange(boolean b) {}
    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
    @Override
    public void onAddStream(MediaStream mediaStream) {}
    @Override
    public void onRemoveStream(MediaStream mediaStream) {}
    @Override
    public void onRenegotiationNeeded() {}
    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
} 