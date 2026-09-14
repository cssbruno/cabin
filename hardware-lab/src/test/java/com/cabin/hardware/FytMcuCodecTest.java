package com.cabin.hardware;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class FytMcuCodecTest {
    static byte[] hex(String value) {
        String[] words = value.trim().split(" +");
        byte[] bytes = new byte[words.length];
        for (int i = 0; i < words.length; i++) bytes[i] = (byte) Integer.parseInt(words[i], 16);
        return bytes;
    }
    private static final byte[] WIRE = hex("88 55 00 02 C1 04 C7");

    @Test public void matchesManuallyCalculatedWireVector() {
        assertArrayEquals(WIRE, FytMcuCodec.encode(hex("C1 04")));
        assertArrayEquals(hex("88 55 00 03 80 FF 00 7C"), FytMcuCodec.encode(hex("80 FF 00")));
        assertArrayEquals(hex("88 55 00 00 00"), FytMcuCodec.encode(new byte[0]));
    }

    @Test public void allChunkBoundariesProduceOneCompleteFrame() {
        for (int split = 0; split <= WIRE.length; split++) {
            FytMcuCodec decoder = new FytMcuCodec();
            List<FytMcuCodec.Frame> frames = new ArrayList<>();
            decoder.accept(WIRE, 0, split, frames::add);
            if (split < WIRE.length) assertTrue(frames.isEmpty());
            decoder.accept(WIRE, split, WIRE.length - split, frames::add);
            assertEquals(1, frames.size());
            assertArrayEquals(hex("C1 04"), frames.get(0).payload());
            assertEquals(0, frames.get(0).offset);
            assertEquals(0xC1, frames.get(0).opcode());
            assertEquals(0, decoder.pendingBytes());
        }
    }

    @Test public void handlesOverlappingHeaderNoiseAndAdjacentFrames() {
        byte[] wire = hex("00 88 88 55 00 02 C1 04 C7 88 55 00 00 00");
        FytMcuCodec decoder = new FytMcuCodec();
        List<FytMcuCodec.Frame> frames = new ArrayList<>();
        decoder.accept(wire, 0, wire.length, frames::add);
        assertEquals(2, frames.size());
        assertEquals(2, frames.get(0).offset);
        assertEquals(9, frames.get(1).offset);
        assertEquals(-1, frames.get(1).opcode());
        assertEquals(2, decoder.discardedBytes());
    }

    @Test public void rejectsBadChecksumAndOversizedLengthThenRecovers() {
        byte[] wire = hex("88 55 00 02 C1 04 C6 88 55 FF FF 88 55 00 02 C1 04 C7");
        FytMcuCodec decoder = new FytMcuCodec();
        List<FytMcuCodec.Frame> frames = new ArrayList<>();
        decoder.accept(wire, 0, wire.length, frames::add);
        assertEquals(1, frames.size());
        assertEquals(11, frames.get(0).offset);
        assertEquals(1, decoder.checksumFailures());
        assertEquals(1, decoder.invalidLengths());
        assertEquals(11, decoder.discardedBytes());
    }

    @Test public void recoversFrameInsideCorruptCandidateWithoutDroppingItsHeader() {
        byte[] wire = hex("88 55 00 07 88 55 00 02 C1 04 C7 01");
        FytMcuCodec decoder = new FytMcuCodec();
        List<FytMcuCodec.Frame> frames = new ArrayList<>();
        decoder.accept(wire, 0, wire.length, frames::add);
        assertEquals(1, frames.size());
        assertEquals(4, frames.get(0).offset);
        assertEquals(1, decoder.checksumFailures());
        assertEquals(5, decoder.discardedBytes());
    }

    @Test public void maximumPayloadUsesBigEndianLengthAndPreservesEmbeddedHeaders() {
        byte[] payload = new byte[512];
        Arrays.fill(payload, (byte) 0xFF);
        payload[100] = (byte) 0x88; payload[101] = 0x55;
        byte[] wire = FytMcuCodec.encode(payload);
        assertEquals(2, wire[2]); assertEquals(0, wire[3]);
        FytMcuCodec decoder = new FytMcuCodec();
        List<FytMcuCodec.Frame> frames = new ArrayList<>();
        for (byte value : wire) decoder.accept(new byte[]{value}, 0, 1, frames::add);
        assertEquals(1, frames.size());
        assertArrayEquals(payload, frames.get(0).payload());
        assertEquals(0, decoder.discardedBytes());
        assertThrows(IllegalArgumentException.class, () -> FytMcuCodec.encode(new byte[513]));
    }

    @Test public void incompleteFrameIsNeverEmittedAndNewConnectionStartsEmpty() {
        FytMcuCodec decoder = new FytMcuCodec();
        decoder.accept(WIRE, 0, WIRE.length - 1, frame -> fail("Incomplete frame emitted"));
        assertEquals(6, decoder.pendingBytes());
        assertEquals(0, decoder.frameCount());
        assertEquals(0, new FytMcuCodec().pendingBytes());
    }

    @Test public void consumerCannotMutateSubsequentFrameContents() {
        FytMcuCodec decoder = new FytMcuCodec();
        List<FytMcuCodec.Frame> frames = new ArrayList<>();
        byte[] input = WIRE.clone();
        decoder.accept(input, 0, input.length, frames::add);
        Arrays.fill(input, (byte) 0);
        byte[] copy = frames.get(0).payload(); copy[0] = 0;
        assertArrayEquals(hex("C1 04"), frames.get(0).payload());
    }

    @Test public void validatesSlicesBeforeChangingState() {
        FytMcuCodec decoder = new FytMcuCodec();
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.accept(WIRE, -1, 1, f -> {}));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.accept(WIRE, 1, Integer.MAX_VALUE, f -> {}));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.accept(WIRE, 0, -1, f -> {}));
        assertThrows(NullPointerException.class, () -> decoder.accept(WIRE, 0, 1, null));
        assertEquals(0, decoder.receivedBytes());
    }

    @Test public void largeNoisyInputIsBoundedAndIndependentOfChunking() throws Exception {
        Random random = new Random(891);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 5000; i++) {
            byte[] noise = new byte[19]; random.nextBytes(noise);
            out.write(noise); out.write(WIRE);
        }
        byte[] input = out.toByteArray();
        FytMcuCodec whole = new FytMcuCodec(), chunks = new FytMcuCodec();
        List<Long> a = new ArrayList<>(), b = new ArrayList<>();
        whole.accept(input, 0, input.length, frame -> a.add(frame.offset));
        for (int i = 0; i < input.length;) {
            int count = Math.min(input.length - i, 1 + random.nextInt(1000));
            chunks.accept(input, i, count, frame -> b.add(frame.offset));
            assertTrue(chunks.pendingBytes() <= 516); i += count;
        }
        assertTrue(a.size() > 4900);
        assertEquals(a, b);
        assertEquals(whole.discardedBytes(), chunks.discardedBytes());
        assertEquals(whole.checksumFailures(), chunks.checksumFailures());
        assertEquals(whole.invalidLengths(), chunks.invalidLengths());
        assertEquals(whole.pendingBytes(), chunks.pendingBytes());
        assertEquals(input.length, chunks.receivedBytes());
    }
}
