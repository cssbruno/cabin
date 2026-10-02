package com.cabin.audio

import org.junit.Assert.*
import org.junit.Test

class AudioRingFrameTest {
    @Test fun fractionalRateCapacityAndReadRemainAligned() {
        for (rate in listOf(8000, 16000, 44100, 48000)) for (channels in 1..2) {
            val frame = channels * 2
            val ring = AudioRingBuffer(7, rate, channels)
            val capacity = ring.availableForWrite()
            assertEquals(0, capacity % frame)
            assertTrue(capacity >= (7L * rate * frame / 1000).toInt())
            ring.write(ByteArray(capacity) { it.toByte() })
            assertEquals(capacity, ring.availableForRead())
            while (ring.availableForRead() > 0) assertEquals(0, ring.read(ByteArray(13)) % frame)
        }
    }
    @Test fun overflowKeepsNewestWholeFramesAcrossWrap() {
        val ring = AudioRingBuffer(1, 8000, 2) // eight frames
        ring.write(ByteArray(24) { 1 })
        assertEquals(12, ring.read(ByteArray(13)))
        ring.write(ByteArray(24) { 2 })
        assertEquals(4L, ring.discardedBytes)
        val result = ByteArray(32)
        assertEquals(32, ring.read(result))
        assertArrayEquals(ByteArray(8) { 1 } + ByteArray(24) { 2 }, result)
        ring.write(ByteArray(40) { it.toByte() })
        ring.read(result)
        assertArrayEquals(ByteArray(32) { (it + 8).toByte() }, result)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartialInputFrame() {
        AudioRingBuffer(20, 44100, 2).write(ByteArray(3))
    }
    @Test fun randomizedWrapAndOverflowMatchesWholeFrameReferenceQueue() {
        val random = java.util.Random(71029)
        val ring = AudioRingBuffer(3, 44100, 2)
        val capacityFrames = ring.availableForWrite() / 4
        val expected = java.util.ArrayDeque<Int>()
        repeat(2000) {
            if (random.nextBoolean()) {
                val count = random.nextInt(capacityFrames * 2 + 1)
                val values = IntArray(count) { random.nextInt() }
                val bytes = java.nio.ByteBuffer.allocate(count * 4)
                values.forEach(bytes::putInt)
                ring.write(bytes.array())
                values.forEach { expected.addLast(it) }
                while (expected.size > capacityFrames) expected.removeFirst()
            } else {
                val output = ByteArray(random.nextInt(capacityFrames * 4 + 3))
                val count = ring.read(output)
                assertEquals(0, count % 4)
                val bytes = java.nio.ByteBuffer.wrap(output)
                repeat(count / 4) { assertEquals(expected.removeFirst().toInt(), bytes.int) }
            }
            assertEquals(expected.size * 4, ring.availableForRead())
        }
    }

}
