package com.cabin.hardware;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FirmwareBundleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void exportsExactFirmwareBytesWithHashAndMissingFileStatus() throws Exception {
        File source = temporary.newFile("sample.apk"); Files.write(source.toPath(), new byte[]{1,2,3});
        Map<String,File> inputs = new LinkedHashMap<>();
        inputs.put("packages/example/base.apk", source); inputs.put("libraries/missing.so", new File(temporary.getRoot(), "missing"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FirmwareBundle.write(output, new JSONObject().put("replacementCompatibility", "unverified"), inputs);
        Map<String,byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertArrayEquals(new byte[]{1,2,3}, entries.get("packages/example/base.apk"));
        JSONObject report = new JSONObject(new String(entries.get("hardware-report.json"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("unverified", report.getString("replacementCompatibility"));
        assertEquals(64, report.getJSONArray("exportedFiles").getJSONObject(0).getString("sha256").length());
        assertEquals("not readable", report.getJSONArray("exportedFiles").getJSONObject(1).getString("status"));
        assertFalse(entries.containsKey("libraries/missing.so"));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(source.toPath()));
    }
    @Test public void rejectsArchivePathTraversal() throws Exception {
        File source = temporary.newFile("sample");
        assertThrows(IOException.class, () -> FirmwareBundle.write(new ByteArrayOutputStream(), new JSONObject(), Map.of("../outside", source)));
    }
    @Test public void skipsOversizeFilesWithoutReadingThem() throws Exception {
        File large = temporary.newFile("large");
        try (RandomAccessFile f = new RandomAccessFile(large, "rw")) { f.setLength(FirmwareBundle.MAX_FILE + 1); }
        JSONObject report = new JSONObject();
        FirmwareBundle.write(new ByteArrayOutputStream(), report, Map.of("packages/large.apk", large));
        assertEquals("size limit", report.getJSONArray("exportedFiles").getJSONObject(0).getString("status"));
    }
}
