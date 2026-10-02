package com.cabin.quality

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box checks of the optimized app through Android accessibility and public component intents.
 * No Compose test hooks or app implementation classes are linked into the instrumentation APK.
 * The soak covers no-adapter service lifecycle, real driver/settings UI, and recreation; it does not
 * certify physical projection, AudioRecord/AudioTrack hardware, or sustained decoder playback.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private fun text(name: String, vararg arguments: Any): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "Missing UI resource: $name" }
        return context.getString(id, *arguments)
    }
    private fun launch(): ActivityScenario<Activity> = ActivityScenario.launch<Activity>(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, "com.cabin.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        try { val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) { /* Drain command. */ } }
        finally { stream.close() }
    }
    private fun nodes(): ArrayList<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result.add(node)
            for (index in 0 until node.childCount) { val child = node.getChild(index); if (child != null) visit(child) }
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root != null) visit(root)
        return result
    }
    private fun containsText(value: CharSequence?, fragment: String): Boolean =
        value != null && Pattern.compile(Pattern.quote(fragment)).matcher(value).find()
    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString() == label || node.contentDescription?.toString() == label || node.hintText?.toString() == label
    private fun present(label: String): Boolean {
        for (node in nodes()) if (node.isVisibleToUser && matches(node, label)) return true
        return false
    }
    private fun await(description: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val labels = StringBuilder()
        for (node in nodes()) if (node.isVisibleToUser) labels.append(node.text).append('/').append(node.contentDescription).append('\n')
        fail("Timed out: $description\n$labels")
    }
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        return false
    }
    private fun scroll(action: Int, navigation: Boolean = false): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val window = Rect()
        root.getBoundsInScreen(window)
        var target: Rect? = null
        var horizontal = false
        // Select one viewport. Scrolling both the tab strip and body invalidates virtual
        // nodes on API35, while full-page accessibility jumps can skip a short control.
        for (node in nodes()) {
            if (!node.isVisibleToUser) continue
            var scrollAction = node.isScrollable
            for (candidate in node.actionList) if (
                candidate.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                candidate.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                candidate.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                candidate.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            ) scrollAction = true
            if (!scrollAction) continue
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.intersect(window) || bounds.width() < 40 || bounds.height() < 40) continue
            val strip = bounds.width() > window.width() / 2 && bounds.height() < window.height() / 4
            val rail = bounds.width() < window.width() * 2 / 5 && bounds.height() > window.height() / 3
            val body = bounds.width() > window.width() / 2 && bounds.height() > window.height() / 4
            if ((navigation && (strip || rail)) || (!navigation && body)) {
                target = bounds
                horizontal = strip
                break
            }
        }
        val viewport = target ?: return false
        val forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (horizontal) {
            val start = viewport.left + viewport.width() * (if (forward) 75 else 25) / 100
            val end = viewport.left + viewport.width() * (if (forward) 25 else 75) / 100
            shell("input swipe $start ${viewport.centerY()} $end ${viewport.centerY()} 400")
        } else {
            val start = viewport.top + viewport.height() * (if (forward) 65 else 35) / 100
            val end = viewport.top + viewport.height() * (if (forward) 35 else 65) / 100
            shell("input swipe ${viewport.centerX()} $start ${viewport.centerX()} $end 400")
        }
        SystemClock.sleep(150)
        return true
    }
    private fun describeVisibleUi(): String {
        val result = StringBuilder()
        for (node in nodes()) if (node.isVisibleToUser) {
            result.append(node.text).append('/').append(node.contentDescription)
                .append(" selected=").append(node.isSelected).append(" clickable=").append(node.isClickable).append('\n')
        }
        return result.toString()
    }
    private fun click(label: String, completed: (() -> Boolean)? = null) {
        // Compose exposes ACTION_SHOW_ON_SCREEN for laid-out content outside a scroll viewport.
        val navigation = label == text("settings_tab_teyes") || label == text("settings_tab_logs")
        for (pass in 0 until 32) {
            // Some platform accessibility versions return false after dispatching an
            // action that has already changed the chip label. Confirm its observable result.
            if (completed?.invoke() == true) return
            val snapshot = nodes()
            for (index in snapshot.size - 1 downTo 0) {
                val node = snapshot[index]
                if (matches(node, label)) {
                    if (node.isVisibleToUser && clickNode(node)) { SystemClock.sleep(100); return }
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                }
            }
            scroll(if (pass < 16) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, navigation)
            SystemClock.sleep(100)
        }
        if (completed?.invoke() == true) return
        fail("No reachable action: $label\n" + describeVisibleUi())
    }
    private fun dismissImmersiveEducation() {
        for (node in nodes()) if (node.text?.toString() == "Got it" || node.text?.toString() == "GOT IT") clickNode(node)
    }
    private fun openSettings() {
        // Education may appear after the app opens or after a recreation, so handle it
        // on every poll instead of assuming it was present on the first snapshot.
        var lastRequestAt = 0L
        var visibleSince = 0L
        await("Settings opened") {
            dismissImmersiveEducation()
            val now = SystemClock.uptimeMillis()
            if (present(text("settings_back_cabin"))) {
                // A just-closed settings window may still be in the accessibility tree.
                // Require stable visibility before acting on one of its tabs.
                if (visibleSince == 0L) visibleSince = now
                now - visibleSince >= 350
            }
            else {
                visibleSince = 0L
                // Avoid dispatching a second toggle while the first opening animation
                // is still pending on a slower emulator.
                if (now - lastRequestAt >= 750) {
                    val snapshot = nodes()
                    var clicked = false
                    for (node in snapshot) if (node.isVisibleToUser && matches(node, text("app_status_park_confirm"))) {
                        clicked = clickNode(node) || clicked
                    }
                    if (!clicked) for (index in snapshot.size - 1 downTo 0) {
                        val node = snapshot[index]
                        if (node.isVisibleToUser && matches(node, text("action_settings")) && clickNode(node)) {
                            clicked = true
                            break
                        }
                    }
                    // Narrow dashboard layouts also expose the persistent projection
                    // settings shortcut; it opens the same full settings screen.
                    if (!clicked) for (index in snapshot.size - 1 downTo 0) {
                        val node = snapshot[index]
                        if (node.isVisibleToUser && matches(node, text("settings_carplay")) && clickNode(node)) {
                            clicked = true
                            break
                        }
                    }
                    if (clicked) lastRequestAt = now
                }
                false
            }
        }
    }
    private fun selectDriver(slot: Int) {
        click(text("settings_tab_teyes"))
        await("FYT driver settings content") {
            var ready = false
            for (node in nodes()) if (
                matches(node, text("teyes_setup")) || matches(node, text("teyes_driver_profile")) ||
                (matches(node, text("settings_tab_teyes")) && node.isSelected)
            ) ready = true
            // The header can already be above the viewport after restoring tab scroll.
            for (node in nodes()) for (driver in 1..3) if (
                matches(node, text("teyes_driver_slot", driver)) || matches(node, text("teyes_driver_slot_active", driver))
            ) ready = true
            ready
        }
        val preferences = context.getSharedPreferences("teyes_features_v1", 0)
        if (preferences.getInt("active", 0) != slot) {
            click(text("teyes_driver_slot", slot + 1), completed = { preferences.getInt("active", 0) == slot })
        }
        await("Driver ${slot + 1} selection persisted") { preferences.getInt("active", 0) == slot }
        System.out.println("Soak selected driver ${slot + 1}; persisted slot=" + preferences.getInt("active", 0))
    }
    private fun setSearchQuery(value: String) {
        // The log workspace's first editable field is the labelled full-text search field.
        for (pass in 0 until 16) {
            for (node in nodes()) {
                if (node.isVisibleToUser && node.isEditable && node.isEnabled) {
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return
                }
            }
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        fail("Log search text field is not editable")
    }
    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        val output = FileOutputStream(file)
        try { output.write(content.toByteArray(StandardCharsets.UTF_8)) } finally { output.close() }
    }

    @Test fun firstLaunchDashboardSettingsDeniedPermissionsAndDocumentExport() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        for (permission in permissions) {
            shell("pm revoke ${context.packageName} $permission")
            shell("pm set-permission-flags ${context.packageName} $permission user-set user-fixed")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            shell("pm revoke ${context.packageName} android.permission.POST_NOTIFICATIONS")
            shell("pm set-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
        }
        val log = File(context.filesDir, "logs/release-smoke.log")
        write(log, "2026-09-30T12:00:00.000 I [TEST] release-smoke-marker\n")
        val activity = launch()
        try {
            openSettings()
            assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            click(text("settings_tab_logs"))
            click(text("lxg_workspace"))
            await("Log workspace") { present(text("logs_close_viewer")) }
            setSearchQuery("release-smoke-marker")
            // File selection opens expanded; choose only this fixture to bound export work.
            click(log.name)
            click(text("lxg_search"))
            await("Search result from the fixture") {
                var found = false
                for (node in nodes()) if (node.isVisibleToUser && containsText(node.text, "[TEST] release-smoke-marker")) found = true
                found
            }
            click(text("lxg_export_all"))
            click(text("lxg_resume"))
            await("Android document picker") {
                containsText(instrumentation.uiAutomation.rootInActiveWindow?.packageName, "documentsui")
            }
            var saved = false
            await("Android document picker Save") {
                for (node in nodes()) if ((node.text?.toString() == "Save" || node.text?.toString() == "SAVE") && node.isEnabled) {
                    if (clickNode(node)) saved = true
                }
                saved
            }
            await("Document picker returned to Cabin") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
            }
            await("Export completed") { !present(text("lxg_resume")) }
        } finally { activity.close(); log.delete() }
    }

    @Test fun longSessionResourceBudget() {
        val rawCycles = InstrumentationRegistry.getArguments().getString("soakCycles")
        val requested = try { Integer.parseInt(rawCycles ?: "30") } catch (_: NumberFormatException) { 30 }
        val cycles = Math.max(10, Math.min(200, requested))
        val samples = ArrayList<Sample>()
        val original = context.getSharedPreferences("teyes_features_v1", 0).getInt("active", 0)
        val activity = launch()
        var failure: Throwable? = null
        var phase = "start"
        try {
            for (index in 0 until cycles + 5) {
                phase = "cycle ${index + 1}/${cycles + 5}: open settings"
                System.out.println("Soak $phase")
                openSettings()
                phase = "cycle ${index + 1}: select driver ${index % 3 + 1}"
                System.out.println("Soak $phase")
                selectDriver(index % 3)
                phase = "cycle ${index + 1}: connect/stop"
                System.out.println("Soak $phase")
                // Explicit public service actions, observed in CabinProjectionService's contract.
                val service = ComponentName(context.packageName, "com.cabin.background.CabinProjectionService")
                context.startForegroundService(Intent("com.carlink.action.CONNECT_PHONE").setComponent(service))
                SystemClock.sleep(400)
                context.startService(Intent("com.carlink.action.STOP_BACKGROUND").setComponent(service))
                phase = "cycle ${index + 1}: logs/back"
                System.out.println("Soak $phase")
                click(text("settings_tab_logs"))
                click(text("settings_back_cabin"))
                await("Settings closed before the next lifecycle action") { !present(text("settings_back_cabin")) }
                if (index % 5 == 0) {
                    phase = "cycle ${index + 1}: recreate"
                    System.out.println("Soak $phase")
                    activity.recreate()
                }
                SystemClock.sleep(300)
                Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100)
                if (index >= 5) samples.add(Sample(Thread.getAllStackTraces().size, File("/proc/self/fd").list()!!.size,
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()))
            }
        } catch (error: Throwable) {
            failure = error
            System.err.println("Soak failed at $phase: " + android.util.Log.getStackTraceString(error))
            throw error
        } finally {
            try { openSettings(); selectDriver(original) }
            catch (restore: Throwable) {
                System.err.println("Soak restore failed: " + android.util.Log.getStackTraceString(restore))
                if (failure == null) throw restore
            }
            finally { activity.close() }
        }
        val rows = JSONArray()
        for (sample in samples) rows.put(JSONObject().put("threads", sample.threads).put("fds", sample.fds).put("heapBytes", sample.heap))
        val report = JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("device", android.os.Build.MODEL)
            .put("cycles", cycles).put("scope", "No-adapter connect/stop via public service intents; driver selection and settings navigation via Android accessibility; activity recreation")
            .put("samples", rows)
        val resultFile = File(context.getExternalFilesDir(null), "quality/soak.json")
        write(resultFile, report.toString(2))
        System.out.println("Soak report: ${resultFile.path}")
        var firstThreads = 0.0; var lastThreads = 0.0; var firstFds = 0.0; var lastFds = 0.0; var firstHeap = 0.0; var lastHeap = 0.0
        for (index in 0 until 5) {
            val first = samples[index]; val last = samples[samples.size - 5 + index]
            firstThreads += first.threads; lastThreads += last.threads
            firstFds += first.fds; lastFds += last.fds
            firstHeap += first.heap; lastHeap += last.heap
        }
        assertTrue("Sustained thread growth", (lastThreads - firstThreads) / 5 <= 8)
        assertTrue("Sustained descriptor growth", (lastFds - firstFds) / 5 <= 12)
        assertTrue("Sustained retained heap growth", (lastHeap - firstHeap) / 5 <= 24 * 1024 * 1024)
    }
    private data class Sample(val threads: Int, val fds: Int, val heap: Long)
}
