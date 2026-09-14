package com.cabin.hardware.replacement;

import com.cabin.hardware.FytMcuCodec;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ReplacementRuntimeTest {
    static final class Feed implements McuTransport {
        final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        volatile boolean closed;
        public int read(byte[] buffer) throws IOException {
            try { byte[] next = queue.take(); if (closed) throw new IOException("closed"); if (next.length == 0) return -1; System.arraycopy(next,0,buffer,0,next.length); return next.length; }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
        }
        public void write(byte[] ignored) { fail("Runtime must never transmit"); }
        public void close() { closed = true; queue.offer(new byte[0]); }
    }
    @Test public void receivesCanExpiresFieldsAndClearsOnDisconnect() throws Exception {
        AtomicLong clock = new AtomicLong(1); Feed feed = new Feed(); CountDownLatch decoded = new CountDownLatch(1);
        ReplacementRuntime runtime = new ReplacementRuntime(new HondaCanDecoder(0x10012a,false,false),clock::get);
        runtime.onChange(() -> { if (runtime.snapshot().containsKey(37)) decoded.countDown(); });
        runtime.start(feed,"test receive"); feed.queue.add(FytMcuCodec.encode(new byte[] {(byte)0xe3,0x24,1,0x40}));
        assertTrue(decoded.await(2,TimeUnit.SECONDS)); assertEquals(Integer.valueOf(1),runtime.snapshot().get(37));
        clock.set(5001); assertTrue(runtime.snapshot().isEmpty());
        feed.queue.add(new byte[0]); assertTrue(runtime.await(2000)); assertEquals(ReplacementRuntime.Status.COMPLETE,runtime.status());
        assertTrue(runtime.snapshot().isEmpty()); assertEquals(1L,runtime.diagnostics().get("frames")); assertTrue(feed.closed); runtime.close();
    }
    @Test public void brokenChecksumNeverReachesVehicleStateAndReconnectDropsPartialFrame() throws Exception {
        ReplacementRuntime runtime = new ReplacementRuntime(null,() -> 0L);
        byte[] broken = FytMcuCodec.encode(new byte[] {(byte)0xe3,0x24,1,0x40}); broken[broken.length-1] ^= 1;
        ByteArrayOutputStream capture = new ByteArrayOutputStream(); capture.write(broken); capture.write(new byte[] {(byte)0x88,0x55,0});
        runtime.start(new ReplayTransport(new ByteArrayInputStream(capture.toByteArray())),"first"); assertTrue(runtime.await(2000));
        assertEquals(1L,runtime.diagnostics().get("checksumFailures")); assertEquals(0L,runtime.diagnostics().get("frames"));
        runtime.start(new ReplayTransport(new ByteArrayInputStream(FytMcuCodec.encode(new byte[] {0x61,0x12,0x34}))),"second"); assertTrue(runtime.await(2000));
        assertEquals(1L,runtime.diagnostics().get("frames")); assertEquals(0L,runtime.diagnostics().get("checksumFailures")); assertTrue(runtime.snapshot().isEmpty()); runtime.close();
    }
    @Test public void closeCancelsBlockedReaderAndCannotRestart() throws Exception {
        Feed feed = new Feed(); ReplacementRuntime runtime = new ReplacementRuntime(null,() -> 0L); runtime.start(feed,"test");
        assertThrows(IllegalStateException.class, () -> runtime.start(new Feed(),"duplicate"));
        runtime.close(); assertTrue(runtime.await(2000)); assertTrue(feed.closed); assertEquals(ReplacementRuntime.Status.CLOSED,runtime.status());
        assertThrows(IllegalStateException.class, () -> runtime.start(new Feed(),"closed"));
    }
    @Test public void readFailureIsReportedAndListenerFailureDoesNotKillReceive() throws Exception {
        ReplacementRuntime runtime = new ReplacementRuntime(null,() -> 0L); runtime.onChange(() -> { throw new IllegalStateException("UI gone"); });
        runtime.start(new McuTransport() {
            public int read(byte[] ignored) throws IOException { throw new IOException("disconnected"); }
            public void write(byte[] ignored) { fail(); } public void close() { }
        },"failed");
        assertTrue(runtime.await(2000)); assertEquals(ReplacementRuntime.Status.FAILED,runtime.status());
        assertTrue(runtime.diagnostics().get("error").toString().contains("disconnected")); runtime.close();
    }
    private static byte[] radioCapture(boolean corrupt) throws IOException {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        int[][] values = {{6,0},{1,1},{2,1},{3,0}};
        for (int i=0;i<values.length;i++) {
            if (corrupt && i==2) { byte[] broken=FytMcuCodec.encode(new byte[] {9}); broken[broken.length-1]^=1; capture.write(broken); }
            capture.write(FytMcuCodec.encode(new byte[] {1,3,(byte)values[i][0],(byte)values[i][1]}));
        }
        return capture.toByteArray();
    }
    @Test public void radioRequiresOptInAndEofKeepsOnlyHistoricalFeedback() throws Exception {
        for(boolean enabled:new boolean[] {false,true}) {
            try(ReplacementRuntime runtime=new ReplacementRuntime(null,()->0L,enabled)) {
                runtime.start(new ReplayTransport(new ByteArrayInputStream(radioCapture(false))),"radio replay");
                assertTrue(runtime.await(2000)); assertTrue(runtime.radioSnapshot().isEmpty());
                Map<?,?> history=(Map<?,?>)runtime.diagnostics().get("lastDecodedRadioFieldsHistorical");
                if(enabled) assertEquals(10100,history.get("1")); else assertTrue(history.isEmpty());
            }
        }
    }
    @Test public void corruptFrameCannotJoinRadioFrequencyFragments() throws Exception {
        try(ReplacementRuntime runtime=new ReplacementRuntime(null,()->0L,true)) {
            runtime.start(new ReplayTransport(new ByteArrayInputStream(radioCapture(true))),"corrupt radio replay");
            assertTrue(runtime.await(2000));
            assertEquals(1L,runtime.diagnostics().get("checksumFailures"));
            assertFalse(((Map<?,?>)runtime.diagnostics().get("lastDecodedRadioFieldsHistorical")).containsKey("1"));
        }
    }
    @Test public void radioFeedbackExpiresAndNeverTransmits() throws Exception {
        AtomicLong clock=new AtomicLong(); Feed feed=new Feed(); CountDownLatch decoded=new CountDownLatch(1);
        try(ReplacementRuntime runtime=new ReplacementRuntime(null,clock::get,true)) {
            runtime.onChange(()->{if(runtime.radioSnapshot().containsKey(1)) decoded.countDown();});
            runtime.start(feed,"radio receive"); feed.queue.add(radioCapture(false));
            assertTrue(decoded.await(2,TimeUnit.SECONDS)); assertEquals(Integer.valueOf(10100),runtime.radioSnapshot().get(1));
            clock.set(5000); assertTrue(runtime.radioSnapshot().isEmpty());
        }
        assertTrue(feed.closed);
    }
    @Test public void indexedPresetsRetainMultipleChannelsAndExpireIndependently() throws Exception {
        AtomicLong clock=new AtomicLong(); Feed feed=new Feed(); java.util.concurrent.BlockingQueue<Integer> changes=new LinkedBlockingQueue<>();
        try(ReplacementRuntime runtime=new ReplacementRuntime(null,clock::get,true)) {
            runtime.onChange(()->changes.offer(runtime.radioPresetSnapshot().size())); runtime.start(feed,"preset receive");
            java.util.function.IntFunction<byte[]> preset = selector -> {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                for(int[] pair:new int[][] {{0x10,selector},{1,11},{2,1},{3,0}}) {
                    byte[] frame=FytMcuCodec.encode(new byte[] {1,3,(byte)pair[0],(byte)pair[1]}); bytes.write(frame,0,frame.length);
                }
                return bytes.toByteArray();
            };
            feed.queue.add(preset.apply(1)); assertEquals(Integer.valueOf(1),changes.poll(2,TimeUnit.SECONDS));
            clock.set(1000); feed.queue.add(preset.apply(2)); assertEquals(Integer.valueOf(2),changes.poll(2,TimeUnit.SECONDS));
            assertEquals(2,runtime.radioCached(4).size());
            assertArrayEquals(new int[] {65536,10100},runtime.radioCached(4).get(0).integers());
            assertArrayEquals(new int[] {65537,10100},runtime.radioCached(4).get(1).integers());
            clock.set(5000); assertEquals(1,runtime.radioCached(4).size());
            assertArrayEquals(new int[] {65537,10100},runtime.radioCached(4).get(0).integers());
            feed.queue.add(new byte[0]); assertTrue(runtime.await(2000)); assertTrue(runtime.radioCached(4).isEmpty());
            assertEquals(2,((Map<?,?>)runtime.diagnostics().get("lastDecodedRadioPresetsHistorical")).size());
        }
    }
}
