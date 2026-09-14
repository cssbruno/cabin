package com.cabin.hardware;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Read-only inventory. Never opens device nodes, loads libraries, binds vendor services or requests root. */
final class HardwareInventory {
    static final String[] LIBRARIES = {"libsqlserial.so", "libjni_serial.so", "libsqlcontrol.so", "libsqltouch.so", "libjni_i2c.so", "libjni_spectrum.so", "libjni_toolkit.so", "libsyu_jni.so"};
    private static final String[] PROPERTIES = {"ro.board.platform", "ro.hardware", "ro.build.display.id", "ro.build.fytmanufacturer", "ro.fyt.platform", "sys.fyt.platform", "ro.fyt.realplatform", "ro.fyt.mcu_type"};

    static JSONObject collect(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("mode", "read-only inventory");
        root.put("replacementCompatibility", "unverified");
        root.put("hardwareCommandsSent", false);
        root.put("mcuProtocol", "unverified");
        root.put("dspChip", "unverified");
        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("model", Build.MODEL);
        device.put("hardware", Build.HARDWARE);
        device.put("board", Build.BOARD);
        device.put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS));
        device.put("androidSdk", Build.VERSION.SDK_INT);
        device.put("androidRelease", Build.VERSION.RELEASE);
        device.put("buildDisplay", Build.DISPLAY);
        // Deliberately exclude serial numbers, Android ID, accounts and network identifiers.
        root.put("device", device);
        JSONObject properties = new JSONObject();
        for (String key : PROPERTIES) properties.put(key, property(key));
        root.put("properties", properties);
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add("/system/lib"); dirs.add("/system/lib64");
        dirs.add("/vendor/lib"); dirs.add("/vendor/lib64");
        dirs.add("/system/vendor/lib"); dirs.add("/system/vendor/lib64");
        JSONArray packages = new JSONArray();
        PackageManager pm = context.getPackageManager();
        for (String name : new String[]{"com.syu.ms", "com.syu.canbus"}) {
            JSONObject item = new JSONObject().put("packageName", name);
            try {
                PackageInfo info = pm.getPackageInfo(name, 0);
                item.put("visibility", "visible");
                item.put("versionName", info.versionName);
                item.put("versionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
                ApplicationInfo app = info.applicationInfo;
                if (app != null) {
                    item.put("apkPath", app.sourceDir);
                    item.put("nativeLibraryDir", app.nativeLibraryDir);
                    item.put("systemApp", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    item.put("uid", app.uid);
                    if (app.nativeLibraryDir != null) dirs.add(app.nativeLibraryDir);
                }
            } catch (PackageManager.NameNotFoundException | SecurityException ex) {
                item.put("visibility", "not visible to this app");
            }
            packages.put(item);
        }
        root.put("vendorPackages", packages);
        JSONArray services = new JSONArray();
        for (ResolveInfo match : pm.queryIntentServices(new Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"), 0)) {
            if (match.serviceInfo == null) continue;
            services.put(new JSONObject().put("package", match.serviceInfo.packageName)
                .put("class", match.serviceInfo.name).put("exported", match.serviceInfo.exported)
                .put("permission", match.serviceInfo.permission == null ? JSONObject.NULL : match.serviceInfo.permission));
        }
        root.put("visibleToolkitServices", services);
        JSONArray libs = new JSONArray();
        for (String dir : dirs) for (String name : LIBRARIES) libs.put(pathInfo(new File(dir, name)));
        root.put("referenceNativeLibraries", libs);
        root.put("libraryNote", "These names come from the 9853i reference. Presence does not prove ABI, protocol, DSP or replacement compatibility.");
        root.put("deviceNodes", directoryMatches(new File("/dev"), "^(tty(S|USB|ACM|Mbx)[0-9]+|can[0-9]+|i2c-[0-9]+)$"));
        root.put("networkInterfaces", directoryMatches(new File("/sys/class/net"), "^can[0-9]+$"));
        root.put("interfaceNote", "Directory metadata only. Nodes are never opened. No visible entry does not prove that hardware is absent.");
        return root;
    }
    static JSONObject pathInfo(File file) throws JSONException {
        JSONObject item = new JSONObject().put("path", file.getPath());
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            item.put("visibility", "visible"); item.put("regularFile", attrs.isRegularFile());
            item.put("bytes", attrs.size());
            item.put("readableByApp", Files.isReadable(file.toPath()));
        } catch (NoSuchFileException ex) { item.put("visibility", "not found at this path"); }
        catch (Exception ex) { item.put("visibility", "inaccessible or unavailable"); }
        return item;
    }
    static JSONObject directoryMatches(File dir, String pattern) throws JSONException {
        JSONObject result = new JSONObject().put("path", dir.getPath());
        JSONArray entries = new JSONArray();
        File[] files;
        try { files = dir.listFiles(); } catch (SecurityException ex) { files = null; }
        result.put("visibility", files == null ? "inaccessible or unavailable" : "visible");
        if (files != null) {
            int count = 0;
            for (File file : files) if (file.getName().matches(pattern) && count++ < 64) entries.put(pathInfo(file));
        }
        return result.put("entries", entries);
    }
    private static JSONObject property(String key) throws JSONException {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) return new JSONObject().put("status", "timeout");
            if (process.exitValue() != 0) return new JSONObject().put("status", "unavailable");
            byte[] buffer = new byte[512];
            int length = process.getInputStream().read(buffer);
            String value = length <= 0 ? "" : new String(buffer, 0, length, java.nio.charset.StandardCharsets.UTF_8).trim();
            return new JSONObject().put("status", value.isEmpty() ? "not reported" : "reported").put("value", value);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return new JSONObject().put("status", "interrupted");
        } catch (Exception ex) { return new JSONObject().put("status", "unavailable"); }
        finally { if (process != null) process.destroy(); }
    }
}
