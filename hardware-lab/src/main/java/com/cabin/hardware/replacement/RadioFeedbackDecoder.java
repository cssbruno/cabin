package com.cabin.hardware.replacement;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Receive-only driver-1 radio subset. Input is a checksum-verified MCU payload.
 * Call reset on framing loss/session changes. No I/O or vendor query side effects.
 */
public final class RadioFeedbackDecoder {
    public static final long ASSEMBLY_TTL_MS = 1000;
    private int band = -1, stage, frequency;
    private long started, selectedAt;
    private int selected = -1;
    private Map<Integer,Integer> presetUpdates = Collections.emptyMap();
    /** Indexed module-1 field-4 tuples produced by the most recent accept call. */
    public Map<Integer,Integer> presetUpdates() { return presetUpdates; }

    public void reset() { band = -1; stage = 0; frequency = 0; selected = -1; presetUpdates = Collections.emptyMap(); }

    private static boolean validFrequency(int bandOrPreset,int value) {
        return bandOrPreset >= 65536 ? value >= 6500 && value <= 10800 : value >= 150 && value <= 30000;
    }

    /** Returns module-1 scalar field updates; unknown/partial payloads produce none. */
    public Map<Integer, Integer> accept(byte[] payload, long now) {
        presetUpdates = Collections.emptyMap();
        if (selected != -1 && (now < selectedAt || now - selectedAt >= ASSEMBLY_TTL_MS)) selected = -1;
        if (stage != 0 && (now < started || now - started >= ASSEMBLY_TTL_MS)) stage = 0;
        if (payload == null || payload.length < 2 || payload[0] != 1 || payload[1] != 3)
            return Collections.emptyMap();
        if (payload.length != 4) { stage = 0; selected = -1; return Collections.emptyMap(); }
        int code = payload[2] & 255, value = payload[3] & 255;
        Map<Integer, Integer> result = new TreeMap<>();
        switch (code) {
            case 6:
                stage = 0; selected = -1;
                band = value <= 2 ? 65536 + value : value >= 10 && value <= 11 ? value - 10 : -1;
                if (band != -1) result.put(0, band);
                break;
            case 0x10:
                stage = 0; selectedAt = now;
                selected = value >= 1 && value <= 18 ? 65536 + value - 1
                    : value >= 0x65 && value <= 0x70 ? value - 0x65 : -1;
                break;
            case 1:
                frequency = value * 10000; stage = 1; started = now;
                break;
            case 2:
                if (stage == 1 && value < 100) { frequency += value * 100; stage = 2; }
                else stage = 0;
                break;
            case 3:
                if (stage == 2 && value < 100) {
                    int complete = frequency + value;
                    if (complete > 100000) {
                        int preset = complete - 100000;
                        if (selected != -1 && validFrequency(selected,preset))
                            presetUpdates = Collections.singletonMap(selected,preset);
                    } else if (band != -1 && validFrequency(band,complete)) result.put(1,complete);
                }
                stage = 0; selected = -1;
                break;
            default:
                // Unrecognized radio messages interrupt any assembly.
                stage = 0; selected = -1;
                break;
        }
        return Collections.unmodifiableMap(result);
    }
}
