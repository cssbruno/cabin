package com.cabin.hardware.replacement;

/** C7604.z0 -> d.a -> j.a(mode 2): positive Q21 amplitude at register 7. */
public final class C7604Gain {
    private C7604Gain() { }
    public static int word(double decibels) {
        if (!Double.isFinite(decibels)) throw new IllegalArgumentException("Finite gain required");
        if (decibels <= -145) return 0;
        double amplitude=Math.pow(10,Math.min(decibels,12)/20.0);
        return (int)Math.min(8388607,amplitude*2097152.0+0.5);
    }
    public static byte[] packet(double decibels) {
        int value=word(decibels);
        return new byte[] {(byte)0x80,0,7,(byte)(value>>>16),(byte)(value>>>8),(byte)value};
    }
}
