package com.cabin.hardware;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HardwareInventoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void inventoriesFileMetadataWithoutChangingContents() throws Exception {
        File lib = temporary.newFile("libsqlserial.so");
        byte[] bytes = {1, 2, 3, 4}; Files.write(lib.toPath(), bytes);
        JSONObject info = HardwareInventory.pathInfo(lib);
        assertEquals("visible", info.getString("visibility"));
        assertEquals(4, info.getLong("bytes"));
        assertTrue(info.getBoolean("regularFile"));
        assertArrayEquals(bytes, Files.readAllBytes(lib.toPath()));
    }
    @Test public void unknownFilesAndDirectoriesAreNotReportedAsWorkingHardware() throws Exception {
        assertEquals("not found at this path", HardwareInventory.pathInfo(new File(temporary.getRoot(), "absent.so")).getString("visibility"));
        JSONObject directory = HardwareInventory.directoryMatches(new File(temporary.getRoot(), "absent"), ".*");
        assertEquals("inaccessible or unavailable", directory.getString("visibility"));
        assertEquals(0, directory.getJSONArray("entries").length());
    }
    @Test public void nodeInventoryFiltersUnrelatedEntries() throws Exception {
        temporary.newFile("ttyS0"); temporary.newFile("unrelated"); temporary.newFile("ttyUSB2");
        JSONObject result = HardwareInventory.directoryMatches(temporary.getRoot(), "^tty(S|USB)[0-9]+$");
        assertEquals(2, result.getJSONArray("entries").length());
    }
    @Test public void reportDoesNotClaimRealHardwareCompatibilityOrIncludeDeviceIdentifiers() throws Exception {
        JSONObject result = HardwareInventory.collect(RuntimeEnvironment.getApplication());
        assertEquals("unverified", result.getString("replacementCompatibility"));
        assertFalse(result.getBoolean("hardwareCommandsSent"));
        assertEquals("unverified", result.getString("dspChip"));
        JSONObject device = result.getJSONObject("device");
        assertFalse(device.has("serial")); assertFalse(device.has("androidId"));
        assertEquals(2, result.getJSONArray("vendorPackages").length());
    }
}
