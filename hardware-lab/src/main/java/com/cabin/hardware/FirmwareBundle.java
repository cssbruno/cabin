package com.cabin.hardware;

import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** User-initiated export of public firmware binaries. Never reads app data or device nodes. */
final class FirmwareBundle {
    static final long MAX_FILE = 64L * 1024 * 1024;
    static final long MAX_TOTAL = 192L * 1024 * 1024;
    static void export(Context context, OutputStream output) throws Exception {
        JSONObject inventory = HardwareInventory.collect(context);
        Map<String, File> files = new LinkedHashMap<>();
        for (String pkg : new String[]{"com.syu.ms", "com.syu.canbus"}) {
            try {
                android.content.pm.ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
                if (app.sourceDir != null) files.put("packages/" + pkg + "/base.apk", new File(app.sourceDir));
                if (app.splitSourceDirs != null) for (int i = 0; i < app.splitSourceDirs.length; i++)
                    files.put("packages/" + pkg + "/split-" + i + ".apk", new File(app.splitSourceDirs[i]));
            } catch (PackageManager.NameNotFoundException | SecurityException ignored) { }
        }
        JSONArray libs = inventory.getJSONArray("referenceNativeLibraries");
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.getJSONObject(i);
            if (lib.optBoolean("regularFile") && lib.optBoolean("readableByApp")) {
                File file = new File(lib.getString("path"));
                files.put("libraries/" + i + "/" + file.getName(), file);
            }
        }
        write(output, inventory, files);
    }
    static void write(OutputStream output, JSONObject inventory, Map<String, File> files) throws Exception {
        JSONArray results = new JSONArray();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, File> entry : files.entrySet()) {
                String name = entry.getKey();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("Invalid archive path");
                File file = entry.getValue();
                JSONObject result = new JSONObject().put("entry", name);
                results.put(result);
                if (!file.isFile() || !file.canRead()) { result.put("status", "not readable"); continue; }
                long length = file.length();
                if (length > MAX_FILE || length > MAX_TOTAL - total) { result.put("status", "size limit"); continue; }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                // Readable metadata alone does not guarantee open permission under SELinux.
                InputStream input;
                try { input = new FileInputStream(file); }
                catch (IOException ex) { result.put("status", "could not open"); continue; }
                long copied = 0;
                try (InputStream source = input) {
                    zip.putNextEntry(new ZipEntry(name));
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = source.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export interrupted");
                        copied += n;
                        if (copied > MAX_FILE || copied > MAX_TOTAL - total) throw new IOException("Firmware changed size during export");
                        zip.write(buffer, 0, n); digest.update(buffer, 0, n);
                    }
                    zip.closeEntry();
                }
                total += copied;
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                result.put("status", "copied").put("bytes", copied).put("sha256", hash.toString());
            }
            inventory.put("exportedFiles", results);
            inventory.put("bundleNote", "Only readable firmware files are included. This is not a complete recovery backup and does not establish replacement compatibility.");
            zip.putNextEntry(new ZipEntry("hardware-report.json"));
            zip.write(inventory.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
