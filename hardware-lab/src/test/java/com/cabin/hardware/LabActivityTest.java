package com.cabin.hardware;

import android.os.HandlerThread;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LabActivityTest {
    @Test public void landingStartsVendorReceiverAndStopsItWhenLeaving() throws Exception {
        var controller = Robolectric.buildActivity(LabActivity.class).setup();
        LabActivity activity = controller.get();
        var field = LabActivity.class.getDeclaredField("receiver"); field.setAccessible(true);
        LiveCanReceiver receiver = (LiveCanReceiver) field.get(activity);
        assertNotNull(receiver);
        var threadField = LiveCanReceiver.class.getDeclaredField("thread"); threadField.setAccessible(true);
        HandlerThread thread = (HandlerThread) threadField.get(receiver);
        try {
            String text = text(activity.getWindow().getDecorView());
            assertTrue(text.contains("Reconnect to FYT"));
            assertTrue(text.contains("Offline test bench (simulated data)"));
            assertFalse(text.contains("Toggle simulated door"));
            assertFalse(text.contains("SIMULATION ONLY"));
        } finally { controller.pause().stop().destroy(); }
        thread.join(1000);
        assertFalse(thread.isAlive());
        assertNull(field.get(activity));
    }
    private String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.append(text(group.getChildAt(i)));
        }
        return result.toString();
    }
}
