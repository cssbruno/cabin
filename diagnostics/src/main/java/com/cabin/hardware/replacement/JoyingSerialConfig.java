package com.cabin.hardware.replacement;

/** Resolution rules from the pinned Joying firmware. Platform family is not the SoC name. */
public final class JoyingSerialConfig {
    public final String path;
    public final int baud;
    private JoyingSerialConfig(String path, int baud) { this.path = path; this.baud = baud; }
    public static JoyingSerialConfig resolve(int family, int subtype, int mcuType, boolean qstMailbox) {
        if (family < 1 || family > 11 || subtype < 0 || mcuType < 0)
            throw new IllegalArgumentException("Explicit verified platform family/subtype required");
        String path;
        switch (family) {
            case 1: path = qstMailbox ? "/dev/ttyMbx3" : "/dev/ttyS3"; break;
            case 3: case 5: case 7: case 9: case 10: case 11: path = "/dev/ttyS2"; break;
            case 6: path = "/dev/ttyS3"; break;
            default: path = "/dev/ttyS0";
        }
        return new JoyingSerialConfig(path, subtype == 9 || subtype == 24 || subtype == 39 || mcuType == 1 ? 115200 : 38400);
    }
}
