package com.cabin.hardware.replacement;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class ReplacementProtocolTest {
    @Test public void resolvesAllSerialFamiliesAndBaudOverrides() {
        String[] paths = {"ttyS3", "ttyS0", "ttyS2", "ttyS0", "ttyS2", "ttyS3", "ttyS2", "ttyS0", "ttyS2", "ttyS2", "ttyS2"};
        for (int family = 1; family <= 11; family++) {
            JoyingSerialConfig config = JoyingSerialConfig.resolve(family, 0, 0, false);
            assertEquals("/dev/" + paths[family-1], config.path); assertEquals(38400, config.baud);
        }
        assertEquals("/dev/ttyMbx3", JoyingSerialConfig.resolve(1, 0, 0, true).path);
        for (int subtype : new int[] {9, 24, 39}) assertEquals(115200, JoyingSerialConfig.resolve(3, subtype, 0, false).baud);
        assertEquals(115200, JoyingSerialConfig.resolve(3, 0, 1, false).baud);
        assertThrows(IllegalArgumentException.class, () -> JoyingSerialConfig.resolve(0, 0, 0, false));
    }
    @Test public void doorBitsAreIndependentAndOrientationIsExplicit() {
        HondaCanDecoder normal = new HondaCanDecoder(0x10012a, false, false);
        int[] shifts = {2,6,7,4,5,3};
        for (int index = 0; index < shifts.length; index++) {
            Map<Integer, Integer> fields = normal.decode(new byte[] {0x24, 1, (byte) (1 << shifts[index])});
            assertEquals(6, fields.size());
            for (int field = 36; field <= 41; field++) assertEquals(Integer.valueOf(field == 36 + index ? 1 : 0), fields.get(field));
        }
        assertEquals(Integer.valueOf(1), new HondaCanDecoder(0x10012a,true,false).decode(new byte[] {0x24,1,(byte)0x80}).get(37));
        assertThrows(IllegalArgumentException.class, () -> new HondaCanDecoder(0x12a,false,false));
    }
    @Test public void climatePreservesSentinelsAndCapsFanWithoutGuessingUnits() {
        byte[] packet = {0x21,8,(byte)0xc0,(byte)0xff,0,(byte)0xff,(byte)0x85,0,0,0};
        Map<Integer,Integer> result = new HondaCanDecoder(0x10012a,false,false).decode(packet);
        assertEquals(Integer.valueOf(1), result.get(24)); assertEquals(Integer.valueOf(7), result.get(29));
        assertEquals(Integer.valueOf(-2), result.get(25)); assertEquals(Integer.valueOf(-3), result.get(31));
        assertEquals(Integer.valueOf(1), result.get(22)); assertFalse(result.containsKey(1000));
        result = new HondaCanDecoder(0x10012a,false,true).decode(packet);
        assertEquals(Integer.valueOf(-3), result.get(25)); assertEquals(Integer.valueOf(-2), result.get(31));
    }
    @Test public void truncatedAndUnknownCanFramesNeverPublishDefaults() {
        HondaCanDecoder decoder = new HondaCanDecoder(0x10012a,false,false);
        for (int length = 0; length < 10; length++) { byte[] packet = new byte[length]; if (length > 0) packet[0] = 0x21; assertTrue(decoder.decode(packet).isEmpty()); }
        assertTrue(decoder.decode(new byte[] {0x22,1,0}).isEmpty());
    }
    @Test public void powerRequiresOrderAndDoesNotInferWakeFromOtherTraffic() {
        PowerSequence power = new PowerSequence();
        power.accept(new byte[] {1,0,(byte)0x89,0x55}); assertEquals(PowerSequence.State.OUT_OF_ORDER,power.state());
        power.accept(new byte[] {1,0,(byte)0x89,0x53});
        power.accept(new byte[] {1,0,(byte)0x89,0x54});
        power.accept(new byte[] {1,0,(byte)0x89,0x55}); assertEquals(PowerSequence.State.SLEEP_REQUESTED,power.state());
        power.accept(new byte[] {1,0,(byte)0x89,0x55}); assertEquals(PowerSequence.State.SLEEP_REQUESTED,power.state());
        power.accept(new byte[] {1,0,(byte)0x89,0x53,9}); assertEquals(PowerSequence.State.SLEEP_REQUESTED,power.state());
        power.reset(); assertEquals(PowerSequence.State.UNKNOWN,power.state());
    }
    @Test public void c7604PacksSigned24BitsThenCommitsExactlyOnce() throws Exception {
        List<byte[]> writes = new ArrayList<>();
        try (C7604Writer writer = new C7604Writer(new C7604Writer.Bus() {
            public void write(byte[] bytes) { writes.add(bytes.clone()); } public void close() { }
        })) { writer.writeCoefficients(0x89,0x1234,new int[] {0x123456,-1,-8388608}); }
        assertEquals(2,writes.size());
        assertArrayEquals(new byte[] {(byte)0x89,0x12,0x34,0x12,0x34,0x56,-1,-1,-1,(byte)0x80,0,0},writes.get(0));
        assertArrayEquals(new byte[] {(byte)0xa4,0,0},writes.get(1));
        assertThrows(IllegalArgumentException.class, () -> C7604Writer.coefficients(0x84,0,new int[] {8388608}));
        assertThrows(IllegalArgumentException.class, () -> C7604Writer.coefficients(0x90,0,new int[] {0}));
    }
    @Test public void failedDspWritesNeverCommitOrRetryUncertainState() throws Exception {
        int[] writes = {0};
        C7604Writer writer = new C7604Writer(new C7604Writer.Bus() {
            public void write(byte[] bytes) throws IOException { writes[0]++; throw new IOException("short write"); } public void close() { }
        });
        assertThrows(IOException.class, () -> writer.writeCoefficients(0x84,0,new int[] {0}));
        assertThrows(IOException.class, () -> writer.writeCoefficients(0x84,0,new int[] {0}));
        assertEquals(1,writes[0]); writer.close();
    }
    @Test public void replayRejectsWritesAndClosesItsInput() throws Exception {
        boolean[] closed = {false};
        ReplayTransport replay = new ReplayTransport(new ByteArrayInputStream(new byte[] {1}) { public void close() { closed[0] = true; } });
        assertEquals(1,replay.read(new byte[1])); assertEquals(-1,replay.read(new byte[1]));
        assertThrows(IOException.class, () -> replay.write(new byte[] {1})); replay.close(); assertTrue(closed[0]);
        assertThrows(IOException.class, () -> replay.read(new byte[1]));
    }
}
