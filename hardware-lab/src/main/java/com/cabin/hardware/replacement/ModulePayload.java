package com.cabin.hardware.replacement;

import android.os.Parcel;
import java.util.Arrays;

/** Defensive, bounded representation of the three arrays in the observed SYU interface. */
public final class ModulePayload {
    public static final ModulePayload EMPTY = new ModulePayload(null, null, null);
    private final int[] ints;
    private final float[] floats;
    private final String[] strings;
    public ModulePayload(int[] ints, float[] floats, String[] strings) {
        if (ints != null && ints.length > 64 || floats != null && floats.length > 64 || strings != null && strings.length > 16)
            throw new IllegalArgumentException("Module array limit exceeded");
        if (floats != null) for (float value : floats) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite module value");
        if (strings != null) for (String value : strings) if (value != null && value.length() > 256) throw new IllegalArgumentException("Module string too long");
        this.ints = ints == null ? null : ints.clone(); this.floats = floats == null ? null : floats.clone(); this.strings = strings == null ? null : strings.clone();
    }
    public static ModulePayload integers(int... values) { return new ModulePayload(values,null,null); }
    public int[] integers() { return ints == null ? null : ints.clone(); }
    public float[] floats() { return floats == null ? null : floats.clone(); }
    public String[] strings() { return strings == null ? null : strings.clone(); }
    public boolean integerOnly() { return (floats == null || floats.length == 0) && (strings == null || strings.length == 0); }
    public void write(Parcel out) { out.writeIntArray(ints); out.writeFloatArray(floats); out.writeStringArray(strings); }
    private static int count(Parcel in,int max,int width) {
        if (in.dataAvail() < 4) throw new IllegalArgumentException("Truncated array");
        int count = in.readInt();
        if (count < -1 || count > max || count > in.dataAvail()/width) throw new IllegalArgumentException("Invalid array count");
        return count;
    }
    public static ModulePayload read(Parcel in) {
        if (in.dataSize() > 16384) throw new IllegalArgumentException("Oversized module request");
        int n = count(in,64,4); int[] ints = n < 0 ? null : new int[n];
        if (ints != null) for (int i=0;i<n;i++) ints[i] = in.readInt();
        n = count(in,64,4); float[] floats = n < 0 ? null : new float[n];
        if (floats != null) for (int i=0;i<n;i++) floats[i] = in.readFloat();
        n = count(in,16,4); String[] strings = n < 0 ? null : new String[n];
        if (strings != null) for (int i=0;i<n;i++) {
            if (in.dataAvail() < 4) throw new IllegalArgumentException("Truncated string");
            // Android validates string storage against the bounded Parcel before allocating.
            strings[i] = in.readString();
            if (strings[i] != null && strings[i].length() > 256) throw new IllegalArgumentException("Module string too long");
        }
        return new ModulePayload(ints,floats,strings);
    }
    @Override public boolean equals(Object other) { if (!(other instanceof ModulePayload)) return false; ModulePayload value=(ModulePayload)other; return Arrays.equals(ints,value.ints) && Arrays.equals(floats,value.floats) && Arrays.equals(strings,value.strings); }
    @Override public int hashCode() { return 31*(31*Arrays.hashCode(ints)+Arrays.hashCode(floats))+Arrays.hashCode(strings); }
}
