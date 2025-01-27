import Foundation
import Capacitor
import CoreNFC
import WebRTC

struct WebRTCConfiguration {
    static let iceServers = [
        RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])
    ]
}

@objc(P2PCounterPlugin)
public class P2PCounterPlugin: CAPPlugin, NFCNDEFReaderSessionDelegate, NFCTagReaderSessionDelegate {
    private var nfcSession: NFCNDEFReaderSession?
    private var readerSession: NFCTagReaderSession?
    private let factory = RTCPeerConnectionFactory()
    private var peerConnections: [String: RTCPeerConnection] = [:]
    private var dataChannels: [String: RTCDataChannel] = [:]
    private var processedCodes: [String: Bool] = [:]
    private var lastPingTimes: [String: TimeInterval] = [:]
    private var keepaliveIntervals: [String: TimeInterval] = [:]
    private var rttHistory: [String: [TimeInterval]] = [:]
    private var packetLossCount: [String: Int] = [:]
    private var keepaliveTimer: Timer?
    private let minKeepaliveInterval: TimeInterval = 5 // 5 seconds
    private let maxKeepaliveInterval: TimeInterval = 30 // 30 seconds
    private let latencyThreshold: TimeInterval = 1 // 1 second
    private let packetLossThreshold: Double = 0.1 // 10%
    private let peerTimeout: TimeInterval = 15 // 15 seconds
    
    deinit {
        stopKeepaliveTimer()
    }
    
