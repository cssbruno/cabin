package com.cabin

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityInstanceOwnerTest {
    @Test fun `duplicate MainActivity forwards USB intent and finishes before creating a manager`() {
        val owner = org.robolectric.util.ReflectionHelpers.getStaticField<ActivityInstanceOwner<MainActivity>>(
            MainActivity::class.java, "activityOwner")
        val existing = Robolectric.buildActivity(MainActivity::class.java).get()
        val intent = android.content.Intent(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val duplicate = Robolectric.buildActivity(MainActivity::class.java, intent)
        owner.claim(existing)
        try {
            duplicate.create()
            assertTrue(duplicate.get().isFinishing)
            assertEquals(intent.action, existing.intent.action)
            assertNull(org.robolectric.util.ReflectionHelpers.getField<CabinManager?>(duplicate.get(), "cabinManager"))
            duplicate.destroy()
            assertSame(existing, owner.claim(duplicate.get()))
        } finally { owner.release(existing) }
    }

    @Test fun `second task reuses existing activity and its cleanup cannot clear the owner`() {
        val owner = ActivityInstanceOwner<Activity>()
        val first = Robolectric.buildActivity(Activity::class.java).create()
        val duplicate = Robolectric.buildActivity(Activity::class.java).create()
        try {
            assertNull(owner.claim(first.get()))
            assertSame(first.get(), owner.claim(duplicate.get()))
            owner.release(duplicate.get())
            assertSame(first.get(), owner.claim(duplicate.get()))
            first.get().finish()
            assertNull(owner.claim(duplicate.get()))
            owner.release(first.get())
            assertSame(duplicate.get(), owner.claim(first.get()))
        } finally {
            first.destroy()
            duplicate.destroy()
        }
    }

    @Test fun `destroyed activity does not block a replacement`() {
        val owner = ActivityInstanceOwner<Activity>()
        val old = Robolectric.buildActivity(Activity::class.java).create()
        val next = Robolectric.buildActivity(Activity::class.java).create()
        try {
            owner.claim(old.get())
            old.destroy()
            assertNull(owner.claim(next.get()))
        } finally { next.destroy() }
    }
}
