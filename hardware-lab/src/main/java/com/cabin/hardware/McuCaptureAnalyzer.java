package com.cabin.hardware;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, offline raw-binary capture analysis; no vehicle field inference. */
final class McuCaptureAnalyzer {
    static final int MAX_CAPTURE_BYTES = 8 * 1024 * 1024;
    private McuCaptureAnalyzer() { }

    static JSONObject analyze(InputStream stream) throws IOException, JSONException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        FytMcuCodec decoder = new FytMcuCodec();
        long[] opcodes = new long[257]; // Empty payload has its own bucket.
        long[] firstOffsets = new long[8];
        int[] saved = {0};
        byte[] chunk = new byte[4096];
        int total = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Analysis cancelled");
            int count = stream.read(chunk, 0, Math.min(chunk.length, MAX_CAPTURE_BYTES - total + 1));
            if (count < 0) break;
            if (count == 0) throw new IOException("Capture stream made no progress");
            total += count;
            if (total > MAX_CAPTURE_BYTES) throw new IOException("Capture exceeds 8 MiB limit");
            digest.update(chunk, 0, count);
            decoder.accept(chunk, 0, count, frame -> {
                opcodes[frame.opcode() + 1]++;
                if (saved[0] < firstOffsets.length) firstOffsets[saved[0]++] = frame.offset;
            });
        }
        JSONArray histogram = new JSONArray();
        for (int i = 0; i < opcodes.length; i++) {
            if (opcodes[i] > 0) histogram.put(new JSONObject()
                .put("firstPayloadByte", i == 0 ? "empty" : String.format(Locale.ROOT, "%02X", i - 1))
                .put("frames", opcodes[i]));
        }
        JSONArray offsets = new JSONArray();
        for (int i = 0; i < saved[0]; i++) offsets.put(firstOffsets[i]);
        StringBuilder sha256 = new StringBuilder();
        for (byte value : digest.digest()) sha256.append(String.format(Locale.ROOT, "%02x", value & 255));
        return new JSONObject().put("schemaVersion", 1)
            .put("mode", "offline-raw-binary")
            .put("framingReference", "joying-uis7862-20230831")
            .put("hardwareCompatibility", "unverified")
            .put("direction", "unknown")
            .put("sha256", sha256.toString())
            .put("bytes", decoder.receivedBytes())
            .put("validFrames", decoder.frameCount())
            .put("checksumFailures", decoder.checksumFailures())
            .put("invalidLengths", decoder.invalidLengths())
            .put("discardedBytes", decoder.discardedBytes())
            .put("incompleteBytes", decoder.pendingBytes())
            .put("firstFrameOffsets", offsets)
            .put("firstPayloadByteCounts", histogram);
    }
}
