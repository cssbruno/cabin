package com.cabin.reports;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Snapshot first. No document picker or broad storage permission is needed. */
public final class ReportExport {
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    public static void show(Context context, String name, String report) {
        final String snapshot = DebugJournal.decorate(report);
        new AlertDialog.Builder(context).setTitle("Export debug report")
            .setItems(new String[]{Build.VERSION.SDK_INT >= 29 ? "Save to Downloads" : "Save to app Documents", "Share file", "Copy report"}, (dialog, which) -> {
                if (which == 2) { copy(context,snapshot); return; }
                IO.execute(() -> {
                    try {
                        // Also retain the exact export snapshot privately if external saving/sharing fails.
                        File file = cache(context, name, snapshot);
                        if (which == 0) {
                            String location = save(context, name, snapshot);
                            new Handler(Looper.getMainLooper()).post(() -> message(context,"Saved: " + location));
                        } else {
                            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".reports",file);
                            Intent send = new Intent(Intent.ACTION_SEND).setType("application/json")
                                .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            send.setClipData(ClipData.newRawUri("Debug report",uri));
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (!isAlive(context)) { message(context,"Report captured. Reopen export to share it."); return; }
                                try { context.startActivity(Intent.createChooser(send,"Share debug report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                                catch (RuntimeException ex) { failed(context,snapshot,ex); }
                            });
                        }
                    } catch (Exception ex) { new Handler(Looper.getMainLooper()).post(() -> failed(context,snapshot,ex)); }
                });
            }).setNegativeButton("Cancel",null).show();
    }
    static File cache(Context context, String name, String report) throws IOException {
        File directory = new File(context.getFilesDir(),"debug-reports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create report folder");
        File[] old = directory.listFiles();
        if (old != null && old.length >= 20) {
            java.util.Arrays.sort(old,java.util.Comparator.comparingLong(File::lastModified));
            for (int i=0;i<=old.length-20;i++) old[i].delete();
        }
        File file = new File(directory, UUID.randomUUID() + "-" + safeName(name));
        try (OutputStream stream = new FileOutputStream(file)) { stream.write(report.getBytes(StandardCharsets.UTF_8)); }
        return file;
    }
    static String save(Context context, String name, String report) throws IOException {
        String filename = System.currentTimeMillis() + "-" + safeName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/Cabin");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if (uri == null) throw new IOException("Downloads storage is unavailable");
            try {
                try (OutputStream out = resolver.openOutputStream(uri,"w")) {
                    if (out == null) throw new IOException("Downloads returned no output stream");
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING,0);
                if (resolver.update(uri,values,null,null) != 1) throw new IOException("Downloads could not publish the report");
                return "Downloads/Cabin/" + filename;
            } catch (Exception ex) {
                try { resolver.delete(uri,null,null); } catch (RuntimeException ignored) { }
                if (ex instanceof IOException) throw (IOException) ex;
                throw new IOException("Downloads save failed",ex);
            }
        }
        File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new IOException("Storage unavailable; use Share file or Copy report");
        File target = new File(folder,filename);
        try (OutputStream out = new FileOutputStream(target)) { out.write(report.getBytes(StandardCharsets.UTF_8)); }
        return target.getAbsolutePath();
    }
    static String safeName(String name) { return name.replaceAll("[^A-Za-z0-9._-]","_"); }
    private static void failed(Context context, String report, Exception error) {
        DebugJournal.record("export","failure",error.toString());
        if (!isAlive(context)) { message(context,"Export failed: " + error.getMessage()); return; }
        new AlertDialog.Builder(context).setTitle("Export failed")
            .setMessage("Your captured report is still available. " + error.getClass().getSimpleName() + ": " + error.getMessage())
            .setPositiveButton("Copy report",(d,w) -> copy(context,report)).setNegativeButton("Close",null).show();
    }
    private static void copy(Context context, String report) {
        try {
            context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Cabin debug report",report));
            message(context,"Report copied");
        } catch (RuntimeException ex) { message(context,"Could not copy report: " + ex.getMessage()); }
    }
    private static void message(Context context,String text) { Toast.makeText(context,text,Toast.LENGTH_LONG).show(); }
    private static boolean isAlive(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof android.app.Activity) {
                android.app.Activity activity = (android.app.Activity) current;
                return !activity.isFinishing() && !activity.isDestroyed();
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return true;
    }
    private ReportExport() { }
}
