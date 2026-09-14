package com.cabin.reports;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ReportExportTest {
    private Context context;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }
    @Test public void reportSnapshotIsStableAndDebugHistoryBounded() throws Exception {
        for(int i=0;i<110;i++) DebugJournal.record("CAN","callback",String.valueOf(i));
        String report = DebugJournal.decorate("{\"callbackCount\":7}");
        DebugJournal.record("CAN","later","not in captured export");
        org.json.JSONObject json = new org.json.JSONObject(report);
        assertEquals(7,json.getInt("callbackCount"));
        assertEquals(100,json.getJSONArray("debugEvents").length());
        assertFalse(report.contains("not in captured export"));
    }
    @Test public void sharingFileKeepsExactBytesAndHasContentUri() throws Exception {
        File file = ReportExport.cache(context,"report.json","{\"door\":1}");
        assertEquals("{\"door\":1}", new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        Uri uri = androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".reports",file);
        assertEquals("content",uri.getScheme());
        File outside = new File(context.getFilesDir(),"private.txt"); Files.write(outside.toPath(),"private".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".reports",outside));
    }
    @Test public void downloadsPublishesOnlyAfterWriting() throws Exception {
        Downloads provider = new Downloads();
        provider.file = File.createTempFile("report","json",context.getCacheDir());
        register(provider);
        String path = ReportExport.save(context,"report.json","exact report");
        assertTrue(path.startsWith("Downloads/Cabin/"));
        assertEquals("exact report",new String(Files.readAllBytes(provider.file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(provider.published); assertFalse(provider.deleted);
    }
    @Test public void failedWriteRemovesIncompleteDownload() {
        Downloads provider = new Downloads();
        register(provider);
        assertThrows(IOException.class, () -> ReportExport.save(context,"report.json","test"));
        assertTrue(provider.deleted); assertFalse(provider.published);
    }
    private void register(Downloads provider) {
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "media"; info.exported = true;
        provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal("media",provider);
    }
    public static class Downloads extends ContentProvider {
        File file; boolean published,deleted;
        public boolean onCreate() { return true; }
        public String getType(Uri uri) { return "application/json"; }
        public Cursor query(Uri uri,String[] p,String s,String[] a,String sort) { return null; }
        public Uri insert(Uri uri,ContentValues values) {
            assertEquals(Integer.valueOf(1),values.getAsInteger("is_pending"));
            return Uri.parse("content://media/external/downloads/123");
        }
        public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            if(file == null) throw new FileNotFoundException("Storage refused write");
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        public int update(Uri uri,ContentValues v,String s,String[] a) { assertEquals(Integer.valueOf(0),v.getAsInteger("is_pending")); published=true; return 1; }
        public int delete(Uri uri,String s,String[] a) { deleted=true; return 1; }
    }
}
