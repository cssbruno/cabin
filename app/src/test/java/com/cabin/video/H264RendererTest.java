package com.cabin.video;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.cabin.util.AppExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class H264RendererTest {
    private final QueuedExecutors executors = new QueuedExecutors();
    private final AtomicInteger keyframes = new AtomicInteger();
    private final AtomicReference<Thread> recoveryThread = new AtomicReference<>();
    private SurfaceTexture texture;
    private Surface surface;
    private H264Renderer renderer;

    @Before
    public void setUp() {
        texture = new SurfaceTexture(0);
        surface = new Surface(texture);
        renderer = new H264Renderer(640, 480, surface, message -> {}, executors, null);
        renderer.setKeyframeRequestCallback(() -> {
            keyframes.incrementAndGet();
            recoveryThread.set(Thread.currentThread());
        });
        renderer.start();
    }

    @After
    public void tearDown() {
        renderer.stop();
        surface.release();
        texture.release();
        executors.shutdown();
    }

    @Test
    public void overlayRecoveryIsDeferredAndCoalesced() throws Exception {
        renderer.flushCodec();
        renderer.flushCodec();

        assertEquals(0, keyframes.get());
        assertEquals(1, executors.tasks.size());
        Thread worker = runQueuedRecovery();
        assertEquals(1, keyframes.get());
        assertSame(worker, recoveryThread.get());

        renderer.flushCodec();
        assertEquals(1, executors.tasks.size());
    }

    @Test
    public void queuedRecoveryCannotRestartStoppedRenderer() throws Exception {
        renderer.flushCodec();
        renderer.stop();
        runQueuedRecovery();

        assertEquals(0, keyframes.get());
        renderer.flushCodec();
        assertEquals(0, executors.tasks.size());
    }

    @Test
    public void queuedRecoveryCannotUseReleasedSurface() throws Exception {
        renderer.flushCodec();
        surface.release();
        runQueuedRecovery();

        assertEquals(0, keyframes.get());
    }

    @Test
    public void queuedRecoverySkipsCodecAlreadyReset() throws Exception {
        renderer.flushCodec();
        renderer.reset();
        assertEquals(1, keyframes.get());

        runQueuedRecovery();
        assertEquals(1, keyframes.get());
    }

    @Test
    public void queuedRecoverySkipsReplacementSurface() throws Exception {
        renderer.flushCodec();
        Surface replacement = new Surface(texture);
        try {
            renderer.resume(replacement);
            int requestsAfterResume = keyframes.get();
            runQueuedRecovery();
            assertEquals(requestsAfterResume, keyframes.get());
        } finally {
            renderer.stop();
            replacement.release();
        }
    }

    @Test
    public void rejectedSubmissionAllowsLaterRecovery() throws Exception {
        executors.reject = true;
        renderer.flushCodec();
        executors.reject = false;
        renderer.flushCodec();

        assertEquals(1, executors.tasks.size());
        runQueuedRecovery();
        assertEquals(1, keyframes.get());
    }

    @Test
    public void producerRecoversWhenAnExhaustedPoolReceivesABuffer() throws Exception {
        Field poolField = H264Renderer.class.getDeclaredField("framePool");
        poolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Object> pool = (ConcurrentLinkedQueue<Object>) poolField.get(renderer);
        pool.clear();
        Field writableField = H264Renderer.class.getDeclaredField("writeFrame");
        writableField.setAccessible(true);
        Object returnedFrame = writableField.get(renderer);
        writableField.set(renderer, null);
        byte[] frame = {0, 0, 0, 1, 1, 42};

        assertFalse(renderer.feedDirect(frame, 0, frame.length));
        pool.offer(returnedFrame);
        assertTrue("A returned buffer must restore ingestion without a codec reset",
                renderer.feedDirect(frame, 0, frame.length));
        assertEquals(0, keyframes.get());
    }

    private Thread runQueuedRecovery() throws Exception {
        Runnable task = executors.tasks.remove();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-codec-recovery");
        worker.start();
        worker.join(5000);
        assertFalse("Recovery must finish", worker.isAlive());
        assertNull(failure.get());
        return worker;
    }

    private static final class QueuedExecutors extends AppExecutors {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override
        public Executor mediaCodec1() {
            return task -> {
                if (reject) throw new RejectedExecutionException("test executor stopped");
                tasks.add(task);
            };
        }
    }
}
