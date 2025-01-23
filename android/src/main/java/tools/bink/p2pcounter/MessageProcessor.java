package tools.bink.p2pcounter;

import android.util.Log;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.json.JSONException;

public class MessageProcessor {
    private static final String TAG = "MessageProcessor";
    private static final int MAX_CHUNK_SIZE = 16000; // WebRTC data channel recommended max
    private static final int COMPRESSION_THRESHOLD = 1000; // Bytes
    private final Map<String, MessageAssembler> messageAssemblers;
    private final String localDeviceId;

    private static class MessageAssembler {
        final Map<Integer, byte[]> chunks;
        final int totalChunks;
        final long timestamp;
        final String messageId;

        MessageAssembler(int totalChunks, String messageId) {
            this.chunks = new HashMap<>();
            this.totalChunks = totalChunks;
            this.timestamp = System.currentTimeMillis();
            this.messageId = messageId;
        }

        boolean isComplete() {
            return chunks.size() == totalChunks;
        }

        byte[] assembleMessage() {
            ByteBuffer buffer = ByteBuffer.allocate(totalChunks * MAX_CHUNK_SIZE);
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    buffer.put(chunk);
                }
            }
            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }
    }

    public MessageProcessor(String localDeviceId) {
        this.localDeviceId = localDeviceId;
        this.messageAssemblers = new ConcurrentHashMap<>();
    }

    public List<JSONObject> processOutgoingMessage(String message) {
        try {
            byte[] messageBytes = message.getBytes();
            byte[] processedData = messageBytes;

            // Compress if message is large enough
            boolean isCompressed = false;
            if (messageBytes.length > COMPRESSION_THRESHOLD) {
                processedData = compress(messageBytes);
                isCompressed = true;
            }

            // Fragment if necessary
            List<JSONObject> chunks = new ArrayList<>();
            String messageId = UUID.randomUUID().toString();
            int totalChunks = (int) Math.ceil(processedData.length / (double) MAX_CHUNK_SIZE);

            for (int i = 0; i < totalChunks; i++) {
                int start = i * MAX_CHUNK_SIZE;
                int end = Math.min(start + MAX_CHUNK_SIZE, processedData.length);
                byte[] chunk = Arrays.copyOfRange(processedData, start, end);

                JSONObject chunkObj = new JSONObject();
                chunkObj.put("type", "messageChunk");
                chunkObj.put("messageId", messageId);
                chunkObj.put("chunkIndex", i);
                chunkObj.put("totalChunks", totalChunks);
                chunkObj.put("compressed", isCompressed);
                chunkObj.put("data", Base64.getEncoder().encodeToString(chunk));
                chunkObj.put("sourceId", localDeviceId);
                chunks.add(chunkObj);
            }

            return chunks;
        } catch (Exception e) {
            Log.e(TAG, "Error processing outgoing message", e);
            return Collections.emptyList();
        }
    }

    public String processIncomingChunk(JSONObject chunk) throws JSONException {
        String messageId = chunk.getString("messageId");
        int chunkIndex = chunk.getInt("chunkIndex");
        int totalChunks = chunk.getInt("totalChunks");
        boolean isCompressed = chunk.getBoolean("compressed");
        byte[] data = Base64.getDecoder().decode(chunk.getString("data"));

        MessageAssembler assembler = messageAssemblers.computeIfAbsent(
            messageId, 
            k -> new MessageAssembler(totalChunks, messageId)
        );

        assembler.chunks.put(chunkIndex, data);

        if (assembler.isComplete()) {
            byte[] assembled = assembler.assembleMessage();
            messageAssemblers.remove(messageId);

            // Decompress if necessary
            if (isCompressed) {
                assembled = decompress(assembled);
            }

            return new String(assembled);
        }

        return null;
    }

    private byte[] compress(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteBuffer buffer = ByteBuffer.allocate(data.length);
        byte[] temp = new byte[1024];

        while (!deflater.finished()) {
            int count = deflater.deflate(temp);
            buffer.put(temp, 0, count);
        }

        deflater.end();
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private byte[] decompress(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);

        ByteBuffer buffer = ByteBuffer.allocate(data.length * 2);
        byte[] temp = new byte[1024];

        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(temp);
                buffer.put(temp, 0, count);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decompressing data", e);
        }

        inflater.end();
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        messageAssemblers.entrySet().removeIf(entry -> 
            now - entry.getValue().timestamp > 30000 // Remove incomplete messages after 30 seconds
        );
    }
} 