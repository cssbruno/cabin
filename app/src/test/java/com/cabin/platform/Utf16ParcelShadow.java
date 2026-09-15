package com.cabin.platform;

import android.os.Parcel;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowParcel;

/**
 * Robolectric 4.14 stores strings as opaque objects and rejects reading their length header.
 * Model Android's String16 length + UTF16 terminator + four-byte padding for wire-codec tests.
 * All other Parcel operations retain the standard shadow, including Binder and interface tokens.
 */
@Implements(Parcel.class)
public class Utf16ParcelShadow extends ShadowParcel {
    // Android 10 uses the pre-String16 native method names, with the same UTF-16 wire format.
    @Implementation(maxSdk = 29)
    protected static void nativeWriteString(long pointer, String value) {
        nativeWriteString16(pointer, value);
    }

    @Implementation(maxSdk = 29)
    protected static String nativeReadString(long pointer) {
        return nativeReadString16(pointer);
    }

    @Implementation
    protected static void nativeWriteString16(long pointer, String value) {
        nativeWriteInt(pointer, value == null ? -1 : value.length());
        if (value == null) return;
        for (int offset = 0; offset < value.length() + 1; offset += 2) {
            int low = offset < value.length() ? value.charAt(offset) : 0;
            int high = offset + 1 < value.length() ? value.charAt(offset + 1) : 0;
            nativeWriteInt(pointer, low | (high << 16));
        }
    }

    @Implementation
    protected static String nativeReadString16(long pointer) {
        int length = nativeReadInt(pointer);
        if (length == -1) return null;
        if (length < 0 || length > 4096) throw new AssertionError("Invalid test string length");
        char[] chars = new char[length];
        for (int offset = 0; offset < length + 1; offset += 2) {
            int word = nativeReadInt(pointer);
            if (offset < length) chars[offset] = (char) word;
            if (offset + 1 < length) chars[offset + 1] = (char) (word >>> 16);
        }
        return new String(chars);
    }
}
