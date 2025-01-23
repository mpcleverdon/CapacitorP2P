package tools.bink.p2pcounter;

import android.util.Log;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageDeduplicator {
    private static final String TAG = "MessageDeduplicator";
    private final Map<String, Long> messageHashes;
    private final long MESSAGE_TTL = 30000; // 30 seconds TTL for messages
    private final int MAX_CACHE_SIZE = 1000;
    private Timer cleanupTimer;

    public MessageDeduplicator() {
        this.messageHashes = Collections.synchronizedMap(new LinkedHashMap<String, Long>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
        startCleanupTimer();
    }

    public boolean isNewMessage(String message, String sourceId) {
        String hash = generateMessageHash(message + sourceId);
        long now = System.currentTimeMillis();
        
        synchronized (messageHashes) {
            Long lastSeen = messageHashes.get(hash);
            if (lastSeen == null || now - lastSeen > MESSAGE_TTL) {
                messageHashes.put(hash, now);
                return true;
            }
            return false;
        }
    }

    private String generateMessageHash(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error generating message hash", e);
            // Fallback to simple hash if SHA-256 is not available
            return String.valueOf(message.hashCode());
        }
    }

    private void startCleanupTimer() {
        cleanupTimer = new Timer(true);
        cleanupTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                cleanup();
            }
        }, MESSAGE_TTL, MESSAGE_TTL);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        synchronized (messageHashes) {
            messageHashes.entrySet().removeIf(entry -> 
                now - entry.getValue() > MESSAGE_TTL
            );
        }
    }

    public void stop() {
        if (cleanupTimer != null) {
            cleanupTimer.cancel();
            cleanupTimer = null;
        }
    }
} 