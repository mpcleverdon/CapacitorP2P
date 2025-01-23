package tools.bink.p2pcounter;

import android.util.Log;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MessagePriorityManager {
    private static final String TAG = "MessagePriorityManager";
    private final PriorityBlockingQueue<PrioritizedMessage> messageQueue;
    private final Map<String, Set<String>> messageAcks;
    private final Map<String, PrioritizedMessage> pendingMessages;
    private final AtomicInteger sequenceNumber;
    private Timer retryTimer;
    private static final long RETRY_INTERVAL = 1000; // 1 second
    private static final int MAX_RETRIES = 3;

    public enum Priority {
        VERY_HIGH,
        HIGH,
        MEDIUM,
        LOW
    }

    public static class PrioritizedMessage implements Comparable<PrioritizedMessage> {
        public final String message;
        public final Priority priority;
        public final Set<String> targetPeers;
        final String messageId;
        final long timestamp;
        int retryCount;
        final int sequence;

        public PrioritizedMessage(String message, Priority priority, Set<String> targetPeers) {
            this.message = message;
            this.priority = priority;
            this.targetPeers = targetPeers;
            this.messageId = UUID.randomUUID().toString();
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
            this.sequence = 0; // Assuming a default sequence number
        }

        @Override
        public int compareTo(PrioritizedMessage other) {
            // First compare by priority
            int priorityCompare = priority.compareTo(other.priority);
            if (priorityCompare != 0) return priorityCompare;

            // Then by retry count (more retries = higher priority)
            int retryCompare = other.retryCount - retryCount;
            if (retryCompare != 0) return retryCompare;

            // Finally by sequence number
            return sequence - other.sequence;
        }
    }

    public MessagePriorityManager() {
        this.messageQueue = new PriorityBlockingQueue<>();
        this.messageAcks = new HashMap<>();
        this.pendingMessages = new HashMap<>();
        this.sequenceNumber = new AtomicInteger(0);
        startRetryTimer();
    }

    public void queueMessage(String message, Priority priority, Set<String> targetPeers) {
        String messageId = generateMessageId();
        PrioritizedMessage prioritizedMessage = new PrioritizedMessage(
            message, priority, targetPeers
        );
        messageQueue.offer(prioritizedMessage);
        pendingMessages.put(messageId, prioritizedMessage);
        messageAcks.put(messageId, new HashSet<>(targetPeers));
    }

    public PrioritizedMessage getNextMessage() {
        return messageQueue.poll();
    }

    public void handleAck(String messageId, String peerId) {
        Set<String> acks = messageAcks.get(messageId);
        if (acks != null) {
            acks.add(peerId);
            PrioritizedMessage message = pendingMessages.get(messageId);
            if (message != null && acks.containsAll(message.targetPeers)) {
                // Message fully acknowledged
                pendingMessages.remove(messageId);
                messageAcks.remove(messageId);
            }
        }
    }

    private void startRetryTimer() {
        retryTimer = new Timer(true);
        retryTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkForRetries();
            }
        }, RETRY_INTERVAL, RETRY_INTERVAL);
    }

    private void checkForRetries() {
        long now = System.currentTimeMillis();
        List<PrioritizedMessage> messagesToRetry = new ArrayList<>();

        // Find messages that need retry
        for (PrioritizedMessage message : pendingMessages.values()) {
            if (now - message.timestamp > RETRY_INTERVAL * (message.retryCount + 1)) {
                Set<String> acks = messageAcks.get(message.messageId);
                Set<String> remainingPeers = new HashSet<>(message.targetPeers);
                if (acks != null) {
                    remainingPeers.removeAll(acks);
                }

                if (!remainingPeers.isEmpty() && message.retryCount < MAX_RETRIES) {
                    message.retryCount++;
                    // Create new message for remaining peers
                    PrioritizedMessage retryMessage = new PrioritizedMessage(
                        message.message,
                        message.priority,
                        remainingPeers
                    );
                    retryMessage.retryCount = message.retryCount;
                    messagesToRetry.add(retryMessage);
                }
            }
        }

        // Queue retry messages
        for (PrioritizedMessage message : messagesToRetry) {
            messageQueue.offer(message);
        }
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString();
    }

    public void stop() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
    }

    public boolean hasPendingMessages() {
        return !messageQueue.isEmpty() || !pendingMessages.isEmpty();
    }

    public int getPendingCount() {
        return messageQueue.size() + pendingMessages.size();
    }
} 