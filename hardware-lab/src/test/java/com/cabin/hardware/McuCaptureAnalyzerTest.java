package com.cabin.hardware;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class McuCaptureAnalyzerTest {
    @Test public void packagedExampleReportsCorruptionTruncationAndOffsets() throws Exception {
        JSONObject report;
        try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open("mcu-example.bin")) {
            report = McuCaptureAnalyzer.analyze(input);
        }
        assertEquals(38, report.getLong("bytes"));
        assertEquals("fca9ce65932dfd56221df57e6295b8aa58ce1d92d9b2b4da9f259698b723e3e9", report.getString("sha256"));
        assertEquals(3, report.getLong("validFrames"));
        assertEquals(1, report.getLong("checksumFailures"));
        assertEquals(1, report.getLong("invalidLengths"));
        assertEquals(13, report.getLong("discardedBytes"));
        assertEquals(5, report.getLong("incompleteBytes"));
        assertEquals("[2,9,28]", report.getJSONArray("firstFrameOffsets").toString());
        assertEquals("unverified", report.getString("hardwareCompatibility"));
        assertEquals("unknown", report.getString("direction"));
        assertEquals(3, report.getJSONArray("firstPayloadByteCounts").length());
    }

    @Test public void emptyCaptureHasKnownHashAndNoSyntheticTelemetry() throws Exception {
        JSONObject report = McuCaptureAnalyzer.analyze(new ByteArrayInputStream(new byte[0]));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", report.getString("sha256"));
        assertEquals(0, report.getLong("validFrames"));
        assertEquals(0, report.getJSONArray("firstPayloadByteCounts").length());
    }

    @Test public void capsCaptureAndStopsAfterOneExcessByte() {
        final long[] consumed = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { consumed[0]++; return 0; }
            @Override public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 0);
                consumed[0] += length; return length;
            }
        };
        assertThrows(IOException.class, () -> McuCaptureAnalyzer.analyze(endless));
        assertEquals(McuCaptureAnalyzer.MAX_CAPTURE_BYTES + 1L, consumed[0]);
    }

    @Test public void allowsExactLimitAndDoesNotRetainEveryFrame() throws Exception {
        byte[] bytes = new byte[McuCaptureAnalyzer.MAX_CAPTURE_BYTES];
        byte[] frame = FytMcuCodecTest.hex("88 55 00 00 00");
        for (int i = 0; i <= bytes.length - frame.length; i += frame.length)
            System.arraycopy(frame, 0, bytes, i, frame.length);
        JSONObject report = McuCaptureAnalyzer.analyze(new ByteArrayInputStream(bytes));
        assertEquals(bytes.length / 5, report.getLong("validFrames"));
        assertEquals(8, report.getJSONArray("firstFrameOffsets").length());
        assertEquals(1, report.getJSONArray("firstPayloadByteCounts").length());
    }

    @Test public void ioFailureDoesNotReturnAPartialSuccessfulReport() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("unreadable"); }
        };
        assertThrows(IOException.class, () -> McuCaptureAnalyzer.analyze(broken));
    }

    @Test public void cancelledWorkDoesNotReadInput() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                () -> McuCaptureAnalyzer.analyze(new ByteArrayInputStream(new byte[0])));
        } finally { Thread.interrupted(); }
    }
}
