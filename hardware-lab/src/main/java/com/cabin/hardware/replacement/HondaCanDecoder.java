package com.cabin.hardware.replacement;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Receive-only subset of module/canbus/v.M2 for profile 0x10012a.
 * Input starts at the CAN command (outer E3 removed). No guessed transmit commands.
 * Temperature values retain the vendor raw units and -2/-3 sentinels.
 */
public final class HondaCanDecoder {
    public static final int PROFILE = 0x10012a;
    private final boolean swappedDoors, reversedTemperatures;
    public HondaCanDecoder(int profile, boolean swappedDoors, boolean reversedTemperatures) {
        if (profile != PROFILE) throw new IllegalArgumentException("CAN profile not implemented");
        this.swappedDoors = swappedDoors; this.reversedTemperatures = reversedTemperatures;
    }
    public Map<Integer, Integer> decode(byte[] packet) {
        if (packet.length < 3) return Collections.emptyMap();
        Map<Integer, Integer> result = new TreeMap<>();
        int command = packet[0] & 255, bits = packet[2] & 255;
        if (command == 0x24) {
            result.put(37, bit(bits, swappedDoors ? 7 : 6));
            result.put(38, bit(bits, swappedDoors ? 6 : 7));
            result.put(39, bit(bits, swappedDoors ? 5 : 4));
            result.put(40, bit(bits, swappedDoors ? 4 : 5));
            result.put(41, bit(bits, 3)); result.put(36, bit(bits, 2));
        } else if (command == 0x21 && packet.length >= 10) {
            int[] fields = {32, 24, 21, 51, 20, 30, 23};
            for (int i = 0; i < fields.length; i++) result.put(fields[i], bit(bits, 7 - i));
            bits = packet[3] & 255;
            result.put(28, bit(bits, 7)); result.put(26, bit(bits, 6)); result.put(27, bit(bits, 5));
            result.put(29, Math.min(bits & 15, 7));
            result.put(25, temperature(packet[reversedTemperatures ? 5 : 4]));
            result.put(31, temperature(packet[reversedTemperatures ? 4 : 5]));
            result.put(22, bit(packet[6], 7)); result.put(33, bit(packet[6], 0)); result.put(34, bit(packet[6], 2));
            // Remaining variant-specific fields are intentionally absent.
        }
        return result;
    }
    private static int bit(int value, int bit) { return (value >>> bit) & 1; }
    private static int temperature(byte value) { return value == 0 ? -2 : value == -1 ? -3 : value & 255; }
}