    @objc func startKeepalive(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.startKeepaliveTimer()
            call.resolve()
        }
    }
    
    @objc func stopKeepalive(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.stopKeepaliveTimer()
            call.resolve()
        }
    }
    
    private func startKeepaliveTimer() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = Timer.scheduledTimer(
            withTimeInterval: minKeepaliveInterval,
            repeats: true
        ) { [weak self] _ in
            self?.sendKeepaliveToAllPeers()
            self?.checkPeerTimeouts()
        }
    }
    
    private func stopKeepaliveTimer() {
        keepaliveTimer?.invalidate()
        keepaliveTimer = nil
    }
    
    private func sendKeepaliveToAllPeers() {
        let now = Date().timeIntervalSince1970 * 1000
        let ping: [String: Any] = [
            "type": "ping",
            "timestamp": now
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: ping),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            return
        }
        
        for (deviceId, channel) in dataChannels {
            // Get adaptive interval for this peer
            let interval = keepaliveIntervals[deviceId] ?? minKeepaliveInterval
            
            // Only send if it's time for this peer
            if let lastPing = lastPingTimes[deviceId],
               now - lastPing < interval * 1000 {
                continue
            }
            
            let buffer = RTCDataBuffer(
                data: jsonString.data(using: .utf8)!,
                isBinary: false
            )
            channel.sendData(buffer)
            lastPingTimes[deviceId] = now
        }
    }
    
    private func updatePeerMetrics(deviceId: String, rtt: TimeInterval) {
        // Update RTT history
        var history = rttHistory[deviceId] ?? []
        history.append(rtt)
        if history.count > 10 { // Keep last 10 measurements
            history.removeFirst()
        }
        rttHistory[deviceId] = history
        
        // Calculate average RTT
        let avgRtt = history.reduce(0, +) / TimeInterval(history.count)
        
        // Get packet loss rate
        let lossCount = packetLossCount[deviceId] ?? 0
        let lossRate = history.isEmpty ? 0 : Double(lossCount) / Double(history.count)
        
        // Adjust keepalive interval
        let currentInterval = keepaliveIntervals[deviceId] ?? minKeepaliveInterval
        var newInterval = currentInterval
        
        if avgRtt > latencyThreshold || lossRate > packetLossThreshold {
            newInterval = min(currentInterval * 2, maxKeepaliveInterval)
        } else if avgRtt < latencyThreshold / 2 && lossRate < packetLossThreshold / 2 {
            newInterval = max(currentInterval / 2, minKeepaliveInterval)
        }
        
        keepaliveIntervals[deviceId] = newInterval
    }
    
    private func checkPeerTimeouts() {
        let currentTime = Date().timeIntervalSince1970 * 1000
        var timedOutPeers: [String] = []
        
        for (deviceId, lastPing) in lastPingTimes {
            if currentTime - lastPing > peerTimeout * 1000 {
                timedOutPeers.append(deviceId)
            }
        }
        
        for deviceId in timedOutPeers {
            let timeoutEvent = [
                "event": "peerTimeout",
                "deviceId": deviceId
            ]
            notifyListeners("peerTimeout", data: timeoutEvent)
            disconnectPeer(deviceId)
        }
    }
    
    private func handlePing(deviceId: String, data: [String: Any]) {
        if let timestamp = data["timestamp"] as? TimeInterval {
            lastPingTimes[deviceId] = timestamp
            
            let pong: [String: Any] = [
                "type": "pong",
                "originalTimestamp": timestamp,
                "timestamp": Date().timeIntervalSince1970 * 1000
            ]
            
            if let jsonData = try? JSONSerialization.data(withJSONObject: pong),
               let jsonString = String(data: jsonData, encoding: .utf8),
               let channel = dataChannels[deviceId] {
                let buffer = RTCDataBuffer(
                    data: jsonString.data(using: .utf8)!,
                    isBinary: false
                )
                channel.sendData(buffer)
            }
        }
    }
    
    private func handlePong(deviceId: String, data: [String: Any]) {
        let now = Date().timeIntervalSince1970 * 1000
        if let originalTimestamp = data["originalTimestamp"] as? TimeInterval {
            let rtt = now - originalTimestamp
            lastPingTimes[deviceId] = now
            updatePeerMetrics(deviceId: deviceId, rtt: rtt)
        }
    }
    
    private func setupDataChannelEvents(channel: RTCDataChannel, deviceId: String) {
        let delegate = DataChannelDelegate { [weak self] message in
            guard let self = self,
                  let data = message.data(using: .utf8),
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return
            }
            
            if let type = dict["type"] as? String {
                switch type {
                case "ping":
                    self.handlePing(deviceId: deviceId, data: dict)
                    return
                case "pong":
                    self.handlePong(deviceId: deviceId, data: dict)
                    return
                default:
                    break
                }
            }
            
            guard let code = dict["code"] as? String,
                  let isPresent = dict["isPresent"] as? Bool,
                  let timestamp = dict["timestamp"] as? TimeInterval else {
                return
            }
            
            // Update attendance status
            self.processedCodes[code] = isPresent
        }
        
        channel.delegate = delegate
        // Store delegate to prevent deallocation
        delegates[deviceId] = delegate
    }
    
    @objc func startNFCDiscovery(_ call: CAPPluginCall) {
        guard NFCTagReaderSession.readingAvailable else {
            call.reject("NFC not available")
            return
        }
        
        readerSession = NFCTagReaderSession(pollingOption: .iso14443, delegate: self)
        readerSession?.begin()
        call.resolve()
    }
    
    @objc func stopNFCDiscovery(_ call: CAPPluginCall) {
        readerSession?.invalidate()
        readerSession = nil
        call.resolve()
    }
    
    @objc func sendNFCMessage(_ call: CAPPluginCall) {
        guard let message = call.getString("message") else {
            call.reject("Message is required")
            return
        }
        
        let payload = message.data(using: .utf8)!
        let record = NFCNDEFPayload(
            format: .nfcWellKnown,
            type: "T".data(using: .utf8)!,
            identifier: Data(),
            payload: payload
        )
        let message = NFCNDEFMessage(records: [record])
        
        // In real implementation, this would use NFCNDEFReaderSession's writeMessage
        // However, iOS requires special entitlements for NFC writing
        call.resolve()
    }
    
    @objc func initializeWebRTC(_ call: CAPPluginCall) {
        let config = RTCConfiguration()
        config.iceServers = WebRTCConfiguration.iceServers
        
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )
        
        peerConnection = factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: nil
        )
        
        call.resolve()
    }
    
    @objc func createPeerConnection(_ call: CAPPluginCall) {
        guard let deviceId = call.getString("deviceId"),
              let isInitiator = call.getBool("isInitiator") else {
            call.reject("Device ID and isInitiator are required")
            return
        }
        
        let config = RTCConfiguration()
        config.iceServers = WebRTCConfiguration.iceServers
        
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )
        
        let newConnection = factory.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        peerConnections[deviceId] = newConnection
        
        if isInitiator {
            let config = RTCDataChannelConfiguration()
            if let channel = newConnection.dataChannel(
                forLabel: "counter",
                configuration: config
            ) {
                dataChannels[deviceId] = channel
                setupDataChannelEvents(channel: channel, deviceId: deviceId)
            }
        }
        )
        
        let jsObject = JSObject()
        jsObject["event"] = "peerConnected"
        jsObject["deviceId"] = deviceId
        notifyListeners("peerConnected", data: jsObject)
        
        call.resolve()
    }
    
    @objc func sendCounter(_ call: CAPPluginCall) {
        guard let code = call.getString("code"),
              let isPresent = call.getBool("isPresent") else {
            call.reject("Code and isPresent status are required")
            return
        }
        
        // Update attendance status
        processedCodes[code] = isPresent
            
        let messageDict: [String: Any] = [
            "code": code,
            "timestamp": Date().timeIntervalSince1970 * 1000,
            "isPresent": isPresent
        ]
            
        if let jsonData = try? JSONSerialization.data(withJSONObject: messageDict),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            let buffer = RTCDataBuffer(
                data: jsonString.data(using: .utf8)!,
                isBinary: false
            )
                
            // Broadcast to all connected peers
            for channel in dataChannels.values {
                channel.sendData(buffer)
            }
        }
        
        call.resolve()
    }
    
    @objc func disconnectPeer(_ call: CAPPluginCall) {
        guard let deviceId = call.getString("deviceId") else {
            call.reject("Device ID is required")
            return
        }
        
        disconnectPeer(deviceId)
        call.resolve()
    }
    
    private func disconnectPeer(_ deviceId: String) {
        if let connection = peerConnections[deviceId] {
            connection.close()
            peerConnections.removeValue(forKey: deviceId)
        }
        
        if let channel = dataChannels[deviceId] {
            channel.close()
            dataChannels.removeValue(forKey: deviceId)
        }
        
        lastPingTimes.removeValue(forKey: deviceId)
    }
    
    @objc func getNetworkStats(_ call: CAPPluginCall) {
        let peerCount = peerConnections.count
        var totalLatency: TimeInterval = 0
        var totalLoss: Double = 0
        
        if peerCount > 0 {
            for deviceId in peerConnections.keys {
                if let history = rttHistory[deviceId], !history.isEmpty {
                    totalLatency += history.reduce(0, +) / TimeInterval(history.count)
                }
                
                let lossCount = packetLossCount[deviceId] ?? 0
                let history = rttHistory[deviceId] ?? []
                totalLoss += history.isEmpty ? 0 : Double(lossCount) / Double(history.count)
            }
            
            call.resolve([
                "averageLatency": totalLatency / TimeInterval(peerCount),
                "packetLoss": totalLoss / Double(peerCount),
                "keepaliveInterval": (keepaliveIntervals.values.reduce(0, +) / TimeInterval(peerCount)) * 1000
            ])
        } else {
            call.resolve([
                "averageLatency": 0,
                "packetLoss": 0,
                "keepaliveInterval": minKeepaliveInterval * 1000
            ])
        }
    }
    
    @objc func shareConnectionInfo(_ call: CAPPluginCall) {
        let connectionData: [String: Any] = [
            "deviceId": getDeviceId(),
            "timestamp": Date().timeIntervalSince1970,
            "webrtcConfig": webrtcConfig
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: connectionData),
              let base64Data = jsonData.base64EncodedString().data(using: .utf8) else {
            call.reject("Failed to encode connection data")
            return
        }
        
        let activityVC = UIActivityViewController(
            activityItems: ["p2pcounter://\(base64Data)"],
            applicationActivities: nil
        )
        
        DispatchQueue.main.async {
            self.bridge?.viewController?.present(activityVC, animated: true)
            call.resolve()
        }
    }

    @objc func receiveConnectionInfo(_ call: CAPPluginCall) {
        guard let sharedData = call.getString("sharedData") else {
            call.reject("No shared data provided")
            return
        }
        
        do {
            let cleanData = sharedData.replacingOccurrences(of: "p2pcounter://", with: "")
            guard let decodedData = Data(base64Encoded: cleanData),
                  let json = try JSONSerialization.jsonObject(with: decodedData) as? [String: Any],
                  let deviceId = json["deviceId"] as? String else {
                throw NSError(domain: "", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid data format"])
            }
            
            createPeerConnection(deviceId: deviceId, isInitiator: true)
            call.resolve()
        } catch {
            call.reject("Failed to process connection data", error)
        }
    }

    @objc func generateConnectionQR(_ call: CAPPluginCall) {
        let connectionData: [String: Any] = [
            "deviceId": getDeviceId(),
            "timestamp": Date().timeIntervalSince1970,
            "webrtcConfig": webrtcConfig
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: connectionData),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            call.reject("Failed to generate QR data")
            return
        }
        
        let qrFilter = CIFilter(name: "CIQRCodeGenerator")
        qrFilter?.setValue(jsonString.data(using: .utf8), forKey: "inputMessage")
        
        guard let qrImage = qrFilter?.outputImage else {
            call.reject("Failed to generate QR code")
            return
        }
        
        let transform = CGAffineTransform(scaleX: 10, y: 10)
        let scaledQrImage = qrImage.transformed(by: transform)
        
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaledQrImage, from: scaledQrImage.extent) else {
            call.reject("Failed to create QR image")
            return
        }
        
        let uiImage = UIImage(cgImage: cgImage)
        guard let imageData = uiImage.pngData() else {
            call.reject("Failed to encode QR image")
            return
        }
        
        let base64String = imageData.base64EncodedString()
        call.resolve(["qrData": "data:image/png;base64,\(base64String)"])
    }

    @objc func scanConnectionQR(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let scannerVC = QRScannerViewController { [weak self] result in
                switch result {
                case .success(let scannedData):
                    do {
                        let json = try JSONSerialization.jsonObject(with: scannedData.data(using: .utf8)!) as! [String: Any]
                        self?.createPeerConnection(deviceId: json["deviceId"] as! String, isInitiator: true)
                        call.resolve()
                    } catch {
                        call.reject("Invalid QR code data", error)
                    }
                case .failure(let error):
                    call.reject("Failed to scan QR code", error)
                }
            }
            self.bridge?.viewController?.present(scannerVC, animated: true)
        }
    }
    
    // MARK: - NFCNDEFReaderSessionDelegate
    
    public func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        guard let message = messages.first,
              let record = message.records.first,
              let payload = String(data: record.payload, encoding: .utf8) else {
            return
        }
        
        let jsObject = JSObject()
        jsObject["event"] = "nfcDiscovered"
        jsObject["message"] = payload
        notifyListeners("nfcDiscovered", data: jsObject)
    }
    
    public func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        let jsObject = JSObject()
        jsObject["error"] = error.localizedDescription
        notifyListeners("nfcError", data: jsObject)
    }
    
    // MARK: - NFCTagReaderSessionDelegate
    
    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        // Session started
    }
    
    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        // Handle errors
    }
    
    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { return }
        
        session.connect(to: tag) { error in
            if let error = error {
                print("Connection error: \(error)")
                return
            }
            
            // Send device info
            let deviceId = String(Date().timeIntervalSince1970)
            let data: [String: Any] = [
                "deviceId": deviceId,
                "timestamp": Date().timeIntervalSince1970
            ]
            
            self.notifyListeners("nfcDiscovered", data: data)
        }
    }
}

class DataChannelDelegate: NSObject, RTCDataChannelDelegate {
    private let onMessage: (String) -> Void
    
    init(onMessage: @escaping (String) -> Void) {
        self.onMessage = onMessage
        super.init()
    }
    
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        if !buffer.isBinary,
           let message = String(data: buffer.data, encoding: .utf8) {
            onMessage(message)
        }
    }
}