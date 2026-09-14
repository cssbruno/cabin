package com.cabin.hardware.replacement;

/** Four-channel field2Ic/F0 mapping from the reference; positions range 0..16, center 8. */
public final class C7604Channels {
    private static final int[] ATTENUATION={0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x1e,0x24,0x2a,0x30,0x36,0x3c,0x42,0xff};
    private C7604Channels() { }
    public static int[] field(int balance,int fade) {
        if(balance<0 || balance>16 || fade<0 || fade>16) throw new IllegalArgumentException("Field position must be 0..16");
        return new int[] {ATTENUATION[Math.max(balance,fade)],ATTENUATION[Math.max(16-balance,fade)],
            ATTENUATION[Math.max(balance,16-fade)],ATTENUATION[Math.max(16-balance,16-fade)]};
    }
    public static int[] muted() { return new int[] {255,255,255,255}; }
}
